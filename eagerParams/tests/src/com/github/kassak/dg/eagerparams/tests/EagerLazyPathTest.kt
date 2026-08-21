package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.ConstructorInjectionFixture
import com.github.kassak.dg.eagerparams.fixtures.DeclaredFieldFixture
import com.github.kassak.dg.eagerparams.fixtures.InheritsFieldFixture
import com.github.kassak.dg.eagerparams.fixtures.MultipleArgumentsFixture
import com.github.kassak.dg.eagerparams.fixtures.NamedSetFixture
import com.github.kassak.dg.eagerparams.fixtures.SingleArgumentFixture
import com.github.kassak.dg.eagerparams.fixtures.TwoMethodsFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The lazy path — plain Jupiter, no `intellij-eager-params` engine — and the baseline it establishes.
 *
 * This is the defect that motivates the engine, pinned so it cannot quietly go away: after `discover()` the
 * class node has **no** children, and the invocations only show up during execution as `dynamicTestRegistered`.
 * [EagerTreeShapeTest] asserts the same fixtures, with the same names and counts, fully present in the plan
 * instead. The fixtures are ordinary `@ParameterizedClass`es either way — there is nothing to opt into.
 */
class EagerLazyPathTest {
  @Test
  fun `the class node is a class template addressed by its own FQCN`() {
    val plan = EagerParamsTestSupport.discover(SingleArgumentFixture::class.java)

    val classNode = plan.getChildren(plan.roots.single()).single()
    val lastSegment = classNode.uniqueIdObject.lastSegment
    assertThat(lastSegment.type).isEqualTo("class-template")
    assertThat(lastSegment.value).isEqualTo(SingleArgumentFixture::class.java.name)
    assertThat(classNode.displayName).isEqualTo(SingleArgumentFixture::class.java.simpleName)
  }

  @Test
  fun `nothing below the class node exists after discovery`() {
    val plan = EagerParamsTestSupport.discover(SingleArgumentFixture::class.java)

    val classNode = plan.getChildren(plan.roots.single()).single()
    assertThat(plan.getChildren(classNode)).isEmpty()
    assertThat(plan.countTestIdentifiers { it.isTest }).isZero()
  }

  @Test
  fun `invocations and their tests arrive during execution as dynamic registrations`() {
    val run = EagerParamsTestSupport.run(SingleArgumentFixture::class.java)

    assertThat(run.failures()).isEmpty()
    // Three invocations plus one test method inside each.
    assertThat(run.dynamicallyRegistered()).containsExactly(
      "[1] alpha", "records(TestInfo)",
      "[2] beta", "records(TestInfo)",
      "[3] gamma", "records(TestInfo)",
    )
  }

  @Test
  fun `invocation indices are one-based and sequential`() {
    val run = EagerParamsTestSupport.run(TwoMethodsFixture::class.java)

    val indices = run.events
      .filter { it.kind == EagerEventKind.DYNAMIC && it.segmentType == "class-template-invocation" }
      .map { it.segmentValue }

    assertThat(indices).containsExactly("#1", "#2")
  }

  /** The baseline the eager tree is compared against: names and counts of every fixture that must run clean. */
  @Test
  fun `every well-formed fixture produces a stable tree`() {
    val expected = mapOf(
      SingleArgumentFixture::class.java to listOf("[1] alpha", "[2] beta", "[3] gamma"),
      MultipleArgumentsFixture::class.java to listOf("a=#1", "b=#2"),
      ConstructorInjectionFixture::class.java to listOf("x/1", "y/2"),
      NamedSetFixture::class.java to listOf("first set", "second set"),
      TwoMethodsFixture::class.java to listOf("p", "q"),
      InheritsFieldFixture::class.java to listOf("base1", "base2"),
      DeclaredFieldFixture::class.java to listOf("base1", "base2"),
    )

    for ((fixture, invocationNames) in expected) {
      val run = EagerParamsTestSupport.run(fixture)
      assertThat(run.failureMessages()).describedAs(fixture.simpleName).isEmpty()
      assertThat(run.startedContainers())
        .describedAs(fixture.simpleName)
        .containsSubsequence(invocationNames)
    }
  }
}
