import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
  id("org.jetbrains.intellij.platform") version "2.18.1"
  kotlin("jvm") version "2.4.0"
}

version = "0.18"

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

kotlin {
  jvmToolchain(21)
}

sourceSets {
  main {
    java.srcDirs("main/src")
    kotlin.srcDirs("main/src")
    resources.srcDirs("main/resources")
  }
  test {
    java.srcDir("tests/src")
    kotlin.srcDirs("tests/src")
    resources.srcDirs("tests/testData")
  }
}

// The eager engine ships beside the plugin, not inside it: `DGConfigurationExtension` puts this jar on the
// classpath of a test run, and that is the whole of the switch. `isTransitive = false` because only the jar
// travels -- junit-platform and kotlin-stdlib are already on any dbe test classpath.
val eagerParamsRt: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = false
}

dependencies {
  eagerParamsRt(project(":eagerParams"))
  intellijPlatform {
    val localPath = project.properties["local.idea"] as String?
    if (localPath != null) {
      local(localPath)
    } else {
      intellijIdeaUltimate("2025.3")
    }
    bundledPlugins("com.intellij.java", "com.intellij.database")
  }
}

intellijPlatform {
  pluginConfiguration {
    name = "dg-test-helper"
    ideaVersion {
      sinceBuild = "262"
      untilBuild = "993.*"
    }
  }
  publishing {
    token = providers.gradleProperty("publish.token")
    channels = listOf(project.properties["publish.channel"] as String? ?: "Stable")
  }
}

tasks {
  runIde {
    maxHeapSize = "2g"
  }
  buildSearchableOptions {
    enabled = false
  }
  // `rt`, deliberately not `lib`: anything under `lib` joins the plugin's classloader, and a JUnit engine
  // registered through META-INF/services has no business being visible to the IDE. buildPlugin zips the
  // whole plugin directory, so this reaches the distribution too.
  withType<PrepareSandboxTask>().configureEach {
    from(eagerParamsRt) {
      into(pluginName.map { "$it/rt" })
      rename { "eagerParams.jar" }
    }
  }
}
