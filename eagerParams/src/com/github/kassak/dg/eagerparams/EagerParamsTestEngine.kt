package com.github.kassak.dg.eagerparams

import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.DiscoveryFilter
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.EngineDiscoveryListener
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.OutputDirectoryCreator
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.discovery.UniqueIdSelector
import org.junit.platform.engine.reporting.OutputDirectoryProvider
import java.util.ServiceLoader

/**
 * Mirrors the whole `junit-jupiter` tree at **discovery**, expanding every template into its invocations, and
 * delegates the running of it back to Jupiter.
 *
 * Jupiter cannot do that on its own: the invocations of a `@ClassTemplate` (hence `@ParameterizedClass`) or of
 * a `@TestTemplate` (hence `@ParameterizedTest` and `@RepeatedTest`) come from a provider that is handed an
 * `ExtensionContext`, i.e. one that only exists while executing. The IDE draws its tree from the `TestPlan`
 * produced by discovery, so it shows nothing under such a node and then makes invocations pop up one at a time.
 * For a class with hundreds of invocations that is unusable. This engine is an IDE convenience and nothing
 * else: it arrives by being on the test classpath and is switched off with
 * `-Dintellij.test.eagerParams.enabled=false`.
 *
 * **Every** Jupiter test is taken over, not just the parameterized ones. That is not a simplification, it is
 * required: the unit of takeover is whatever the filter can recognize from a descriptor, and a method template
 * lives inside an ordinary class. Taking over just the `[class:C]/[test-template:m()]` node would split class
 * `C` across two engines — two class nodes in the IDE, and `@BeforeAll`/`@AfterAll` and a `PER_CLASS` instance
 * run twice, because those are two independent Jupiter sessions. Deciding "does this class contain a template"
 * from the filter would mean reimplementing Jupiter's discovery rules and drifting from them. Taking over
 * everything gives the exact, stateless predicate `enclosingEngineId(id) == "junit-jupiter"`.
 *
 * How the delegation stays honest:
 * - our segment types mirror Jupiter's one to one, so ids translate by swapping the engine segment
 *   ([EagerIds]) and every selector, filter and reporter keeps working — including inside a `@Suite`, where
 *   both engines are rooted under the suite descriptor;
 * - the invocation count and every display name come from the node's own invocation context provider
 *   ([EagerTemplateEnumerator]), the same one the lazy path uses, so this engine has no opinion of its own
 *   about parameterization to drift out of sync;
 * - `@TestFactory` is the one shape with no provider to ask, because its tests are produced by the method
 *   body. Those are enumerated by running that body with the dynamic tests themselves skipped
 *   ([EagerFactoryEnumerator]) — the only place this engine executes user code to build a tree, hence a switch
 *   of its own, `-Dintellij.test.eagerParams.factories=false`;
 * - execution is one plain Jupiter request over translated ids, so arguments, injection and lifecycle
 *   callbacks are resolved by Jupiter and never by us, and one request means one engine-level store — hence
 *   one test application and one `@BeforeAll` per class;
 * - Jupiter's own copy of the tree is removed by [EagerParamsPostDiscoveryFilter], so nothing runs twice;
 * - a template whose invocations cannot be enumerated at discovery is still taken over, but as a node that
 *   fills itself in while running — laziness is all that is lost, and the errors are still Jupiter's own,
 *   reported once (see [EagerTemplateEnumerator]).
 */
class EagerParamsTestEngine : TestEngine {
  private val jupiterEngine: TestEngine by lazy {
    ServiceLoader.load(TestEngine::class.java, EagerParamsTestEngine::class.java.classLoader)
      .find { it.id == JUPITER_ENGINE_ID }
    ?: error("The $JUPITER_ENGINE_ID engine is not on the classpath")
  }

  override fun getId(): String = EAGER_ENGINE_ID

  override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
    val root = EagerEngineDescriptor(uniqueId)
    if (!isEagerParamsEnabled()) return root
    // From here on Jupiter's tree is ours to run; see EagerEngineLatch for why the filter waits for this.
    EagerEngineLatch.arm()
    val ids = EagerIds(uniqueId)

    // Rooted at the id the real, neighbouring Jupiter tree has, so the nodes we see are literally the nodes
    // the launcher is about to remove.
    val request = EagerDiscoveryRequest(discoveryRequest, translateSelectors(discoveryRequest, ids))
    val jupiterRoot = try {
      jupiterEngine.discover(request, ids.jupiterRoot)
    }
    catch (_: Throwable) {
      // The ordinary Jupiter engine gets the same request and will report the same problem; staying quiet
      // here avoids reporting it twice.
      return root
    }

