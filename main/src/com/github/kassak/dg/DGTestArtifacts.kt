package com.github.kassak.dg

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentFolder
import com.intellij.openapi.util.NullableLazyValue
import com.intellij.openapi.util.text.StringUtil
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

class DGTestArtifacts(
  @JvmField val fileName: String,
  @JvmField val drivers: List<DGTestArtifact>
) : DGTestUtils.ConfigFile<DGTestArtifacts.DGTestArtifact> {

  companion object {
    fun list(project: Project): JBIterable<DGTestArtifacts> {
      val td: JBIterable<VirtualFile> = DGTestUtils.getTestContents(project)
        .flatten { e -> e.getSourceFolders(JavaResourceRootType.TEST_RESOURCE) }
        .filterMap(ContentFolder::getFile)
        .flatten { f -> JBIterable.of(*f.children).filter { o -> o.name.endsWith("test-database-artifacts.xml") } }
      val real: JBIterable<VirtualFile> = DGTestUtils.getContent(project, "intellij.database.connectivity")
        .flatten { e -> e.getSourceFolders(JavaResourceRootType.RESOURCE) }
        .filterMap(ContentFolder::getFile)
        .flatten { f -> JBIterable.of(*f.children).filter { o -> o.name == "resources" } }
        .flatten { f -> JBIterable.of(*f.children).filter { o -> o.name == "database-artifacts.xml" } }
      val psiManager = PsiManager.getInstance(project)
      return td.append(real).filterMap { psiManager.findFile(it) }
        .map { f -> CachedValuesManager.getCachedValue(f) { CachedValueProvider.Result.create(parse(f), f) } }
    }

    private fun parse(f: PsiFile): DGTestArtifacts = DGTestArtifacts(f.name, extract(f))

    private fun extract(f: PsiFile): List<DGTestArtifact> {
      val xml = ObjectUtils.tryCast(f, XmlFile::class.java)
      val rootTag = xml?.rootTag ?: return emptyList()
      val res = mutableListOf<DGTestArtifact>()
      for (artifact in rootTag.findSubTags("artifact")) {
        val parsed = parse(artifact, null)
        if (parsed != null && parsed.version != null) ContainerUtil.addIfNotNull(res, parsed)
        for (version in artifact.findSubTags("version")) {
          ContainerUtil.addIfNotNull(res, parse(version, parsed))
        }
      }
      return res
    }

    private fun parse(art: XmlTag, parent: DGTestArtifact?): DGTestArtifact? {
      var id = art.getAttributeValue("id")
      if (id == null) {
        val name = art.getAttributeValue("name")
        if (name != null) {
          id = StringUtil.trimEnd(name.replace("[^a-zA-Z0-9. _-]".toRegex(), ""), " 8")
        }
      }
      if (id == null && parent != null) id = parent.id
      val version = art.getAttributeValue("version")
      return if (id == null) null else DGTestArtifact(id, version, art)
    }
  }

  override fun getFileName(): String = fileName
  override fun getItems(): JBIterable<DGTestArtifact> = JBIterable.from(drivers)

  class DGTestArtifact(
    val id: String,
    val version: String?,
    source: XmlTag
  ) : DGTestUtils.ConfigItem {
    val source: SmartPsiElementPointer<XmlTag> = SmartPointerManager.createPointer(source)
    private val myIcon: NullableLazyValue<Icon> = DGTestUtils.createDbmsCorneredIcon(id, AllIcons.Nodes.Artifact)

    override fun getName(): String = id + (if (version == null) "" else " $version")
    override fun getIcon(): Icon? = myIcon.value
    override fun getSource(): XmlTag? = source.element
  }
}
