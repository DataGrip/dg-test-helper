package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.EAGER_ENGINE_ID
import com.github.kassak.dg.eagerparams.EagerParamsPostDiscoveryFilter
import com.github.kassak.dg.eagerparams.EagerParamsTestEngine
import com.github.kassak.dg.eagerparams.fixtures.EagerRecorder
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.util.Collections
import java.util.ServiceLoader

/**
 * Runs fixture classes in an isolated nested [org.junit.platform.launcher.Launcher] and records what happened.
 *
 * Isolated on purpose: nothing from the ambient run (auto-registered engines, listeners, filters,
 * implicit configuration parameters) may influence the assertions, and the fixtures — some of which
 * fail deliberately — must not leak into the surrounding module run.
 */
internal object EagerParamsTestSupport {
  /** The lazy path: plain Jupiter, no `intellij-eager-params` engine. */
  fun jupiterEngine(): TestEngine = engine("junit-jupiter")

  fun suiteEngine(): TestEngine = engine(SUITE_ENGINE_ID)

  private fun engine(id: String): TestEngine =
    ServiceLoader.load(TestEngine::class.java, EagerParamsTestSupport::class.java.classLoader)
      .firstOrNull { it.id == id }
    ?: error("the $id engine is not on the classpath")

  fun discover(vararg classes: Class<*>): TestPlan = discover(selectClasses(*classes), eager = false)

  fun run(vararg classes: Class<*>): EagerRun = run(selectClasses(*classes), eager = false)

  /** The eager path: our engine alongside Jupiter, with the filter that keeps claimed classes from running twice. */
  fun discoverEager(vararg classes: Class<*>): TestPlan = discover(selectClasses(*classes), eager = true)

  fun runEager(vararg classes: Class<*>): EagerRun = run(selectClasses(*classes), eager = true)

  /**
   * The same nested run as [discoverEager]/[runEager], but with the `junit-platform-suite` engine added.
   *
   * That engine is off by default because its root descriptor survives pruning even when it resolved nothing,
   * and an extra plan root would change what every other assertion here is looking at.
   */
  fun discoverSuite(vararg suiteClasses: Class<*>): TestPlan =
    discover(selectClasses(*suiteClasses), eager = true, suites = true)

  fun runSuite(vararg suiteClasses: Class<*>): EagerRun =
    run(selectClasses(*suiteClasses), eager = true, suites = true)

  fun discover(selectors: List<DiscoverySelector>, eager: Boolean, suites: Boolean = false): TestPlan =
    launcher(eager, suites).discover(request(selectors))

  /**
   * @param discoverBeforeRun discover once before executing, the way the IDE runner does — it must not double
   *   the tree. It *does* enumerate the argument sources again (see `EagerDoubleEnumerationTest`), so tests that
   *   count enumerations pass `false` and read the plan `execute` built instead.
   */
  fun run(
    selectors: List<DiscoverySelector>,
    eager: Boolean,
    extraListeners: List<TestExecutionListener> = emptyList(),
    suites: Boolean = false,
    discoverBeforeRun: Boolean = true,
  ): EagerRun {
    EagerRecorder.reset()
    val recording = RecordingListener()
    val launcher = launcher(eager, suites)
    val plan = if (discoverBeforeRun) launcher.discover(request(selectors)) else null
    launcher.execute(request(selectors), recording, *extraListeners.toTypedArray())
    val finalPlan = recording.plan ?: plan ?: error("execution reported no test plan")
    return EagerRun(plan ?: finalPlan, finalPlan, recording.events.toList(), EagerRecorder.snapshot())
  }

  fun selectClasses(vararg classes: Class<*>): List<DiscoverySelector> = classes.map { DiscoverySelectors.selectClass(it) }

  /** The root of our engine's subtree; absent means the engine claimed nothing. */
  fun eagerRoot(plan: TestPlan): TestIdentifier? = root(plan, EAGER_ENGINE_ID)

  fun jupiterRoot(plan: TestPlan): TestIdentifier? = root(plan, "junit-jupiter")

