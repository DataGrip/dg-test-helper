package com.github.kassak.dg.eagerparams

import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.launcher.PostDiscoveryFilter

/**
 * Removes the `junit-jupiter` tree that [EagerParamsTestEngine] has taken over, so that nothing runs twice.
 *
 * Hiding a class from Jupiter's own discovery is not possible — a test class *is* a test class as far as
 * `TestClassPredicates` is concerned, there is no opt-out annotation and the platform has no arbitration
 * between engines — so a post-discovery filter is the only place anything can be hidden.
 *
 * The predicate is "does this descriptor belong to Jupiter", nothing more. That is the whole payoff of taking
 * over every Jupiter test rather than only the parameterized ones: there is no marker to look for, no
 * reflection duplicating Jupiter's discovery rules, and no decision recorded during discovery and handed over
 * — asking twice about the same descriptor gives the same answer forever.
 *
 * Three details make it hold:
 * - the engine mirrors everything Jupiter discovered, whether or not a template's invocations could be
 *   enumerated eagerly (an unenumerable one gets a childless node that `mayRegisterTests()` and is filled in
 *   while running), so "Jupiter's" and "taken over" are the same set;
 * - post-discovery filters are applied to the trees of *all* engines, this engine's own included, hence the
 *   engine check — and they reach into `@Suite` subtrees, where the engine segment is not the first one, hence
 *   [enclosingEngineId] rather than `UniqueId.getEngineId`;
 * - the Jupiter engine node itself is left in place. Excluding it would do nothing at the top level (the
 *   launcher never removes an engine root) and inside a `@Suite` it is not needed either: with all of its
 *   descendants gone the node is empty, and `SuiteReporter.isSkipped` suppresses any node whose last segment
 *   is `engine`, so the IDE shows no leftover "JUnit Jupiter" entry. Keeping it also means a *failed* Jupiter
 *   discovery still surfaces, instead of being filtered away.
 *
 * Two guards keep this from being a way to lose tests:
 * - the kill switch is read here as well as in the engine, otherwise switching the engine off would hide
 *   everything instead of running it lazily;
 * - [EagerEngineLatch] — the engine has to have actually run for this filter to do anything. If the engine is
 *   absent or was excluded by an `EngineFilter` while this filter is still loaded, every Jupiter test would
 *   otherwise disappear silently.
 */
class EagerParamsPostDiscoveryFilter : PostDiscoveryFilter {
  override fun apply(testDescriptor: TestDescriptor): FilterResult {
    if (!isEagerParamsEnabled()) return FilterResult.included(null)
    if (!EagerEngineLatch.isArmed) return FilterResult.included(null)
    val id = testDescriptor.uniqueId
    if (id.lastSegment.type == ENGINE_SEGMENT) return FilterResult.included(null)
    if (enclosingEngineId(id) != JUPITER_ENGINE_ID) return FilterResult.included(null)
    return FilterResult.excluded("taken over by the $EAGER_ENGINE_ID engine")
  }
}
