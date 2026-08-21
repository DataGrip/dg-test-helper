package com.github.kassak.dg.eagerparams.tests

import com.github.kassak.dg.eagerparams.EagerEngineLatch
import com.github.kassak.dg.eagerparams.EagerParamsPostDiscoveryFilter
import com.github.kassak.dg.eagerparams.fixtures.InheritsFieldFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The one guard that keeps a full takeover from being a way to lose every test in the process.
 *
 * The filter removes Jupiter's whole tree, so it must be certain the engine actually ran and mirrored it. The
 * two are registered together and read the same kill switch, but they are *loaded* separately: an
 * `EngineFilter` can exclude the engine while leaving the filter installed, and then every Jupiter test would
 * disappear in silence rather than fail. So the filter waits for the engine to have run at least once.
 *
 * A one-way `@Volatile` flag, never reset, deliberately: tying it to a request boundary would need a listener
 * that can itself be left out of a launcher, which is the very failure mode being guarded against.
 */
class EagerArmedLatchTest {
  @Test
  fun `the filter excludes nothing until the engine has run`() {
    val jupiterNode = jupiterClassTemplate(InheritsFieldFixture::class.java)

    EagerEngineLatch.withArmed(false) {
      assertThat(EagerParamsPostDiscoveryFilter().apply(jupiterNode).included()).isTrue()
    }
  }

  @Test
  fun `discovering arms it, and then Jupiter's tree is ours`() {
    val jupiterNode = jupiterClassTemplate(InheritsFieldFixture::class.java)

    EagerEngineLatch.withArmed(false) {
      EagerParamsTestSupport.discoverEager(InheritsFieldFixture::class.java)

      assertThat(EagerEngineLatch.isArmed).isTrue()
      assertThat(EagerParamsPostDiscoveryFilter().apply(jupiterNode).excluded()).isTrue()
    }
  }

  /** Switched off, the engine returns before arming — so the filter has two independent reasons to stay inert. */
  @Test
  fun `a switched-off discovery does not arm it`() {
    EagerEngineLatch.withArmed(false) {
      withEagerDisabled {
        EagerParamsTestSupport.discoverEager(InheritsFieldFixture::class.java)
      }

      assertThat(EagerEngineLatch.isArmed).isFalse()
      assertThat(EagerParamsPostDiscoveryFilter().apply(jupiterClassTemplate(InheritsFieldFixture::class.java)).included())
        .isTrue()
    }
  }
}
