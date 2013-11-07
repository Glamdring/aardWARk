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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
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
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
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

    public void contextInitialized(ServletContextEvent sce) {

        String projectDir = sce.getServletContext().getContextPath().replace("/aardwark-", "").replace('.', '/');

        try {
            Model model = readMavenModel(projectDir);

            watcher = fs.newWatchService();
            projectPath = fs.getPath(projectDir);
            webappPath = fs.getPath(sce.getServletContext().getRealPath("/")).getParent().resolve(getTargetWebapp(model));
            MavenProject project = new MavenProject(model);

            executor = Executors.newSingleThreadExecutor();
            watchProject(projectPath, project, false);

            // also watch dependent projects that are within the same workspace, so that their classes are copied as well (rather than their jars). TODO remove these jars from the copied dependencies
            Set<String> dependencies = new HashSet<>();
            for (Dependency dependency : project.getDependencies()) {
                dependencies.add(dependency.getGroupId() + ":" + dependency.getArtifactId());
            }
            Path currentPath = projectPath;
            MavenProject currentProject = project;
            while (currentProject != null) {
                watchDependentProjects(currentProject.getParent(), dependencies, projectPath);
                currentProject = currentProject.getParent();
                currentPath = currentPath.getParent();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to watch file system", e);
        }
    }

    private void watchDependentProjects(MavenProject project, Set<String> dependencies, Path projectPath) throws IOException {
        if (project == null) {
            return;
        }

        List<String> modules = project.getModules();
        if ((modules == null || modules.isEmpty())) {
            if (dependencies.contains(project.getGroupId() + ":" + project.getArtifactId())) {
                watchProject(projectPath, null, true);
            }
        } else {
            for (String module : modules) {
                Path modulePath = projectPath.resolve(module);
                Model model = readMavenModel(modulePath.toString());
                watchDependentProjects(new MavenProject(model), dependencies, modulePath);
            }
        }
    }

    private void watchProject(Path projectPath, final MavenProject project, final boolean dependencyProject) throws IOException {

        projectPath.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    WatchKey key;
                    while ((key = watcher.take()) != null) {
                        List<WatchEvent<?>> events = key.pollEvents();
                        for (WatchEvent<?> event : events) {
                            try {
                                Path eventPath = (Path) event.context();
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
                                    copyDependencies(project);
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
            reader = new FileReader(fs.getPath(projectDir, "pom.xml").toFile());
            MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
            model = xpp3Reader.read(reader);
        } catch (XmlPullParserException e) {
            throw new IllegalStateException("Cannot read maven model");
        } finally {
            reader.close();
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

    private Path determineTarget(Path projectPath, Path webappPath) {
        if (projectPath.toString().contains("src/main/webapp")) {
            return webappPath;
        }
        if (projectPath.toString().contains("target/classes")) {
            return webappPath.resolve("WEB-INF/classes");
        }

        return null;
    }

    private void copyDependencies(MavenProject project) throws IOException, MojoExecutionException {

        MavenSession session = null;

        try {
            PlexusContainer container = new DefaultPlexusContainer();
            RepositorySystemSession repoSession = new DefaultRepositorySystemSession();
            MavenExecutionRequest request = new DefaultMavenExecutionRequest();
            MavenExecutionResult result = new DefaultMavenExecutionResult().setProject(project);
            session = new MavenSession(container, repoSession, request, result);
        } catch (PlexusContainerException e) {
            throw new IllegalStateException(e);
        }

        Path lib = webappPath.resolve("WEB-INF/lib");
        File[] libs = lib.toFile().listFiles();
        for (File libFile : libs) {
            libFile.delete();
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
