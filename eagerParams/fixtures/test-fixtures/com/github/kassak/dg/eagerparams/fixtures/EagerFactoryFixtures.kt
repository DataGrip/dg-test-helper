@file:Suppress("unused")

package com.github.kassak.dg.eagerparams.fixtures

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.atomic.AtomicInteger

// Fixtures for `@TestFactory`. Naming rule: see EagerParamsFixtures.kt.
//
// A factory is the one template the engine cannot interrogate — its tests are created by the method body, not
// by a provider — so enumerating it eagerly means invoking it at discovery with the dynamic tests skipped.
// These fixtures pin both halves of that: the shapes it has to reproduce, and the cost it has to admit to.

/**
 * Everything a factory can return: plain dynamic tests and a container with children of its own.
 *
 * The container is the interesting half. It is not produced by the factory call at all — Jupiter expands it
 * only when it executes the container node — so getting its children into the tree proves the probe follows
 * the registration all the way down rather than reading one level of return value.
 */
class DynamicTreeFixture {
  @TestFactory
  fun tree(): List<DynamicNode> = listOf(
    DynamicTest.dynamicTest("first") { EagerRecorder.record("dyn:first") },
    DynamicContainer.dynamicContainer(
      "group",
      listOf(
        DynamicTest.dynamicTest("inner a") { EagerRecorder.record("dyn:a") },
        DynamicTest.dynamicTest("inner b") { EagerRecorder.record("dyn:b") },
      ),
    ),
    DynamicTest.dynamicTest("last") { EagerRecorder.record("dyn:last") },
  )
}

/**
 * Counts how often the factory body runs, and how often the class is set up around it.
 *
 * Both counters pin documented costs rather than desirable behaviour: eager enumeration calls the factory once
 * at discovery and Jupiter calls it again while running, and getting into the factory at all means constructing
 * the class and running its `@BeforeAll`.
 */
class CountingFactoryLifecycleFixture {
  @TestFactory
  fun counted(): List<DynamicTest> {
    factoryCalls.incrementAndGet()
    return listOf(
      DynamicTest.dynamicTest("counted 1") { EagerRecorder.record("counted:1") },
      DynamicTest.dynamicTest("counted 2") { EagerRecorder.record("counted:2") },
    )
  }

  companion object {
    val factoryCalls: AtomicInteger = AtomicInteger()
    val beforeAllCalls: AtomicInteger = AtomicInteger()

    @JvmStatic
    @BeforeAll
    fun setUp() {
      beforeAllCalls.incrementAndGet()
    }

    fun reset() {
      factoryCalls.set(0)
      beforeAllCalls.set(0)
    }
  }
}

/**
 * A factory that throws instead of returning tests.
 *
 * The probe has to swallow this: discovery must finish, the node must stay in the tree childless, and the
 * error must be reported exactly once — by the real run, in its own words.
 */
class ThrowingFactoryMethodFixture {
  @TestFactory
  fun broken(): List<DynamicTest> = throw IllegalStateException("no dynamic tests for you")
}
