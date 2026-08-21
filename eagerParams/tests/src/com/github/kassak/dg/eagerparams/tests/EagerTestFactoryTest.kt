package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.CountingFactoryLifecycleFixture
import com.github.kassak.dg.eagerparams.fixtures.DynamicTreeFixture
import com.github.kassak.dg.eagerparams.fixtures.ThrowingFactoryMethodFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan

/**
 * `@TestFactory` at discovery: the one enumeration that has to **run** user code.
 *
 * Every other template answers the question "what would your invocations be called" from a provider. A dynamic
 * test has no provider and no name until the factory body has made it, so the engine runs the body with the
 * dynamic tests themselves skipped and keeps what got registered. That is a real cost — the class is
 * constructed, `@BeforeAll` runs, and the whole thing happens again for the real run — so it has a switch of
 * its own and the cost is pinned below rather than glossed over.
 */
class EagerTestFactoryTest {
  @Test
  fun `the dynamic tests, containers included, are in the tree before anything runs`() {
    val plan = EagerParamsTestSupport.discoverEager(DynamicTreeFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  DynamicTreeFixture",
      "    tree()",
      "      first",
      "      group",
      "        inner a",
      "        inner b",
      "      last",
    )
    // The container is the interesting half: Jupiter only expands it when it *executes* the container node,
    // so its children prove the probe followed the registrations down rather than reading a return value.
    assertThat(plan.countTestIdentifiers { it.isTest }).isEqualTo(4)
  }

  /** Nothing about the nodes is ours: names, order and kinds all come from the same registrations Jupiter makes. */
  @Test
  fun `names and order match the lazy path exactly`() {
    val lazy = EagerParamsTestSupport.run(DynamicTreeFixture::class.java)
    val plan = EagerParamsTestSupport.discoverEager(DynamicTreeFixture::class.java)

    // Registration order *is* pre-order: Jupiter registers a node and immediately executes it, and executing a
    // container registers its children before moving on to the next sibling.
    assertThat(preOrder(plan, factoryNode(plan))).isEqualTo(lazy.dynamicallyRegistered())
  }

  @Test
  fun `and nothing is left to announce dynamically while running`() {
    val run = EagerParamsTestSupport.runEager(DynamicTreeFixture::class.java)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactly("dyn:first", "dyn:a", "dyn:b", "dyn:last")
    assertThat(run.startedTests()).containsExactly("first", "inner a", "inner b", "last")
    assertThat(run.eagerEvents().filter { it.kind == EagerEventKind.DYNAMIC }).isEmpty()
    assertThat(run.skipped()).isEmpty()
  }

