plugins {
  // No version: KGP is already on the buildscript classpath via the root project's `plugins {}` block,
  // and repeating the version here is a hard error.
  kotlin("jvm")
}

repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(21)
}

// junit-jupiter 5.14.4 <-> junit-platform 1.14.4. Every JUnit coordinate below is unversioned.
val junitBom = "org.junit:junit-bom:5.14.4"

sourceSets {
  // setSrcDirs rather than srcDirs: the main source directory is literally named `src`, so the
  // additive form would leave `src/main/kotlin`, `src/test/java` and friends as live roots inside it.
  main {
    java.setSrcDirs(listOf("src"))
    kotlin.setSrcDirs(listOf("src"))
    resources.setSrcDirs(listOf("resources"))
  }
  // The subjects under test, not tests: several of them fail or abort on purpose. Their own source set
  // is what keeps them out of the `test` task, and it has no dependency on `main` on purpose -- a
  // custom source set's compile classpath is only its own configurations, so the engine stays invisible
  // here and `EagerRecorder`'s "public because the fixtures live in their own module" stays enforced.
  create("fixtures") {
    java.setSrcDirs(listOf("fixtures/test-fixtures"))
    kotlin.setSrcDirs(listOf("fixtures/test-fixtures"))
    resources.setSrcDirs(emptyList<String>())
  }
  test {
    java.setSrcDirs(listOf("tests/src"))
    kotlin.setSrcDirs(listOf("tests/src"))
    resources.setSrcDirs(emptyList<String>())
  }
}

dependencies {
  implementation(platform(junitBom))
  implementation("org.junit.platform:junit-platform-engine")
  implementation("org.junit.platform:junit-platform-commons")
  implementation("org.junit.platform:junit-platform-launcher")
  implementation("org.junit.jupiter:junit-jupiter-api")
  implementation("org.jetbrains:annotations:26.0.2") // @TestOnly in EagerParamsSwitches.kt
  // Jupiter engine internals: org.junit.jupiter.engine.Constants, .config.DefaultJupiterConfiguration,
  // .extension.MutableExtensionRegistry. compileOnly keeps this jar off every runtime classpath, so
  // the test source set has to ask for it again below.
  compileOnly("org.junit.jupiter:junit-jupiter-engine")

  // `fixturesImplementation` and friends are created by the java plugin for the source set above.
  // They must be addressed by string: the Kotlin DSL generates typed accessors from plugins, not from
  // configurations created in this script's own body.
  "fixturesImplementation"(platform(junitBom))
  "fixturesImplementation"("org.junit.jupiter:junit-jupiter-api")
  "fixturesImplementation"("org.junit.jupiter:junit-jupiter-params")
  "fixturesImplementation"("org.junit.platform:junit-platform-suite-api")

  testImplementation(sourceSets["fixtures"].output) // the classes only, never their dependencies
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.junit.platform:junit-platform-launcher")
  testImplementation("org.assertj:assertj-core:4.0.0-M1")
  // Not inherited, because main declares it compileOnly. testImplementation rather than
  // testRuntimeOnly: the test compilation is a Kotlin associate of main and so resolves main's
  // `internal` signatures, some of which mention MutableExtensionRegistry.
  testImplementation("org.junit.jupiter:junit-jupiter-engine")
  // The fixtures' own dependencies do not flow through `sourceSets.output`, but the nested launchers
  // load fixture classes at runtime and need them.
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.platform:junit-platform-suite")
}

tasks.test {
  useJUnitPlatform()

  // The engine under test self-registers through META-INF/services on this classpath, so these tests
  // run through it -- deliberate, and the same as in the monorepo. To take it out of the loop while
  // debugging an assertion use `useJUnitPlatform { excludeEngines("intellij-eager-params") }`, never
  // -Dintellij.test.eagerParams.enabled=false: that switch is process-global, the nested launchers in
  // EagerParamsTestSupport read it too, and every eager assertion would fail.

  // EagerRecorder, EagerEngineLatch, the property switches and the fixtures' counters are all
  // process-global mutable state. Keep this sequential, and never set
  // junit.jupiter.execution.parallel.enabled.
  maxParallelForks = 1

  // Gradle 9 default, restated because it is the safety net that matters here: if the engine and the
  // post-discovery filter ever conspire to hide everything, this fails instead of passing green.
  failOnNoDiscoveredTests = true

  testLogging {
    events("failed", "skipped")
    showStackTraces = true
    setExceptionFormat("full")
  }
}
