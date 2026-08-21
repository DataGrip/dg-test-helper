package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.DynamicChildrenFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A method template and a `@TestFactory` hanging off the same class template: both expanded, in every invocation.
 *
 * The two are enumerated by completely different means — the template by asking its provider, the factory by
 * running its body with the dynamic tests skipped — and they meet here, under a class-template invocation that
 * exists on our side only. So this is where it shows whether the factory probe agrees with the mirroring about
 * which invocation a dynamic node belongs to.
 *
 * The lazy fallback is asserted alongside, because it is what `-Dintellij.test.eagerParams.factories=false`
 * buys and it is still the path any factory the probe cannot enumerate takes.
 */
class EagerDynamicChildrenTest {
  @Test
  fun `both the method template and the factory are expanded inside every class invocation`() {
    val plan = EagerParamsTestSupport.discoverEager(DynamicChildrenFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  DynamicChildrenFixture",
      "    u",
      "      each(int)",
      "        [1] 1",
      "        [2] 2",
      "      generated()",
      "        generated u",
    )
  }

  @Test
  fun `nothing is left to announce dynamically`() {
    val run = EagerParamsTestSupport.runEager(DynamicChildrenFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("each:u:1", "each:u:2", "generated:u")
    // The whole tree was in the plan before execution started, which is the entire point of the engine.
    assertThat(run.eagerEvents().filter { it.kind == EagerEventKind.DYNAMIC }).isEmpty()
  }

  @Test
  fun `with factories switched off the factory's children arrive during execution and are translated onto our tree`() {
    val run = withEagerFactoriesDisabled { EagerParamsTestSupport.runEager(DynamicChildrenFixture::class.java) }

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("each:u:1", "each:u:2", "generated:u")

    val dynamic = run.eagerEvents().filter { it.kind == EagerEventKind.DYNAMIC }
    // Only the factory's own tests: the method template does not depend on this switch.
    assertThat(dynamic.map { it.segmentType }).containsOnly("dynamic-test")
    assertThat(dynamic.map { it.displayName }).containsExactly("generated u")
    // Registered under our engine, not Jupiter's: otherwise the IDE would drop them on the floor.
    assertThat(dynamic.map { it.id }).allSatisfy { assertThat(it).startsWith("[engine:intellij-eager-params]/") }
  }

  @Test
  fun `every dynamically registered node is also started and finished`() {
    val run = withEagerFactoriesDisabled { EagerParamsTestSupport.runEager(DynamicChildrenFixture::class.java) }

    val dynamic = run.eagerEvents().filter { it.kind == EagerEventKind.DYNAMIC }.map { it.id }
    val finished = run.eagerEvents().filter { it.kind == EagerEventKind.FINISHED }.map { it.id }
    assertThat(finished).containsAll(dynamic)
  }
}