  @Test
  fun `discovery runs the factory body, and its class setup, exactly once`() {
    CountingFactoryLifecycleFixture.reset()

    val plan = EagerParamsTestSupport.discoverEager(CountingFactoryLifecycleFixture::class.java)

    assertThat(CountingFactoryLifecycleFixture.factoryCalls).hasValue(1)
    // The documented price of a factory node: getting into the method means constructing the class around it.
    assertThat(CountingFactoryLifecycleFixture.beforeAllCalls).hasValue(1)
    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  CountingFactoryLifecycleFixture",
      "    counted()",
      "      counted 1",
      "      counted 2",
    )
  }

  /** The dynamic tests are *registered*, not run — the probe would otherwise be the whole run, twice. */
  @Test
  fun `discovery does not run the dynamic tests themselves`() {
    CountingFactoryLifecycleFixture.reset()

    val run = EagerParamsTestSupport.run(
      EagerParamsTestSupport.selectClasses(CountingFactoryLifecycleFixture::class.java),
      eager = true,
      discoverBeforeRun = false,
    )

    // One probe for the discovery inside `execute`, one real call while running. Nothing else got in.
    assertThat(CountingFactoryLifecycleFixture.factoryCalls).hasValue(2)
    assertThat(CountingFactoryLifecycleFixture.beforeAllCalls).hasValue(2)
    // Two records, not four: the probe registered both tests without executing either of them.
    assertThat(run.recorded).containsExactly("counted:1", "counted:2")
  }

  @Test
  fun `with the switch off the factory node stays childless`() {
    CountingFactoryLifecycleFixture.reset()

    val plan = withEagerFactoriesDisabled {
      EagerParamsTestSupport.discoverEager(CountingFactoryLifecycleFixture::class.java)
    }

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  CountingFactoryLifecycleFixture",
      "    counted()",
    )
    // No probe at all: discovery costs exactly what the lazy path costs.
    assertThat(CountingFactoryLifecycleFixture.factoryCalls).hasValue(0)
    assertThat(CountingFactoryLifecycleFixture.beforeAllCalls).hasValue(0)
  }

  /** And the node then fills itself in while running, which is the behaviour the switch restores. */
  @Test
  fun `with the switch off the children arrive during execution`() {
    CountingFactoryLifecycleFixture.reset()

    val run = withEagerFactoriesDisabled {
      EagerParamsTestSupport.run(
        EagerParamsTestSupport.selectClasses(CountingFactoryLifecycleFixture::class.java),
        eager = true,
        discoverBeforeRun = false,
      )
    }

    assertThat(CountingFactoryLifecycleFixture.factoryCalls).hasValue(1)
    assertThat(run.recorded).containsExactly("counted:1", "counted:2")
    assertThat(run.dynamicallyRegistered()).containsExactly("counted 1", "counted 2")
  }

  /**
   * A factory that throws cannot be enumerated, and must not take discovery down with it.
   *
   * The probe swallows it, the node keeps the childless shape it has without the probe, and the error is
   * reported once — by the real run, in Jupiter's own words.
   */
  @Test
  fun `a throwing factory leaves discovery intact and is reported once`() {
    val plan = EagerParamsTestSupport.discoverEager(ThrowingFactoryMethodFixture::class.java)

    assertThat(EagerParamsTestSupport.claimedClasses(plan))
      .containsExactly(ThrowingFactoryMethodFixture::class.java.name)
    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  ThrowingFactoryMethodFixture",
      "    broken()",
    )

    val run = EagerParamsTestSupport.runEager(ThrowingFactoryMethodFixture::class.java)

    assertThat(run.singleFailure()).hasMessageContaining("no dynamic tests for you")
  }

  /**
   * Rerunning one dynamic test: the probe has to narrow the same way the run will.
   *
   * The id the IDE remembered names a node *under* a factory, and Jupiter turns such a selector into
   * `allowUniqueIdPrefix` on the factory's `DynamicDescendantFilter`. Handing the probe that id instead of the
   * factory's own is what keeps the tree from showing N nodes of which N−1 report as skipped.
   */
  @Test
  fun `selecting one dynamic test leaves exactly that one`() {
    val full = EagerParamsTestSupport.discoverEager(DynamicTreeFixture::class.java)
    val inner = EagerParamsTestSupport.child(
      full, EagerParamsTestSupport.child(full, factoryNode(full), "group"), "inner b",
    )

    val run = EagerParamsTestSupport.run(listOf(DiscoverySelectors.selectUniqueId(inner.uniqueId)), eager = true)

    assertThat(run.failureMessages()).isEmpty()
    assertThat(run.recorded).containsExactly("dyn:b")
    assertThat(run.startedTests()).containsExactly("inner b")
    assertThat(run.skipped()).isEmpty()
    assertThat(run.discoveredPlan.countTestIdentifiers { it.isTest }).isEqualTo(1)
  }

  private fun factoryNode(plan: TestPlan): TestIdentifier {
    val root = EagerParamsTestSupport.eagerRoot(plan)!!
    return plan.getChildren(plan.getChildren(root).single()).single()
  }

  /** Display names of everything under [node], parents before children, in tree order. */
  private fun preOrder(plan: TestPlan, node: TestIdentifier): List<String> =
    plan.getChildren(node).flatMap { listOf(it.displayName) + preOrder(plan, it) }
}
