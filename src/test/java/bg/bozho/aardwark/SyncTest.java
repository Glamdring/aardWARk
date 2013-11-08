package bg.bozho.aardwark;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.sonatype.aether.util.artifact.DefaultArtifact;

import com.google.common.collect.Lists;

public class SyncTest {

	@Test
	public void targetPathTest() {
		FileSystem fs = FileSystems.getDefault();
		StartupListener listener = new StartupListener();
		listener.setProjectPath(fs.getPath("/workspace/foo/bar"));
		listener.setWebappPath(fs.getPath("/tomcat/webapps/bar"));
		Path target = listener.determineTarget(fs.getPath("/workspace/foo/bar/target/classes/bg/bozho/Some.class"));
		// replacing "\" with "/", so that the test works on both OSs
		Assert.assertEquals("/tomcat/webapps/bar/WEB-INF/classes/bg/bozho/Some.class", target.toString().replace("\\", "/"));
	}
	
	@Test
	public void watchDependentProjectsTest() throws Exception {
		FileSystem fs = FileSystems.getDefault();
		StartupListener listener = new StartupListener();
		listener.setProjectPath(fs.getPath("/workspace/foo/bar"));
		listener.setWebappPath(fs.getPath("/tomcat/webapps/bar"));
		WatchService watcher = Mockito.mock(WatchService.class);
		listener.setWatcher(watcher);
		listener = Mockito.spy(listener);
		Set<String> dependencies = new HashSet<>();
		dependencies.add("com.foo:bar");
		Model model = new Model();
		model.setModules(Lists.newArrayList("another"));
		listener.watchDependentProjects(model, dependencies, fs.getPath("/workspace/foo/another"));
		// one direct invocation + one invocation for the dependent project "another"
		Mockito.verify(listener, Mockito.times(2)).watchDependentProjects(Mockito.<Model>any(), Mockito.<Set<String>>any(), Mockito.<Path>any());
	}
}
