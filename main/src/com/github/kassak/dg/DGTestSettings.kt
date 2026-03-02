package com.github.kassak.dg

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

@State(name = "DGTestSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DGTestSettings : PersistentStateComponent<DGTestSettings.State> {
  companion object {
    fun getInstance(project: Project): DGTestSettings = project.getService(DGTestSettings::class.java)
  }

  private var myState = State()

  override fun getState(): State = myState

  override fun loadState(state: State) {
    myState = state
  }

  fun getFilters(): MutableSet<String> = myState.filters
  fun getCurrent(): String? = myState.current
  fun setCurrent(current: String?) { myState.current = current }
  fun isAsk(): Boolean = myState.ask
  fun setAsk(ask: Boolean) { myState.ask = ask }
  fun isOverwrite(): Boolean = myState.overwrite
  fun setOverwrite(overwrite: Boolean) { myState.overwrite = overwrite }
  fun isInProcessRmi(): Boolean = myState.inProcessRmi
  fun setInProcessRmi(inProcessRmi: Boolean) { myState.inProcessRmi = inProcessRmi }
  fun isAttachRemote(): Boolean = myState.attachRemote
  fun setAttachRemote(attachRemote: Boolean) { myState.attachRemote = attachRemote }

  class State {
    @JvmField var filters: MutableSet<String> = sortedSetOf()
    @JvmField var current: String? = null
    @JvmField var ask: Boolean = false
    @JvmField var overwrite: Boolean = false
    @JvmField var inProcessRmi: Boolean = false
    @JvmField var attachRemote: Boolean = false
  }
}
