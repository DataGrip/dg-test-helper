package com.github.kassak.dg.eagerparams

import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.FileEntry
import org.junit.platform.engine.reporting.ReportEntry

/**
 * Rewrites the delegated Jupiter run's events onto our own tree.
 *
 * Two rules make this safe. First, only nodes the platform knows about may receive events —
 * `ExecutionListenerAdapter` throws for anything absent from the `TestPlan` — so a Jupiter node without a
 * counterpart of ours is mirrored on the fly and announced with `dynamicTestRegistered` before anything
 * else is said about it. Second, every node of ours must end up reported: Jupiter says nothing at all
 * about the children of a container that failed in `@BeforeAll`, and those children *are* in the plan
 * already, so they are explicitly skipped before their parent finishes ([reportUnvisited] is the backstop).
 */
internal class TranslatingEngineExecutionListener(
  private val root: TestDescriptor,
  private val ids: EagerIds,
  private val delegate: EngineExecutionListener,
) : EngineExecutionListener {
  private val known = HashMap<UniqueId, TestDescriptor>()
  private val visited = HashSet<UniqueId>()

  /** The result Jupiter reported for its own engine node, where engine-level failures land. */
  var engineResult: TestExecutionResult? = null
    private set

  init {
    register(root)
  }

  override fun dynamicTestRegistered(testDescriptor: TestDescriptor) {
    // Announced by `translate` itself, and only for nodes that are genuinely new to us. Class template
    // invocations are not: they are the very nodes this engine put into the plan at discovery.
    translate(testDescriptor)
  }

  override fun executionSkipped(testDescriptor: TestDescriptor, reason: String?) {
    val ours = translate(testDescriptor) ?: return
    if (ours === root) return
    markVisited(ours)
    delegate.executionSkipped(ours, reason)
  }

  override fun executionStarted(testDescriptor: TestDescriptor) {
    val ours = translate(testDescriptor) ?: return
    if (ours === root) return
    visited.add(ours.uniqueId)
    delegate.executionStarted(ours)
  }

  override fun executionFinished(testDescriptor: TestDescriptor, testExecutionResult: TestExecutionResult) {
    val ours = translate(testDescriptor) ?: return
    if (ours === root) {
      engineResult = testExecutionResult
      return
    }
    skipUnvisited(ours)
    delegate.executionFinished(ours, testExecutionResult)
  }

  override fun reportingEntryPublished(testDescriptor: TestDescriptor, entry: ReportEntry) {
    delegate.reportingEntryPublished(nearestKnown(testDescriptor), entry)
  }

  override fun fileEntryPublished(testDescriptor: TestDescriptor, file: FileEntry) {
    delegate.fileEntryPublished(nearestKnown(testDescriptor), file)
  }

  /** Reports everything the delegated run never mentioned, so that no node is left dangling in the IDE. */
  fun reportUnvisited() {
    skipUnvisited(root)
  }

  private fun register(descriptor: TestDescriptor) {
    known[descriptor.uniqueId] = descriptor
    descriptor.children.forEach(::register)
  }

  private fun translate(jupiter: TestDescriptor): TestDescriptor? {
    val id = ids.toEager(jupiter.uniqueId)
    known[id]?.let { return it }
    val parent = jupiter.parent.orElse(null) ?: return null
    val ourParent = translate(parent) ?: return null
    val mirror = EagerMirrorDescriptor.mirror(id, jupiter)
    ourParent.addChild(mirror)
    known[id] = mirror
    delegate.dynamicTestRegistered(mirror)
    return mirror
  }

  /** The closest ancestor we have a counterpart for; report entries are attached there. */
  private fun nearestKnown(jupiter: TestDescriptor): TestDescriptor {
    var current: TestDescriptor? = jupiter
    while (current != null) {
      known[ids.toEager(current.uniqueId)]?.let { return it }
      current = current.parent.orElse(null)
    }
    return root
  }

  private fun skipUnvisited(container: TestDescriptor) {
    for (child in container.children) {
      if (visited.contains(child.uniqueId)) {
        skipUnvisited(child)
      }
      else {
        markVisited(child)
        delegate.executionSkipped(child, "did not run")
      }
    }
  }

  private fun markVisited(descriptor: TestDescriptor) {
    visited.add(descriptor.uniqueId)
    descriptor.children.forEach(::markVisited)
  }
}
