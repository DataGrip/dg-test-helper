// Load-bearing: `build/distributions/dg-test-helper-<version>.zip` and `build/libs/*.jar` derive their
// base name from `project.name`, which came from the directory name while this build had no settings
// file. `intellijPlatform.pluginConfiguration.name` does not control it.
rootProject.name = "dg-test-helper"

// Plain kotlin("jvm"), deliberately without the IntelliJ Platform: a JUnit 5 engine must not have an
// IDE (and its own JUnit) on its classpath.
include("eagerParams")
