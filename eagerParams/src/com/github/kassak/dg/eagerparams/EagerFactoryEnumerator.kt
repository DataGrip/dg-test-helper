package com.github.kassak.dg.eagerparams

import org.junit.jupiter.engine.Constants
import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.DiscoveryFilter
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.EngineDiscoveryListener
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.OutputDirectoryCreator
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.discovery.UniqueIdSelector
import org.junit.platform.engine.reporting.OutputDirectoryProvider
import org.junit.platform.engine.support.store.Namespace
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore
import java.util.Optional

/**
 * The dynamic children of every `@TestFactory` node, keyed by the **Jupiter** id of their parent.
 *
 * Keyed by Jupiter id rather than ours because that is the id the probe run reports and the only one both
 * sides can compute: [EagerIds.toJupiter] of a node being mirrored gives exactly the key to look up, whether
 * the factory sits in a plain class or inside a materialized class-template invocation.
 */
internal class EagerFactoryChildren(private val byParent: Map<UniqueId, List<EagerNodeSnapshot>>) {
  operator fun get(jupiterId: UniqueId): List<EagerNodeSnapshot> = byParent[jupiterId] ?: emptyList()

  companion object {
    val EMPTY: EagerFactoryChildren = EagerFactoryChildren(emptyMap())
  }
}

/**
 * Enumerates `@TestFactory` methods at discovery by **running them**, with their dynamic tests skipped.
 *
 * Every other kind of template can be asked what its invocations would be called — a provider takes an
 * `ExtensionContext` and returns contexts with display names, and [EagerTemplateEnumerator] does nothing but
 * ask. A dynamic test has no provider and no name until the factory body has produced it, so there is nothing
 * to ask: the body has to run. This is the one place where the engine executes user code in order to build a
 * tree, which is why it has a switch of its own ([isEagerFactoriesEnabled]).
 *
 * What the probe run is, precisely:
 * - **only factory methods are selected** — one `UniqueIdSelector` per `[test-factory:…]` node found in the
 *   tree being mirrored, so no other test, template or method is even resolved;
 * - the dynamic tests are **registered but not executed**, because [EagerFactoryProbe] is injected into the
 *   run and skips them (`interceptDynamicTest`), while the factory method itself proceeds;
 * - the injection is local to this run: the ambient configuration is wrapped with Jupiter's extension
 *   autodetection enabled and its include pattern set to that one class, plus [PROBE_PARAMETER] so the
 *   interceptor can tell a probe from a real run. Nothing global is touched;
 * - anything that goes wrong is swallowed, and the factory node keeps the childless shape it has without this
 *   pass — its tests then arrive as `dynamicTestRegistered` while running, exactly as before.
 *
 * What it costs, which is the reason for the switch: for a class with a factory the probe runs the class
 * constructor, `@BeforeAll`/`@AfterAll`, the factory's `@BeforeEach`/`@AfterEach` and every extension callback
 * around them — and then the real run does it all again. For a factory inside a `@ParameterizedClass` that is
 * once per class invocation.
 */
