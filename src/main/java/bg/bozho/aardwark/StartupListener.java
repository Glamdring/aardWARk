package bg.bozho.aardwark;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebListener
public class StartupListener implements ServletContextListener {

    private static final Logger logger = LoggerFactory.getLogger(StartupListener.class);

    private ExecutorService executor;
    private WatchService watcher;
    private FileSystem fs = FileSystems.getDefault();
    private Map<String, Path> webappPaths = new HashMap<>();
    private Map<String, Path> projectPaths = new HashMap<>();
    // a map holding mapping from watch keys to paths and related metadata,
    // because each WatchEvent contains only the file name, and not the path to
    // the file
    // using this map we can get the full path to a file for a given WatchEvent.
    // Also, we get the project metadata. This is needed, because only one
    // thread should
    // handle WatchEvents, and we should be able to differentiate projects based
    // on the event.
    private Map<WatchKey, WatchableDirectory> watched = new HashMap<>();

    public void contextInitialized(ServletContextEvent sce) {

        // supporting multiple projects
        List<String> projectDirs = getProjectDirectories(sce);

        for (String projectDir : projectDirs) {
            try {
                Model model = readMavenModel(projectDir);

                watcher = fs.newWatchService();
                String webappName = getTargetWebapp(model);
                Path projectPath = fs.getPath(projectDir);
                projectPaths.put(webappName, projectPath);
                Path webappPath = fs.getPath(sce.getServletContext().getRealPath("/")).getParent().resolve(webappName);
                webappPaths.put(webappName, webappPath);

                // if the webapp does not exist, assume ROOT is used
                if (Files.notExists(webappPath)) {
                    logger.warn("No webapp found under " + webappPath.toString() + ". Using ROOT instead.");
                    webappPath = webappPath.getParent().resolve("ROOT");
                }

                executor = Executors.newSingleThreadExecutor();

                // copy once on startup
                copyDependencies(webappName, model);

                watchProject(webappName, projectPath, model, false);

                // also watch dependent projects that are within the same workspace,
                // so that their classes are copied as well (rather than their
                // jars). TODO remove these jars from the copied dependencies?
                // Adding only the artifactId (rather than groupId+artifactId) as
                // groupIds tend to be variables, and we can't resolve variables
                // here. Might lead to inappropriate copies, but they can't do any
                // harm
                Set<String> dependencies = new HashSet<>();
                for (Dependency dependency : model.getDependencies()) {
                    dependencies.add(dependency.getArtifactId());
                }
                Path currentPath = projectPath;
                Model currentModel = model;
                while (currentModel != null) {
                    currentPath = currentPath.getParent();
                    Model currentProjectModel = readMavenModel(currentPath.toString());
                    watchDependentProjects(webappName, currentProjectModel, dependencies, currentPath);
                    currentModel = currentProjectModel;
                }

                startWatching();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to watch file system", e);
            }
        }
    }

    private List<String> getProjectDirectories(ServletContextEvent sce) {
        // if there's a properties file, read it and determine the target project path
        Path webappsDirectory = fs.getPath(sce.getServletContext().getRealPath("/")).getParent();
        Path propertiesFile = webappsDirectory.resolve("aardwark.properties");
        if (Files.exists(propertiesFile)) {
           try {
                List<String> lines = Files.readAllLines(propertiesFile, Charset.forName("UTF-8"));
                if (!lines.isEmpty()) {
                    return lines;
                }
           } catch (IOException e) {
               throw new IllegalStateException(e);
           }
        }
        // default to the war filename
        return Arrays.asList(sce.getServletContext().getContextPath().replace("/aardwark-", "").replace('.', '/'));
    }

    void watchDependentProjects(String webappName, Model model, Set<String> dependencies, Path projectPath) throws IOException {
        if (model == null) {
            return;
        }

        List<String> modules = model.getModules();
        if ((modules == null || modules.isEmpty())) {
            if (dependencies.contains(model.getArtifactId())) {
                watchProject(webappName, projectPath, null, true);
            }
        } else {
            for (String module : modules) {
                Path modulePath = projectPath.resolve(module);
                Model moduleModel = readMavenModel(modulePath.toString());
                watchDependentProjects(webappName, moduleModel, dependencies, modulePath);
            }
        }
    }

