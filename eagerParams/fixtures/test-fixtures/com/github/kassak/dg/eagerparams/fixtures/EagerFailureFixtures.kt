@file:Suppress("unused")

package com.github.kassak.dg.eagerparams.fixtures

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.Parameter
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

// Fixtures that fail on purpose. They must never be picked up by the ambient run — see EagerParamsFixtures.kt
// for the naming rule that guarantees it.
//
// There is nothing here about bad name patterns, bad argument indices or unsupported class shapes any more:
// validating a `@ParameterizedClass` is Jupiter's job, and the engine has no parallel rules left to test. What
// remains is the two things the *engine* has to get right — a class it cannot enumerate at discovery, and a
// container that fails or aborts before the children it already put in the plan ever start.

/** Cannot be enumerated at discovery: the engine must decline it and let the lazy path report the failure. */
@ParameterizedClass
@MethodSource("data")
class ThrowingFactoryFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("throwing:$value")
  }

  companion object {
    @JvmStatic
    fun data(): List<String> = throw IllegalStateException("factory blew up")
  }
}

/** `@BeforeAll` fails: the class node fails and its invocations never start. */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["a", "b"])
class FailingBeforeAllFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun records() {
    EagerRecorder.record("beforeAll:$value")
  }

  companion object {
    @JvmStatic
    @BeforeAll
    fun setUp() {
      throw IllegalStateException("@BeforeAll blew up")
    }
  }
}

/** An assumption in `@BeforeEach` aborts every test without failing anything. */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["a", "b"])
class AbortingBeforeEachFixture {
  @field:Parameter
  lateinit var value: String

  @BeforeEach
  fun check() {
    Assumptions.assumeTrue(false, "not today")
  }

  @Test
  fun records() {
    EagerRecorder.record("aborted:$value")
  }
}
