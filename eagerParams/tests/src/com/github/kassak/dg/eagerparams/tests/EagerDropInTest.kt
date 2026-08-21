package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.InheritsFieldFixture
import com.github.kassak.dg.eagerparams.fixtures.ParameterizedMethodFixture
import com.github.kassak.dg.eagerparams.fixtures.PlainJupiterFixture
import com.github.kassak.dg.eagerparams.fixtures.RepeatedMethodFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The requirement the whole design exists to satisfy: the engine arrives by being on the classpath and leaves
 * by being switched off, and neither the test classes nor the way they run change in the slightest.
 *
 * There is nothing to opt into any more — no annotation, no marker extension, no per-class decision. So the
 * comparison is no longer "this class against that class" but "the same class, engine present against
 * `-Dintellij.test.eagerParams.enabled=false`", which is exactly the switch a user gets. Every assertion here
 * is that comparison over a class template, a method template and a class with no template at all.
 */
class EagerDropInTest {
  private val fixtures = listOf(
    InheritsFieldFixture::class.java,
    ParameterizedMethodFixture::class.java,
    RepeatedMethodFixture::class.java,
    PlainJupiterFixture::class.java,
  )

  @Test
  fun `every fixture has its whole tree right after discovery, and none of it without the engine`() {
    for (fixture in fixtures) {
      val eager = EagerParamsTestSupport.discoverEager(fixture)
      val off = withEagerDisabled { EagerParamsTestSupport.discoverEager(fixture) }

      assertThat(eager.countTestIdentifiers { it.isTest }).describedAs("eager ${fixture.simpleName}").isPositive()
      // Switched off, a template contributes no tests at all before it runs — the defect this engine removes.
      // A class without templates has its tests either way, which is why the counts are compared and not equated.
      assertThat(off.countTestIdentifiers { it.isTest })
        .describedAs("off ${fixture.simpleName}")
        .isLessThanOrEqualTo(eager.countTestIdentifiers { it.isTest })
    }
  }

  @Test
  fun `a template contributes its invocations to the plan only with the engine`() {
    val templates = listOf(
      InheritsFieldFixture::class.java,
      ParameterizedMethodFixture::class.java,
      RepeatedMethodFixture::class.java,
    )
    for (fixture in templates) {
      val eager = EagerParamsTestSupport.discoverEager(fixture)
      val off = withEagerDisabled { EagerParamsTestSupport.discoverEager(fixture) }

      assertThat(planned(eager)).describedAs("eager ${fixture.simpleName}").isNotEmpty()
      assertThat(planned(off)).describedAs("off ${fixture.simpleName}").isEmpty()
    }
  }

  @Test
  fun `every fixture runs identically with the engine and with it switched off`() {
    for (fixture in fixtures) {
      val eager = EagerParamsTestSupport.runEager(fixture)
      val off = withEagerDisabled { EagerParamsTestSupport.runEager(fixture) }

      assertThat(eager.failureMessages()).describedAs("eager ${fixture.simpleName}").isEmpty()
      assertThat(off.failureMessages()).describedAs("off ${fixture.simpleName}").isEmpty()
      assertThat(eager.recorded).describedAs(fixture.simpleName).isEqualTo(off.recorded)
      assertThat(eager.invocationNames(BOTH_INVOCATION_SEGMENTS))
        .describedAs(fixture.simpleName)
        .isEqualTo(off.invocationNames(BOTH_INVOCATION_SEGMENTS))
      assertThat(eager.startedTests()).describedAs(fixture.simpleName).isEqualTo(off.startedTests())
    }
  }

  @Test
  fun `the engine takes over every class, and the switch gives them all back`() {
    for (fixture in fixtures) {
      val eager = EagerParamsTestSupport.discoverEager(fixture)
      val off = withEagerDisabled { EagerParamsTestSupport.discoverEager(fixture) }

      assertThat(EagerParamsTestSupport.claimedClasses(eager)).describedAs(fixture.simpleName).containsExactly(fixture.name)
      assertThat(EagerParamsTestSupport.jupiterClasses(eager)).describedAs(fixture.simpleName).isEmpty()
      assertThat(EagerParamsTestSupport.claimedClasses(off)).describedAs(fixture.simpleName).isEmpty()
      assertThat(EagerParamsTestSupport.jupiterClasses(off)).describedAs(fixture.simpleName).containsExactly(fixture.name)
    }
  }

  /** Drop-in spelled out: no fixture refers to this mechanism in any way, and it still takes them over. */
  @Test
  fun `no fixture is annotated with anything of ours`() {
    for (fixture in fixtures) {
      val annotations = fixture.annotations.map { it.annotationClass.java.name } +
                        fixture.methods.flatMap { method -> method.annotations.map { it.annotationClass.java.name } }

      assertThat(annotations).describedAs(fixture.simpleName).isNotEmpty()
      assertThat(annotations).describedAs(fixture.simpleName).noneMatch { it.startsWith("com.github.kassak.dg.") }
    }
  }

  private fun planned(plan: org.junit.platform.launcher.TestPlan): List<String> =
    EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan), BOTH_INVOCATION_SEGMENTS)
}
