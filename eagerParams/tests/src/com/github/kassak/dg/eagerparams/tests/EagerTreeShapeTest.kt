package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.fixtures.EagerRecorder
import com.github.kassak.dg.eagerparams.fixtures.PlainJupiterFixture
import com.github.kassak.dg.eagerparams.fixtures.SingleArgumentFixture
import com.github.kassak.dg.eagerparams.fixtures.ThrowingFactoryFixture
import com.github.kassak.dg.eagerparams.fixtures.TwoMethodsFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.platform.engine.support.descriptor.ClassSource

/**
 * The whole point of the engine: after `discover()` the tree is already complete.
 *
 * Compare with [EagerLazyPathTest], which pins the same fixtures having *nothing* below the class node at
 * that moment. Everything the IDE draws — display names, unique ids, sources — has to be right here,
 * because this is all it gets before execution starts.
 */
class EagerTreeShapeTest {
  @Test
  fun `the full tree is in the plan right after discovery`() {
    val plan = EagerParamsTestSupport.discoverEager(SingleArgumentFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  SingleArgumentFixture",
      "    [1] alpha",
      "      records(TestInfo)",
      "    [2] beta",
      "      records(TestInfo)",
      "    [3] gamma",
      "      records(TestInfo)",
    )
  }

  @Test
  fun `invocations multiply every test method`() {
    val plan = EagerParamsTestSupport.discoverEager(TwoMethodsFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  TwoMethodsFixture",
      "    p",
      "      first()",
      "      second()",
      "    q",
      "      first()",
      "      second()",
    )
  }

  @Test
  fun `discovery does not run anything`() {
    EagerRecorder.reset()

    EagerParamsTestSupport.discoverEager(SingleArgumentFixture::class.java)

    assertThat(EagerRecorder.snapshot()).isEmpty()
  }

  /** The other half of the takeover: what the engine mirrors, the filter removes, so nothing runs twice. */
  @Test
  fun `a claimed class is gone from the ordinary Jupiter tree`() {
    val plan = EagerParamsTestSupport.discoverEager(SingleArgumentFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.jupiterRoot(plan)))
      .containsExactly("JUnit Jupiter")
  }

  /** A class with no template in it is mirrored node for node — the engine has nothing to add, and adds nothing. */
  @Test
  fun `an unparameterized class comes through the engine unchanged`() {
    val plan = EagerParamsTestSupport.discoverEager(PlainJupiterFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan))).containsExactly(
      "Eager Parameterized Classes",
      "  PlainJupiterFixture",
      "    plain()",
    )
    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.jupiterRoot(plan)))
      .containsExactly("JUnit Jupiter")
  }

  /**
   * Template or not, one request leaves everything in one engine.
   *
   * Splitting a request between the two would be the actual hazard: two class nodes for one class in the IDE,
   * and `@BeforeAll` twice. Taking over the whole request is what makes that unrepresentable.
   */
  @Test
  fun `templates and plain classes in one request end up in this engine together`() {
    val plan = EagerParamsTestSupport.discoverEager(TwoMethodsFixture::class.java, PlainJupiterFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .contains("  TwoMethodsFixture", "  PlainJupiterFixture")
    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.jupiterRoot(plan)))
      .containsExactly("JUnit Jupiter")
  }

  /** Segment types mirror Jupiter's one to one; only the engine segment differs. */
  @Test
  fun `unique ids mirror the Jupiter ones`() {
    val plan = EagerParamsTestSupport.discoverEager(TwoMethodsFixture::class.java)

    assertThat(plan.countTestIdentifiers { it.isTest }).isEqualTo(4)

    val fqcn = TwoMethodsFixture::class.java.name
    val classNode = plan.getChildren(EagerParamsTestSupport.eagerRoot(plan)!!).single()
    val leaf = EagerParamsTestSupport.child(plan, EagerParamsTestSupport.child(plan, classNode, "p"), "first()")
    assertThat(leaf.uniqueId).isEqualTo(
      "[engine:intellij-eager-params]/[class-template:$fqcn]/[class-template-invocation:#1]/[method:first()]"
    )
  }

  /** Indices follow the order the factory returned, which is the order the arguments will be injected in. */
  @Test
  fun `invocation indices follow the factory order`() {
    val plan = EagerParamsTestSupport.discoverEager(SingleArgumentFixture::class.java)

    val classNode = plan.getChildren(EagerParamsTestSupport.eagerRoot(plan)!!).single()
    val byName = plan.getChildren(classNode).associate { it.displayName to it.uniqueIdObject.lastSegment.value }
    assertThat(byName).containsExactlyInAnyOrderEntriesOf(
      mapOf("[1] alpha" to "#1", "[2] beta" to "#2", "[3] gamma" to "#3")
    )
  }

  /** Navigation: an invocation node points at the class, so double-clicking it in the IDE goes somewhere. */
  @Test
  fun `invocation nodes carry the class source`() {
    val plan = EagerParamsTestSupport.discoverEager(SingleArgumentFixture::class.java)

    val classNode = plan.getChildren(EagerParamsTestSupport.eagerRoot(plan)!!).single()
    val invocation = plan.getChildren(classNode).first()
    val source = invocation.source.orElseThrow { AssertionError("no source on ${invocation.displayName}") }
    assertThat(source).isInstanceOf(ClassSource::class.java)
    assertThat((source as ClassSource).className).isEqualTo(SingleArgumentFixture::class.java.name)
  }

  /** CI filters and the TeamCity reporter key on this, so it is copied from Jupiter rather than invented. */
  @Test
  fun `legacy reporting names are copied from Jupiter`() {
    val plan = EagerParamsTestSupport.discoverEager(TwoMethodsFixture::class.java)

    val classNode = plan.getChildren(EagerParamsTestSupport.eagerRoot(plan)!!).single()
    val method = EagerParamsTestSupport.child(plan, EagerParamsTestSupport.child(plan, classNode, "p"), "second()")
    assertThat(method.legacyReportingName).isEqualTo("second()")
  }

  /**
   * A class we could not resolve eagerly is still taken over, but as a bare node: nothing below it until it
   * runs, at which point the delegated Jupiter run supplies the invocations and its own error.
   *
   * It has to be taken over. [com.github.kassak.dg.eagerparams.EagerParamsPostDiscoveryFilter]
   * hides Jupiter's whole tree, and it decides that from the unique id alone — it cannot be told which nodes
   * this engine gave up on. Leaving the class behind here would delete it from both trees.
   */
  @Test
  fun `an unresolvable class is taken over as a bare node`() {
    val plan = EagerParamsTestSupport.discoverEager(ThrowingFactoryFixture::class.java)

    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.eagerRoot(plan)))
      .containsExactly("Eager Parameterized Classes", "  ThrowingFactoryFixture")
    assertThat(EagerParamsTestSupport.tree(plan, EagerParamsTestSupport.jupiterRoot(plan)))
      .doesNotContain("  ThrowingFactoryFixture")
  }
}
