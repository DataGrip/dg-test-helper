package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.ArgumentSetMethodParityFixture
import com.github.kassak.dg.eagerparams.fixtures.ArgumentSetParityFixture
import com.github.kassak.dg.eagerparams.fixtures.CsvSourceParityFixture
import com.github.kassak.dg.eagerparams.fixtures.CustomSourceParityFixture
import com.github.kassak.dg.eagerparams.fixtures.DisplayNameMethodParityFixture
import com.github.kassak.dg.eagerparams.fixtures.DisplayNamePatternParityFixture
import com.github.kassak.dg.eagerparams.fixtures.EnumSourceParityFixture
import com.github.kassak.dg.eagerparams.fixtures.FieldSourceParityFixture
import com.github.kassak.dg.eagerparams.fixtures.IndexedPatternParityFixture
import com.github.kassak.dg.eagerparams.fixtures.MethodSourceParityFixture
import com.github.kassak.dg.eagerparams.fixtures.MultipleSourcesParityFixture
import com.github.kassak.dg.eagerparams.fixtures.ValueSourceMethodParityFixture
import com.github.kassak.dg.eagerparams.fixtures.ValueSourceParityFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proof that display names are *delegated* to Jupiter rather than reimplemented.
 *
 * The engine asks the class's or method's own provider for its invocation contexts and calls
 * `getDisplayName(index)` on each — the very method `ClassTemplateInvocationTestDescriptor` and
 * `TestTemplateInvocationTestDescriptor` call on the lazy path. So for every kind of argument source and every
 * `name` pattern the two paths must produce the same strings, character for character, and the way to check
 * that is to run both and compare instead of hardcoding expectations.
 *
 * Both axes are covered, because the two are named by different code and against different context levels: a
 * class template's pattern resolves `{displayName}` against the class, a method template's against the method.
 *
 * [EagerTreeShapeTest] pins a few literal names for the fixtures that use explicit patterns; this test covers
 * the *default* pattern, where a reimplementation would diverge first.
 */
class EagerNameParityTest {
  private val fixtures = listOf(
    ValueSourceParityFixture::class.java,
    MethodSourceParityFixture::class.java,
    CsvSourceParityFixture::class.java,
    FieldSourceParityFixture::class.java,
    EnumSourceParityFixture::class.java,
    CustomSourceParityFixture::class.java,
    ArgumentSetParityFixture::class.java,
    MultipleSourcesParityFixture::class.java,
    DisplayNamePatternParityFixture::class.java,
    IndexedPatternParityFixture::class.java,
  )

  /** The same question on the method axis. Their invocations are tests, not containers, hence the other segment. */
  private val methodFixtures = listOf(
    ValueSourceMethodParityFixture::class.java,
    ArgumentSetMethodParityFixture::class.java,
    DisplayNameMethodParityFixture::class.java,
  )

  @Test
  fun `every source kind names its invocations the same on both paths`() {
    for (fixture in fixtures) {
      val lazy = EagerParamsTestSupport.run(fixture)
      val eager = EagerParamsTestSupport.runEager(fixture)

      assertThat(lazy.failureMessages()).describedAs("lazy ${fixture.simpleName}").isEmpty()
      assertThat(eager.failureMessages()).describedAs("eager ${fixture.simpleName}").isEmpty()
      assertThat(lazy.invocationNames()).describedAs("lazy ${fixture.simpleName}").isNotEmpty()
      assertThat(eager.invocationNames()).describedAs(fixture.simpleName).isEqualTo(lazy.invocationNames())
    }
  }

  /** The names have to be right in the *plan*, not merely by the end of the run — that is the whole point. */
  @Test
  fun `the names are already correct at discovery`() {
    for (fixture in fixtures) {
      val plan = EagerParamsTestSupport.discoverEager(fixture)
      val planned = EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan))

      assertThat(planned)
        .describedAs(fixture.simpleName)
        .isEqualTo(EagerParamsTestSupport.run(fixture).invocationNames())
    }
  }

  @Test
  fun `every method template names its invocations the same on both paths`() {
    for (fixture in methodFixtures) {
      val lazy = EagerParamsTestSupport.run(fixture)
      val eager = EagerParamsTestSupport.runEager(fixture)

      assertThat(lazy.failureMessages()).describedAs("lazy ${fixture.simpleName}").isEmpty()
      assertThat(eager.failureMessages()).describedAs("eager ${fixture.simpleName}").isEmpty()
      assertThat(lazy.invocationNames(setOf(TEMPLATE_INVOCATION_SEGMENT))).describedAs("lazy ${fixture.simpleName}").isNotEmpty()
      assertThat(eager.invocationNames(setOf(TEMPLATE_INVOCATION_SEGMENT)))
        .describedAs(fixture.simpleName)
        .isEqualTo(lazy.invocationNames(setOf(TEMPLATE_INVOCATION_SEGMENT)))
    }
  }

  @Test
  fun `method template names are already correct at discovery`() {
    for (fixture in methodFixtures) {
      val plan = EagerParamsTestSupport.discoverEager(fixture)
      val planned = EagerParamsTestSupport.plannedInvocations(
        plan, EagerParamsTestSupport.eagerRoot(plan), setOf(TEMPLATE_INVOCATION_SEGMENT),
      )

      assertThat(planned)
        .describedAs(fixture.simpleName)
        .isEqualTo(EagerParamsTestSupport.run(fixture).invocationNames(setOf(TEMPLATE_INVOCATION_SEGMENT)))
    }
  }

  /** `{displayName}` in a method template's pattern is the *method's* name — the reason the context is two levels deep. */
  @Test
  fun `the method display name feeds the displayName placeholder`() {
    val plan = EagerParamsTestSupport.discoverEager(DisplayNameMethodParityFixture::class.java)

    assertThat(EagerParamsTestSupport.plannedInvocations(
      plan, EagerParamsTestSupport.eagerRoot(plan), setOf(TEMPLATE_INVOCATION_SEGMENT),
    )).containsExactly("Renamed method fixture #1 <d1>", "Renamed method fixture #2 <d2>")
  }

  /**
   * A couple of literals, so that a change in Jupiter's default pattern is *visible* rather than merely
   * mirrored by both paths.
   */
  @Test
  fun `the default pattern renders field arguments by name`() {
    val plan = EagerParamsTestSupport.discoverEager(ValueSourceParityFixture::class.java)

    assertThat(EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .containsExactly("[1] value=a", "[2] value=b")
  }

  @Test
  fun `the class display name feeds the displayName placeholder`() {
    val plan = EagerParamsTestSupport.discoverEager(DisplayNamePatternParityFixture::class.java)

    assertThat(EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .containsExactly("Renamed parity fixture #1 <d1>", "Renamed parity fixture #2 <d2>")
  }

  /** Argument sets name the invocation themselves, and the default pattern prefers that name. */
  @Test
  fun `argument set names win over rendered arguments`() {
    val plan = EagerParamsTestSupport.discoverEager(ArgumentSetParityFixture::class.java)

    assertThat(EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .containsExactly("[1] sweet set", "[2] sour set")
  }
}
