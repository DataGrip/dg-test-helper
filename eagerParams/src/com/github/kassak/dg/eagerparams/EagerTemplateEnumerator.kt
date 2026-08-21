package com.github.kassak.dg.eagerparams

import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
import org.junit.jupiter.engine.config.DefaultJupiterConfiguration
import org.junit.jupiter.engine.extension.MutableExtensionRegistry
import org.junit.platform.commons.support.AnnotationSupport
import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.OutputDirectoryCreator
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.stream.Stream

/**
 * Asks Jupiter, at discovery time, what a template node would be split into.
 *
 * An eager tree needs exactly two things from a template: how many invocations there are, and what each one is
 * called. It never needs the argument *values* — execution is delegated to Jupiter, which resolves them again
 * itself. Both come straight out of the public SPI: a `ClassTemplateInvocationContextProvider` or a
 * `TestTemplateInvocationContextProvider` enumerates the invocations and `getDisplayName(index)` names them,
 * which is the same call Jupiter's own invocation descriptors make on the lazy path. So naming is not
 * reimplemented here, it is delegated — `name` patterns, placeholders, `argumentSet` names,
 * `junit.jupiter.params.displayname.default` and `@RepeatedTest(name = …)` all keep working without this file
 * knowing they exist.
 *
 * The providers come from a **replica of Jupiter's own extension registry** rather than from a scan of
 * `@ExtendWith`. That is not a refinement, it is the only way that works: `@RepeatedTest` is a `@TestTemplate`
 * with no `@ExtendWith` at all, and its provider is a package-private *default* extension of the registry, as
 * are the auto-detected ones. So the chain `createRegistryWithDefaultExtensions` → per-class
 * `createRegistryFrom` → per-method `createRegistryFrom` is built exactly as
 * `TestTemplateTestDescriptor.prepare` builds it, and the providers are read out of it with `stream(...)`, the
 * way `TemplateExecutor.validateProviders` does. Two more `@API(INTERNAL)` classes is the price; both break at
 * compile time rather than at runtime if a platform upgrade moves them.
 *
 * **Enumeration happens twice per run** — once here and once when Jupiter executes. That is the price of
 * reusing Jupiter's own parameterization instead of memoizing our own: an arguments factory is called twice,
 * `autoCloseArguments` never sees this pass's copies, and a source that does not return the same arguments
 * both times shifts names and indices (which [TranslatingEngineExecutionListener] degrades to
 * `dynamicTestRegistered` rather than choking on).
 *
 * @param configuration the ambient configuration parameters; the registry, the display-name defaults and the
 *   instance lifecycle are all read from it, so an enumerated name matches what the run will produce
 */
