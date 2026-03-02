package com.github.kassak.dg

import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentWithBrowseButton
import com.intellij.openapi.ui.popup.*
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.pom.Navigatable
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.util.ArrayUtil
import com.intellij.util.ObjectUtils
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.intellij.lang.regexp.RegExpFileType
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingConstants

class DGFilterComboBoxAction : ComboBoxAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
    val project = dataContext.getData(PlatformDataKeys.PROJECT) ?: return DefaultActionGroup()
    val settings = DGTestSettings.getInstance(project)
    val actions = mutableListOf<AnAction>()
    actions.add(object : AnAction("New Filter...") {
      override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        editFilter(project, ".*").thenAccept { res ->
          val settings = DGTestSettings.getInstance(project)
          settings.getFilters().add(res)
          settings.setCurrent(res)
        }
      }
    })
    actions.add(Separator.getInstance())
    actions.add(MyAskAction())
    actions.add(MyOverwriteAction())
    actions.add(MyInProcessRmiAction())
    actions.add(MyAttachRemoteAction())
    actions.add(Separator.getInstance())
    for (filter in settings.getFilters()) {
      if (filter.isNotEmpty()) actions.add(MyFilterAction(filter))
    }
    return object : DefaultActionGroup(actions) {
      override fun isDumbAware(): Boolean = true
    }
  }

  private fun setUpPopup(popup: JBPopup) {
    if (popup is ListPopupImpl) registerActions(popup)
    popup.setAdText("Del - delete, F4 - edit, F3 - navigate", SwingConstants.LEFT)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val frame = WindowManager.getInstance().getFrame(project)
    if (frame !is IdeFrame) return
    val popup = createActionPopup(e.dataContext, frame.component, null)
    popup.showCenteredInCurrentWindow(project)
  }

  override fun createActionPopup(context: DataContext, component: JComponent, disposeCallback: Runnable?): ListPopup {
    val group = createPopupActionGroup(component, context)
    val popup = JBPopupFactory.getInstance().createActionGroupPopup(
      null, group, context, false, shouldShowDisabledActions(), false, disposeCallback, maxRows, preselectCondition
    )
    popup.setMinimumSize(Dimension(minWidth, minHeight))
    setUpPopup(popup)
    return popup
  }

  override fun update(e: AnActionEvent) {
    val presentation = e.presentation
    val project = e.project
    val isMenu = ActionPlaces.isMainMenuOrActionSearch(e.place)
    val dg = project != null && isDGProject(project)
    presentation.isEnabledAndVisible = dg
    val currentFilter = project?.let { DGTestSettings.getInstance(it).getCurrent() }
    if (isMenu) {
      presentation.setText("Manage DS Filters...")
    } else {
      presentation.setText(StringUtil.notNullize(currentFilter, "<No DS filter>"), true)
    }
    presentation.icon = if (currentFilter == null) AllIcons.General.Filter else ExecutionUtil.getLiveIndicator(AllIcons.General.Filter)
  }

  private fun registerActions(popup: ListPopupImpl) {
    popup.registerAction("editFilter", KeyStroke.getKeyStroke("F4"), object : javax.swing.AbstractAction() {
      override fun actionPerformed(e: ActionEvent) {
        val filter = getSelectedFilter(popup) ?: return
        filter.edit(popup.project)
        popup.cancel()
      }
    })

    popup.registerAction("navigateFilter", KeyStroke.getKeyStroke("F3"), object : javax.swing.AbstractAction() {
      override fun actionPerformed(e: ActionEvent) {
        val filter = getSelectedFilter(popup) ?: return
        val p: Pattern = try {
          Pattern.compile(filter.myFilter)
        } catch (pse: PatternSyntaxException) {
          return
        }
        val targets = DGTestDataSources.list(popup.project)
          .flatten { dss -> dss.dataSources }
          .filter { ds -> p.matcher(ds.uuid).matches() }
          .toList()
        if (targets.isEmpty()) return
        if (targets.size == 1) {
          navigate(targets[0])
          return
        }
        popup.cancel()
        JBPopupFactory.getInstance()
          .createPopupChooserBuilder(targets)
          .setItemChosenCallback { navigate(it) }
          .setRenderer(SimpleListCellRenderer.create { lbl, o, _ ->
            lbl.icon = o.getIcon()
            lbl.text = o.uuid
          })
          .createPopup()
          .showInFocusCenter()
      }

      private fun navigate(ds: DGTestDataSources.DGTestDataSource) {
        val element = ds.source.element
        navigate(element, true)
      }
    })

    popup.registerAction("deleteFilter", KeyStroke.getKeyStroke("DELETE"), object : javax.swing.AbstractAction() {
      override fun actionPerformed(e: ActionEvent) {
        val filter = getSelectedFilter(popup) ?: return
        filter.delete(popup.project)
        popup.cancel()
      }
    })
  }

  private fun getSelectedFilter(popup: ListPopupImpl): MyFilterAction? {
    val any = ArrayUtil.getFirstElement(popup.selectedValues)
    val actionItem = ObjectUtils.tryCast(any, PopupFactoryImpl.ActionItem::class.java)
    val action = actionItem?.action
    return ObjectUtils.tryCast(action, MyFilterAction::class.java)
  }

  private class MyFilterAction(val myFilter: String) : ToggleAction(""), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
      super.update(e)
      e.presentation.setText(StringUtil.escapeMnemonics(myFilter), false)
    }

    override fun isSelected(e: AnActionEvent): Boolean {
      val project = e.project ?: return false
      return myFilter == DGTestSettings.getInstance(project).getCurrent()
    }

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
      val project = e.project ?: return
      DGTestSettings.getInstance(project).setCurrent(if (selected) myFilter else null)
    }

    fun delete(project: Project) {
      val settings = DGTestSettings.getInstance(project)
      settings.getFilters().remove(myFilter)
      if (myFilter == settings.getCurrent()) {
        settings.setCurrent(null)
      }
    }

    fun edit(project: Project) {
      editFilter(project, myFilter).thenAccept { res ->
        val settings = DGTestSettings.getInstance(project)
        settings.getFilters().remove(myFilter)
        settings.getFilters().add(res)
        settings.setCurrent(res)
      }
    }
  }

  private class MyAskAction : ToggleAction("Always Ask"), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean =
      e.project?.let { DGTestSettings.getInstance(it).isAsk() } ?: false
    override fun setSelected(e: AnActionEvent, selected: Boolean) {
      e.project?.let { DGTestSettings.getInstance(it).setAsk(selected) }
    }
  }

  private class MyOverwriteAction : ToggleAction("Overwrite Test Data"), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean =
      e.project?.let { DGTestSettings.getInstance(it).isOverwrite() } ?: false
    override fun setSelected(e: AnActionEvent, selected: Boolean) {
      e.project?.let { DGTestSettings.getInstance(it).setOverwrite(selected) }
    }
  }

  private class MyInProcessRmiAction : ToggleAction("In-Process RMI"), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean =
      e.project?.let { DGTestSettings.getInstance(it).isInProcessRmi() } ?: false
    override fun setSelected(e: AnActionEvent, selected: Boolean) {
      e.project?.let { DGTestSettings.getInstance(it).setInProcessRmi(selected) }
    }
  }

  private class MyAttachRemoteAction : ToggleAction("Attach Remote"), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean =
      e.project?.let { DGTestSettings.getInstance(it).isAttachRemote() } ?: false
    override fun setSelected(e: AnActionEvent, selected: Boolean) {
      e.project?.let { DGTestSettings.getInstance(it).setAttachRemote(selected) }
    }
  }

}