internal class EagerFactoryEnumerator(
  private val jupiterEngine: TestEngine,
  private val request: EngineDiscoveryRequest,
  private val ids: EagerIds,
) {
  /** Ids the request narrows to, in Jupiter's space; `toJupiter` is a no-op for ids that are already there. */
  private val selectedIds: List<UniqueId> by lazy {
    request.getSelectorsByType(UniqueIdSelector::class.java).map { ids.toJupiter(it.uniqueId) }
  }

  fun enumerate(jupiterRoot: TestDescriptor): EagerFactoryChildren {
    if (!isEagerFactoriesEnabled()) return EagerFactoryChildren.EMPTY
    val factoryIds = mutableListOf<UniqueId>()
    jupiterRoot.accept { descriptor ->
      if (descriptor.uniqueId.lastSegment.type == TEST_FACTORY_SEGMENT) factoryIds.add(descriptor.uniqueId)
    }
    if (factoryIds.isEmpty()) return EagerFactoryChildren.EMPTY
    return try {
      probe(selectors(factoryIds))
    }
    catch (_: Throwable) {
      // Every factory node stays childless and fills itself in while running, which is the behaviour without
      // this pass. The real run reports whatever the real problem is, once.
      EagerFactoryChildren.EMPTY
    }
  }

  /**
   * What to probe: every factory node, unless the request is already reaching inside one.
   *
   * Rerunning a single dynamic test from the IDE selects it by unique id, and that id names a node under a
   * factory. Handing Jupiter those ids instead of the factory's own is what makes the probe narrow the same
   * way the run will: `MethodSelectorResolver` turns them into `allowUniqueIdPrefix` on the factory's
   * `DynamicDescendantFilter`, so only the selected dynamic nodes are registered — and therefore only they end
   * up in the tree, instead of N nodes of which N−1 will report as skipped.
   */
  private fun selectors(factoryIds: List<UniqueId>): List<DiscoverySelector> {
    val narrowed = selectedIds.filter { id -> id.segments.any { it.type == TEST_FACTORY_SEGMENT } }
    val chosen = narrowed.ifEmpty { factoryIds }
    return chosen.map { DiscoverySelectors.selectUniqueId(it) }
  }

  private fun probe(selectors: List<DiscoverySelector>): EagerFactoryChildren {
    if (selectors.isEmpty()) return EagerFactoryChildren.EMPTY
    val configuration = ProbeConfigurationParameters(request.configurationParameters)
    val root = jupiterEngine.discover(
      ProbeDiscoveryRequest(selectors, configuration, request),
      ids.jupiterRoot,
    )
    // As in the delegated run: `discover` does not prune, and an unpruned class template keeps its children
    // where the invocations would otherwise pick them up from.
    root.accept { it.prune() }
    if (root.children.isEmpty()) return EagerFactoryChildren.EMPTY

    val collected = CollectingExecutionListener()
    jupiterEngine.execute(
      ExecutionRequest.create(root, collected, configuration, request.outputDirectoryCreator, probeStore())
    )
    return EagerFactoryChildren(collected.byParent)
  }

  /**
   * A store of the probe's own, two levels deep and never closed.
   *
   * Two levels because `LauncherStoreFacade` insists the request-level store have a parent — Jupiter reads the
   * session level through it. Never closed, and with no close action, on purpose: whatever an extension put
   * there belongs to the run that is about to happen for real. Closing it would, for dbe, dispose the test
   * application the probe just created only for the real run to create it again.
   */
  private fun probeStore(): NamespacedHierarchicalStore<Namespace> =
    NamespacedHierarchicalStore(NamespacedHierarchicalStore<Namespace>(null))

  /** Collects the descriptors the probe registered, as values: the descriptors themselves are recycled. */
  private class CollectingExecutionListener : EngineExecutionListener {
    val byParent: MutableMap<UniqueId, MutableList<EagerNodeSnapshot>> = LinkedHashMap()

    override fun dynamicTestRegistered(testDescriptor: TestDescriptor) {
      val id = testDescriptor.uniqueId
      if (id.segments.size < 2) return
      byParent.getOrPut(id.removeLastSegment()) { mutableListOf() }.add(EagerNodeSnapshot.of(testDescriptor))
    }
    // Everything else is deliberately ignored: a factory that throws simply contributes nothing, and the real
    // run is where that gets reported.
  }

  /**
   * The ambient parameters plus what the probe needs, and nothing else.
   *
   * `autodetection.enabled` alone would register every `Extension` service on the classpath; the include
   * pattern narrows that to [EagerFactoryProbe]. Parallel execution is turned off so the order in which
   * dynamic nodes are registered is the order the factory produced them.
   */
  private class ProbeConfigurationParameters(private val delegate: ConfigurationParameters) : ConfigurationParameters {
    private val overrides = mapOf(
      Constants.EXTENSIONS_AUTODETECTION_ENABLED_PROPERTY_NAME to "true",
      Constants.EXTENSIONS_AUTODETECTION_INCLUDE_PROPERTY_NAME to EagerFactoryProbe::class.java.name,
      Constants.PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME to "false",
      PROBE_PARAMETER to "true",
    )

    override fun get(key: String): Optional<String> =
      overrides[key]?.let { Optional.of(it) } ?: delegate.get(key)

    override fun getBoolean(key: String): Optional<Boolean> =
      overrides[key]?.let { Optional.of(it.toBoolean()) } ?: delegate.getBoolean(key)

    @Deprecated("Deprecated in Java")
    override fun size(): Int = keySet().size

    override fun keySet(): Set<String> = delegate.keySet() + overrides.keys
  }

  /** Selects exactly the factory nodes; filtering already happened when the tree being mirrored was built. */
  private class ProbeDiscoveryRequest(
    private val selectors: List<DiscoverySelector>,
    private val configuration: ConfigurationParameters,
    private val source: EngineDiscoveryRequest,
  ) : EngineDiscoveryRequest {
    override fun <T : DiscoverySelector> getSelectorsByType(selectorType: Class<T>): List<T> =
      selectors.filterIsInstance(selectorType)

    override fun <T : DiscoveryFilter<*>> getFiltersByType(filterType: Class<T>): List<T> = emptyList()

    override fun getConfigurationParameters(): ConfigurationParameters = configuration

    override fun getDiscoveryListener(): EngineDiscoveryListener = EngineDiscoveryListener.NOOP

    override fun getOutputDirectoryCreator(): OutputDirectoryCreator = source.outputDirectoryCreator

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getOutputDirectoryProvider(): OutputDirectoryProvider = source.outputDirectoryProvider
  }
}
