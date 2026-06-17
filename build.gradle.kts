plugins {
  id("org.jetbrains.intellij.platform") version "2.11.0"
  kotlin("jvm") version "2.2.0"
}

version = "0.17"

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

dependencies {
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
      sinceBuild = "233"
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
}
