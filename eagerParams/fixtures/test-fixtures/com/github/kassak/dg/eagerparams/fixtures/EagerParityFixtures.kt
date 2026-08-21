@file:Suppress("unused")

package com.github.kassak.dg.eagerparams.fixtures

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.Parameter
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.FieldSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.support.ParameterDeclarations
import java.util.stream.Stream

// One fixture per way of naming an invocation, for EagerNameParityTest. Naming rule: see EagerParamsFixtures.kt.
//
// Unlike the fixtures over there, these deliberately keep the *default* `name` pattern wherever the point is
// the source rather than the pattern. The default is where a reimplemented formatter would diverge first: it
// renders field-injected arguments as `value=a`, argument sets as their set name, and falls back per argument
// type. Nothing here asserts a literal string — EagerNameParityTest runs each fixture both ways and compares.

/** The plainest source there is, with the default pattern. */
@ParameterizedClass
@ValueSource(strings = ["a", "b"])
class ValueSourceParityFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("value:$value")
  }
}

/** A factory method, which the discovery pass has to invoke through our synthetic `ExecutableInvoker`. */
@ParameterizedClass
@MethodSource("data")
class MethodSourceParityFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("method:$value")
  }

  companion object {
    @JvmStatic
    fun data(): List<String> = listOf("m1", "m2")
  }
}

/** Two arguments: the default pattern joins them, so a naive formatter gets the separator wrong. */
@ParameterizedClass
@CsvSource(value = ["p, 1", "q, 2"])
class CsvSourceParityFixture {
  @field:Parameter(0)
  lateinit var value: String

  @field:Parameter(1)
  var number: Int = -1

  @Test
  fun records() {
    EagerRecorder.record("csv:$value:$number")
  }
}

/** A static field as the source; `@JvmField` in the companion is what makes it one. */
@ParameterizedClass
@FieldSource("data")
class FieldSourceParityFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("field:$value")
  }

  companion object {
    @JvmField
    val data: List<String> = listOf("f1", "f2")
  }
}

enum class ParityFlavor { SWEET, SOUR }

/** An enum constant renders through its own `toString`, not through `Arguments`. */
@ParameterizedClass
@EnumSource(ParityFlavor::class)
class EnumSourceParityFixture {
  @field:Parameter
  lateinit var flavor: ParityFlavor

  @Test
  fun records() {
    EagerRecorder.record("enum:$flavor")
  }
}

/** Instantiated by Jupiter through `ExtensionContext.getExecutableInvoker()`, ours included. */
class ParityArgumentsProvider : ArgumentsProvider {
  override fun provideArguments(parameters: ParameterDeclarations, context: ExtensionContext): Stream<out Arguments> =
    Stream.of(Arguments.of("custom1"), Arguments.of("custom2"))
}

/** A third-party source: the discovery pass must be able to construct the provider. */
@ParameterizedClass
@ArgumentsSource(ParityArgumentsProvider::class)
class CustomSourceParityFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("custom:$value")
  }
}

/** Named argument sets take over the default pattern's `{argumentSetNameOrArgumentsWithNames}` half. */
@ParameterizedClass
@MethodSource("sets")
class ArgumentSetParityFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("set:$value")
  }

  companion object {
    @JvmStatic
    fun sets(): List<Arguments> = listOf(
      Arguments.argumentSet("sweet set", "one"),
      Arguments.argumentSet("sour set", "two"),
    )
  }
}

/** Two sources on one class, and a `null` argument the pattern has to render. */
@ParameterizedClass
@NullSource
@ValueSource(strings = ["present"])
class MultipleSourcesParityFixture {
  @field:Parameter
  var value: String? = null

  @Test
  fun records() {
    EagerRecorder.record("multi:$value")
  }
}

/**
 * `{displayName}` resolves against the *class* node's display name, which the discovery pass can only get
 * right by taking it from the Jupiter descriptor it is mirroring rather than from the class itself.
 */
@DisplayName("Renamed parity fixture")
@ParameterizedClass(name = "{displayName} #{index} <{arguments}>")
@ValueSource(strings = ["d1", "d2"])
class DisplayNamePatternParityFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("displayName:$value")
  }
}

/** A `MessageFormat` pattern with indexed segments, quoting and all. */
@ParameterizedClass(name = "{0} takes '{1}' at {index}")
@CsvSource(value = ["one, 1", "two, 2"])
class IndexedPatternParityFixture {
  @field:Parameter(0)
  lateinit var value: String

  @field:Parameter(1)
  var number: Int = -1

  @Test
  fun records() {
    EagerRecorder.record("indexed:$value:$number")
  }
}
