package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.ParameterizedMethodFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors

/**
 * Rerunning **one** iteration of a method template — the operation the IDE performs on a double-click.
 *
 * Jupiter selects invocations with `DynamicDescendantFilter`, which `MethodSelectorResolver` fills in from the
 * request: a `UniqueIdSelector` reaching inside the template node, or an `IterationSelector` over its method.
 * That filter is package-private and, for a method template, has nothing to act on at discovery — the node has
 * no children yet — so the engine replicates the selection while mirroring.
 *
 * Getting it wrong is not a small thing: the eager tree would show all N invocations while the delegated run
 * ran one, so the IDE would report N−1 skipped tests for every single-iteration rerun.
 */
class EagerIterationSelectorTest {
  private val method = DiscoverySelectors.selectMethod(
    ParameterizedMethodFixture::class.java, "each", "java.lang.String",
  )

  /** `selectIteration` indices are 0-based; invocation ids are 1-based. */
  @Test
  fun `an iteration selector leaves exactly that invocation in the tree`() {
    val plan = EagerParamsTestSupport.discover(listOf(DiscoverySelectors.selectIteration(method, 1)), eager = true)

    assertThat(EagerParamsTestSupport.plannedInvocations(
      plan, EagerParamsTestSupport.eagerRoot(plan), setOf(TEMPLATE_INVOCATION_SEGMENT),
    )).containsExactly("m2")
    assertThat(plan.countTestIdentifiers { it.isTest }).isEqualTo(1)
  }

  @Test
  fun `and only that invocation runs, with nothing skipped`() {
    val run = EagerParamsTestSupport.run(listOf(DiscoverySelectors.selectIteration(method, 1)), eager = true)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactly("param:m2")
    assertThat(run.startedTests()).containsExactly("m2")
    assertThat(run.skipped()).isEmpty()
  }

  /**
   * The other way the IDE reruns one invocation: by the unique id it remembered from the last run.
   *
   * That id is *ours*, so the engine translates it into Jupiter's before discovering with it — and the mirroring
   * pass matches it against the invocation ids it is about to build.
   */
  @Test
  fun `a unique id selector naming one invocation leaves exactly that one`() {
    val full = EagerParamsTestSupport.discoverEager(ParameterizedMethodFixture::class.java)
    val root = EagerParamsTestSupport.eagerRoot(full)!!
    val template = full.getChildren(full.getChildren(root).single()).single()
    val second = EagerParamsTestSupport.child(full, template, "m2")

    val run = EagerParamsTestSupport.run(listOf(DiscoverySelectors.selectUniqueId(second.uniqueId)), eager = true)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactly("param:m2")
    assertThat(EagerParamsTestSupport.plannedInvocations(
      run.discoveredPlan, EagerParamsTestSupport.eagerRoot(run.discoveredPlan), setOf(TEMPLATE_INVOCATION_SEGMENT),
    )).containsExactly("m2")
    assertThat(run.skipped()).isEmpty()
  }

  /** The default: a selector that merely reaches the template node keeps every invocation, as `allowAll` would. */
  @Test
  fun `selecting the method itself keeps every invocation`() {
    val run = EagerParamsTestSupport.run(listOf(method), eager = true)

    assertThat(run.recorded).containsExactly("param:m1", "param:m2")
    assertThat(run.skipped()).isEmpty()
  }
}
