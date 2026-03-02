package com.github.kassak.dg

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentFolder
import com.intellij.openapi.util.NullableLazyValue
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

class DGTestDrivers(
  @JvmField val fileName: String,
  @JvmField val drivers: List<DGTestDriver>
) : DGTestUtils.ConfigFile<DGTestDrivers.DGTestDriver> {

  companion object {
    fun isTestDatabaseDrivers(name: String): Boolean = name.endsWith("-drivers.xml")

    fun list(project: Project): JBIterable<DGTestDrivers> {
      val td: JBIterable<VirtualFile> = DGTestUtils.getTestContents(project)
        .flatten { e -> e.getSourceFolders(JavaResourceRootType.TEST_RESOURCE) }
        .filterMap(ContentFolder::getFile)
        .flatten { f -> JBIterable.of(*f.children).filter { o -> o.name.endsWith("test-database-drivers.xml") } }
      val real: JBIterable<VirtualFile> = DGTestUtils.findDGSubModules(project, "intellij.database")
        .flatten(DGTestUtils::getModuleContent)
        .flatten { e -> e.getSourceFolders(JavaResourceRootType.RESOURCE) }
        .filterMap(ContentFolder::getFile)
        .flatten { f -> JBIterable.of(*f.children).filter { o -> o.name == "databaseDrivers" } }
        .flatten { f -> JBIterable.of(*f.children).filter { o -> isTestDatabaseDrivers(o.name) } }
      val psiManager = PsiManager.getInstance(project)
      return td.append(real).filterMap { psiManager.findFile(it) }
        .map { f -> CachedValuesManager.getCachedValue(f) { CachedValueProvider.Result.create(parse(f), f) } }
    }

    private fun parse(f: PsiFile): DGTestDrivers = DGTestDrivers(f.name, extract(f))

    private fun extract(f: PsiFile): List<DGTestDriver> {
      val xml = ObjectUtils.tryCast(f, XmlFile::class.java)
      val rootTag = xml?.rootTag ?: return emptyList()
      val res = mutableListOf<DGTestDriver>()
      for (driver in rootTag.findSubTags("driver")) {
        ContainerUtil.addIfNotNull(res, parse(driver))
      }
      return res
    }

    private fun parse(dr: XmlTag): DGTestDriver? {
      val id = dr.getAttributeValue("id") ?: return null
      val parentId = dr.getAttributeValue("based-on")
      val artifact = dr.findFirstSubTag("artifact")
      val artifactName = artifact?.getAttributeValue("name")
      val artifactVersion = artifact?.getAttributeValue("version")
      return DGTestDriver(id, parentId, artifactName, artifactVersion, dr)
    }
  }

  override fun getFileName(): String = fileName
  override fun getItems(): JBIterable<DGTestDriver> = JBIterable.from(drivers)

  class DGTestDriver(
    val id: String,
    val parentId: String?,
    val artifactName: String?,
    val artifactVersion: String?,
    source: XmlTag
  ) : DGTestUtils.ConfigItem {
    val source: SmartPsiElementPointer<XmlTag> = SmartPointerManager.createPointer(source)
    private val myIcon: NullableLazyValue<Icon> = DGTestUtils.createDbmsCorneredIcon(id, AllIcons.General.GearPlain)

    override fun getName(): String = id
    override fun getIcon(): Icon? = myIcon.value
    override fun getSource(): XmlTag? = source.element
  }
}