  /**
   * The whole subtree as `display name` lines indented by depth — the shape the IDE would draw.
   *
   * Assertions on this are the point of the exercise: on the eager path the tree must be complete right
   * after discovery.
   */
  fun tree(plan: TestPlan, root: TestIdentifier?, indent: String = ""): List<String> {
    if (root == null) return emptyList()
    val lines = mutableListOf("$indent${root.displayName}")
    // Siblings are sorted, not left in tree order: Jupiter resolves test methods in reflection order, which
    // is not the declaration order and not stable across JVMs. Ordering that this engine *is* responsible
    // for — the invocation indices — is asserted separately, on unique ids.
    for (child in plan.getChildren(root).sortedBy { it.displayName }) {
      lines.addAll(tree(plan, child, "$indent  "))
    }
    return lines
  }

  /**
   * FQCNs of the classes our engine took over, empty when it claimed nothing.
   *
   * Not the same question as "is [eagerRoot] there": an engine descriptor is a plan root, and `prune()` only
   * removes descriptors that have a parent, so our root shows up childless even when it claimed nothing.
   */
  fun claimedClasses(plan: TestPlan): List<String> {
    val root = eagerRoot(plan) ?: return emptyList()
    return plan.getChildren(root).map { it.uniqueIdObject.lastSegment.value }
  }

  /** FQCNs of the classes left for plain Jupiter to run. */
  fun jupiterClasses(plan: TestPlan): List<String> {
    val root = jupiterRoot(plan) ?: return emptyList()
    return plan.getChildren(root).map { it.uniqueIdObject.lastSegment.value }
  }

  /**
   * Display names of every invocation node already present in [plan] under [root], in tree order.
   *
   * Empty on the lazy path — that is the defect — and complete on the eager one, which is the fix. [segments]
   * picks which kind of template is being asked about; the two kinds are separate questions, because a class
   * template's invocations are containers and a method template's are tests.
   */
  fun plannedInvocations(
    plan: TestPlan,
    root: TestIdentifier?,
    segments: Set<String> = setOf(CLASS_INVOCATION_SEGMENT),
  ): List<String> {
    if (root == null) return emptyList()
    val names = mutableListOf<String>()
    collectInvocations(plan, root, segments, names)
    return names
  }

  private fun collectInvocations(
    plan: TestPlan,
    node: TestIdentifier,
    segments: Set<String>,
    into: MutableList<String>,
  ) {
    if (node.uniqueIdObject.lastSegment.type in segments) into.add(node.displayName)
    for (child in plan.getChildren(node)) collectInvocations(plan, child, segments, into)
  }

  /** The child of [parent] with the given display name. */
  fun child(plan: TestPlan, parent: TestIdentifier, displayName: String): TestIdentifier =
    plan.getChildren(parent).firstOrNull { it.displayName == displayName }
    ?: error("no child '$displayName' under '${parent.displayName}', only ${plan.getChildren(parent).map { it.displayName }}")

  /**
   * The `[engine:…]` node with the given id, wherever it sits.
   *
   * Not `plan.roots` filtered by [org.junit.platform.engine.UniqueId.getEngineId]: inside a `@Suite` the
   * engines are not roots at all but children of the suite descriptor, and their ids start with
   * `[engine:junit-platform-suite]`. Searching by the *last* segment covers both shapes.
   *
   * A suite run has the same engine twice over — once nested under the suite, where the work is, and once as
   * an empty top-level root, because an engine handed a selector it resolved nothing from still contributes a
   * root and `prune()` cannot remove a descriptor that has no parent. The deepest match is the interesting one.
   */
  private fun root(plan: TestPlan, engineId: String): TestIdentifier? =
    plan.roots.asSequence()
      .flatMap { sequenceOf(it) + plan.getDescendants(it).asSequence() }
      .filter { it.uniqueIdObject.lastSegment.let { segment -> segment.type == ENGINE_SEGMENT && segment.value == engineId } }
      .maxByOrNull { it.uniqueIdObject.segments.size }

