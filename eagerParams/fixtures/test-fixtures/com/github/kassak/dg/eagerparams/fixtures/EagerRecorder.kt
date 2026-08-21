package com.github.kassak.dg.eagerparams.fixtures

import java.util.Collections

/**
 * Side channel through which fixtures report what was injected into them.
 *
 * Public because the fixtures live in their own module: nothing else may see them, and Kotlin `internal`
 * stops at the module boundary. The test module reads it back through `EagerParamsTestSupport`.
 */
object EagerRecorder {
  private val entries: MutableList<String> = Collections.synchronizedList(mutableListOf())

  fun record(entry: String) {
    entries.add(entry)
  }

  fun reset() {
    entries.clear()
  }

  fun snapshot(): List<String> = synchronized(entries) { entries.toList() }
}
