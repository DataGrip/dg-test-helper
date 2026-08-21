package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.BeforeAllCountingFixture
import com.github.kassak.dg.eagerparams.fixtures.PlainJupiterFixture
import com.github.kassak.dg.eagerparams.fixtures.TwoMethodsFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Classes the engine has nothing to gain from, which is most of them.
 *
 * Taking over everything is what makes the filter's predicate exact, and the price is that every ordinary class
 * now travels through this engine. So the interesting assertions are all negative: same tree, same order, same
 * lifecycle, nothing announced dynamically, nothing skipped.
 *
 * The `@BeforeAll` counter is the regression test for the reason the takeover is class-wide at all. Take over
 * only the template method of [BeforeAllCountingFixture] and its plain test would stay with Jupiter — two
 * engines, two `ClassBasedTestDescriptor`s, two class lifecycles, and this counter reads 2.
 */
class EagerPlainClassTest {
  @Test
  fun `a class without templates keeps its exact tree`() {
    val eager = EagerParamsTestSupport.discoverEager(PlainJupiterFixture::class.java)
    val lazy = EagerParamsTestSupport.discover(PlainJupiterFixture::class.java)

    // Compared without the engine node, which is the only line that legitimately differs.
    assertThat(EagerParamsTestSupport.tree(eager, EagerParamsTestSupport.eagerRoot(eager)).drop(1))
      .isEqualTo(EagerParamsTestSupport.tree(lazy, EagerParamsTestSupport.jupiterRoot(lazy)).drop(1))
    assertThat(eager.countTestIdentifiers { it.isTest })
      .isEqualTo(lazy.countTestIdentifiers { it.isTest })
  }

  @Test
  fun `a class without templates runs identically`() {
    val eager = EagerParamsTestSupport.runEager(PlainJupiterFixture::class.java)
    val lazy = EagerParamsTestSupport.run(PlainJupiterFixture::class.java)

    assertThat(eager.failureMessages()).isEmpty()
    assertThat(eager.recorded).isEqualTo(lazy.recorded)
    assertThat(eager.startedTests()).isEqualTo(lazy.startedTests())
    assertThat(eager.dynamicallyRegistered()).isEmpty()
    assertThat(eager.skipped()).isEmpty()
  }

  /** The whole reason the unit of takeover is the class: one class, one lifecycle, one engine. */
  @Test
  fun `a class mixing a template with a plain test runs its BeforeAll once`() {
    BeforeAllCountingFixture.beforeAllCalls.set(0)

    val run = EagerParamsTestSupport.runEager(BeforeAllCountingFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(BeforeAllCountingFixture.beforeAllCalls.get()).isEqualTo(1)
    assertThat(run.recorded).containsExactlyInAnyOrder("counted:x", "counted:y", "counted:plain")
    // Both kinds of test live under the one class node, in the one engine.
    assertThat(EagerParamsTestSupport.claimedClasses(run.discoveredPlan))
      .containsExactly(BeforeAllCountingFixture::class.java.name)
    assertThat(EagerParamsTestSupport.jupiterClasses(run.discoveredPlan)).isEmpty()
  }

  /** …and the count matches the lazy path, so "once" is Jupiter's own semantics rather than a coincidence. */
  @Test
  fun `the BeforeAll count is the same without the engine`() {
    BeforeAllCountingFixture.beforeAllCalls.set(0)
    EagerParamsTestSupport.run(BeforeAllCountingFixture::class.java)
    val lazyCount = BeforeAllCountingFixture.beforeAllCalls.get()

    BeforeAllCountingFixture.beforeAllCalls.set(0)
    EagerParamsTestSupport.runEager(BeforeAllCountingFixture::class.java)

    assertThat(BeforeAllCountingFixture.beforeAllCalls.get()).isEqualTo(lazyCount)
  }

  /** A plain class and a class template in the same request share one delegated run, hence one store. */
  @Test
  fun `a mixed request is one run, not two`() {
    val run = EagerParamsTestSupport.runEager(PlainJupiterFixture::class.java, TwoMethodsFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).contains("plain")
    assertThat(run.eagerEvents().filter { it.segmentType == ENGINE_SEGMENT && it.kind == EagerEventKind.STARTED })
      .hasSize(1)
  }
}
