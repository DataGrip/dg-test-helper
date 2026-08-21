package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.EAGER_ENGINE_ID
import com.github.kassak.dg.eagerparams.fixtures.EagerAggregateFixture
import com.github.kassak.dg.eagerparams.fixtures.InheritsFieldFixture
import com.github.kassak.dg.eagerparams.fixtures.ThrowingFactoryFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The engine inside a `@Suite`, which is how every dbe test actually runs.
 *
 * A suite is not a mere wrapper: the launcher hands each nested engine an id built from the suite's own id, so
 * our root becomes `[engine:junit-platform-suite]/[suite:…]/[engine:intellij-eager-params]` and the engine
 * segment is no longer the first one. Everything the engine and the filter do with ids is therefore relative to
 * that root — the engine's translation to the id of its neighbouring Jupiter tree, and the filter's question of
 * which engine a descriptor belongs to. Read the *first* engine segment instead and both answer
 * `junit-platform-suite` for every node in sight: nothing is hidden from Jupiter, and every taken-over class
 * runs twice.
 *
 * That the outer filter reaches in at all is by design on the suite engine's side: it discovers eagerly, its
 * nested engines are children rather than roots, and it re-derives its own view from whatever survived
 * filtering (`SuiteTestDescriptor`, issue #2838).
 */
class EagerSuiteTest {
  @Test
  fun `a claimed class inside a suite has its whole tree right after discovery`() {
    val plan = EagerParamsTestSupport.discoverSuite(EagerAggregateFixture::class.java)

    assertThat(EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .containsExactly("base1", "base2")
  }

  @Test
  fun `our engine is rooted under the suite, not beside it`() {
    val plan = EagerParamsTestSupport.discoverSuite(EagerAggregateFixture::class.java)
    val eagerRoot = EagerParamsTestSupport.eagerRoot(plan)

    assertThat(eagerRoot).isNotNull
    assertThat(eagerRoot!!.uniqueId)
      .isEqualTo("[engine:$SUITE_ENGINE_ID]/[suite:${EagerAggregateFixture::class.java.name}]/[engine:$EAGER_ENGINE_ID]")
    // The same engines are registered at top level too, where the only selector was the suite class and they
    // resolved nothing. Those roots stay empty and cannot be pruned — `prune()` needs a parent — so the shape to
    // assert is that all the work sits under the suite.
    assertThat(plan.roots.filter { plan.getChildren(it).isNotEmpty() }.map { it.uniqueId })
      .containsExactly("[engine:$SUITE_ENGINE_ID]")
  }

  @Test
  fun `the suite's members are gone from its Jupiter subtree`() {
    val plan = EagerParamsTestSupport.discoverSuite(EagerAggregateFixture::class.java)

    // Both members are taken over — the enumerable one with its invocations, the other as a bare node to be
    // filled in while running. Anything left in the Jupiter subtree would run twice.
    assertThat(EagerParamsTestSupport.claimedClasses(plan))
      .containsExactlyInAnyOrder(InheritsFieldFixture::class.java.name, ThrowingFactoryFixture::class.java.name)
    assertThat(EagerParamsTestSupport.jupiterClasses(plan)).isEmpty()
  }

  @Test
  fun `each invocation of a claimed class inside a suite runs exactly once`() {
    val run = EagerParamsTestSupport.runSuite(EagerAggregateFixture::class.java)

    assertThat(run.invocationNames()).containsExactly("base1", "base2")
    assertThat(run.recorded).containsExactly("dropIn:base1", "dropIn:base2")
  }

  /** The unenumerable class must still run, once, and report its own failure once. */
  @Test
  fun `an unenumerable class inside a suite reports its failure once`() {
    val run = EagerParamsTestSupport.runSuite(EagerAggregateFixture::class.java)

    assertThat(run.failures()).hasSize(1)
    assertThat(run.failureMessages()).contains("factory blew up")
  }
}
