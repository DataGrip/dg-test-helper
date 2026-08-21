package com.github.kassak.dg.eagerparams

import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.TestTag
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.EngineDescriptor

/**
 * The engine's own root, which also carries what execution needs to know about discovery.
 *
 * Both fields exist for the same decision, made in [EagerParamsTestEngine.execute]: has anything been taken
 * *out* of this tree since it was built? If not, the delegated run can simply repeat [discoveryRequest] and
 * get the identical tree back — no selectors to synthesize, no third discovery to pay for. If something was
 * removed, whatever it was (a CI filter, a shard, a `TESTBRIDGE_TEST_ONLY` pattern), the delegation has to
 * name what is left instead.
 */
internal class EagerEngineDescriptor(uniqueId: UniqueId) : EngineDescriptor(uniqueId, "Eager Parameterized Classes") {
  /** The request this tree was discovered from, or `null` when discovery never got that far. */
  var discoveryRequest: EngineDiscoveryRequest? = null

  /** How many descendants the tree had when discovery finished; post-discovery filters can only lower it. */
  var discoveredDescendants: Int = 0
}

/**
 * A stand-in for a Jupiter descriptor, carrying over everything anybody downstream reads.
 *
 * `source` and `legacyReportingName` are what the IDE navigates by and what the CI post-discovery filters
 * (buckets, shards, performance, ignore lists) and the TeamCity reporter key on, so they are copied rather
 * than recomputed: whatever Jupiter decided stays true.
 */
internal class EagerMirrorDescriptor private constructor(
  uniqueId: UniqueId,
  displayName: String,
  source: TestSource?,
  private val descriptorType: TestDescriptor.Type,
  private val reportingName: String,
  private val descriptorTags: Set<TestTag>,
  private val registersTests: Boolean,
) : AbstractTestDescriptor(uniqueId, displayName, source) {
  override fun getType(): TestDescriptor.Type = descriptorType

  override fun getLegacyReportingName(): String = reportingName

  override fun getTags(): Set<TestTag> = descriptorTags

  override fun mayRegisterTests(): Boolean = registersTests

  companion object {
    /** Mirrors [origin] under [uniqueId], keeping its name unless [displayName] overrides it. */
    fun mirror(uniqueId: UniqueId, origin: TestDescriptor, displayName: String = origin.displayName): EagerMirrorDescriptor =
      EagerMirrorDescriptor(
        uniqueId, displayName, origin.source.orElse(null), origin.type,
        origin.legacyReportingName, origin.tags, origin.mayRegisterTests(),
      )

    /**
     * Mirrors a template node — one whose invocations may or may not have been enumerated eagerly.
     *
     * `mayRegisterTests` cannot be copied here the way [mirror] copies it: Jupiter's class template descriptor
     * reports `false` until it is pruned, because that is when it moves its children into invocation
     * prototypes, and a childless node that registers nothing is exactly what `prune()` deletes. So this node
     * says up front what it is about to do, whether or not we managed to fill it in; anything we could not
     * enumerate arrives from the delegated run through `dynamicTestRegistered`.
     */
    fun template(uniqueId: UniqueId, origin: TestDescriptor): EagerMirrorDescriptor =
      EagerMirrorDescriptor(
        uniqueId, origin.displayName, origin.source.orElse(null), origin.type,
        origin.legacyReportingName, origin.tags, true,
      )

    /**
     * A node that has no Jupiter counterpart yet: one invocation of a class or method template.
     *
     * Jupiter creates these only while executing, which is the whole reason this engine exists. The template's
     * own source is reused so that the IDE can navigate from the invocation node to the class or method.
     */
    fun invocation(
      uniqueId: UniqueId,
      displayName: String,
      source: TestSource?,
      type: TestDescriptor.Type,
    ): EagerMirrorDescriptor =
      EagerMirrorDescriptor(uniqueId, displayName, source, type, displayName, emptySet(), false)

    /** Mirrors a node that was observed earlier and is no longer around to be read; see [EagerNodeSnapshot]. */
    fun snapshot(uniqueId: UniqueId, snapshot: EagerNodeSnapshot): EagerMirrorDescriptor =
      EagerMirrorDescriptor(
        uniqueId, snapshot.displayName, snapshot.source, snapshot.type,
        snapshot.legacyReportingName, snapshot.tags, snapshot.mayRegisterTests,
      )
  }
}

/**
 * Everything the engine needs about a descriptor it saw once, read out while it was still valid.
 *
 * The dynamic nodes a `@TestFactory` produces are observed during [EagerFactoryEnumerator]'s probe run, whose
 * descriptors are transient — `DynamicTestTestDescriptor` deliberately drops its `DynamicTest`, and with it the
 * closure over the test instance, as soon as the node is done — so they are read into values on the spot rather
 * than held on to. The segment, not the whole id, because the mirrored parent's path may differ: a factory
 * inside a class template lives under an invocation node on our side.
 */
internal class EagerNodeSnapshot(
  val segment: UniqueId.Segment,
  val displayName: String,
  val source: TestSource?,
  val type: TestDescriptor.Type,
  val legacyReportingName: String,
  val tags: Set<TestTag>,
  val mayRegisterTests: Boolean,
) {
  companion object {
    fun of(descriptor: TestDescriptor): EagerNodeSnapshot = EagerNodeSnapshot(
      descriptor.uniqueId.lastSegment, descriptor.displayName, descriptor.source.orElse(null),
      descriptor.type, descriptor.legacyReportingName, descriptor.tags, descriptor.mayRegisterTests(),
    )
  }
}
