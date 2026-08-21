package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.InstanceSourceFixture
import com.github.kassak.dg.eagerparams.fixtures.PlainJupiterFixture
import com.github.kassak.dg.eagerparams.fixtures.RegisteredProviderFixture
import com.github.kassak.dg.eagerparams.fixtures.ThrowingFactoryFixture
import com.github.kassak.dg.eagerparams.fixtures.UnsupportedContextFixture
import com.github.kassak.dg.eagerparams.fixtures.ZeroInvocationsFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The degradation policy, which is also the drop-in guarantee: anything the engine cannot enumerate at
 * discovery keeps running exactly as it would have without the engine — same invocations, same names, errors
 * reported by Jupiter, in Jupiter's words, once. Only the eager tree is lost.
 *
 * That is what keeps the mechanism safe to leave switched on. A source that needs a test instance, a factory
 * that throws, a future Jupiter calling an `ExtensionContext` method our discovery-time stand-in refuses —
 * each of them costs laziness, never correctness.
 *
 * Which engine ends up running such a class is not part of that promise. Every class is taken over, enumerable
 * or not, because the filter hides all of Jupiter's tree and could not be told otherwise without the engine
 * recording its decisions somewhere. So an unenumerable template runs lazily *under this engine*, its
 * invocations arriving through `dynamicTestRegistered` from the delegated Jupiter run.
 */
class EagerDegradationTest {
  /** Taken over, but impossible to enumerate at discovery: filled in while running instead. */
  private val lazyUnderUs = listOf(
    ThrowingFactoryFixture::class.java,
    UnsupportedContextFixture::class.java,
    ZeroInvocationsFixture::class.java,
    InstanceSourceFixture::class.java,
  )

  /** Nothing to enumerate in the first place, and nothing that may change because of it. */
  private val noTemplates = listOf(
    PlainJupiterFixture::class.java,
  )

  @Test
  fun `a class that cannot be enumerated is still taken over`() {
    for (fixture in lazyUnderUs) {
      val plan = EagerParamsTestSupport.discoverEager(fixture)

      assertThat(EagerParamsTestSupport.claimedClasses(plan)).describedAs(fixture.simpleName).containsExactly(fixture.name)
      assertThat(EagerParamsTestSupport.jupiterClasses(plan)).describedAs(fixture.simpleName).isEmpty()
      // Taken over, but with nothing under it yet — that is the whole difference from an enumerated class.
      assertThat(EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan), BOTH_INVOCATION_SEGMENTS))
        .describedAs(fixture.simpleName).isEmpty()
    }
  }

  /** No template anywhere in it, and still ours: the unit of takeover is the class, not the template. */
  @Test
  fun `a class with no template of its own is taken over whole`() {
    for (fixture in noTemplates) {
      val eager = EagerParamsTestSupport.discoverEager(fixture)
      val off = withEagerDisabled { EagerParamsTestSupport.discoverEager(fixture) }

      assertThat(EagerParamsTestSupport.claimedClasses(eager)).describedAs(fixture.simpleName).containsExactly(fixture.name)
      assertThat(EagerParamsTestSupport.jupiterClasses(eager)).describedAs(fixture.simpleName).isEmpty()
      // Same tree, one engine over: nothing about a plain class is rearranged on the way through.
      assertThat(EagerParamsTestSupport.tree(eager, EagerParamsTestSupport.eagerRoot(eager)).drop(1))
        .describedAs(fixture.simpleName)
        .isEqualTo(EagerParamsTestSupport.tree(off, EagerParamsTestSupport.jupiterRoot(off)).drop(1))
    }
  }

  @Test
  fun `an unenumerable class runs identically with and without the engine`() {
    for (fixture in lazyUnderUs + noTemplates) {
      val eager = EagerParamsTestSupport.runEager(fixture)
      val lazy = EagerParamsTestSupport.run(fixture)

      assertThat(eager.recorded).describedAs(fixture.simpleName).isEqualTo(lazy.recorded)
      assertThat(eager.invocationNames(BOTH_INVOCATION_SEGMENTS))
        .describedAs(fixture.simpleName).isEqualTo(lazy.invocationNames(BOTH_INVOCATION_SEGMENTS))
      assertThat(eager.startedTests()).describedAs(fixture.simpleName).isEqualTo(lazy.startedTests())
      assertThat(eager.failureMessages()).describedAs(fixture.simpleName).isEqualTo(lazy.failureMessages())
    }
  }

  @Test
  fun `a throwing factory is reported once, by Jupiter`() {
    val run = EagerParamsTestSupport.runEager(ThrowingFactoryFixture::class.java)

    assertThat(run.failures()).hasSize(1)
    assertThat(run.failureMessages()).contains("factory blew up")
    assertThat(run.recorded).isEmpty()
  }

  /**
   * A provider on a static `@RegisterExtension` field is enumerable — and that is a consequence of the design,
   * not a special case.
   *
   * The engine builds the same registry `TestTemplateTestDescriptor.prepare()` builds instead of scanning
   * `@ExtendWith`, so every way of registering a provider that does not need a test instance comes along for
   * free: default extensions (`@RepeatedTest`), auto-detected ones, and this.
   */
  @Test
  fun `a provider registered on a static field is enumerated eagerly`() {
    val plan = EagerParamsTestSupport.discoverEager(RegisteredProviderFixture::class.java)

    assertThat(EagerParamsTestSupport.plannedInvocations(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .containsExactly("manual 1", "manual 2")

    val run = EagerParamsTestSupport.runEager(RegisteredProviderFixture::class.java)
    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.invocationNames()).containsExactly("manual 1", "manual 2")
    assertThat(run.recorded).containsExactly("registered", "registered")
  }

  /**
   * A source that needs the test *instance*: the one shape the replicated registry cannot reach.
   *
   * `PER_CLASS` makes a non-static `@MethodSource` legal, and Jupiter calls it on an instance it has already
   * created. Discovery has no instance to offer, so the template node stays lazy — and the invocations still
   * appear, named by Jupiter, while running.
   */
  @Test
  fun `a source that needs an instance gets the lazy path`() {
    val run = EagerParamsTestSupport.runEager(InstanceSourceFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactly("instance:i1", "instance:i2")
    assertThat(run.dynamicallyRegistered()).contains("[1] i1", "[2] i2")
  }

  /**
   * The forward-compatibility case: the provider calls something only a real, executing `ExtensionContext`
   * can do. Our enumeration throws, the node stays lazy, and the same call succeeds while running.
   *
   * "Once" is the point: the class is hidden from Jupiter's own tree, so if the delegated run did not report
   * it the test would vanish rather than fail, and if both trees ran it everything would happen twice.
   */
  @Test
  fun `a provider that needs more than the discovery context runs once, lazily, under our engine`() {
    val run = EagerParamsTestSupport.runEager(UnsupportedContextFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.invocationNames()).containsExactly("reported")
    assertThat(run.recorded).containsExactly("reported")
    // Reported by our engine, and only by it.
    assertThat(run.eagerEvents().filter { it.segmentType == CLASS_INVOCATION_SEGMENT }.map { it.displayName })
      .contains("reported")
    assertThat(run.dynamicallyRegistered()).contains("reported")
  }
}
