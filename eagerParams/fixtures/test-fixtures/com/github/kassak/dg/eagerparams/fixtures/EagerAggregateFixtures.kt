@file:Suppress("unused")

package com.github.kassak.dg.eagerparams.fixtures

import org.junit.platform.suite.api.IncludeClassNamePatterns
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

// Aggregating fixtures for EagerSuiteTest. Naming rule: see EagerParamsFixtures.kt — and note that `Suite`
// is one of the forbidden words in the FQCN, which is why these are called `Aggregate`.

/**
 * One claimed class and one declined one behind a `@Suite`, which is the shape every dbe run has.
 *
 * `@IncludeClassNamePatterns(".*")` is not decoration: a suite turns the standard class-name filter on, and
 * `*Fixture` does not match it.
 */
@Suite
@SelectClasses(InheritsFieldFixture::class, ThrowingFactoryFixture::class)
@IncludeClassNamePatterns(".*")
class EagerAggregateFixture
