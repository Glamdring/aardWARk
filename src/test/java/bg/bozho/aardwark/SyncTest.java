package bg.bozho.aardwark;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.model.Model;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class SyncTest {

    @Test
    public void targetPathTest() {
        FileSystem fs = FileSystems.getDefault();
        StartupListener listener = new StartupListener();
        Path projectPath = fs.getPath("/workspace/foo/bar");
        listener.addProjectPath("bar", projectPath);
        listener.addWebappPath("bar", fs.getPath("/tomcat/webapps/bar"));
        Path target = listener.determineTarget("bar", fs.getPath("/workspace/foo/bar/target/classes/bg/bozho/Some.class"),
                projectPath);
        // replacing "\" with "/", so that the test works on both OSs
        Assert.assertEquals("/tomcat/webapps/bar/WEB-INF/classes/bg/bozho/Some.class",
                target.toString().replace("\\", "/"));
    }

    @Test
    public void watchDependentProjectsTest() throws Exception {
        FileSystem fs = FileSystems.getDefault();
        StartupListener listener = new StartupListener();
        listener.addProjectPath("bar", fs.getPath("/workspace/foo/bar"));
        listener.addWebappPath("bar", fs.getPath("/tomcat/webapps/bar"));
        WatchService watcher = Mockito.mock(WatchService.class);
        listener.setWatcher(watcher);
        listener = Mockito.spy(listener);
        Set<String> dependencies = new HashSet<>();
        dependencies.add("another");
        Model model = new Model();
        model.setModules(Arrays.asList("another"));
        listener.watchDependentProjects("another", model, dependencies, fs.getPath("/workspace/foo/another"));
        // one direct invocation + one invocation for the dependent project
        // "another"
        Mockito.verify(listener, Mockito.times(2)).watchDependentProjects(Mockito.anyString(), Mockito.<Model> any(),
                Mockito.<Set<String>> any(), Mockito.<Path> any());
    }
}