  private fun launcher(eager: Boolean, suites: Boolean): Launcher {
    val builder = LauncherConfig.builder()
      .enableTestEngineAutoRegistration(false)
      .enableTestExecutionListenerAutoRegistration(false)
      .enablePostDiscoveryFilterAutoRegistration(false)
      .enableLauncherDiscoveryListenerAutoRegistration(false)
      .enableLauncherSessionListenerAutoRegistration(false)
    if (suites) builder.addTestEngines(suiteEngine())
    if (eager) {
      builder.addTestEngines(EagerParamsTestEngine(), jupiterEngine())
        .addPostDiscoveryFilters(EagerParamsPostDiscoveryFilter())
    }
    else {
      builder.addTestEngines(jupiterEngine())
    }
    return LauncherFactory.create(builder.build())
  }

  private fun request(selectors: List<DiscoverySelector>) =
    LauncherDiscoveryRequestBuilder.request()
      .enableImplicitConfigurationParameters(false)
      .selectors(selectors)
      .build()
}

/** The switches, spelled out rather than imported: they are documented system properties, not API. */
internal const val ENABLED_PROPERTY: String = "intellij.test.eagerParams.enabled"

internal const val FACTORIES_PROPERTY: String = "intellij.test.eagerParams.factories"

/**
 * Runs [action] with the mechanism switched off, then restores the property.
 *
 * The other half of every drop-in comparison: same jar, same classes, same launcher — only the switch differs.
 */
internal fun <T> withEagerDisabled(action: () -> T): T = withProperty(ENABLED_PROPERTY, "false", action)

/**
 * Runs [action] with `@TestFactory` enumeration switched off, the rest of the mechanism untouched.
 *
 * Its own switch because its own cost: this is the only enumeration that runs user code, so someone has to be
 * able to decline it and keep everything else eager.
 */
internal fun <T> withEagerFactoriesDisabled(action: () -> T): T = withProperty(FACTORIES_PROPERTY, "false", action)

private fun <T> withProperty(key: String, value: String, action: () -> T): T {
  val previous = System.setProperty(key, value)
  try {
    return action()
  }
  finally {
    if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
  }
}

/** A stand-in Jupiter class-template node, for asking the filter about a descriptor without discovering one. */
internal fun jupiterClassTemplate(testClass: Class<*>): TestDescriptor =
  descriptor(UniqueId.forEngine("junit-jupiter").append("class-template", testClass.name))

internal fun descriptor(uniqueId: UniqueId): TestDescriptor =
  object : AbstractTestDescriptor(uniqueId, uniqueId.lastSegment.value) {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER
  }

/** The segment types Jupiter gives the invocations of a class and of a method template; ours mirror them. */
internal const val CLASS_INVOCATION_SEGMENT: String = "class-template-invocation"

internal const val TEMPLATE_INVOCATION_SEGMENT: String = "test-template-invocation"

internal val BOTH_INVOCATION_SEGMENTS: Set<String> = setOf(CLASS_INVOCATION_SEGMENT, TEMPLATE_INVOCATION_SEGMENT)

internal const val ENGINE_SEGMENT: String = "engine"

internal const val SUITE_ENGINE_ID: String = "junit-platform-suite"

internal enum class EagerEventKind { STARTED, FINISHED, SKIPPED, DYNAMIC }

internal class EagerEvent(
  val kind: EagerEventKind,
  val id: String,
  val displayName: String,
  val isTest: Boolean,
  /** Type of the last unique id segment, e.g. `class-template-invocation` or `method`. */
  val segmentType: String,
  val segmentValue: String,
  val status: TestExecutionResult.Status? = null,
  val throwable: Throwable? = null,
  val skipReason: String? = null,
) {
  override fun toString(): String = "$kind $displayName${status?.let { " -> $it" } ?: ""}"
}

