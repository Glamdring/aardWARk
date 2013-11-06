AardWARk
========

Allows easy syncing of a maven project with a servlet container. How?

1. Put the aardwark.war file in your servlet container
2. Rename the war file to `aardwark-<path to project home>`, with slashes replaced by dots. For example:

    `aardwark-.home.workspace.hello-world` would sync the project in `/home/workspace/hello-world` with the appropriate application within the web context The target application is chosen by examining the artifact name of your maven project.

3. Start your servlet container - everything (classes, resources, jars) will be synced whenever you make a change in your IDE.

The project is servlet-container-independent and IDE-independent