    val mirroring = MirrorContext(
      EagerTemplateEnumerator(discoveryRequest.configurationParameters, discoveryRequest.outputDirectoryCreator),
      EagerInvocationSelection(discoveryRequest, ids),
      // Before mirroring, because a factory's children are the one thing that has to be *run* to be known,
      // and the probe wants the whole set of factory nodes in one pass — one Jupiter request, one engine
      // store, one test application, one `@BeforeAll` per class.
      EagerFactoryEnumerator(jupiterEngine, request, ids).enumerate(jupiterRoot),
      ids,
    )
    for (child in jupiterRoot.children) {
      root.addChild(mirror(uniqueId, child, mirroring))
    }
    root.discoveryRequest = request
    root.discoveredDescendants = descendantCount(root)
    return root
  }

  override fun execute(request: ExecutionRequest) {
    val root = request.rootTestDescriptor
    val listener = request.engineExecutionListener
    listener.executionStarted(root)
    val result = try {
      delegate(request, root, EagerIds(root.uniqueId)) ?: TestExecutionResult.successful()
    }
    catch (e: Throwable) {
      TestExecutionResult.failed(e)
    }
    listener.executionFinished(root, result)
  }

  /**
   * Runs everything that survived post-discovery filtering as **one** Jupiter request.
   *
   * One request rather than one per class on purpose: a single request means a single engine-level store,
   * hence one test application lifecycle and one `@BeforeAll` per class.
   */
  private fun delegate(request: ExecutionRequest, root: TestDescriptor, ids: EagerIds): TestExecutionResult? {
    val discoveryRequest = jupiterRequest(request, root, ids) ?: return null
    val jupiterRoot = jupiterEngine.discover(discoveryRequest, ids.jupiterRoot)
    // `discover` does not prune — the launcher does, and it is not in this loop. Without this the class
    // template descriptor keeps its children in `children` instead of `childrenPrototypes`, and the
    // invocations run with no children at all.
    jupiterRoot.accept { it.prune() }

    val translating = TranslatingEngineExecutionListener(root, ids, request.engineExecutionListener)
    jupiterEngine.execute(
      ExecutionRequest.create(
        jupiterRoot, translating, request.configurationParameters,
        request.outputDirectoryCreator, request.store,
      )
    )
    translating.reportUnvisited()
    return translating.engineResult
  }

  /**
   * The selectors to discover with: our own unique ids translated into Jupiter's, everything else untouched.
   *
   * Translating is what makes rerunning a single invocation from the IDE work, since the id the IDE remembers
   * is ours. There is no longer any bailing out — with every Jupiter test taken over, a request this engine
   * declined to look at would be a request whose tests nobody runs.
   */
  private fun translateSelectors(request: EngineDiscoveryRequest, ids: EagerIds): List<DiscoverySelector> =
    request.getSelectorsByType(DiscoverySelector::class.java).map { selector ->
      if (selector is UniqueIdSelector && ids.owns(selector.uniqueId)) {
        DiscoverySelectors.selectUniqueId(ids.toJupiter(selector.uniqueId))
      }
      else {
        selector
      }
    }

  /** What the mirroring pass needs to carry along; every entry is per-request. */
  private class MirrorContext(
    val enumerator: EagerTemplateEnumerator,
    val selection: EagerInvocationSelection,
    val factories: EagerFactoryChildren,
    val ids: EagerIds,
  )

  /**
   * Copies one Jupiter node — and its subtree — under [parentId], expanding templates on the way.
   *
   * The id is built by appending rather than by translating, because inside a materialized class template
   * invocation our path and Jupiter's diverge: the prototype children Jupiter parks under the class node are
   * copied under each invocation node instead.
   */
  private fun mirror(parentId: UniqueId, origin: TestDescriptor, context: MirrorContext): TestDescriptor {
    val id = parentId.append(origin.uniqueId.lastSegment)
    return when (origin.uniqueId.lastSegment.type) {
      CLASS_TEMPLATE_SEGMENT, NESTED_CLASS_TEMPLATE_SEGMENT -> mirrorClassTemplate(id, origin, context)
      TEST_TEMPLATE_SEGMENT -> mirrorMethodTemplate(id, origin, context)
      TEST_FACTORY_SEGMENT -> mirrorFactory(id, origin, context)
      else -> EagerMirrorDescriptor.mirror(id, origin).also { node ->
        for (child in origin.children) {
          node.addChild(mirror(id, child, context))
        }
      }
    }
  }

  /**
   * `[class-template:C]` → `[class-template-invocation:#N]` → the children Jupiter discovered, mirrored again.
   *
   * Jupiter puts the plain method descriptors straight under the class template node and only moves them into
   * invocation prototypes when pruning, so before pruning they are exactly the per-invocation children to
   * copy — and copying them through [mirror] is what expands a `@ParameterizedTest` inside a
   * `@ParameterizedClass` in both axes. A `UniqueIdSelector` naming one invocation makes Jupiter materialize
   * that invocation during discovery instead, with its own children; then only the selected invocations are
   * mirrored, so rerunning one of them reruns one of them.
   */
  private fun mirrorClassTemplate(id: UniqueId, origin: TestDescriptor, context: MirrorContext): TestDescriptor {
    val node = EagerMirrorDescriptor.template(id, origin)
    val names = context.enumerator.classInvocations(origin) ?: return node
    val source = origin.source.orElse(null)

    val (materialized, prototypes) = origin.children.partition {
      it.uniqueId.lastSegment.type == CLASS_TEMPLATE_INVOCATION_SEGMENT
    }
    val byIndex = materialized.mapNotNull { child -> invocationIndex(child.uniqueId)?.let { it to child } }.toMap()
    val indices = if (byIndex.isEmpty()) names.indices.map { it + 1 } else byIndex.keys.sorted()

    for (index in indices) {
      val name = names.getOrNull(index - 1) ?: continue
      val invocationId = id.append(CLASS_TEMPLATE_INVOCATION_SEGMENT, "#$index")
      val invocation = EagerMirrorDescriptor.invocation(invocationId, name, source, TestDescriptor.Type.CONTAINER)
      node.addChild(invocation)
      for (child in byIndex[index]?.children ?: prototypes) {
        invocation.addChild(mirror(invocationId, child, context))
      }
    }
    return node
  }

  /**
   * `[test-template:m()]` → `[test-template-invocation:#N]` leaves.
   *
   * Unlike a class template these have no children of their own — Jupiter's invocation descriptor is a
   * `TestMethodTestDescriptor`, a test — and unlike a class template Jupiter has materialized none of them at
   * discovery, so which ones to keep is decided from the request's selectors ([EagerInvocationSelection]).
   */
  private fun mirrorMethodTemplate(id: UniqueId, origin: TestDescriptor, context: MirrorContext): TestDescriptor {
    val node = EagerMirrorDescriptor.template(id, origin)
    val names = context.enumerator.methodInvocations(origin) ?: return node
    val source = origin.source.orElse(null)
    val accepts = context.selection.forTemplate(id, templateMethod(origin))

    for ((position, name) in names.withIndex()) {
      val index = position + 1
      val invocationId = id.append(TEST_TEMPLATE_INVOCATION_SEGMENT, "#$index")
      if (!accepts(invocationId, index)) continue
      node.addChild(EagerMirrorDescriptor.invocation(invocationId, name, source, TestDescriptor.Type.TEST))
    }
    return node
  }

  /**
   * `[test-factory:m()]` → the `dynamic-test` and `dynamic-container` nodes the probe run saw it produce.
   *
   * Nothing is forced here the way it is for a template: `TestFactoryTestDescriptor.mayRegisterTests()` is
   * already `true`, so a plain mirror survives pruning whether or not the probe found anything. With no probe
   * — switched off, or it failed — this is exactly the childless node of before, filled in while running.
   *
   * The children are looked up by the node's *Jupiter* id, which is what the probe reported. That works for a
   * factory inside a class template too: there our path runs through a materialized invocation node, and so
   * does Jupiter's, with the same `#N`.
   */
  private fun mirrorFactory(id: UniqueId, origin: TestDescriptor, context: MirrorContext): TestDescriptor {
    val node = EagerMirrorDescriptor.mirror(id, origin)
    addDynamicChildren(node, id, context.ids.toJupiter(id), context)
    return node
  }

  /** Copies one level of probed dynamic nodes and recurses, so a `dynamicContainer` keeps its own subtree. */
  private fun addDynamicChildren(node: TestDescriptor, id: UniqueId, jupiterId: UniqueId, context: MirrorContext) {
    for (child in context.factories[jupiterId]) {
      val childId = id.append(child.segment)
      val mirror = EagerMirrorDescriptor.snapshot(childId, child)
      node.addChild(mirror)
      addDynamicChildren(mirror, childId, jupiterId.append(child.segment), context)
    }
  }

  /**
   * What to hand Jupiter for the delegated run, or `null` when there is nothing left to run.
   *
   * When the tree still has every node it was discovered with — the ordinary case, and the only one in the
   * IDE — the answer is *the discovery request itself*, repeated. That is exact by construction: the same
   * selectors and the same filters produced the tree being mirrored, so replaying them reproduces it, down to
   * the narrowing a single-iteration rerun asked for. It also costs nothing to build.
   *
   * Only when something was removed — a CI post-discovery filter, a shard, a `TESTBRIDGE_TEST_ONLY` pattern —
   * does the delegation have to name the survivors, and then it names the *leaves*, one `UniqueIdSelector`
   * each. Naming an inner node instead would be wrong rather than merely coarse: re-selecting a class or a
   * template by its own id re-expands it in full, undoing whatever the filter had narrowed it to.
   */
  private fun jupiterRequest(request: ExecutionRequest, root: TestDescriptor, ids: EagerIds): EngineDiscoveryRequest? {
    if (root.children.isEmpty()) return null
    if (root is EagerEngineDescriptor && descendantCount(root) == root.discoveredDescendants) {
      root.discoveryRequest?.let { return it }
    }
    val selectors = leaves(root).map { DiscoverySelectors.selectUniqueId(ids.toJupiter(it.uniqueId)) }
    if (selectors.isEmpty()) return null
    return EagerExecutionDiscoveryRequest(request, selectors)
  }

  private fun leaves(root: TestDescriptor): List<TestDescriptor> {
    val found = mutableListOf<TestDescriptor>()
    for (child in root.children) {
      collectLeaves(child, found)
    }
    return found
  }

  private fun collectLeaves(node: TestDescriptor, out: MutableList<TestDescriptor>) {
    if (node.children.isEmpty()) {
      out.add(node)
      return
    }
    for (child in node.children) {
      collectLeaves(child, out)
    }
  }

  private fun descendantCount(node: TestDescriptor): Int = node.children.sumOf { 1 + descendantCount(it) }
}

