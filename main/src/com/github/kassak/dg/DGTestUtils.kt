package com.github.kassak.dg

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.NullableLazyValue
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.LayeredIcon
import com.intellij.util.IconUtil
import com.intellij.util.ObjectUtils
import com.intellij.util.containers.JBIterable
import com.intellij.util.ui.EmptyIcon
import javax.swing.Icon

object DGTestUtils {
  fun getTestContents(project: Project): JBIterable<ContentEntry> {
    return getContent(project, "intellij.database.tests")
      .append(getContent(project, "intellij.database.connectivity.tests"))
      .append(getContent(project, "intellij.database.testFramework"))
  }

  fun getContent(project: Project, moduleName: String): JBIterable<ContentEntry> {
    return getModuleContent(findDGModule(project, moduleName))
  }

  fun getModuleContent(m: Module?): JBIterable<ContentEntry> {
    return if (m == null) JBIterable.empty() else JBIterable.of(*ModuleRootManager.getInstance(m).contentEntries)
  }

  fun findDGModule(project: Project, moduleName: String): Module? {
    return if (isDGProject(project))
      ModuleManager.getInstance(project).findModuleByName(moduleName)
    else null
  }

  fun getDGModules(project: Project): JBIterable<Module> {
    return if (isDGProject(project))
      JBIterable.of(*ModuleManager.getInstance(project).modules)
    else
      JBIterable.empty()
  }

  fun findDGSubModules(project: Project, moduleNamePrefix: String): JBIterable<Module> {
    return getDGModules(project).filter { it.name.startsWith(moduleNamePrefix) }
  }

  fun createDbmsCorneredIcon(dbmsStr: String, corner: Icon): NullableLazyValue<Icon> {
    return NullableLazyValue.lazyNullable {
      val ph = ApplicationManager.getApplication().getService(PresentationHelper::class.java)
        ?: return@lazyNullable null
      val dbmsIcon = ObjectUtils.notNull(ph.detectIcon(dbmsStr), EmptyIcon.ICON_16)
      val result = LayeredIcon(2)
      result.setIcon(dbmsIcon, 0)
      val h = dbmsIcon.iconHeight / 2
      val w = dbmsIcon.iconWidth / 2
      result.setIcon(IconUtil.toSize(corner, w, h), 1, w, h)
      result
    }
  }

  interface ConfigFile<T : ConfigItem> {
    fun getFileName(): String
    fun getItems(): JBIterable<T>
  }

  interface ConfigItem {
    fun getName(): String
    fun getIcon(): Icon?
    fun getSource(): XmlTag?
  }

  interface PresentationHelper {
    fun getIcon(dbms: String?): Icon?
    fun detectIcon(text: String?): Icon?
  }
}
