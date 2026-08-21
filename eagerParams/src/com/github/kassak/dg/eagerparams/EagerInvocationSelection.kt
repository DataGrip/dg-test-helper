package com.github.kassak.dg.eagerparams

import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.IterationSelector
import org.junit.platform.engine.discovery.MethodSelector
import org.junit.platform.engine.discovery.UniqueIdSelector
import java.lang.reflect.Method

/**
 * Decides which invocations of a **method** template belong in the eager tree.
 *
 * Jupiter answers the same question with `DynamicDescendantFilter`, which `MethodSelectorResolver` fills in
 * while resolving selectors: a `UniqueIdSelector` reaching into a template node calls `allowUniqueIdPrefix`,
 * an `IterationSelector` over the template's method calls `allowIndex`, and anything that merely reaches the
 * node itself calls `allowAll`. That filter is package-private and, for a method template, has nothing to act
 * on at discovery — the node has no children yet — so the selection is replicated here from the request's own
 * selectors.
 *
 * It has to be replicated rather than skipped. Rerunning one failed iteration from the IDE is
 * `DiscoverySelectors.selectIteration(methodSelector, index)`; without this the eager tree would show all N
 * invocations and the delegated run would report N−1 of them as skipped.
 *
 * Class templates need none of this: Jupiter materializes the selected invocations itself while we discover,
 * and the engine mirrors exactly the ones it finds.
 */
internal class EagerInvocationSelection(request: EngineDiscoveryRequest, ids: EagerIds) {
  /** Selected ids in *our* id space, so they compare directly against the nodes being built. */
  private val selectedIds: List<UniqueId> = request.getSelectorsByType(UniqueIdSelector::class.java)
    .map { ids.toEager(it.uniqueId) }

  private val iterations: List<IterationSelector> = request.getSelectorsByType(IterationSelector::class.java)

  /**
   * A predicate over `(invocation id, 1-based index)` for one template node.
   *
   * "No selector looks inside this node" means the node was reached as a whole, which is `allowAll` — the same
   * conclusion `DynamicDescendantFilter.isEverythingAllowed` reaches from an empty filter.
   */
  fun forTemplate(templateId: UniqueId, testMethod: Method?): (UniqueId, Int) -> Boolean {
    val inside = selectedIds.filter { it.hasPrefix(templateId) && it != templateId }
    val indices = when (testMethod) {
      null -> emptySet()
      else -> iterations.filter { names(it.parentSelector, testMethod) }.flatMapTo(HashSet()) { it.iterationIndices }
    }
    if (inside.isEmpty() && indices.isEmpty()) return { _, _ -> true }
    return { invocationId, index ->
      // `isPrefixOrViceVersa`: a selector may name the invocation, or something below it.
      inside.any { it.hasPrefix(invocationId) || invocationId.hasPrefix(it) } || indices.contains(index - 1)
    }
  }

  /**
   * Only a plain `MethodSelector` parent counts, because that is the only shape
   * `MethodSelectorResolver.resolve(IterationSelector, …)` acts on. Being more generous here would show
   * invocations Jupiter is not going to run.
   */
  private fun names(selector: DiscoverySelector, testMethod: Method): Boolean =
    selector is MethodSelector && runCatching { selector.javaMethod == testMethod }.getOrDefault(false)
}
