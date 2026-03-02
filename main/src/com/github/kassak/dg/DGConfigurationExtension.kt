package com.github.kassak.dg

import com.intellij.execution.*
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.execution.remote.RemoteConfigurationType
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ObjectUtils
import java.awt.Toolkit
import javax.swing.JFrame
import javax.swing.JList

class DGConfigurationExtension : RunConfigurationExtension() {
  companion object {
    private const val DB_FILTER = "db.filter"
    private const val OVERWRITE_DATA = "idea.tests.overwrite.data"
    private const val IN_PROCESS_RMI = "idea.rmi.server.in.process"
    private const val REMOTE_DEBUG = "db.remote.process.debug"
    private const val DEBUG_INVITATION = "Remote JDBC process is ready for debug: "
  }

  override fun <T : RunConfigurationBase<*>> updateJavaParameters(
    configuration: T,
    parameters: JavaParameters,
    settings: RunnerSettings?
  ) {
    if (!isApplicableFor(configuration)) return
    val project = configuration.project
    val params = parameters.vmParametersList
    if (!params.hasProperty(DB_FILTER)) {
      params.defineProperty(DB_FILTER, getFilter(project))
    }
    if (!params.hasProperty(OVERWRITE_DATA) && DGTestSettings.getInstance(project).isOverwrite()) {
      params.defineProperty(OVERWRITE_DATA, "true")
    }
    if (!params.hasProperty(IN_PROCESS_RMI) && DGTestSettings.getInstance(project).isInProcessRmi()) {
      params.defineProperty(IN_PROCESS_RMI, "true")
    }
    if (!params.hasProperty(REMOTE_DEBUG) && DGTestSettings.getInstance(project).isAttachRemote()) {
      params.defineProperty(REMOTE_DEBUG, "true")
    }
  }

  private fun getFilter(project: Project): String? {
    val settings = DGTestSettings.getInstance(project)
    if (!settings.isAsk()) {
      return settings.getCurrent()
    }
    val noFilter = "<No filter>"
    val manage = "<Manage...>"
    val variants = mutableListOf<String>()
    variants.add(noFilter)
    variants.addAll(settings.getFilters())
    variants.add(manage)
    val res = Ref.create<String>()
    val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
    JBPopupFactory.getInstance().createPopupChooserBuilder(variants)
      .setSelectedValue(settings.getCurrent(), true)
      .setItemChosenCallback { res.set(it) }
      .addListener(object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) {
          loop.exit()
        }
      })
      .setRenderer(object : ColoredListCellRenderer<String>() {
        override fun customizeCellRenderer(list: JList<out String>, o: String?, i: Int, b: Boolean, b1: Boolean) {
          append(
            StringUtil.notNullize(o),
            if (noFilter == o || manage == o) SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES
            else SimpleTextAttributes.REGULAR_ATTRIBUTES
          )
        }
      })
      .createPopup()
      .showCenteredInCurrentWindow(project)
    loop.enter()
    if (res.isNull) throw ProcessCanceledException()
    if (manage == res.get()) {
      ApplicationManager.getApplication().invokeLater {
        val action: AnAction = ActionManager.getInstance().getAction("DGManageFilters")
          ?: throw AssertionError("No manage action")
        val frame = WindowManager.getInstance().getFrame(project)
        ActionUtil.invokeAction(action, DataManager.getInstance().getDataContext(frame), ActionPlaces.KEYBOARD_SHORTCUT, null, null)
      }
      throw ProcessCanceledException()
    }
    return if (noFilter == res.get()) null else res.get()
  }

  override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
    val javaConfig = ObjectUtils.tryCast(configuration, JavaTestConfigurationBase::class.java) ?: return false
    return javaConfig.modules.any { it.name.startsWith("intellij.database") }
  }

  override fun attachToProcess(
    configuration: RunConfigurationBase<*>,
    handler: ProcessHandler,
    runnerSettings: RunnerSettings?
  ) {
    super.attachToProcess(configuration, handler, runnerSettings)
    val project = configuration.project
    if (!DGTestSettings.getInstance(project).isAttachRemote()) return
    handler.addProcessListener(object : ProcessAdapter() {
      override fun onTextAvailable(processEvent: ProcessEvent, key: Key<*>) {
        if (key != ProcessOutputType.STDOUT) return
        val text = processEvent.text ?: return
        if (!text.startsWith(DEBUG_INVITATION)) return
        val params = text.substring(DEBUG_INVITATION.length)
        attach(project, params)
      }
    })
  }

  private fun attach(project: Project, params: String) {
    val port = extractPort(params) ?: return
    val factory = RemoteConfigurationType.getInstance().configurationFactories[0]
    val settings = RunManager.getInstance(project).createConfiguration("remote jdbc debug at $port", factory)
    val remote = settings.configuration as RemoteConfiguration
    remote.PORT = port
    val frame: JFrame? = WindowManager.getInstance().getFrame(project)
    val context: DataContext = DataManager.getInstance().getDataContext(frame)
    ExecutorRegistryImpl.RunnerHelper.run(project, remote, settings, context, DefaultDebugExecutor.getDebugExecutorInstance())
  }

  private fun extractPort(params: String): String? {
    val idx = params.lastIndexOf(':')
    return if (idx == -1) null else params.substring(idx + 1).trim()
  }
}
