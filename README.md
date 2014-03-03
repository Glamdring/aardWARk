AardWARk
========

Allows easy syncing of a maven project with a servlet container. How?

1. Put the aardwark.war file in your servlet container
2. Rename the war file to `aardwark-<path to project home>`, with slashes replaced by dots. For example:

    `aardwark-.home.workspace.hello-world` would sync the project in `/home/workspace/hello-world` with the appropriate application within the web context The target application is chosen by examining the artifact name of your maven project.

    If that doesn't suit you (e.g. your projects have dots in their name) or you don't like the approach, you can put an aardwark.properties file in the webapps directory. The file should contain the paths to the target applications, each on a separate line.

3. Start your servlet container - everything (classes, resources, jars) will be synced whenever you make a change in your IDE. Note: jar files will be synchronized only on startup and after a successful build of your maven project.

The project is servlet-container-independent and IDE-independent.

_Note_: the purpose of the project is to sync the classes and resources of your projects between your workspace and the servlet container. Reloading the classes is beyond the scope of this project. For that to work you have a couple of options:

* Regular <a href="http://docs.oracle.com/javase/1.4.2/docs/guide/jpda/enhancements.html">HotSwap</a> - simply run the servlet container in debug mode
* <a href="https://github.com/spring-projects/spring-loaded">Spring-loaded</a> - a Spring-provided JVM agent that allows class reloading
* <a href="http://ssw.jku.at/dcevm/">DCEVM</a> - a VM enhancement that allows complete reloading of classes
* JRebel - a commercial alternative