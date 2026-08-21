@file:Suppress("unused")

package com.github.kassak.dg.eagerparams.fixtures

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.params.Parameter
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

// Fixtures for the eagerParams self-tests. They are run only through a nested Launcher, so their names must
// match nothing the ambient runners scan for: neither `*Test` nor a `Test`/`Suite` word anywhere in the FQCN.
// Under Gradle their own source set is what keeps them out of the `test` task — `test` only ever selects
// classes found under its own output dirs — and the naming is the second, runner-independent layer.
//
// Nothing here is specific to the eager engine at all: there is no annotation to add and no opt-in. Every one
// of these classes is an ordinary `@ParameterizedClass`, and the engine takes it over because it is on the
// classpath. That is the whole point, and it is what EagerDropInTest checks by running the same class with the
// engine and with `-Dintellij.test.eagerParams.enabled=false`.
//
// The `name` patterns are explicit rather than default because the tests assert on exact display names and
// the default pattern prefixes field-injected arguments with the field name (`[1] value=alpha`). Parity with
// the default pattern is the business of EagerNameParityTest, which compares both paths instead of hardcoding.

/** Three invocations, one test method, one `@Parameter` field. */
@ParameterizedClass(name = "[{index}] {arguments}")
@ValueSource(strings = ["alpha", "beta", "gamma"])
class SingleArgumentFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records(info: TestInfo) {
    EagerRecorder.record("single:$value:${info.displayName}")
  }
}

/** Two test methods, so every invocation has to multiply both of them. */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["p", "q"])
class TwoMethodsFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun first() {
    EagerRecorder.record("first:$value")
  }

  @Test
  fun second() {
    EagerRecorder.record("second:$value")
  }
}

/** Two arguments of different types, injected into two indexed fields. */
@ParameterizedClass(name = "{0}=#{1}")
@CsvSource(value = ["a, 1", "b, 2"])
class MultipleArgumentsFixture {
  @field:Parameter(0)
  lateinit var value: String

  @field:Parameter(1)
  var number: Int = -1

  @Test
  fun records() {
    EagerRecorder.record("multiple:$value:$number")
  }
}

/** Constructor injection instead of fields: Jupiter's choice, made by the shape of the class. */
@ParameterizedClass(name = "{0}/{1}")
@CsvSource(value = ["x, 1", "y, 2"])
class ConstructorInjectionFixture(private val value: String, private val number: Int) {
  @Test
  fun records() {
    EagerRecorder.record("constructor:$value:$number")
  }
}

/** `Arguments.argumentSet` names the invocation itself, which the `{argumentSetName}` placeholder picks up. */
@ParameterizedClass(name = "{argumentSetName}")
@MethodSource("sets")
class NamedSetFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("named:$value")
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
 * A `@Nested` member inside a class template.
 *
 * The shape where the tree is wider than the selector: name only the nested class and Jupiter still resolves
 * the enclosing class as its parent, so the whole `[class-template:…]` node comes back and has to be expanded.
 * The engine sees exactly what the filter is about to remove, because both work from the resolved tree rather
 * than from the request.
 */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["outer1", "outer2"])
class NestedMemberFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun outer() {
    EagerRecorder.record("outer:$value")
  }

  @Nested
  inner class Inner {
    @Test
    fun inner() {
      EagerRecorder.record("inner:$value")
    }
  }
}

/**
 * A base class holding the injected field, which is the shape the dbe hierarchy has:
 * `AbstractParametrizedTest` plus ~140 subclasses.
 *
 * Worth a fixture because the engine has to reach the concrete class, not the declaring one, when it asks who
 * the invocations belong to.
 */
abstract class ParameterFieldBaseFixture {
  @field:Parameter
  lateinit var value: String
}

/** Injected into a field it inherits from [ParameterFieldBaseFixture]. */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["base1", "base2"])
class InheritsFieldFixture : ParameterFieldBaseFixture() {
  @Test
  fun records() {
    EagerRecorder.record("dropIn:$value")
  }
}

/**
 * The same class shape with the field declared here rather than inherited.
 *
 * It records the *same* strings as [InheritsFieldFixture] on purpose: several tests compare the two runs
 * element by element, so a difference in the recorded output means a difference in behaviour rather than in
 * the fixture.
 */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["base1", "base2"])
class DeclaredFieldFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("dropIn:$value")
  }
}