private val IS_DG_PROJECT: Key<Boolean> = Key.create("IS_DG_PROJECT")

fun isDGProject(project: Project): Boolean {
  var isDG = IS_DG_PROJECT.get(project)
  if (isDG == null) {
    isDG = ModuleManager.getInstance(project).findModuleByName("intellij.database") != null
    IS_DG_PROJECT.set(project, isDG)
  }
  return isDG
}

fun navigate(element: XmlTag?, requestFocus: Boolean) {
  if (element != null) {
    val descriptor: Navigatable? = PsiNavigationSupport.getInstance().getDescriptor(element)
    descriptor?.navigate(requestFocus)
  }
}

private fun editFilter(project: Project, def: String): CompletionStage<String> {
  val builder = FormBuilder.createFormBuilder()
  builder.panel.border = JBUI.Borders.empty(UIUtil.DEFAULT_VGAP, UIUtil.DEFAULT_HGAP)
  val closeOk = Ref.create<(KeyEvent) -> Unit>()
  val editor = object : EditorTextField(def, project, RegExpFileType.INSTANCE) {
    override fun processKeyBinding(ks: KeyStroke?, e: KeyEvent, condition: Int, pressed: Boolean): Boolean {
      if (!e.isConsumed && e.keyCode == KeyEvent.VK_ENTER && !closeOk.isNull) {
        closeOk.get().invoke(e)
        return true
      }
      return super.processKeyBinding(ks, e, condition, pressed)
    }
  }
  editor.selectAll()
  val comp = ComponentWithBrowseButton(editor) {
    choosePredefined(project, editor) { text ->
      editor.text = text
      IdeFocusManager.getInstance(project).requestFocus(editor, true)
    }
  }
  builder.addLabeledComponent("Filter:", comp)
  val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(builder.panel, editor)
    .setTitle("Edit Filter")
    .setResizable(true)
    .setModalContext(true)
    .setFocusable(true)
    .setRequestFocus(true)
    .setMovable(true)
    .setBelongsToGlobalPopupStack(true)
    .setCancelKeyEnabled(true)
    .setCancelOnWindowDeactivation(false)
    .setCancelOnClickOutside(true)
    .addUserData("SIMPLE_WINDOW")
    .createPopup()
  closeOk.set { popup.closeOk(it) }
  val res = CompletableFuture<String>()
  popup.addListener(object : JBPopupListener {
    override fun onClosed(event: LightweightWindowEvent) {
      if (event.isOk) res.complete(editor.text)
      else res.completeExceptionally(ProcessCanceledException())
    }
  })
  popup.setMinimumSize(Dimension(200, 10))
  popup.showCenteredInCurrentWindow(project)
  return res
}

private fun choosePredefined(project: Project, component: JComponent, callback: (String) -> Unit) {
  val dss = DGTestDataSources.list(project)
    .flatten { td -> td.dataSources }
    .sort { ds1, ds2 -> StringUtil.naturalCompare(ds1.uuid, ds2.uuid) }
    .toList()
  JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<DGTestDataSources.DGTestDataSource>("Test Data Sources", dss) {
    override fun getIconFor(value: DGTestDataSources.DGTestDataSource) = value.getIcon()
    override fun getTextFor(value: DGTestDataSources.DGTestDataSource) = value.uuid
    override fun onChosen(selectedValue: DGTestDataSources.DGTestDataSource?, finalChoice: Boolean): PopupStep<*>? {
      if (selectedValue != null) callback(selectedValue.uuid)
      return FINAL_CHOICE
    }
    override fun isSpeedSearchEnabled() = true
  }).showUnderneathOf(component)
}