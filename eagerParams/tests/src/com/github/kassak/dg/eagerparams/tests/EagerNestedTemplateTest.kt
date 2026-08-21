package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.BothAxesFixture
import com.github.kassak.dg.eagerparams.fixtures.OuterWithNestedClassTemplateFixture
import com.github.kassak.dg.eagerparams.fixtures.OuterWithNestedTemplateFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Templates inside templates, and templates inside `@Nested` classes.
 *
 * The recursion is not incidental: Jupiter parks the children of a class template under the class node until it
 * prunes, so the engine copies them into *every* invocation — and copying them through the same mirroring pass
 * is what expands a `@ParameterizedTest` inside a `@ParameterizedClass` on both axes at once.
 *
 * The known limitation lives here too: a method template's invocations are enumerated once, without a class
 * instance, so a source that depends on the enclosing class's own argument cannot be named per invocation. None
 * of these fixtures does that; `InstanceSourceFixture` in `EagerDegradationTest` is that shape.
 */
class EagerNestedTemplateTest {
  @Test
  fun `a method template inside a class template is expanded on both axes`() {
    val plan = EagerParamsTestSupport.discoverEager(BothAxesFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  BothAxesFixture",
      "    A",
      "      each(int)",
      "        1",
      "        2",
      "    B",
      "      each(int)",
      "        1",
      "        2",
    )
  }

  @Test
  fun `both axes run once each, with the right arguments`() {
    val eager = EagerParamsTestSupport.runEager(BothAxesFixture::class.java)
    val lazy = EagerParamsTestSupport.run(BothAxesFixture::class.java)

    assertThat(eager.failureMessages()).isEmpty()
    assertThat(eager.recorded).containsExactlyInAnyOrder("both:A:1", "both:A:2", "both:B:1", "both:B:2")
    assertThat(eager.recorded).isEqualTo(lazy.recorded)
    assertThat(eager.dynamicallyRegistered()).isEmpty()
  }

  @Test
  fun `a method template inside a Nested class is expanded`() {
    val plan = EagerParamsTestSupport.discoverEager(OuterWithNestedTemplateFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  OuterWithNestedTemplateFixture",
      "    Inner",
      "      each(String)",
      "        n1",
      "        n2",
      "    outer()",
    )
  }

  /** A `@Nested` class that is itself a template gets its own segment type, and the same expansion. */
  @Test
  fun `a nested class template is expanded`() {
    val plan = EagerParamsTestSupport.discoverEager(OuterWithNestedClassTemplateFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  OuterWithNestedClassTemplateFixture",
      "    Inner",
      "      i1",
      "        records()",
      "      i2",
      "        records()",
      "    outer()",
    )
    assertThat(EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .containsExactly("i1", "i2")
  }

  @Test
  fun `nested classes run identically with and without the engine`() {
    for (fixture in listOf(OuterWithNestedTemplateFixture::class.java, OuterWithNestedClassTemplateFixture::class.java)) {
      val eager = EagerParamsTestSupport.runEager(fixture)
      val lazy = EagerParamsTestSupport.run(fixture)

      assertThat(eager.failureMessages()).describedAs(fixture.simpleName).isEmpty()
      assertThat(eager.recorded).describedAs(fixture.simpleName).isEqualTo(lazy.recorded)
      assertThat(eager.startedTests()).describedAs(fixture.simpleName).isEqualTo(lazy.startedTests())
    }
  }
}
