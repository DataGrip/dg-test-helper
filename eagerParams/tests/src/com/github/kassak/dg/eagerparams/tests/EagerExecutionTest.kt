package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.AbortingBeforeEachFixture
import com.github.kassak.dg.eagerparams.fixtures.ConstructorInjectionFixture
import com.github.kassak.dg.eagerparams.fixtures.FailingBeforeAllFixture
import com.github.kassak.dg.eagerparams.fixtures.FailingInvocationFixture
import com.github.kassak.dg.eagerparams.fixtures.PlainJupiterFixture
import com.github.kassak.dg.eagerparams.fixtures.SingleArgumentFixture
import com.github.kassak.dg.eagerparams.fixtures.ThrowingFactoryFixture
import com.github.kassak.dg.eagerparams.fixtures.TwoMethodsFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult

/**
 * That the delegated run does what the eagerly built tree promised: every node reported exactly once,
 * every argument injected, nothing run twice.
 *
 * The double-run trap is the interesting one — a claimed class exists in the plan of this engine *and*
 * would exist in Jupiter's if it were not filtered out, so counting recorded invocations is the check that
 * matters most.
 */
class EagerExecutionTest {
  @Test
  fun `every invocation runs exactly once`() {
    val run = EagerParamsTestSupport.runEager(SingleArgumentFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactly(
      "single:alpha:records(TestInfo)",
      "single:beta:records(TestInfo)",
      "single:gamma:records(TestInfo)",
    )
  }

  /**
   * The assertion the whole exercise is for: the plan the IDE receives before anything starts running
   * already contains every invocation. On the lazy path this tree is one node deep at that moment.
   */
  @Test
  fun `the plan handed over before execution already holds the whole tree`() {
    val run = EagerParamsTestSupport.runEager(SingleArgumentFixture::class.java)

    val plan = run.finalPlan
    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  SingleArgumentFixture",
      "    [1] alpha",
      "      records(TestInfo)",
      "    [2] beta",
      "      records(TestInfo)",
      "    [3] gamma",
      "      records(TestInfo)",
    )
  }

  @Test
  fun `the static part of the tree is never registered dynamically`() {
    val run = EagerParamsTestSupport.runEager(TwoMethodsFixture::class.java)

    assertThat(run.eagerEvents().filter { it.kind == EagerEventKind.DYNAMIC }).isEmpty()
  }

  @Test
  fun `every node of the tree is started and finished`() {
    val run = EagerParamsTestSupport.runEager(TwoMethodsFixture::class.java)

    val started = run.eagerEvents().filter { it.kind == EagerEventKind.STARTED }.map { it.displayName }
    val finished = run.eagerEvents().filter { it.kind == EagerEventKind.FINISHED }.map { it.displayName }
    assertThat(started).containsExactlyInAnyOrder(
      "Eager Parameterized Classes",
      "TwoMethodsFixture", "p", "first()", "second()", "q", "first()", "second()",
    )
    assertThat(finished).containsExactlyInAnyOrderElementsOf(started)
    assertThat(run.skipped()).isEmpty()
    assertThat(run.failureMessages()).isEmpty()
  }

  @Test
  fun `a failure lands on its own invocation and leaves the siblings alone`() {
    val run = EagerParamsTestSupport.runEager(FailingInvocationFixture::class.java)

    assertThat(run.recorded).containsExactly("checks:good", "checks:bad")
    val failed = run.failures().map { it.displayName }
    assertThat(failed).containsExactly("checks()")
    assertThat(run.singleFailure()).hasMessageContaining("bad argument")

    val checks = run.eagerEvents().filter { it.kind == EagerEventKind.FINISHED && it.displayName == "checks()" }
    assertThat(checks.map { it.status }).containsExactlyInAnyOrder(
      TestExecutionResult.Status.SUCCESSFUL, TestExecutionResult.Status.FAILED,
    )
  }

  @Test
  fun `a template and a plain class in one run each execute once`() {
    val run = EagerParamsTestSupport.runEager(TwoMethodsFixture::class.java, PlainJupiterFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder(
      "first:p", "second:p", "first:q", "second:q", "plain",
    )
  }

  @Test
  fun `arguments reach fields and constructors the same way as on the lazy path`() {
    val eager = EagerParamsTestSupport.runEager(ConstructorInjectionFixture::class.java)
    val lazy = EagerParamsTestSupport.run(ConstructorInjectionFixture::class.java)

    assertThat(eager.failureMessages()).isEmpty()
    assertThat(eager.recorded).isEqualTo(lazy.recorded)
  }

  /**
   * With the kill switch off the engine claims nothing, so the very same class runs through plain Jupiter.
   * This is the escape hatch for the day the delegation misbehaves in CI.
   */
  @Test
  fun `the kill switch hands everything back to Jupiter`() {
    withEagerDisabled {
      val plan = EagerParamsTestSupport.discoverEager(SingleArgumentFixture::class.java)
      assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan)))
        .containsExactly("Eager Parameterized Classes")

      val run = EagerParamsTestSupport.runEager(SingleArgumentFixture::class.java)
      assertThat(run.failureMessages()).isEmpty()
      assertThat(run.recorded).containsExactly(
        "single:alpha:records(TestInfo)",
        "single:beta:records(TestInfo)",
        "single:gamma:records(TestInfo)",
      )
      // Only the empty engine node itself; the launcher keeps engine roots even when they claimed nothing.
      assertThat(run.eagerEvents().map { it.displayName }.distinct()).containsExactly("Eager Parameterized Classes")
    }
  }

  /**
   * `@BeforeAll` blowing up means Jupiter says nothing whatsoever about the children — but they are already
   * in the plan, so somebody has to report them or the IDE leaves them spinning forever.
   */
  @Test
  fun `children of a container that failed before running are reported as skipped`() {
    val run = EagerParamsTestSupport.runEager(FailingBeforeAllFixture::class.java)

    assertThat(run.failureMessages()).contains("@BeforeAll blew up")
    assertThat(run.skipped().map { it.displayName }).isNotEmpty()
    // Nothing may be left without an ending: every started node finished, everything else was skipped.
    val ended = run.eagerEvents()
      .filter { it.kind == EagerEventKind.FINISHED || it.kind == EagerEventKind.SKIPPED }
      .map { it.id }
      .toSet()
    val known = run.eagerEvents().map { it.id }.toSet()
    assertThat(ended).containsAll(known)
  }

  @Test
  fun `an aborted assumption is aborted, not failed, in every invocation`() {
    val run = EagerParamsTestSupport.runEager(AbortingBeforeEachFixture::class.java)

    assertThat(run.failures()).isEmpty()
    val aborted = run.eagerEvents().filter { it.status == TestExecutionResult.Status.ABORTED }
    assertThat(aborted.map { it.displayName }).containsExactly("records()", "records()")
  }

  /**
   * A class the engine could not enumerate runs lazily under it, and the error must still come out — in one
   * place, with the same wording, from the same code.
   */
  @Test
  fun `an unenumerable class still reports its own error`() {
    val run = EagerParamsTestSupport.runEager(ThrowingFactoryFixture::class.java)

    assertThat(run.failureMessages()).contains("factory blew up")
  }
}
