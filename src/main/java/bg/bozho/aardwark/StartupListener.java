package bg.bozho.aardwark;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.name;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;
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

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.DefaultBuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.util.DefaultRepositorySystemSession;

@WebListener
public class StartupListener implements ServletContextListener {

    private ExecutorService executor;
    private BuildPluginManager pluginManager = new DefaultBuildPluginManager();
    private WatchService watcher;
    private FileSystem fs = FileSystems.getDefault();
    private Path webappPath;
    private Path projectPath;
    // a map holding mapping from watch keys to paths, because each WatchEvent contains only the file name, and not the path to the file
    // using this map we can get the full path to a file for a given WatchEvent
    private Map<WatchKey, Path> paths = new HashMap<>();
    
    public void contextInitialized(ServletContextEvent sce) {

        String projectDir = sce.getServletContext().getContextPath().replace("/aardwark-", "").replace('.', '/');

        try {
            Model model = readMavenModel(projectDir);

            watcher = fs.newWatchService();
            projectPath = fs.getPath(projectDir);
            webappPath = fs.getPath(sce.getServletContext().getRealPath("/")).getParent().resolve(getTargetWebapp(model));

            executor = Executors.newSingleThreadExecutor();
            watchProject(projectPath, model, false);

            // also watch dependent projects that are within the same workspace, so that their classes are copied as well (rather than their jars). TODO remove these jars from the copied dependencies
            Set<String> dependencies = new HashSet<>();
            for (Dependency dependency : model.getDependencies()) {
                dependencies.add(dependency.getGroupId() + ":" + dependency.getArtifactId());
            }
            Path currentPath = projectPath;
            Model currentModel = model;
            while (currentModel != null) {
            	currentPath = currentPath.getParent();
            	Model currentProjectModel = readMavenModel(currentPath.toString());
                watchDependentProjects(currentProjectModel, dependencies, currentPath);
                currentModel = currentProjectModel;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to watch file system", e);
        }
    }

    private void watchDependentProjects(Model model, Set<String> dependencies, Path projectPath) throws IOException {
        if (model == null) {
            return;
        }

        List<String> modules = model.getModules();
        if ((modules == null || modules.isEmpty())) {
            if (dependencies.contains(model.getGroupId() + ":" + model.getArtifactId())) {
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

    private void watchProject(Path projectPath, final Model model, final boolean dependencyProject) throws IOException {

    	Files.walkFileTree(projectPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException
            {
            	WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            	paths.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    WatchKey key;
                    while ((key = watcher.take()) != null) {
                        List<WatchEvent<?>> events = key.pollEvents();
                        for (WatchEvent<?> event : events) {
                            try {
                                Path eventPath = paths.get(key).resolve((Path) event.context());
                                Path target = determineTarget(eventPath, webappPath);
                                if (target != null) {
                                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE || event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                                        Files.copy(eventPath, target, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                    if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                                        Files.deleteIfExists(determineTarget(eventPath, webappPath));
                                    }
                                }
                                if (!dependencyProject && eventPath.endsWith("pom.xml")) {
                                    copyDependencies(model);
                                }
                            } catch (IOException | MojoExecutionException ex) {
                                ex.printStackTrace();
                                // TODO warn
                            }
                        }
                    }
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                    //TODO warn
                    // return - the executor has been shutdown
                }
            }
        });
    }

    private Model readMavenModel(String projectDir) throws FileNotFoundException, IOException {
        Model model = null;
        Reader reader = null;
        try {
        	Path pomPath = fs.getPath(projectDir, "pom.xml");
        	if (!pomPath.toFile().exists()) {
        		return null;
        	}
            reader = new FileReader(pomPath.toFile());
            MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
            model = xpp3Reader.read(reader);
        } catch (XmlPullParserException e) {
            throw new IllegalStateException("Cannot read maven model");
        } finally {
        	if (reader != null) {
        		reader.close();
        	}
        }
        return model;
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

    private Path determineTarget(Path filePath, Path webappPath) {
        if (filePath.toString().contains("src" + File.separator + "main" + File.separator + "webapp")) {
            return webappPath;
        }
        if (filePath.toString().contains("target" + File.separator + "classes")) {
            return webappPath.resolve("WEB-INF/classes");
        }

        return null;
    }

    private void copyDependencies(Model model) throws IOException, MojoExecutionException {
    	MavenProject project = new MavenProject(model);
        MavenRequest req = new MavenRequest();
        req.setBaseDirectory(project.getBasedir().getAbsolutePath());
        req.setGoals(Collections.singletonList("dependency:copy-dependencies"));

        MavenSession session = null;

        MavenEmbedder embedder = null;
        try {
            embedder = new MavenEmbedder(getClass().getClassLoader(), req);
            embedder.execute(req);
            PlexusContainer container = new DefaultPlexusContainer();
            RepositorySystemSession repoSession = new DefaultRepositorySystemSession();
            MavenExecutionRequest request = new DefaultMavenExecutionRequest();
            MavenExecutionResult result = new DefaultMavenExecutionResult().setProject(project);
            session = new MavenSession(container, repoSession, request, result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        Path lib = webappPath.resolve("WEB-INF/lib");
        File[] libs = lib.toFile().listFiles();
        for (File libFile : libs) {
            libFile.delete();
        }

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(projectPath.resolve("pom.xml").toFile());
        request.setBaseDirectory(projectPath.toFile());
        request.setGoals(Collections.singletonList("dependency:copy-dependencies"));
        request.setMavenOpts("-DoutputDirectory=" + lib.toString());

        Invoker invoker = new DefaultInvoker();
        try {
            invoker.execute(request);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        executeMojo(
            plugin(
                groupId("org.apache.maven.plugins"),
                artifactId("maven-dependency-plugin"),
                version("2.8")
            ),
            goal("copy-dependencies"),
            configuration(
                element(name("outputDirectory"), lib.toString())
            ),
            executionEnvironment(
                project,
                session,
                pluginManager
            )
        );
    }
    public void contextDestroyed(ServletContextEvent sce) {
        try {
            watcher.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        executor.shutdownNow();
    }

}
