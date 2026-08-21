package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.EAGER_ENGINE_ID
import com.github.kassak.dg.eagerparams.EagerEngineLatch
import com.github.kassak.dg.eagerparams.EagerFactoryProbe
import com.github.kassak.dg.eagerparams.EagerParamsPostDiscoveryFilter
import com.github.kassak.dg.eagerparams.EagerParamsTestEngine
import com.github.kassak.dg.eagerparams.fixtures.InheritsFieldFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.Extension
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.UniqueId
import org.junit.platform.launcher.PostDiscoveryFilter
import java.util.ServiceLoader

/**
 * The three `META-INF/services` registrations, checked through the same mechanism the platform uses.
 *
 * They belong together and must stay together: the engine without the filter runs every taken-over class twice,
 * and the filter without the engine hides Jupiter's whole tree with nothing to run it instead, so the tests
 * disappear in silence — which is what [EagerEngineLatch] is for. The kill switch is the same contract seen
 * from the other side: both sides read it, so turning the mechanism off puts everything back on the lazy path
 * rather than removing it.
 *
 * The third registration is of a different kind: [EagerFactoryProbe] is not part of the mechanism's own wiring
 * but an extension the engine injects into a Jupiter run it does not own, and Jupiter's autodetection finds
 * extensions only through this file.
 *
 * All three live in the engine's own `resources/META-INF/services` rather than next to these tests: a `@Suite`
 * discovers its members through a nested launcher that service-loads engines from the classpath, so the
 * registrations have to travel with the jar to reach every consumer.
 */
class EagerServiceLoaderTest {
  @Test
  fun `the engine is registered`() {
    assertThat(load(TestEngine::class.java)).hasAtLeastOneElementOfType(EagerParamsTestEngine::class.java)
  }

  /** `EngineIdValidator` rejects the `junit-` prefix outright, so this is not cosmetic. */
  @Test
  fun `the engine id is not in the reserved namespace`() {
    val engine = load(TestEngine::class.java).single { it is EagerParamsTestEngine }
    assertThat(engine.id).isEqualTo(EAGER_ENGINE_ID).doesNotStartWith("junit-")
  }

  @Test
  fun `the post-discovery filter is registered`() {
    assertThat(load(PostDiscoveryFilter::class.java))
      .hasAtLeastOneElementOfType(EagerParamsPostDiscoveryFilter::class.java)
  }

  /**
   * Without this, `@TestFactory` enumeration silently degrades: the probe run turns autodetection on and asks
   * for this class by name, and an unregistered service is simply not there to find.
   */
  @Test
  fun `the factory probe is registered as a Jupiter extension`() {
    assertThat(load(Extension::class.java)).hasAtLeastOneElementOfType(EagerFactoryProbe::class.java)
  }

  /**
   * With the switch off the engine claims nothing, so the filter must hide nothing either — otherwise the very
   * tests the switch exists to put back on the lazy path would be the ones removed from it.
   */
  @Test
  fun `the filter excludes nothing while the mechanism is switched off`() {
    val jupiterNode = jupiterClassTemplate(InheritsFieldFixture::class.java)
    val filter = EagerParamsPostDiscoveryFilter()

    // Armed explicitly rather than by luck: whether some earlier test in this process has already armed the
    // latch is not something a unit test may depend on.
    EagerEngineLatch.withArmed(true) {
      assertThat(filter.apply(jupiterNode).excluded()).isTrue()
      withEagerDisabled {
        assertThat(filter.apply(jupiterNode).included()).isTrue()
      }
    }
  }

  /** The engine's own nodes go through the filter too, and must never be mistaken for Jupiter's. */
  @Test
  fun `the filter leaves this engine's own tree alone`() {
    val ours = descriptor(
      UniqueId.forEngine(EAGER_ENGINE_ID).append("class-template", InheritsFieldFixture::class.java.name)
    )

    EagerEngineLatch.withArmed(true) {
      assertThat(EagerParamsPostDiscoveryFilter().apply(ours).included()).isTrue()
    }
  }

  private fun <T> load(type: Class<T>): List<T> = ServiceLoader.load(type, javaClass.classLoader).toList()
}