/** The ambient request with our selector translation applied and discovery events silenced. */
private class EagerDiscoveryRequest(
  private val delegate: EngineDiscoveryRequest,
  private val selectors: List<DiscoverySelector>,
) : EngineDiscoveryRequest by delegate {
  override fun <T : DiscoverySelector> getSelectorsByType(selectorType: Class<T>): List<T> =
    selectors.filterIsInstance(selectorType)

  // The ordinary Jupiter engine gets the same request, so letting this discovery report as well would
  // duplicate every selector resolution and every discovery issue.
  override fun getDiscoveryListener(): EngineDiscoveryListener = EngineDiscoveryListener.NOOP

  override fun getOutputDirectoryCreator(): OutputDirectoryCreator = delegate.outputDirectoryCreator

  @Deprecated("Deprecated in Java")
  @Suppress("DEPRECATION")
  override fun getOutputDirectoryProvider(): OutputDirectoryProvider = delegate.outputDirectoryProvider
}

/** Selects exactly the survivors of our tree; the configuration and the store come from the execution request. */
private class EagerExecutionDiscoveryRequest(
  private val request: ExecutionRequest,
  private val selectors: List<DiscoverySelector>,
) : EngineDiscoveryRequest {
  override fun <T : DiscoverySelector> getSelectorsByType(selectorType: Class<T>): List<T> =
    selectors.filterIsInstance(selectorType)

  // Filtering already happened: these selectors *are* the result of it.
  override fun <T : DiscoveryFilter<*>> getFiltersByType(filterType: Class<T>): List<T> = emptyList()

  override fun getConfigurationParameters(): ConfigurationParameters = request.configurationParameters

  override fun getDiscoveryListener(): EngineDiscoveryListener = EngineDiscoveryListener.NOOP

  override fun getOutputDirectoryCreator(): OutputDirectoryCreator = request.outputDirectoryCreator

  @Deprecated("Deprecated in Java")
  @Suppress("DEPRECATION")
  override fun getOutputDirectoryProvider(): OutputDirectoryProvider = request.outputDirectoryProvider
}