/** Everything one nested run produced: the discovery-time plan, the final plan, events and fixture output. */
internal class EagerRun(
  val discoveredPlan: TestPlan,
  val finalPlan: TestPlan,
  val events: List<EagerEvent>,
  val recorded: List<String>,
) {
  /** Display names of started leaves, in event order. */
  fun startedTests(): List<String> = events.filter { it.kind == EagerEventKind.STARTED && it.isTest }.map { it.displayName }

  /** Display names of started containers, in event order. */
  fun startedContainers(): List<String> =
    events.filter { it.kind == EagerEventKind.STARTED && !it.isTest }.map { it.displayName }

  fun dynamicallyRegistered(): List<String> = events.filter { it.kind == EagerEventKind.DYNAMIC }.map { it.displayName }

  /**
   * Display names of the invocations that were *started*, in event order.
   *
   * The one list that is directly comparable between the two paths: on the lazy path the invocations are
   * registered dynamically and on the eager path they come out of the plan, but both start them, and both
   * start them with the name Jupiter's own formatter produced.
   */
  fun invocationNames(segments: Set<String> = setOf(CLASS_INVOCATION_SEGMENT)): List<String> = events
    .filter { it.kind == EagerEventKind.STARTED && it.segmentType in segments }
    .map { it.displayName }

  /**
   * Only the events our engine produced; on the eager path Jupiter reports its own subtree alongside.
   *
   * `contains` rather than `startsWith`: inside a `@Suite` our engine segment is preceded by the suite's.
   */
  fun eagerEvents(): List<EagerEvent> = events.filter { it.id.contains("[engine:$EAGER_ENGINE_ID]") }

  fun failures(): List<EagerEvent> = events.filter { it.status == TestExecutionResult.Status.FAILED }

  fun skipped(): List<EagerEvent> = events.filter { it.kind == EagerEventKind.SKIPPED }

  /** The single failure of this run; fails loudly when there is not exactly one. */
  fun singleFailure(): Throwable {
    val failures = failures()
    check(failures.size == 1) { "expected exactly one failure, got ${failures.size}: $failures" }
    return checkNotNull(failures.single().throwable) { "failure without a throwable" }
  }

  /** All failure messages joined, for substring assertions that do not care where the error landed. */
  fun failureMessages(): String = failures().joinToString("\n") { causeChain(it.throwable) }

  private fun causeChain(throwable: Throwable?): String {
    val messages = mutableListOf<String>()
    var current = throwable
    while (current != null && messages.size < 10) {
      messages.add("${current.javaClass.name}: ${current.message}")
      current = current.cause.takeIf { it !== current }
    }
    return messages.joinToString(" <- ")
  }

  /** Display names of the plan children of the only class-template node, empty on the lazy path before execution. */
  fun childrenOf(plan: TestPlan, identifier: TestIdentifier): List<String> =
    plan.getChildren(identifier).map { it.displayName }

  fun classNode(plan: TestPlan): TestIdentifier {
    val engineRoot = plan.roots.single()
    return plan.getChildren(engineRoot).single()
  }
}

private class RecordingListener : TestExecutionListener {
  val events: MutableList<EagerEvent> = Collections.synchronizedList(mutableListOf())
  var plan: TestPlan? = null

  override fun testPlanExecutionStarted(testPlan: TestPlan) {
    plan = testPlan
  }

  override fun executionStarted(testIdentifier: TestIdentifier) {
    add(EagerEventKind.STARTED, testIdentifier)
  }

  override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
    add(EagerEventKind.FINISHED, testIdentifier) {
      copyOf(it, status = testExecutionResult.status, throwable = testExecutionResult.throwable.orElse(null))
    }
  }

  override fun executionSkipped(testIdentifier: TestIdentifier, reason: String?) {
    add(EagerEventKind.SKIPPED, testIdentifier) { copyOf(it, skipReason = reason) }
  }

  override fun dynamicTestRegistered(testIdentifier: TestIdentifier) {
    add(EagerEventKind.DYNAMIC, testIdentifier)
  }

  private fun add(
    kind: EagerEventKind,
    identifier: TestIdentifier,
    enrich: (EagerEvent) -> EagerEvent = { it },
  ) {
    val segment = identifier.uniqueIdObject.lastSegment
    events.add(
      enrich(
        EagerEvent(kind, identifier.uniqueId, identifier.displayName, identifier.isTest, segment.type, segment.value)
      )
    )
  }

  private fun copyOf(
    event: EagerEvent,
    status: TestExecutionResult.Status? = null,
    throwable: Throwable? = null,
    skipReason: String? = null,
  ) = EagerEvent(
    event.kind, event.id, event.displayName, event.isTest, event.segmentType, event.segmentValue,
    status = status, throwable = throwable, skipReason = skipReason,
  )
}
