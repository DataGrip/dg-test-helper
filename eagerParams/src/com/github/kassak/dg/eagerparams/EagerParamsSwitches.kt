package com.github.kassak.dg.eagerparams

import org.jetbrains.annotations.TestOnly

private const val ENABLED_PROPERTY = "intellij.test.eagerParams.enabled"

/**
 * The kill switch, read by [EagerParamsTestEngine] and [EagerParamsPostDiscoveryFilter] alike.
 *
 * Both must read it, and read the same thing: the engine claiming nothing while the filter still hides Jupiter's
 * tree would make every test disappear.
 *
 * There is nothing else to configure. Putting the jar on the test classpath is what turns the mechanism on —
 * `META-INF/services` registers the engine and the filter, and from then on every Jupiter test is taken over.
 * No annotation, no opt-in per class.
 */
internal fun isEagerParamsEnabled(): Boolean = System.getProperty(ENABLED_PROPERTY, "true").toBoolean()

private const val FACTORIES_PROPERTY = "intellij.test.eagerParams.factories"

/**
 * Whether `@TestFactory` methods may be enumerated at discovery, which means **running their bodies**.
 *
 * A separate switch from [isEagerParamsEnabled] because the cost is of a different kind. Everywhere else the
 * engine only asks a provider what the invocations would be called; a dynamic test does not exist until the
 * factory has produced it, so there is nothing to ask — the method has to be invoked, and with it the class
 * constructor, `@BeforeAll`/`@AfterAll`, the factory's own `@BeforeEach`/`@AfterEach` and every extension
 * callback around them. For dbe that means the test application, and possibly connections, during discovery.
 * The dynamic tests themselves are not run (see [EagerFactoryProbe]), and nothing outside the factory methods
 * is selected, but the price is real and someone has to be able to decline it:
 * `-Dintellij.test.eagerParams.factories=false` puts factory nodes back on the lazy path while leaving every
 * other kind of template eager.
 */
internal fun isEagerFactoriesEnabled(): Boolean = System.getProperty(FACTORIES_PROPERTY, "true").toBoolean()

/**
 * Records that [EagerParamsTestEngine] has run at least once in this process.
 *
 * [EagerParamsPostDiscoveryFilter] removes Jupiter's tree wholesale, which is only safe if this engine is
 * actually there to run it instead. The engine and the filter are registered together and cannot be separated
 * by configuration, but they *can* be separated by an `EngineFilter` that excludes the engine while leaving the
 * filter in place — and then every test would vanish silently. So the filter waits to be armed.
 *
 * A one-way latch on purpose: it is never reset, so it does not depend on the boundaries of a discovery
 * request, and asking it twice can only ever go from "not yet" to "yes".
 */
object EagerEngineLatch {
  @Volatile
  private var armed: Boolean = false

  val isArmed: Boolean get() = armed

  internal fun arm() {
    armed = true
  }

  /**
   * Runs [action] with the latch forced to [armed], then puts it back.
   *
   * The only way to observe either state deliberately: by the time a test asks, some other test in the same
   * process has almost certainly armed the latch for good. Public for the self-tests, which live in their own
   * module.
   */
  @TestOnly
  fun <T> withArmed(armed: Boolean, action: () -> T): T {
    val previous = this.armed
    this.armed = armed
    try {
      return action()
    }
    finally {
      this.armed = previous
    }
  }
}
