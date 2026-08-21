package com.github.kassak.dg.eagerparams

import org.junit.platform.engine.UniqueId

internal const val JUPITER_ENGINE_ID: String = "junit-jupiter"

/** Must not start with `junit-`: the platform reserves that prefix (`EngineIdValidator`). */
const val EAGER_ENGINE_ID: String = "intellij-eager-params"

internal const val ENGINE_SEGMENT: String = "engine"

/**
 * The segment types Jupiter uses for the nodes this engine has to reason about, mirrored verbatim.
 *
 * Each one is a `SEGMENT_TYPE` constant of the corresponding Jupiter descriptor. They are duplicated rather
 * than referenced because those descriptor classes are `@API(INTERNAL)` and the values are part of the id
 * format every reporter and rerun already depends on.
 */
internal const val CLASS_TEMPLATE_SEGMENT: String = "class-template"
internal const val NESTED_CLASS_TEMPLATE_SEGMENT: String = "nested-class-template"
internal const val CLASS_TEMPLATE_INVOCATION_SEGMENT: String = "class-template-invocation"
internal const val TEST_TEMPLATE_SEGMENT: String = "test-template"
internal const val TEST_TEMPLATE_INVOCATION_SEGMENT: String = "test-template-invocation"
internal const val TEST_FACTORY_SEGMENT: String = "test-factory"

/**
 * Translation between our unique ids and Jupiter's, relative to the root this engine was handed.
 *
 * Our tree mirrors Jupiter's segment types one to one, so translation is nothing but swapping the engine
 * segment: a pure, total and bidirectional operation. That is what lets selectors, CI filters and reporters
 * keep working — they only ever see ids of the shape they already understand.
 *
 * The engine segment is **not** necessarily the first one. Inside a `@Suite` the launcher hands every nested
 * engine an id built with `parentId.appendEngine(...)`, so our root is
 * `[engine:junit-platform-suite]/[suite:S]/[engine:intellij-eager-params]` and the sibling Jupiter root is
 * the same prefix with `[engine:junit-jupiter]`. Hence the position of the engine segment is taken from the
 * given root rather than assumed, and the nested Jupiter discovery this engine performs is rooted at
 * [jupiterRoot] — the very id the real, neighbouring Jupiter tree has.
 */
internal class EagerIds(val eagerRoot: UniqueId) {
  /** Position of the engine segment: the last segment of the root we were given. */
  private val engineIndex: Int = eagerRoot.segments.size - 1

  val jupiterRoot: UniqueId = swapEngine(eagerRoot, JUPITER_ENGINE_ID)

  fun toJupiter(id: UniqueId): UniqueId = swapEngine(id, JUPITER_ENGINE_ID)

  fun toEager(id: UniqueId): UniqueId = swapEngine(id, EAGER_ENGINE_ID)

  /** `true` when the id names a node of this engine's own subtree. */
  fun owns(id: UniqueId): Boolean = id.hasPrefix(eagerRoot)

  private fun swapEngine(id: UniqueId, engineId: String): UniqueId {
    val segments = id.segments
    if (segments.size <= engineIndex) return id
    var result = if (engineIndex == 0) UniqueId.forEngine(engineId)
    else UniqueId.root(segments[0].type, segments[0].value)
    for (index in 1 until segments.size) {
      result = if (index == engineIndex) result.appendEngine(engineId) else result.append(segments[index])
    }
    return result
  }
}

/**
 * The engine an id belongs to: the value of its **last** `engine` segment.
 *
 * Not the first one — inside a `@Suite` every engine is rooted at `[engine:junit-platform-suite]/[suite:S]/…`,
 * so the first `engine` segment names the suite engine for Jupiter's nodes and ours alike.
 */
internal fun enclosingEngineId(id: UniqueId): String? =
  id.segments.lastOrNull { it.type == ENGINE_SEGMENT }?.value

/** The 1-based invocation index encoded in a `class-template-invocation` segment. */
internal fun invocationIndex(id: UniqueId): Int? {
  val segment = id.lastSegment
  if (segment.type != CLASS_TEMPLATE_INVOCATION_SEGMENT) return null
  return segment.value.removePrefix("#").toIntOrNull()
}
