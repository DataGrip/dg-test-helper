package com.github.kassak.dg

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.containers.JBIterable
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import javax.swing.Icon

class DGTestConfigEntityContributor : GotoClassContributor, ChooseByNameContributorEx {

  override fun processNames(
    processor: Processor<in String>,
    scope: GlobalSearchScope,
    idFilter: IdFilter?
  ) {
    val project = scope.project ?: return
    getConfigs(project)
      .flatten { it.getItems() }
      .map { it.getName() }
      .unique()
      .processEach(processor)
  }

  private fun getConfigs(project: Project): JBIterable<DGTestUtils.ConfigFile<*>> {
    val result = mutableListOf<DGTestUtils.ConfigFile<*>>()
    DGTestDataSources.list(project).forEach { result.add(it) }
    DGTestDrivers.list(project).forEach { result.add(it) }
    DGTestArtifacts.list(project).forEach { result.add(it) }
    return JBIterable.from(result)
  }

  override fun processElementsWithName(
    s: String,
    processor: Processor<in NavigationItem>,
    findSymbolParameters: FindSymbolParameters
  ) {
    val project = findSymbolParameters.project
    for (file in getConfigs(project)) {
      for (item in file.getItems()) {
        if (s == item.getName()) {
          processor.process(asNavigationItem(item, file.getFileName()))
        }
      }
    }
  }

  private fun asNavigationItem(item: DGTestUtils.ConfigItem, fileName: String): NavigationItem {
    return object : NavigationItem {
      override fun getName(): String = item.getName()

      override fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
          override fun getPresentableText(): String = item.getName()
          override fun getLocationString(): String = fileName
          override fun getIcon(unused: Boolean): Icon? = item.getIcon()
        }
      }

      override fun navigate(requestFocus: Boolean) {
        navigate(item.getSource(), requestFocus)
      }

      override fun canNavigate(): Boolean = true
      override fun canNavigateToSource(): Boolean = true
    }
  }

  override fun getQualifiedName(navigationItem: NavigationItem): String? = null
  override fun getQualifiedNameSeparator(): String? = null
}
