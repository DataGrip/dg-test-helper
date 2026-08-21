@file:Suppress("unused")

package com.github.kassak.dg.eagerparams.fixtures

import org.junit.jupiter.api.ClassTemplate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ClassTemplateInvocationContext
import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.AfterParameterizedClassInvocation
import org.junit.jupiter.params.BeforeParameterizedClassInvocation
import org.junit.jupiter.params.Parameter
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

// Fixtures for the `@ParameterizedClass` features the engine now inherits instead of reimplementing
// (EagerJupiterFeaturesTest), for the classes it must decline (EagerDegradationTest), and for the one
// documented regression (EagerDoubleEnumerationTest). Naming rule: see EagerParamsFixtures.kt.

/**
 * `PER_CLASS`: one instance per invocation, shared by both test methods.
 *
 * The lifecycle is also the one thing our synthetic `ExtensionContext` has to compute rather than delegate
 * (`getTestInstanceLifecycle()` must be non-empty), so a class that actually declares it is worth having.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["s1", "s2"])
class PerClassLifecycleFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun first() {
    EagerRecorder.record("perClass:$value:${System.identityHashCode(this)}")
  }

  @Test
  fun second() {
    EagerRecorder.record("perClass:$value:${System.identityHashCode(this)}")
  }
}

/** `@Before/AfterParameterizedClassInvocation`, with the invocation's arguments injected into them. */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["c1", "c2"])
class InvocationCallbacksFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("body:$value")
  }

  companion object {
    @JvmStatic
    @BeforeParameterizedClassInvocation
    fun before(value: String) {
      EagerRecorder.record("before:$value")
    }

    @JvmStatic
    @AfterParameterizedClassInvocation
    fun after(value: String) {
      EagerRecorder.record("after:$value")
    }
  }
}

/**
 * `allowZeroInvocations`: legal, and nothing to put in the tree.
 *
 * Enumeration succeeds and returns nothing, so the class node is taken over with no invocation under it —
 * indistinguishable, from the outside, from a class the engine could not enumerate at all.
 */
@ParameterizedClass(allowZeroInvocations = true)
@MethodSource("none")
class ZeroInvocationsFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("zero:$value")
  }

  companion object {
    @JvmStatic
    fun none(): List<String> = emptyList()
  }
}

/** A provider that arrives through `@RegisterExtension` rather than `@ExtendWith`. */
class ManualInvocationProvider : ClassTemplateInvocationContextProvider {
  override fun supportsClassTemplate(context: ExtensionContext): Boolean = true

  override fun provideClassTemplateInvocationContexts(context: ExtensionContext): Stream<ClassTemplateInvocationContext> =
    Stream.of(named("manual 1"), named("manual 2"))

  private fun named(name: String): ClassTemplateInvocationContext = object : ClassTemplateInvocationContext {
    override fun getDisplayName(invocationIndex: Int): String = name
  }
}

/**
 * A `@ClassTemplate` whose provider arrives via `@RegisterExtension` on a static field.
 *
 * Enumerable, because the engine replicates Jupiter's registry instead of scanning annotations: the field is
 * static, so it can be read without a test instance and registered exactly as `prepare()` would. A provider on
 * a *non-static* field cannot be — see `InstanceSourceFixture` for that shape on the method side.
 */
@ClassTemplate
class RegisteredProviderFixture {
  @Test
  fun records() {
    EagerRecorder.record("registered")
  }

  companion object {
    @JvmField
    @RegisterExtension
    val provider: ManualInvocationProvider = ManualInvocationProvider()
  }
}

/**
 * Stands in for a future Jupiter that starts calling an `ExtensionContext` method we do not implement.
 *
 * Report entries are meaningless during discovery and our synthetic context refuses them, so enumeration
 * throws — and the class must simply ride the lazy path, where the very same call succeeds.
 */
class ReportingInvocationProvider : ClassTemplateInvocationContextProvider {
  override fun supportsClassTemplate(context: ExtensionContext): Boolean {
    context.publishReportEntry("eagerParams", "probing")
    return true
  }

  override fun provideClassTemplateInvocationContexts(context: ExtensionContext): Stream<ClassTemplateInvocationContext> =
    Stream.of(object : ClassTemplateInvocationContext {
      override fun getDisplayName(invocationIndex: Int): String = "reported"
    })
}

@ClassTemplate
@ExtendWith(ReportingInvocationProvider::class)
class UnsupportedContextFixture {
  @Test
  fun records() {
    EagerRecorder.record("reported")
  }
}

/**
 * Counts how many times its arguments are enumerated — the price of building the tree at discovery.
 *
 * `EagerDoubleEnumerationTest` pins the consequence rather than a number: on the eager path the source is
 * asked more than once per run, so a factory with side effects or a non-deterministic one is not free.
 */
@ParameterizedClass(name = "{0}")
@MethodSource("data")
class CountingFactoryFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("counting:$value")
  }

  companion object {
    val calls: AtomicInteger = AtomicInteger()

    @JvmStatic
    fun data(): List<String> {
      calls.incrementAndGet()
      return listOf("c1", "c2")
    }
  }
}
