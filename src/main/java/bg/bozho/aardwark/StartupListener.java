package bg.bozho.aardwark;

import hudson.maven.MavenEmbedder;
import hudson.maven.MavenRequest;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
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
import java.util.Collections;
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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.DefaultBuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebListener
public class StartupListener implements ServletContextListener {

    private static final Logger logger = LoggerFactory.getLogger(StartupListener.class);

    private ExecutorService executor;
    private BuildPluginManager pluginManager = new DefaultBuildPluginManager();
    private WatchService watcher;
    private FileSystem fs = FileSystems.getDefault();
    private Path webappPath;
    private Path projectPath;
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

        String projectDir = sce.getServletContext().getContextPath().replace("/aardwark-", "").replace('.', '/');

        try {
            Model model = readMavenModel(projectDir);

            watcher = fs.newWatchService();
            projectPath = fs.getPath(projectDir);
            webappPath = fs.getPath(sce.getServletContext().getRealPath("/")).getParent()
                    .resolve(getTargetWebapp(model));

            executor = Executors.newSingleThreadExecutor();
            watchProject(projectPath, model, false);

            // also watch dependent projects that are within the same workspace,
            // so that their classes are copied as well (rather than their
            // jars). TODO remove these jars from the copied dependencies
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
                watchDependentProjects(currentProjectModel, dependencies, currentPath);
                currentModel = currentProjectModel;
            }

            startWatching();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to watch file system", e);
        }
    }

    void watchDependentProjects(Model model, Set<String> dependencies, Path projectPath) throws IOException {
        if (model == null) {
            return;
        }

        List<String> modules = model.getModules();
        if ((modules == null || modules.isEmpty())) {
            if (dependencies.contains(model.getArtifactId())) {
                watchProject(projectPath, null, true);
            }
        } else {
            for (String module : modules) {
                Path modulePath = projectPath.resolve(module);
                Model moduleModel = readMavenModel(modulePath.toString());
                watchDependentProjects(moduleModel, dependencies, modulePath);
            }
        }
    }

    private void watchProject(final Path projectPath, final Model model, final boolean dependencyProject)
            throws IOException {

        Files.walkFileTree(projectPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                watched.put(key, new WatchableDirectory(dir, projectPath, dependencyProject, model));
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
                                Path target = determineTarget(eventPath, watchableDirectory.getProjectPath());
                                if (target != null) {
                                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                                            || event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                                        target.toFile().mkdirs(); // make sure the directory structure is in place
                                        Files.copy(eventPath, target, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                    if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                                        Files.deleteIfExists(determineTarget(eventPath,
                                                watchableDirectory.getProjectPath()));
                                    }
                                }
                                if (!watchableDirectory.isDependencyProject() && eventPath.endsWith("pom.xml")) {
                                    copyDependencies(watchableDirectory.getMavenModel());
                                }
                            } catch (IOException | MojoExecutionException ex) {
                                logger.warn("Exception when attempting to watch directory", ex);
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
            if (!pomPath.toFile().exists()) {
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

    Path determineTarget(Path filePath, Path projectPath) {
        if (filePath.toString().contains("src" + File.separator + "main" + File.separator + "webapp")) {
            Path pathWithinProject = projectPath.resolve("src/main/webapp").relativize(filePath);
            return webappPath.resolve(pathWithinProject);
        }
        if (filePath.toString().contains("target" + File.separator + "classes")) {
            Path pathWithinClasspath = projectPath.resolve("target/classes").relativize(filePath);
            return webappPath.resolve("WEB-INF/classes").resolve(pathWithinClasspath);
        }

        return null;
    }

    private void copyDependencies(Model model) throws IOException, MojoExecutionException {
        MavenProject project = new MavenProject(model);
        MavenRequest req = new MavenRequest();
        req.setBaseDirectory(projectPath.toString());
        req.setGoals(Collections.singletonList("dependency:copy-dependencies"));
        MavenSession session = null;

        Path lib = webappPath.resolve("WEB-INF/lib");
        System.setProperty("MAVEN_OPTS", "-DoutputDirectory=" + lib.toString());

        File[] libs = lib.toFile().listFiles();
        for (File libFile : libs) {
            libFile.delete();
        }

        MavenEmbedder embedder = null;
        try {
            embedder = new MavenEmbedder(getClass().getClassLoader(), req);
            embedder.execute(req);
            //PlexusContainer container = new DefaultPlexusContainer();
            //RepositorySystemSession repoSession = new DefaultRepositorySystemSession();
            //MavenExecutionRequest request = new DefaultMavenExecutionRequest();
            //MavenExecutionResult result = new DefaultMavenExecutionResult().setProject(project);
            //session = new MavenSession(container, repoSession, request, result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }



//        InvocationRequest request = new DefaultInvocationRequest();
//        request.setPomFile(projectPath.resolve("pom.xml").toFile());
//        request.setBaseDirectory(projectPath.toFile());
//        request.setGoals(Collections.singletonList("dependency:copy-dependencies"));
//        request.setMavenOpts("-DoutputDirectory=" + lib.toString());
//
//        Invoker invoker = new DefaultInvoker();
//        try {
//            invoker.execute(request);
//        } catch (Exception ex) {
//            ex.printStackTrace();
//        }
//
//        executeMojo(plugin(groupId("org.apache.maven.plugins"), artifactId("maven-dependency-plugin"), version("2.8")),
//                goal("copy-dependencies"), configuration(element(name("outputDirectory"), lib.toString())),
//                executionEnvironment(project, session, pluginManager));
    }

    public void contextDestroyed(ServletContextEvent sce) {
        try {
            watcher.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        executor.shutdownNow();
    }

    public void setProjectPath(Path projectPath) {
        this.projectPath = projectPath;
    }

    public void setWebappPath(Path webappPath) {
        this.webappPath = webappPath;
    }

    public void setWatcher(WatchService watcher) {
        this.watcher = watcher;
    }

    public static class WatchableDirectory {
        private Path directory;
        private Path projectPath;
        private boolean dependencyProject;
        private Model mavenModel;

        public WatchableDirectory(Path dir, Path projectPath, boolean dependencyProject, Model mavenModel) {
            super();
            this.directory = dir;
            this.projectPath = projectPath;
            this.dependencyProject = dependencyProject;
            this.mavenModel = mavenModel;
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
    }

}
