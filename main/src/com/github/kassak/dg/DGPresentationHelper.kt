package com.github.kassak.dg

import com.intellij.database.Dbms
import javax.swing.Icon

class DGPresentationHelper : DGTestUtils.PresentationHelper {
  override fun getIcon(dbmsName: String?): Icon? {
    val dbms = Dbms.byName(dbmsName) ?: return null
    return dbms.icon
  }

  override fun detectIcon(text: String?): Icon? {
    val dbms = Dbms.fromString(text)
    return if (dbms == Dbms.UNKNOWN) null else dbms.icon
  }
}
