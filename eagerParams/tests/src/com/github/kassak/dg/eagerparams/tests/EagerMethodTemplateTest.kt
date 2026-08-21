package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.CustomTemplateFixture
import com.github.kassak.dg.eagerparams.fixtures.ParameterizedMethodFixture
import com.github.kassak.dg.eagerparams.fixtures.RepeatedMethodFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Templates on **methods**: `@ParameterizedTest`, `@RepeatedTest`, and a hand-written `@TestTemplate`.
 *
 * The same defect as a class template and the same fix, one level down — but the provider lookup is a different
 * problem. `@RepeatedTest` carries no `@ExtendWith` at all: its provider is a package-private *default*
 * extension of Jupiter's registry, so scanning annotations cannot find it. The engine therefore builds the
 * registry `TestTemplateTestDescriptor.prepare()` builds — defaults, class-level `@ExtendWith`, method-level
 * `@ExtendWith`, static `@RegisterExtension` — and asks it for providers. All three fixtures here are
 * enumerated by that one path.
 */
class EagerMethodTemplateTest {
  private val fixtures = listOf(
    ParameterizedMethodFixture::class.java,
    RepeatedMethodFixture::class.java,
    CustomTemplateFixture::class.java,
  )

  /** An explicit `name` pattern, so the tree can be asserted literally rather than against the other path. */
  @Test
  fun `a parameterized method is fully expanded at discovery`() {
    val plan = EagerParamsTestSupport.discoverEager(ParameterizedMethodFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  ParameterizedMethodFixture",
      "    each(String)",
      "      m1",
      "      m2",
    )
  }

  /** `@RepeatedTest`: the case that decides the whole provider-lookup design. */
  @Test
  fun `a repeated test is expanded by the replicated registry`() {
    val plan = EagerParamsTestSupport.discoverEager(RepeatedMethodFixture::class.java)
    val planned = EagerParamsTestSupport.plannedInvocations(
      plan, EagerParamsTestSupport.eagerRoot(plan), setOf(TEMPLATE_INVOCATION_SEGMENT),
    )

    assertThat(planned).hasSize(3)
    assertThat(planned).isEqualTo(
      EagerParamsTestSupport.run(RepeatedMethodFixture::class.java).invocationNames(setOf(TEMPLATE_INVOCATION_SEGMENT))
    )
  }

  /** A third-party `@TestTemplate` provider: the engine knows nothing about `org.junit.jupiter.params` here. */
  @Test
  fun `a hand-written test template is expanded too`() {
    val plan = EagerParamsTestSupport.discoverEager(CustomTemplateFixture::class.java)

    assertThat(EagerParamsTestSupport.plannedInvocations(
      plan, EagerParamsTestSupport.eagerRoot(plan), setOf(TEMPLATE_INVOCATION_SEGMENT),
    )).containsExactly("thrice 1", "thrice 2", "thrice 3")
  }

  /**
   * Invocations of a method template are **tests**, not containers.
   *
   * The IDE decides what to draw and what to count from this flag, and the delegated Jupiter run reports its
   * own — so getting it wrong here would show up as a node that is announced as one kind and finished as the
   * other.
   */
  @Test
  fun `method template invocations are leaves`() {
    val plan = EagerParamsTestSupport.discoverEager(ParameterizedMethodFixture::class.java)
    val root = EagerParamsTestSupport.eagerRoot(plan)!!
    val classNode = plan.getChildren(root).single()
    val template = plan.getChildren(classNode).single()

    assertThat(template.isContainer).isTrue()
    assertThat(plan.getChildren(template)).allSatisfy { invocation ->
      assertThat(invocation.isTest).describedAs(invocation.displayName).isTrue()
      assertThat(plan.getChildren(invocation)).isEmpty()
    }
  }

  @Test
  fun `every kind of method template runs exactly like it does without the engine`() {
    for (fixture in fixtures) {
      val eager = EagerParamsTestSupport.runEager(fixture)
      val lazy = EagerParamsTestSupport.run(fixture)

      assertThat(eager.failureMessages()).describedAs(fixture.simpleName).isEmpty()
      assertThat(eager.recorded).describedAs(fixture.simpleName).isEqualTo(lazy.recorded)
      assertThat(eager.startedTests()).describedAs(fixture.simpleName).isEqualTo(lazy.startedTests())
      assertThat(eager.skipped()).describedAs(fixture.simpleName).isEmpty()
    }
  }

  /**
   * Nothing arrives dynamically any more, which is the observable difference from the lazy path.
   *
   * On the lazy path every invocation of every template is announced through `dynamicTestRegistered` while
   * running, and that is exactly what makes the IDE tree grow one node at a time.
   */
  @Test
  fun `no invocation is announced during execution`() {
    for (fixture in fixtures) {
      val eager = EagerParamsTestSupport.runEager(fixture)
      val lazy = EagerParamsTestSupport.run(fixture)

      assertThat(eager.dynamicallyRegistered()).describedAs(fixture.simpleName).isEmpty()
      assertThat(lazy.dynamicallyRegistered()).describedAs("lazy ${fixture.simpleName}").isNotEmpty()
    }
  }
}
