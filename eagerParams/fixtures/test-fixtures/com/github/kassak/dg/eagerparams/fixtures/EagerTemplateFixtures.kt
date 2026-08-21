@file:Suppress("unused")

package com.github.kassak.dg.eagerparams.fixtures

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
import org.junit.jupiter.params.Parameter
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

// Fixtures for templates on *methods* — `@ParameterizedTest`, `@RepeatedTest`, a hand-written `@TestTemplate` —
// and for the two shapes that only matter once the engine takes over whole classes: a class with no template
// in it at all, and a template nested inside another template.
// Naming rule: see EagerParamsFixtures.kt.
//
// A method template is why the engine has to take over everything. Its node lives inside an ordinary class, so
// taking over just the template would put the same class in two engines at once, and `@BeforeAll` would run
// twice — which is what BeforeAllCountingFixture measures.

/** The plainest method template there is. An explicit pattern, because the tests assert its names literally. */
class ParameterizedMethodFixture {
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = ["m1", "m2"])
  fun each(value: String) {
    EagerRecorder.record("param:$value")
  }
}

/**
 * `@RepeatedTest`, which is the case that decides how providers are looked up.
 *
 * It is a `@TestTemplate` with no `@ExtendWith` anywhere: its provider is a package-private *default* extension
 * of Jupiter's registry. Scanning annotations for providers cannot see it, so the engine has to build the same
 * registry Jupiter builds instead.
 */
class RepeatedMethodFixture {
  @RepeatedTest(3)
  fun repeats(info: RepetitionInfo) {
    EagerRecorder.record("repeat:${info.currentRepetition}")
  }
}

/** A third-party `@TestTemplate` provider: nothing about `org.junit.jupiter.params` is involved. */
class ThriceInvocationProvider : TestTemplateInvocationContextProvider {
  override fun supportsTestTemplate(context: ExtensionContext): Boolean = true

  override fun provideTestTemplateInvocationContexts(context: ExtensionContext): Stream<TestTemplateInvocationContext> =
    Stream.of(named("thrice 1"), named("thrice 2"), named("thrice 3"))

  private fun named(name: String): TestTemplateInvocationContext = object : TestTemplateInvocationContext {
    override fun getDisplayName(invocationIndex: Int): String = name
  }
}

/** A bare `@TestTemplate`, the SPI both `@ParameterizedTest` and `@RepeatedTest` are built on. */
class CustomTemplateFixture {
  @TestTemplate
  @ExtendWith(ThriceInvocationProvider::class)
  fun templated() {
    EagerRecorder.record("custom")
  }
}

/**
 * A method template inside a class template: both axes have to be expanded, and in the right order.
 *
 * Jupiter parks the method nodes under the class template node until it prunes, so the engine copies them into
 * every class invocation and enumerates the method template again in each — which is also where the known
 * limitation lives: the method's invocations are enumerated without a class instance, so a source that depends
 * on the class's own argument cannot be named eagerly. This one does not.
 */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["A", "B"])
class BothAxesFixture {
  @field:Parameter
  lateinit var outer: String

  @ParameterizedTest(name = "{0}")
  @ValueSource(ints = [1, 2])
  fun each(number: Int) {
    EagerRecorder.record("both:$outer:$number")
  }
}

/** A method template inside a `@Nested` class of an ordinary class. */
class OuterWithNestedTemplateFixture {
  @Test
  fun outer() {
    EagerRecorder.record("outer")
  }

  @Nested
  inner class Inner {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["n1", "n2"])
    fun each(value: String) {
      EagerRecorder.record("nested:$value")
    }
  }
}

/** A `@Nested` class that is itself a class template, which Jupiter gives its own segment type. */
class OuterWithNestedClassTemplateFixture {
  @Test
  fun outer() {
    EagerRecorder.record("outerPlain")
  }

  @Nested
  @ParameterizedClass(name = "{0}")
  @ValueSource(strings = ["i1", "i2"])
  inner class Inner {
    @field:Parameter
    lateinit var value: String

    @Test
    fun records() {
      EagerRecorder.record("nestedTemplate:$value")
    }
  }
}

/**
 * Counts its own `@BeforeAll`, and mixes a template method with a plain one.
 *
 * The regression test for the reason the engine takes over whole classes: run the template under one engine and
 * the plain test under another and this counter reads 2, because those are two independent Jupiter sessions with
 * their own class descriptors.
 */
class BeforeAllCountingFixture {
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = ["x", "y"])
  fun each(value: String) {
    EagerRecorder.record("counted:$value")
  }

  @Test
  fun plain() {
    EagerRecorder.record("counted:plain")
  }

  companion object {
    val beforeAllCalls: AtomicInteger = AtomicInteger()

    @JvmStatic
    @BeforeAll
    fun setUp() {
      beforeAllCalls.incrementAndGet()
    }
  }
}

/**
 * A source that needs the test *instance*: legal under `PER_CLASS`, impossible to enumerate at discovery.
 *
 * The degradation case for method templates. Jupiter calls the factory on the instance it has already created;
 * our discovery-time context has no instance to offer, so enumeration gives up and the node stays lazy — which
 * is exactly what would happen without the engine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstanceSourceFixture {
  @ParameterizedTest
  @MethodSource("data")
  fun each(value: String) {
    EagerRecorder.record("instance:$value")
  }

  fun data(): List<String> = listOf("i1", "i2")
}

// Parity fixtures for method templates: default `name` patterns throughout, because that is where a
// reimplemented formatter would diverge first. EagerNameParityTest runs each of them both ways and compares.

/** The default `@ParameterizedTest` pattern over a single argument. */
class ValueSourceMethodParityFixture {
  @ParameterizedTest
  @ValueSource(strings = ["a", "b"])
  fun each(value: String) {
    EagerRecorder.record("methodValue:$value")
  }
}

/** Named argument sets, which take over the default pattern's second half. */
class ArgumentSetMethodParityFixture {
  @ParameterizedTest
  @MethodSource("sets")
  fun each(value: String) {
    EagerRecorder.record("methodSet:$value")
  }

  companion object {
    @JvmStatic
    fun sets(): List<Arguments> = listOf(
      Arguments.argumentSet("first set", "one"),
      Arguments.argumentSet("second set", "two"),
    )
  }
}

/**
 * `{displayName}` inside a method template resolves against the **method's** display name.
 *
 * The one thing that forces the discovery-time context to be two levels deep: read the class node's name here
 * and every invocation would be misnamed.
 */
class DisplayNameMethodParityFixture {
  @DisplayName("Renamed method fixture")
  @ParameterizedTest(name = "{displayName} #{index} <{arguments}>")
  @ValueSource(strings = ["d1", "d2"])
  fun each(value: String) {
    EagerRecorder.record("methodDisplayName:$value")
  }
}
