package com.github.kassak.dg

import com.github.kassak.dg.DGTestArtifacts.DGTestArtifact
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.StringPattern
import com.intellij.patterns.XmlPatterns.*
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ArrayUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.JBIterable

class DGConfigReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerReferenceProvider(driverRefValue(), object : PsiReferenceProvider() {
      override fun getReferencesByElement(psiElement: PsiElement, ctx: ProcessingContext): Array<PsiReference> =
        ContainerUtil.ar(DGDriverTagReference(psiElement as XmlTag))
    })
    registrar.registerReferenceProvider(driverBaseValue(), object : PsiReferenceProvider() {
      override fun getReferencesByElement(psiElement: PsiElement, ctx: ProcessingContext): Array<PsiReference> =
        ContainerUtil.ar(DGDriverAttributeReference(psiElement as XmlAttributeValue))
    })
    registrar.registerReferenceProvider(driverArtifactIdValue(), object : PsiReferenceProvider() {
      override fun getReferencesByElement(psiElement: PsiElement, ctx: ProcessingContext): Array<PsiReference> =
        ContainerUtil.ar(DGArtifactIdAttributeReference(psiElement as XmlAttributeValue))
    })
    registrar.registerReferenceProvider(driverArtifactVersionValue(), object : PsiReferenceProvider() {
      override fun getReferencesByElement(psiElement: PsiElement, ctx: ProcessingContext): Array<PsiReference> =
        ContainerUtil.ar(DGArtifactVersionAttributeReference(psiElement as XmlAttributeValue))
    })
  }

  private fun driverRefValue(): ElementPattern<XmlTag> {
    return xmlTag().withName("driver-ref")
      .inFile(PlatformPatterns.psiFile().withName(PlatformPatterns.string().with(
        object : PatternCondition<String>("DGTestDataSources.isTestDataSource") {
          override fun accepts(s: String, processingContext: ProcessingContext?): Boolean =
            DGTestDataSources.isTestDataSource(s)
        }
      )))
  }

  private fun driverBaseValue(): ElementPattern<XmlAttributeValue> {
    return xmlAttributeValue("based-on")
      .withSuperParent(2, xmlTag().withName("driver"))
      .inFile(PlatformPatterns.psiFile().withName(isTestDatabaseDrivers()))
  }

  private fun driverArtifactIdValue(): ElementPattern<XmlAttributeValue> {
    return xmlAttributeValue("id")
      .withSuperParent(2, xmlTag().withName("artifact"))
      .inFile(PlatformPatterns.psiFile().withName(isTestDatabaseDrivers()))
  }

  private fun driverArtifactVersionValue(): ElementPattern<XmlAttributeValue> {
    return xmlAttributeValue("version")
      .withSuperParent(2, xmlTag().withName("artifact"))
      .inFile(PlatformPatterns.psiFile().withName(isTestDatabaseDrivers()))
  }

  private fun isTestDatabaseDrivers(): StringPattern {
    return PlatformPatterns.string().with(
      object : PatternCondition<String>("DGTestDrivers.isTestDatabaseDrivers") {
        override fun accepts(s: String, processingContext: ProcessingContext?): Boolean =
          DGTestDrivers.isTestDatabaseDrivers(s)
      }
    )
  }

  private class DGDriverTagReference(element: XmlTag) : TagValueReference(element), DGDriverReferenceMixin {
    override fun handleElementRename(newElementName: String): PsiElement? = null
  }
  private class DGDriverAttributeReference(element: XmlAttributeValue) : AttrValueReference(element), DGDriverReferenceMixin {
    override fun handleElementRename(newElementName: String): PsiElement? = null
  }

  private class DGArtifactVersionAttributeReference(element: XmlAttributeValue) :
    AttrValueReference(element), PsiPolyVariantReference {

    override fun resolve(): PsiElement? {
      val res = multiResolve(false)
      return if (res.isEmpty()) null else res[0].element
    }

    override fun multiResolve(b: Boolean): Array<ResolveResult> {
      val ver = canonicalText
      return getArtifacts()
        .filter { a -> ver == a.version }
        .filterMap { a ->
          val source = a.getSource() ?: return@filterMap null
          PsiElementResolveResult(source) as ResolveResult
        }
        .toArray(ResolveResult.EMPTY_ARRAY)
    }

    override fun handleElementRename(s: String): PsiElement? = null

    override fun getVariants(): Array<Any> {
      return getArtifacts()
        .map { a -> LookupElementBuilder.create(a.version ?: "").withIcon(a.getIcon()) as Any }
        .toArray(ArrayUtil.EMPTY_OBJECT_ARRAY)
    }

    private fun getArtifacts(): JBIterable<DGTestArtifact> {
      val artifacts = DGTestArtifacts.list(element.project).flatten { it.getItems() }
      val id = getId()
      return if (id == null) artifacts else artifacts.filter { a -> id == a.id }
    }

    private fun getId(): String? {
      val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)
      return tag?.getAttributeValue("id")
    }
  }

  private class DGArtifactIdAttributeReference(element: XmlAttributeValue) :
    AttrValueReference(element), PsiPolyVariantReference {

    override fun resolve(): PsiElement? {
      val res = multiResolve(false)
      return if (res.isEmpty()) null else res[0].element
    }

    override fun multiResolve(b: Boolean): Array<ResolveResult> {
      val id = canonicalText
      return DGTestArtifacts.list(element.project)
        .flatten { it.getItems() }
        .filter { a -> id == a.id }
        .filterMap { a ->
          val source = a.getSource() ?: return@filterMap null
          PsiElementResolveResult(source) as ResolveResult
        }
        .toArray(ResolveResult.EMPTY_ARRAY)
    }

    override fun handleElementRename(s: String): PsiElement? = null

    override fun getVariants(): Array<Any> {
      return DGTestArtifacts.list(element.project)
        .flatten { it.getItems() }
        .map { a -> LookupElementBuilder.create(a.id).withIcon(a.getIcon()) as Any }
        .toArray(ArrayUtil.EMPTY_OBJECT_ARRAY)
    }
  }

  private interface DGDriverReferenceMixin : PsiReference {
    override fun resolve(): PsiElement? {
      val text = canonicalText
      val driver = DGTestDrivers.list(element.project)
        .flatten { it.getItems() }
        .find { d -> text == d.getName() }
      return driver?.getSource()
    }

    override fun handleElementRename(s: String): PsiElement? = null

    override fun getVariants(): Array<Any> {
      return DGTestDrivers.list(element.project)
        .flatten { it.getItems() }
        .map { d -> LookupElementBuilder.create(d.getName()).withIcon(d.getIcon()) as Any }
        .toArray(ArrayUtil.EMPTY_OBJECT_ARRAY)
    }
  }

  private abstract class AttrValueReference(private val myElement: XmlAttributeValue) : PsiReference {
    override fun getElement(): XmlAttributeValue = myElement

    override fun getRangeInElement(): TextRange =
      myElement.valueTextRange.shiftLeft(myElement.textRange.startOffset)

    override fun getCanonicalText(): String = StringUtil.notNullize(myElement.value)

    override fun handleElementRename(newElementName: String): PsiElement? = null

    override fun bindToElement(psiElement: PsiElement): PsiElement? = null

    override fun isReferenceTo(psiElement: PsiElement): Boolean {
      val resolve = resolve()
      return resolve != null && psiElement.isEquivalentTo(resolve)
    }

    override fun isSoft(): Boolean = false
  }

  private abstract class TagValueReference(protected val myElement: XmlTag) : PsiReference {
    override fun getElement(): PsiElement = myElement

    override fun getRangeInElement(): TextRange =
      myElement.value.textRange.shiftLeft(myElement.textRange.startOffset)

    override fun getCanonicalText(): String = myElement.value.text

    val project: Project get() = myElement.project

    override fun handleElementRename(newElementName: String): PsiElement? = null

    override fun bindToElement(psiElement: PsiElement): PsiElement? = null

    override fun isReferenceTo(psiElement: PsiElement): Boolean {
      val resolve = resolve()
      return resolve != null && psiElement.isEquivalentTo(resolve)
    }

    override fun isSoft(): Boolean = false
  }
}
