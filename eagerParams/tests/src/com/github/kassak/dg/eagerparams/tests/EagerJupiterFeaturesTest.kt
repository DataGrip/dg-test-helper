package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.ArgumentSetParityFixture
import com.github.kassak.dg.eagerparams.fixtures.ConstructorInjectionFixture
import com.github.kassak.dg.eagerparams.fixtures.EnumSourceParityFixture
import com.github.kassak.dg.eagerparams.fixtures.InvocationCallbacksFixture
import com.github.kassak.dg.eagerparams.fixtures.MultipleArgumentsFixture
import com.github.kassak.dg.eagerparams.fixtures.MultipleSourcesParityFixture
import com.github.kassak.dg.eagerparams.fixtures.PerClassLifecycleFixture
import com.github.kassak.dg.eagerparams.fixtures.ZeroInvocationsFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `@ParameterizedClass` features that used to be out of scope and now come for free, verified under the eager
 * engine.
 *
 * None of them cost a line of code in the engine: execution is one plain Jupiter request over translated ids,
 * so argument resolution, injection and lifecycle callbacks are Jupiter's business. What these tests actually
 * guard is that the delegation does not *interfere* — that mirroring the tree at discovery leaves the
 * execution semantics alone.
 */
class EagerJupiterFeaturesTest {
  @Test
  fun `constructor injection and Parameter fields both work`() {
    val constructor = EagerParamsTestSupport.runEager(ConstructorInjectionFixture::class.java)
    val fields = EagerParamsTestSupport.runEager(MultipleArgumentsFixture::class.java)

    assertThat(constructor.failureMessages()).isEmpty()
    assertThat(constructor.recorded).containsExactlyInAnyOrder("constructor:x:1", "constructor:y:2")
    assertThat(fields.failureMessages()).isEmpty()
    assertThat(fields.recorded).containsExactlyInAnyOrder("multiple:a:1", "multiple:b:2")
  }

  @Test
  fun `PER_CLASS shares one instance between the test methods of an invocation`() {
    val run = EagerParamsTestSupport.runEager(PerClassLifecycleFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).hasSize(4)
    val instancesByValue = run.recorded
      .map { it.removePrefix("perClass:").split(":") }
      .groupBy({ it[0] }, { it[1] })

    assertThat(instancesByValue.keys).containsExactlyInAnyOrder("s1", "s2")
    for ((value, instances) in instancesByValue) {
      assertThat(instances.distinct()).describedAs(value).hasSize(1)
    }
  }

  /**
   * Who gets which instance is Jupiter's decision, not ours, so it is asserted as a comparison.
   *
   * Identity hashes differ from run to run, so both runs are normalized to first-seen ordinals: what is
   * compared is the *pattern* of sharing, which is the part delegation could plausibly disturb.
   */
  @Test
  fun `PER_CLASS shares instances the same way on both paths`() {
    val eager = EagerParamsTestSupport.runEager(PerClassLifecycleFixture::class.java)
    val lazy = EagerParamsTestSupport.run(PerClassLifecycleFixture::class.java)

    assertThat(normalizeInstances(eager.recorded)).isEqualTo(normalizeInstances(lazy.recorded))
  }

  /** `perClass:s1:12345` → `perClass:s1:#0`, numbering instances in order of first appearance. */
  private fun normalizeInstances(recorded: List<String>): List<String> {
    val ordinals = mutableMapOf<String, Int>()
    return recorded.map { entry ->
      val instance = entry.substringAfterLast(':')
      val ordinal = ordinals.getOrPut(instance) { ordinals.size }
      "${entry.substringBeforeLast(':')}:#$ordinal"
    }
  }

  @Test
  fun `invocation callbacks run around each invocation with its arguments`() {
    val run = EagerParamsTestSupport.runEager(InvocationCallbacksFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactly(
      "before:c1", "body:c1", "after:c1",
      "before:c2", "body:c2", "after:c2",
    )
  }

  @Test
  fun `invocation callbacks behave the same on the lazy path`() {
    val eager = EagerParamsTestSupport.runEager(InvocationCallbacksFixture::class.java)
    val lazy = EagerParamsTestSupport.run(InvocationCallbacksFixture::class.java)

    assertThat(eager.recorded).isEqualTo(lazy.recorded)
  }

  @Test
  fun `allowZeroInvocations runs nothing and fails nothing`() {
    val eager = EagerParamsTestSupport.runEager(ZeroInvocationsFixture::class.java)
    val lazy = EagerParamsTestSupport.run(ZeroInvocationsFixture::class.java)

    assertThat(eager.failureMessages()).isEmpty()
    assertThat(eager.recorded).isEmpty()
    assertThat(eager.startedTests()).isEmpty()
    assertThat(eager.invocationNames()).isEqualTo(lazy.invocationNames())
  }

  @Test
  fun `a named argument set keeps its arguments, not just its name`() {
    val run = EagerParamsTestSupport.runEager(ArgumentSetParityFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("set:one", "set:two")
  }

  @Test
  fun `a null argument is injected as null`() {
    val run = EagerParamsTestSupport.runEager(MultipleSourcesParityFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("multi:null", "multi:present")
  }

  @Test
  fun `an enum argument is converted from its constant name`() {
    val run = EagerParamsTestSupport.runEager(EnumSourceParityFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("enum:SWEET", "enum:SOUR")
  }
}