    private void watchProject(final String webappName, final Path projectPath, final Model model, final boolean dependencyProject)
            throws IOException {

        Files.walkFileTree(projectPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                watched.put(key, new WatchableDirectory(dir, projectPath, dependencyProject, model, webappName));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void startWatching() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    WatchKey key;
                    while ((key = watcher.take()) != null) {
                        List<WatchEvent<?>> events = key.pollEvents();
                        for (WatchEvent<?> event : events) {
                            try {
                                WatchableDirectory watchableDirectory = watched.get(key);
                                Path filename = (Path) event.context();
                                Path eventPath = watchableDirectory.getDirectory().resolve(filename);
                                Path target = determineTarget(watchableDirectory.getWebappName(), eventPath, watchableDirectory.getProjectPath());
                                if (target != null) {
                                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE || event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                                        // make sure directory structure is in place
                                        target.toFile().mkdirs();
                                        if (!Files.isDirectory(target)) {
                                            Files.copy(eventPath, target, StandardCopyOption.REPLACE_EXISTING);
                                        }
                                    }
                                    if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE && !Files.isDirectory(target)) {
                                        Files.deleteIfExists(determineTarget(watchableDirectory.getWebappName(), eventPath, watchableDirectory.getProjectPath()));
                                    }
                                }
                            } catch (IOException ex) {
                                logger.warn("Exception while watching directory", ex);
                            }
                        }
                    }
                } catch (InterruptedException ex) {
                    logger.warn("Watching thread interrupted", ex);
                    // return - the executor has been shutdown
                }
            }
        });
    }

    private Model readMavenModel(String baseDir) throws FileNotFoundException, IOException {
        Reader reader = null;
        try {
            Path pomPath = fs.getPath(baseDir, "pom.xml");
            if (Files.notExists(pomPath)) {
                return null;
            }
            reader = new FileReader(pomPath.toFile());
            MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
            return xpp3Reader.read(reader);
        } catch (XmlPullParserException e) {
            throw new IllegalStateException("Cannot read maven model");
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private String getTargetWebapp(Model model) {
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (plugin.getArtifactId().equals("maven-war-plugin")) {
                Xpp3Dom dom = (Xpp3Dom) plugin.getConfiguration();
                Xpp3Dom[] element = dom.getChildren("warName");
                if (element.length > 0) {
                    return element[0].getValue();
                }
            }
        }
        return model.getArtifactId();
    }

    Path determineTarget(String webappName, Path filePath, Path projectPath) {
        if (filePath.toString().contains("src" + File.separator + "main" + File.separator + "webapp")) {
            Path pathWithinProject = projectPath.resolve("src/main/webapp").relativize(filePath);
            return webappPaths.get(webappName).resolve(pathWithinProject);
        }
        if (filePath.toString().contains("target" + File.separator + "classes")) {
            Path pathWithinClasspath = projectPath.resolve("target/classes").relativize(filePath);
            return webappPaths.get(webappName).resolve("WEB-INF/classes").resolve(pathWithinClasspath);
        }

        return null;
    }

    private void copyDependencies(String webappName, Model model) throws IOException {
        final Path lib = webappPaths.get(webappName).resolve("WEB-INF/lib");
        lib.toFile().mkdirs();
        File[] libs = lib.toFile().listFiles();
        if (libs != null) {
            for (File libFile : libs) {
                libFile.delete();
            }
        }
        // get the lib folder, where all dependencies are located after a successful build
        Path libFolder = projectPaths.get(webappName).resolve("target/" + model.getArtifactId() + "-" + model.getVersion() + "/WEB-INF/lib");
        Files.walkFileTree(libFolder, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, lib.resolve(file.getFileName()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void contextDestroyed(ServletContextEvent sce) {
        try {
            watcher.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        executor.shutdownNow();
    }

    public void addProjectPath(String webappName, Path projectPath) {
        this.projectPaths.put(webappName, projectPath);
    }

    public void addWebappPath(String webappName, Path webappPath) {
        this.webappPaths.put(webappName, webappPath);
    }

    public void setWatcher(WatchService watcher) {
        this.watcher = watcher;
    }

    public static class WatchableDirectory {
        private Path directory;
        private Path projectPath;
        private boolean dependencyProject;
        private Model mavenModel;
        private String webappName;

        public WatchableDirectory(Path dir, Path projectPath, boolean dependencyProject, Model mavenModel, String webappName) {
            super();
            this.directory = dir;
            this.projectPath = projectPath;
            this.dependencyProject = dependencyProject;
            this.mavenModel = mavenModel;
            this.webappName = webappName;
        }

        public boolean isDependencyProject() {
            return dependencyProject;
        }

        public void setDependencyProject(boolean dependencyProject) {
            this.dependencyProject = dependencyProject;
        }

        public Path getProjectPath() {
            return projectPath;
        }

        public void setProjectPath(Path projectPath) {
            this.projectPath = projectPath;
        }

        public Path getDirectory() {
            return directory;
        }

        public void setDirectory(Path dir) {
            this.directory = dir;
        }

        public Model getMavenModel() {
            return mavenModel;
        }

        public void setMavenModel(Model mavenModel) {
            this.mavenModel = mavenModel;
        }

        public String getWebappName() {
            return webappName;
        }

        public void setWebappName(String webappName) {
            this.webappName = webappName;
        }
    }
}
