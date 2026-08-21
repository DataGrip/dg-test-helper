package com.github.kassak.dg.eagerparams

import org.junit.jupiter.api.extension.DynamicTestInvocationContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor

/**
 * The configuration parameter that tells [EagerFactoryProbe] it is running inside a probe.
 *
 * Set only on the wrapped parameters of [EagerFactoryEnumerator]'s own run, so a real run — even one that
 * happens to have extension autodetection turned on — sees the interceptor do nothing.
 */
internal const val PROBE_PARAMETER: String = "intellij.test.eagerParams.probe"

/**
 * Turns the probe run of a `@TestFactory` method into a dry run: the factory body executes, the dynamic tests
 * it produced do not.
 *
 * A dynamic test cannot be named without being created, and it is created by the factory body — so the only
 * way to put dynamic tests in the tree at discovery is to invoke the factory. Their *bodies* must obviously
 * not run then, and `interceptDynamicTest` is the hook that separates the two: Jupiter registers the
 * descriptor (which is all the probe wants) and then asks the interceptor chain to invoke it, where this
 * skips. `interceptTestFactoryMethod` is deliberately not overridden — the inherited default proceeds, which
 * is exactly right.
 *
 * The same trick, and the same hook, as `com.intellij.junit5.CollectInvocationsInterceptor` in the IDE's
 * JUnit 5 runner.
 *
 * Public and registered in `META-INF/services/org.junit.jupiter.api.extension.Extension` because that is the
 * only way an extension gets into a run this engine does not own: [EagerFactoryEnumerator] enables Jupiter's
 * extension autodetection for its own run and restricts it, by include pattern, to this one class.
 */
class EagerFactoryProbe : InvocationInterceptor {
  override fun interceptDynamicTest(
    invocation: InvocationInterceptor.Invocation<Void>,
    invocationContext: DynamicTestInvocationContext,
    extensionContext: ExtensionContext,
  ) {
    val probing = extensionContext.getConfigurationParameter(PROBE_PARAMETER).orElse("false").toBoolean()
    if (probing) invocation.skip() else invocation.proceed()
  }
}
