package com.github.kassak.dg

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentFolder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ObjectUtils
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.JBIterable
import org.jetbrains.jps.model.java.JavaResourceRootType
import javax.swing.Icon

class DGTestDataSources(
  @JvmField val fileName: String,
  @JvmField val dataSources: List<DGTestDataSource>
) : DGTestUtils.ConfigFile<DGTestDataSources.DGTestDataSource> {

  companion object {
    fun isTestDataSource(name: String): Boolean = name.endsWith("test-data-sources.xml")

    fun list(project: Project): JBIterable<DGTestDataSources> {
      val td: JBIterable<VirtualFile> = DGTestUtils.getTestContents(project)
        .flatten { e -> e.getSourceFolders(JavaResourceRootType.TEST_RESOURCE) }
        .filterMap(ContentFolder::getFile)
        .flatten { f -> JBIterable.of(*f.children).filter { o -> isTestDataSource(o.name) } }
      return JBIterable.from(ReadAction.compute<List<DGTestDataSources>, Throwable> {
        val psiManager = PsiManager.getInstance(project)
        td.filterMap { psiManager.findFile(it) }
          .map { f -> CachedValuesManager.getCachedValue(f) { CachedValueProvider.Result.create(parse(f), f) } }
          .toList()
      })
    }

    private fun parse(f: PsiFile): DGTestDataSources = DGTestDataSources(f.name, extract(f))

    private fun extract(f: PsiFile): List<DGTestDataSource> {
      val xml = ObjectUtils.tryCast(f, XmlFile::class.java)
      val rootTag = xml?.rootTag ?: return emptyList()
      val res = mutableListOf<DGTestDataSource>()
      for (dataSource in rootTag.findSubTags("data-source")) {
        ContainerUtil.addIfNotNull(res, parse(dataSource))
      }
      return res
    }

    private fun parse(ds: XmlTag): DGTestDataSource? {
      val uuid = ds.getAttributeValue("uuid") ?: return null
      val info = ds.findFirstSubTag("database-info")
      val dbms = info?.getAttributeValue("dbms")
      val version = info?.getAttributeValue("exact-version")
      return DGTestDataSource(uuid, dbms, version, ds)
    }
  }

  override fun getFileName(): String = fileName
  override fun getItems(): JBIterable<DGTestDataSource> = JBIterable.from(dataSources)

  class DGTestDataSource(
    val uuid: String,
    val dbms: String?,
    val version: String?,
    source: XmlTag
  ) : DGTestUtils.ConfigItem {
    val source: SmartPsiElementPointer<XmlTag> = SmartPointerManager.createPointer(source)

    override fun getIcon(): Icon? {
      val ph = ApplicationManager.getApplication().getService(DGTestUtils.PresentationHelper::class.java)
      return ph?.getIcon(dbms)
    }

    override fun getName(): String = uuid
    override fun getSource(): XmlTag? = source.element
  }
}
