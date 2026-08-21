package com.github.kassak.dg.eagerparams

import org.junit.jupiter.api.MediaType
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExecutableInvoker
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstances
import org.junit.jupiter.api.function.ThrowingConsumer
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.platform.commons.support.AnnotationSupport
import org.junit.platform.commons.support.ReflectionSupport
import org.junit.platform.engine.ConfigurationParameters
import org.junit.platform.engine.TestDescriptor
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.Optional
import java.util.function.Function

/**
 * The `ExtensionContext` an invocation context provider is handed while we enumerate invocations at
 * **discovery**, where Jupiter's real one does not exist yet.
 *
 * Nothing here is a reimplementation of Jupiter behaviour: it is the smallest context that lets the standard
 * providers answer "how many invocations, and what is each one called". [displayName], [uniqueId] and [tags]
 * are taken from the node Jupiter itself has just discovered, so the `{displayName}` placeholder and the
 * configuration-driven parts of a name pattern produce exactly the string the lazy path would produce.
 *
 * There are two shapes of it, matching the two template SPIs, and they nest the way Jupiter's do:
 * [forClass] is what a `ClassTemplateInvocationContextProvider` sees, and [forMethod] is what a
 * `TestTemplateInvocationContextProvider` sees — the latter has the class-level context as its parent and,
 * crucially, a present `getTestMethod()`, which is what `RepeatedTestExtension.supportsTestTemplate` and
 * `ParameterizedTestExtension.supportsTestTemplate` key on.
 *
 * Everything a provider has no business asking for at discovery time — the test instance, the execution
 * exception, reporting — is absent or unsupported. That is deliberate and is the drop-in guarantee: a provider
 * that needs more than this makes [EagerTemplateEnumerator] give up and the node stays lazy, exactly as it
 * would be without this engine. A future Jupiter that starts calling something we do not have degrades the
 * same way instead of breaking.
 */
internal class DiscoveryExtensionContext private constructor(
  private val parentContext: DiscoveryExtensionContext?,
  private val testClass: Class<*>,
  private val testMethod: Method?,
  descriptor: TestDescriptor,
  private val configuration: ConfigurationParameters,
) : ExtensionContext, AutoCloseable {
  private val id = descriptor.uniqueId.toString()
  private val name = descriptor.displayName
  private val tags = descriptor.tags.mapTo(LinkedHashSet()) { it.name }

  /**
   * Owned by the root context only, so that a method-level lookup sees what the class level put there.
   *
   * Jupiter's store is a hierarchy with inheritance from parent contexts; sharing one map is the same thing
   * observed from a two-level chain, and the namespaces providers use already include the class or the method.
   */
  private val stores = if (parentContext == null) mutableListOf<DiscoveryStore>() else null

  override fun getParent(): Optional<ExtensionContext> = Optional.ofNullable(parentContext)

  override fun getRoot(): ExtensionContext = rootContext()

  override fun getUniqueId(): String = id

  override fun getDisplayName(): String = name

  override fun getTags(): Set<String> = tags

  override fun getElement(): Optional<AnnotatedElement> = Optional.of(testMethod ?: testClass)

  override fun getTestClass(): Optional<Class<*>> = Optional.of(testClass)

  override fun getEnclosingTestClasses(): List<Class<*>> = enclosingClasses(testClass)

  /**
   * Must be present: `ParameterizedClassExtension` treats an empty lifecycle as a precondition violation.
   *
   * Computed the way `TestInstanceLifecycleUtils` does — the annotation anywhere up the class hierarchy,
   * otherwise the configured default, otherwise per-method.
   */
  override fun getTestInstanceLifecycle(): Optional<TestInstance.Lifecycle> = Optional.of(
    AnnotationSupport.findAnnotation(testClass, TestInstance::class.java)
      .map { it.value }
      .orElseGet { configuredLifecycle() }
  )

  override fun getTestInstance(): Optional<Any> = Optional.empty()

  override fun getTestInstances(): Optional<TestInstances> = Optional.empty()

  override fun getTestMethod(): Optional<Method> = Optional.ofNullable(testMethod)

  override fun getExecutionException(): Optional<Throwable> = Optional.empty()

  override fun getConfigurationParameter(key: String): Optional<String> = configuration.get(key)

  override fun <T : Any?> getConfigurationParameter(key: String, transformer: Function<String, T>): Optional<T> =
    configuration.get(key, transformer)

  override fun getExecutionMode(): ExecutionMode = ExecutionMode.SAME_THREAD

  override fun getExecutableInvoker(): ExecutableInvoker = DiscoveryExecutableInvoker

  override fun getStore(namespace: ExtensionContext.Namespace): ExtensionContext.Store {
    val root = rootContext()
    val stores = root.stores!!
    return stores.find { it.namespace == namespace } ?: DiscoveryStore(namespace).also { stores.add(it) }
  }

  // The scope only decides how long a value outlives its owner; ours all die with this context.
  override fun getStore(scope: ExtensionContext.StoreScope, namespace: ExtensionContext.Namespace): ExtensionContext.Store =
    getStore(namespace)

  override fun publishReportEntry(map: Map<String, String>): Unit = unsupported("publishReportEntry")

  override fun publishFile(name: String, mediaType: MediaType, action: ThrowingConsumer<Path>): Unit =
    unsupported("publishFile")

  override fun publishDirectory(name: String, action: ThrowingConsumer<Path>): Unit = unsupported("publishDirectory")

  /** Closes whatever the providers left behind, newest first, the way Jupiter's own store does. */
  override fun close() {
    val stores = rootContext().stores ?: return
    for (store in stores.asReversed()) {
      store.close()
    }
    stores.clear()
  }

  private fun rootContext(): DiscoveryExtensionContext = parentContext?.rootContext() ?: this

  private fun configuredLifecycle(): TestInstance.Lifecycle =
    configuration.get(TestInstance.Lifecycle.DEFAULT_LIFECYCLE_PROPERTY_NAME)
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .flatMap { name -> TestInstance.Lifecycle.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }.let { Optional.ofNullable(it) } }
      .orElse(TestInstance.Lifecycle.PER_METHOD)

  private fun enclosingClasses(clazz: Class<*>): List<Class<*>> {
    val enclosing = mutableListOf<Class<*>>()
    var current = clazz.enclosingClass
    while (current != null) {
      enclosing.add(current)
      current = current.enclosingClass
    }
    return enclosing
  }

  private fun unsupported(what: String): Nothing =
    throw UnsupportedOperationException("$what is not available while discovering ${testClass.name}")

  companion object {
    /** What a `ClassTemplateInvocationContextProvider` is asked with; [descriptor] is the class template node. */
    fun forClass(
      testClass: Class<*>,
      descriptor: TestDescriptor,
      configuration: ConfigurationParameters,
    ): DiscoveryExtensionContext = DiscoveryExtensionContext(null, testClass, null, descriptor, configuration)

    /**
     * What a `TestTemplateInvocationContextProvider` is asked with; [descriptor] is the `[test-template:…]`
     * node and [classDescriptor] its enclosing class node, which only supplies the parent context's name.
     */
    fun forMethod(
      testClass: Class<*>,
      testMethod: Method,
      descriptor: TestDescriptor,
      classDescriptor: TestDescriptor,
      configuration: ConfigurationParameters,
    ): DiscoveryExtensionContext {
      val parent = forClass(testClass, classDescriptor, configuration)
      return DiscoveryExtensionContext(parent, testClass, testMethod, descriptor, configuration)
    }
  }
}

