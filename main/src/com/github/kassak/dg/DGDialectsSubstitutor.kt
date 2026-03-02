package com.github.kassak.dg

import com.intellij.database.Dbms
import com.intellij.database.util.DbSqlUtil
import com.intellij.database.util.SqlDialects
import com.intellij.lang.Language
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LanguageSubstitutor

class DGDialectsSubstitutor : LanguageSubstitutor() {
  override fun getLanguage(file: VirtualFile, project: Project): Language? {
    if (!isDGTestData(project, file)) return null
    val dbms = getDbms(file, project) ?: return null
    val dialect = DbSqlUtil.getSqlDialect(dbms)
    return if (SqlDialects.getGenericDialect() == dialect) null else dialect
  }

  private fun getDbms(file: VirtualFile, project: Project): Dbms? {
    detectByFileName(file)?.let { return it }
    val top = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
    var folder = file.parent
    while (folder != null && folder != top) {
      detectByFolderName(folder)?.let { return it }
      folder = folder.parent
    }
    return null
  }

  private fun detectByFolderName(folder: VirtualFile): Dbms? = MAPPING[folder.name]

  private fun detectByFileName(file: VirtualFile): Dbms? =
    MAPPING.entries.firstOrNull { (key, _) -> file.name.startsWith(key) }?.value

  private fun isDGTestData(project: Project, file: VirtualFile): Boolean {
    val module: Module? = ProjectFileIndex.getInstance(project).getModuleForFile(file)
    return module != null && module.name.startsWith("intellij.database") && module.name.contains("test")
  }
}

private val MAPPING: Map<String, Dbms> = buildMap {
  for (dbms in Dbms.allValues()) {
    put(StringUtil.toLowerCase(dbms.name), dbms)
  }
  put("hsql", Dbms.HSQL)
  put("tsql", Dbms.MSSQL)
  put("pg", Dbms.POSTGRES)
  put("postgresql", Dbms.POSTGRES)
  put("chouse", Dbms.CLICKHOUSE)
}