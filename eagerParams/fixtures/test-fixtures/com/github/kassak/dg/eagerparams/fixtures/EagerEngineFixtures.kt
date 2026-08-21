@file:Suppress("unused")

package com.github.kassak.dg.eagerparams.fixtures

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.Parameter
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// Fixtures for the engine-level self-tests. Naming rule: see EagerParamsFixtures.kt.

/**
 * Not parameterized at all: taken over like everything else, and its shape must not change by one node.
 *
 * The engine claims whole classes rather than the templates inside them, so this is the common case, not an
 * exception — most classes in a real run look exactly like this one.
 */
class PlainJupiterFixture {
  @Test
  fun plain() {
    EagerRecorder.record("plain")
  }
}

/** One invocation fails, the other must not notice. */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["good", "bad"])
class FailingInvocationFixture {
  @field:Parameter
  lateinit var value: String

  @Test
  fun checks() {
    EagerRecorder.record("checks:$value")
    if (value == "bad") throw AssertionError("bad argument")
  }
}

/**
 * Both of the things that can hang off a class template: another template, and a factory.
 *
 * `each` is enumerated eagerly by asking the provider; `generated` by running the factory body with its
 * dynamic tests skipped. Both therefore land in the tree once per class invocation, which is the point of
 * having them here rather than in a plain class: the factory's children are keyed by the id of the
 * *materialized* invocation, so getting them onto our side means the two enumerations agree on that id.
 */
@ParameterizedClass(name = "{0}")
@ValueSource(strings = ["u"])
class DynamicChildrenFixture {
  @field:Parameter
  lateinit var value: String

  @ParameterizedTest
  @ValueSource(ints = [1, 2])
  fun each(number: Int) {
    EagerRecorder.record("each:$value:$number")
  }

  @TestFactory
  fun generated(): List<DynamicTest> = listOf(
    DynamicTest.dynamicTest("generated $value") { EagerRecorder.record("generated:$value") }
  )
}