/**
 * A flat, single-context store.
 *
 * Jupiter's store is a hierarchy with inheritance from parent contexts; ours is one map shared by the whole
 * two-level chain, which observes the same way. It exists because `ParameterizedClassExtension` and
 * `ParameterizedTestExtension` memoize their declaration context in it between `supports…` and `provide…`.
 */
private class DiscoveryStore(val namespace: ExtensionContext.Namespace) : ExtensionContext.Store, AutoCloseable {
  private val values = LinkedHashMap<Any, Any>()

  override fun get(key: Any): Any? = values[key]

  override fun <V : Any?> get(key: Any, requiredType: Class<V>): V? = cast(values[key], requiredType)

  override fun <K : Any?, V : Any?> getOrComputeIfAbsent(key: K, defaultCreator: Function<K, V>): Any? {
    values[key as Any]?.let { return it }
    val created = defaultCreator.apply(key) ?: return null
    values[key] = created
    return created
  }

  override fun <K : Any?, V : Any?> getOrComputeIfAbsent(
    key: K,
    defaultCreator: Function<K, V>,
    requiredType: Class<V>,
  ): V? = cast(getOrComputeIfAbsent(key, defaultCreator), requiredType)

  override fun put(key: Any, value: Any?) {
    if (value == null) values.remove(key) else values[key] = value
  }

  override fun remove(key: Any): Any? = values.remove(key)

  override fun <V : Any?> remove(key: Any, requiredType: Class<V>): V? = cast(values.remove(key), requiredType)

  @Suppress("DEPRECATION", "removal")
  override fun close() {
    for (value in values.values.toList().asReversed()) {
      try {
        when (value) {
          is ExtensionContext.Store.CloseableResource -> value.close()
          is AutoCloseable -> value.close()
          else -> {}
        }
      }
      catch (_: Throwable) {
        // Discovery must not fail because a throwaway value complained about being closed.
      }
    }
    values.clear()
  }

  private fun <V : Any?> cast(value: Any?, requiredType: Class<V>): V? =
    if (value == null) null else requiredType.cast(value)
}

/**
 * Invokes exactly what an arguments source needs: a static factory method and a no-argument provider
 * constructor.
 *
 * Jupiter's real invoker resolves parameters through the extension registry, which does not exist at
 * discovery. Anything beyond these two shapes is refused rather than guessed at, and the node falls back to
 * the lazy path.
 */
private object DiscoveryExecutableInvoker : ExecutableInvoker {
  override fun invoke(method: Method, target: Any?): Any? = ReflectionSupport.invokeMethod(method, target)

  override fun <T : Any?> invoke(constructor: Constructor<T>, outerInstance: Any?): T {
    if (constructor.parameterCount != 0 || outerInstance != null) {
      throw UnsupportedOperationException(
        "Only no-argument constructors can be invoked while discovering: ${constructor.declaringClass.name}"
      )
    }
    return ReflectionSupport.newInstance(constructor.declaringClass)
  }
}
