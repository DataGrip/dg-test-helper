package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.CountingFactoryFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The one documented regression of building the tree at discovery: the argument source is enumerated more
 * often than on the lazy path.
 *
 * We enumerate once to learn the invocation count and their names; Jupiter then enumerates again to produce
 * the actual arguments while executing, because execution is delegated and we hand it nothing. Consequences,
 * all of them accepted:
 * - a factory with side effects runs more than once per test run;
 * - `autoCloseArguments` never sees the objects our pass created, since closing them is registered in
 *   `prepareInvocation`, which only the executing path reaches;
 * - a *non-deterministic* source can shift names or counts between the two passes. That degrades gracefully
 *   rather than breaking: `TranslatingEngineExecutionListener` falls back to `dynamicTestRegistered` for
 *   nodes it never planned and `executionSkipped` for planned nodes that never ran.
 *
 * This test pins the shape of the cost — one extra enumeration to build the tree — rather than a total count,
 * which depends on how many times the launcher happens to discover.
 */
class EagerDoubleEnumerationTest {
  @Test
  fun `discovery costs exactly one enumeration`() {
    CountingFactoryFixture.calls.set(0)
    EagerParamsTestSupport.discoverEager(CountingFactoryFixture::class.java)

    assertThat(CountingFactoryFixture.calls.get()).isEqualTo(1)
  }

  @Test
  fun `the lazy path does not enumerate at discovery at all`() {
    CountingFactoryFixture.calls.set(0)
    EagerParamsTestSupport.discover(CountingFactoryFixture::class.java)

    assertThat(CountingFactoryFixture.calls.get()).isZero()
  }

  @Test
  fun `a full eager run enumerates more often than a lazy one`() {
    CountingFactoryFixture.calls.set(0)
    EagerParamsTestSupport.run(CountingFactoryFixture::class.java)
    val lazyCalls = CountingFactoryFixture.calls.get()

    CountingFactoryFixture.calls.set(0)
    EagerParamsTestSupport.runEager(CountingFactoryFixture::class.java)
    val eagerCalls = CountingFactoryFixture.calls.get()

    assertThat(lazyCalls).isPositive()
    assertThat(eagerCalls).describedAs("eager=%s lazy=%s", eagerCalls, lazyCalls).isGreaterThan(lazyCalls)
  }

  /** Enumerating repeatedly must not multiply anything the user sees. */
  @Test
  fun `the extra enumerations do not duplicate invocations or tests`() {
    CountingFactoryFixture.calls.set(0)
    val eager = EagerParamsTestSupport.runEager(CountingFactoryFixture::class.java)
    val lazy = EagerParamsTestSupport.run(CountingFactoryFixture::class.java)

    assertThat(eager.failureMessages()).isEmpty()
    assertThat(eager.invocationNames()).containsExactly("c1", "c2")
    assertThat(eager.recorded).isEqualTo(lazy.recorded)
    assertThat(eager.startedTests()).isEqualTo(lazy.startedTests())
  }
}
