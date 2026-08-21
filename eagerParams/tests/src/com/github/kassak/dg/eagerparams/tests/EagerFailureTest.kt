package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.AbortingBeforeEachFixture
import com.github.kassak.dg.eagerparams.fixtures.FailingBeforeAllFixture
import com.github.kassak.dg.eagerparams.fixtures.ThrowingFactoryFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What happens when a claimed class goes wrong, on the lazy path.
 *
 * These are the baselines the eager run is compared against in [EagerExecutionTest]: the same fixtures, the
 * same messages, produced by Jupiter alone. Validating a `@ParameterizedClass` — name patterns, argument
 * counts, class shape — is entirely Jupiter's business now, so there is nothing of ours left to assert about
 * it; what is left is the handful of failures the engine has to relay without inventing or losing anything.
 */
class EagerFailureTest {
  @Test
  fun `a throwing factory fails the class and runs nothing`() {
    val run = EagerParamsTestSupport.run(ThrowingFactoryFixture::class.java)

    assertThat(run.failureMessages()).contains("factory blew up")
    assertThat(run.recorded).isEmpty()
    assertThat(run.startedTests()).isEmpty()
  }

  @Test
  fun `a failing BeforeAll fails the class once, not every invocation`() {
    val run = EagerParamsTestSupport.run(FailingBeforeAllFixture::class.java)

    assertThat(run.failureMessages()).contains("@BeforeAll blew up")
    assertThat(run.failures()).hasSize(1)
    assertThat(run.recorded).isEmpty()
  }

  @Test
  fun `an aborting BeforeEach skips tests without failing`() {
    val run = EagerParamsTestSupport.run(AbortingBeforeEachFixture::class.java)

    assertThat(run.failures()).isEmpty()
    assertThat(run.recorded).isEmpty()
    assertThat(run.startedTests()).hasSize(2)
    assertThat(run.events.filter { it.status?.name == "ABORTED" }).hasSize(2)
  }
}
