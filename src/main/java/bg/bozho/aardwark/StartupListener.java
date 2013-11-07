package bg.bozho.aardwark;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

@WebListener
public class StartupListener implements ServletContextListener {

    private ExecutorService executor;

    public void contextInitialized(ServletContextEvent sce) {
        FileSystem fs = FileSystems.getDefault();

        String projectDir = sce.getServletContext().getContextPath().replace("/aardwark-", "").replace('.', '/');

        try {
            Model model = readMavenModel(fs, projectDir);


            final WatchService watcher = fs.newWatchService();
            final Path projectPath = fs.getPath(projectDir);
            final Path webappPath = fs.getPath(sce.getServletContext().getRealPath("/")).getParent().resolve(getTargetWebapp(model));

            executor = Executors.newSingleThreadExecutor();
            watchProject(projectPath, webappPath, watcher, false);

            // also watch dependent projects that are within the same workspace, so that their classes are copied as well (rather than their jars). TODO remove these jars from the copied dependencies
            Parent parent = model.getParent();
            // TODO recursively resolve dependent projects
            // 1. get current dependencies 2. match them against the artifact ids of all projects in the project-space
        } catch (IOException e) {
            throw new IllegalStateException("Failed to watch file system", e);
        }
    }

    private void watchProject(Path projectPath, final Path webappPath, final WatchService watcher, final boolean dependencyProject) throws IOException {

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
                                    //copyDependencies(projectDir, fs, target);
                                }
                            } catch (IOException ex) {
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

    private Model readMavenModel(FileSystem fs, String projectDir) throws FileNotFoundException, IOException {
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
            return webappPath.resolve("/WEB-INF/classes");
        }

        return null;
    }

    private void copyDependencies(String projectDir, FileSystem fs, Path target) throws IOException, MojoFailureException {
        //CopyDependenciesMojo copy = new CopyDependenciesMojo();
        //copy.setOutputDirectory(target.resolve("/WEB-INF/lib").toFile());
        //copy.execute();
    }
    public void contextDestroyed(ServletContextEvent sce) {
        executor.shutdownNow();
    }

}