internal class EagerTemplateEnumerator(
  private val configuration: ConfigurationParameters,
  private val outputDirectoryCreator: OutputDirectoryCreator,
) {
  private val defaults: MutableExtensionRegistry? by lazy {
    try {
      MutableExtensionRegistry.createRegistryWithDefaultExtensions(
        DefaultJupiterConfiguration(configuration, outputDirectoryCreator)
      )
    }
    catch (_: Throwable) {
      null
    }
  }

  private val registries = HashMap<UniqueId, MutableExtensionRegistry?>()

  /**
   * The display name of every invocation of a `[class-template:…]`/`[nested-class-template:…]` node, in order,
   * or `null` when it cannot be enumerated eagerly.
   *
   * `null` is not an error — it means "this node gets no eager children". It is still taken over by the engine
   * (the filter hides Jupiter's whole tree and cannot be told otherwise), but as a node that fills itself in
   * while running, which is also where whatever the real problem is gets reported, once. That covers a throwing
   * factory, a provider registered in a way we cannot see (a non-static `@RegisterExtension` field), a provider
   * that wants more of the `ExtensionContext` than [DiscoveryExtensionContext] offers, and legitimately zero
   * invocations.
   */
  fun classInvocations(descriptor: TestDescriptor): List<String>? = try {
    val testClass = classOf(descriptor) ?: return null
    val registry = registryFor(descriptor) ?: return null
    DiscoveryExtensionContext.forClass(testClass, descriptor, configuration).use { context ->
      enumerate(
        providers = registry.stream(ClassTemplateInvocationContextProvider::class.java).toList()
          .filter { it.supportsClassTemplate(context) },
        provide = { it.provideClassTemplateInvocationContexts(context) },
        mayReturnZero = { it.mayReturnZeroClassTemplateInvocationContexts(context) },
        nameOf = { invocation, index -> invocation.getDisplayName(index) },
      )
    }
  }
  catch (_: Throwable) {
    null
  }

  /**
   * The display name of every invocation of a `[test-template:…]` node, in order, or `null` when it cannot be
   * enumerated eagerly. Same contract as [classInvocations].
   */
  fun methodInvocations(descriptor: TestDescriptor): List<String>? = try {
    val testMethod = methodOf(descriptor) ?: return null
    val classDescriptor = enclosingClassDescriptor(descriptor) ?: return null
    // Not `MethodSource.getJavaClass()` and not `Method.getDeclaringClass()`: for an inherited template method
    // the provider has to see the concrete test class, which is what the enclosing class node carries.
    val testClass = classOf(classDescriptor) ?: return null
    val registry = registryFor(descriptor) ?: return null
    DiscoveryExtensionContext
      .forMethod(testClass, testMethod, descriptor, classDescriptor, configuration)
      .use { context ->
        enumerate(
          providers = registry.stream(TestTemplateInvocationContextProvider::class.java).toList()
            .filter { it.supportsTestTemplate(context) },
          provide = { it.provideTestTemplateInvocationContexts(context) },
          mayReturnZero = { it.mayReturnZeroTestTemplateInvocationContexts(context) },
          nameOf = { invocation, index -> invocation.getDisplayName(index) },
        )
      }
  }
  catch (_: Throwable) {
    null
  }

  /**
   * One 1-based index across all providers, exactly like `TemplateExecutor`, so that our `#N` segments match
   * the ones Jupiter will produce while executing.
   *
   * `close()` is where the "at least one invocation" validation lives, so it has to happen inside the `try` of
   * the caller rather than after it.
   */
  private fun <P : Any, C : Any> enumerate(
    providers: List<P>,
    provide: (P) -> Stream<out C>,
    mayReturnZero: (P) -> Boolean,
    nameOf: (C, Int) -> String,
  ): List<String>? {
    if (providers.isEmpty()) return null
    var index = 0
    val names = mutableListOf<String>()
    for (provider in providers) {
      val before = index
      provide(provider).use { stream ->
        stream.forEach { invocation -> names.add(nameOf(invocation, ++index)) }
      }
      if (index == before && !mayReturnZero(provider)) return null
    }
    return names.ifEmpty { null }
  }

  /**
   * The registry a node's providers come from, built by walking down from the engine root the way Jupiter
   * builds it descriptor by descriptor.
   *
   * Memoized per node id because a class's registry is shared by all of its methods, and building it
   * instantiates every extension the class declares.
   */
  private fun registryFor(descriptor: TestDescriptor): MutableExtensionRegistry? =
    registries.getOrPut(descriptor.uniqueId) {
      val parent = descriptor.parent.orElse(null)
      val base = if (parent == null) defaults else registryFor(parent)
      if (base == null) return@getOrPut null
      val element = classOf(descriptor) ?: methodOf(descriptor) ?: return@getOrPut base
      try {
        MutableExtensionRegistry.createRegistryFrom(base, extendWithTypes(element).stream()).also {
          if (element is Class<*>) registerStaticExtensionFields(it, element)
        }
      }
      catch (_: Throwable) {
        null
      }
    }

  private fun extendWithTypes(element: AnnotatedElement): List<Class<out Extension>> =
    AnnotationSupport.findRepeatableAnnotations(element, ExtendWith::class.java)
      .flatMap { it.value.asList() }
      .map { it.java }
      .distinct()

  /**
   * Registers the extensions held in **static** `@RegisterExtension` fields, best effort.
   *
   * An instance field cannot be read without a test instance, which is the one thing discovery does not have,
   * so a provider declared that way is simply not found and the node stays lazy. Only classes that actually
   * declare such a field are touched, so this reads no field — and triggers no static initializer — for a class
   * Jupiter would not have read it from anyway.
   */
  private fun registerStaticExtensionFields(registry: MutableExtensionRegistry, testClass: Class<*>) {
    val fields = try {
      AnnotationSupport.findAnnotatedFields(testClass, RegisterExtension::class.java) {
        Modifier.isStatic(it.modifiers)
      }
    }
    catch (_: Throwable) {
      return
    }
    for (field in fields) {
      try {
        field.trySetAccessible()
        val extension = field.get(null) as? Extension ?: continue
        registry.registerExtension(extension, field)
      }
      catch (_: Throwable) {
        // A field we cannot read is a provider we do not see; the node degrades to the lazy path.
      }
    }
  }

  /** The nearest ancestor backed by a class: the class template node, the `@Nested` class node or an invocation of one. */
  private fun enclosingClassDescriptor(descriptor: TestDescriptor): TestDescriptor? {
    var current = descriptor.parent.orElse(null)
    while (current != null && classOf(current) == null) {
      current = current.parent.orElse(null)
    }
    return current
  }

  private fun classOf(descriptor: TestDescriptor): Class<*>? =
    (descriptor.source.orElse(null) as? ClassSource)?.let { runCatching { it.javaClass }.getOrNull() }

  private fun methodOf(descriptor: TestDescriptor): Method? = templateMethod(descriptor)
}

/** The method a `[test-template:…]` node stands for, `null` when it cannot be resolved. */
internal fun templateMethod(descriptor: TestDescriptor): Method? =
  (descriptor.source.orElse(null) as? MethodSource)?.let { runCatching { it.javaMethod }.getOrNull() }
