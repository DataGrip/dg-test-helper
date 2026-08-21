package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.NestedMemberFixture
import com.github.kassak.dg.eagerparams.fixtures.TwoMethodsFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors

/**
 * Reruns. The IDE remembers the unique id of whatever the user clicked, and after this engine has been in
 * the plan those ids carry *our* engine segment — which Jupiter would refuse to resolve. So the engine
 * translates them back before delegating, and the narrowing has to survive the round trip.
 */
class EagerSelectorTest {
  private val twoMethods = TwoMethodsFixture::class.java.name

  @Test
  fun `selecting one invocation runs only that invocation`() {
    val run = EagerParamsTestSupport.run(
      listOf(DiscoverySelectors.selectUniqueId("[engine:intellij-eager-params]/[class-template:$twoMethods]/[class-template-invocation:#2]")),
      eager = true,
    )

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("first:q", "second:q")
  }

  @Test
  fun `selecting one method of one invocation runs only that`() {
    val run = EagerParamsTestSupport.run(
      listOf(DiscoverySelectors.selectUniqueId("[engine:intellij-eager-params]/[class-template:$twoMethods]/[class-template-invocation:#1]/[method:second()]")),
      eager = true,
    )

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactly("second:p")
  }

  @Test
  fun `selecting one invocation shows only that invocation in the tree`() {
    val plan = EagerParamsTestSupport.discover(
      listOf(DiscoverySelectors.selectUniqueId("[engine:intellij-eager-params]/[class-template:$twoMethods]/[class-template-invocation:#2]")),
      eager = true,
    )

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  TwoMethodsFixture",
      "    q",
      "      first()",
      "      second()",
    )
  }

  /** A baseline recorded before this engine existed still selects the right thing. */
  @Test
  fun `a Jupiter unique id for a claimed class is picked up by this engine`() {
    val run = EagerParamsTestSupport.run(
      listOf(DiscoverySelectors.selectUniqueId("[engine:junit-jupiter]/[class-template:$twoMethods]/[class-template-invocation:#1]")),
      eager = true,
    )

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("first:p", "second:p")
  }

  @Test
  fun `selecting a method runs it in every invocation`() {
    val run = EagerParamsTestSupport.run(
      listOf(DiscoverySelectors.selectMethod(TwoMethodsFixture::class.java, "first")),
      eager = true,
    )

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("first:p", "first:q")
  }

  /**
   * A selector naming only the `@Nested` member must not lose the enclosing template.
   *
   * Jupiter resolves the enclosing class as the nested class's parent, so the tree that comes back is rooted at
   * `[class-template:Outer]` and has to be expanded from there — the selector never mentions it. Mirroring
   * whatever Jupiter resolved, rather than reasoning about what the selectors name, is what makes this fall out.
   */
  @Test
  fun `selecting a nested class of a class template keeps its tree`() {
    val outer = NestedMemberFixture::class.java.name
    val selectors = listOf(DiscoverySelectors.selectNestedClass(listOf(outer), "$outer\$Inner"))

    val plan = EagerParamsTestSupport.discover(selectors, eager = true)
    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  NestedMemberFixture",
      "    outer1",
      "      Inner",
      "        inner()",
      "    outer2",
      "      Inner",
      "        inner()",
    )

    val run = EagerParamsTestSupport.run(selectors, eager = true)
    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactlyInAnyOrder("inner:outer1", "inner:outer2")
  }

  /**
   * A package selector is the shape nothing can be predicted from — and the one the IDE uses for "all tests
   * in a module". Everything it finds, template or not, comes out under this engine.
   */
  @Test
  fun `selecting the whole package puts every class under this engine`() {
    val plan = EagerParamsTestSupport.discover(
      listOf(DiscoverySelectors.selectPackage(TwoMethodsFixture::class.java.packageName)),
      eager = true,
    )

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .contains("  TwoMethodsFixture", "  SingleArgumentFixture", "  PlainJupiterFixture")
    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.jupiterRoot(plan)))
      .containsExactly("JUnit Jupiter")
  }
}
