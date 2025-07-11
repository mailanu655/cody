package com.sourcegraph.cody.autocomplete

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.ui.GotItTooltip
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.sourcegraph.Icons
import com.sourcegraph.cody.CodyToolWindowContent
import com.sourcegraph.cody.agent.CodyAgentService
import com.sourcegraph.cody.agent.protocol_generated.AutocompleteItem
import com.sourcegraph.cody.agent.protocol_generated.AutocompleteResult
import com.sourcegraph.cody.agent.protocol_generated.CompletionItemParams
import com.sourcegraph.cody.auth.CodyAuthService
import com.sourcegraph.cody.autocomplete.Utils.triggerAutocompleteAsync
import com.sourcegraph.cody.autocomplete.render.AutocompleteRendererType
import com.sourcegraph.cody.autocomplete.render.CodyAutocompleteBlockElementRenderer
import com.sourcegraph.cody.autocomplete.render.CodyAutocompleteElementRenderer
import com.sourcegraph.cody.autocomplete.render.CodyAutocompleteSingleLineRenderer
import com.sourcegraph.cody.autocomplete.render.InlayModelUtil.getAllInlaysForEditor
import com.sourcegraph.cody.autoedit.AutoeditManager
import com.sourcegraph.cody.statusbar.CodyStatusService.Companion.resetApplication
import com.sourcegraph.cody.vscode.CancellationToken
import com.sourcegraph.cody.vscode.InlineCompletionTriggerKind
import com.sourcegraph.cody.vscode.IntelliJTextDocument
import com.sourcegraph.cody.vscode.TextDocument
import com.sourcegraph.common.CodyBundle
import com.sourcegraph.common.CodyBundle.fmt
import com.sourcegraph.config.ConfigUtil
import com.sourcegraph.config.ConfigUtil.isCodyEnabled
import com.sourcegraph.utils.CodyEditorUtil.getAllOpenEditors
import com.sourcegraph.utils.CodyEditorUtil.getLanguage
import com.sourcegraph.utils.CodyEditorUtil.getTextRange
import com.sourcegraph.utils.CodyEditorUtil.isCommandExcluded
import com.sourcegraph.utils.CodyEditorUtil.isEditorValidForAutocomplete
import com.sourcegraph.utils.CodyEditorUtil.isImplicitAutocompleteEnabledForEditor
import com.sourcegraph.utils.CodyFormatter
import com.sourcegraph.utils.CodyIdeUtil
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.annotations.VisibleForTesting

/** Responsible for triggering and clearing inline code completions (the autocomplete feature). */
@Service(Service.Level.PROJECT)
class CodyAutocompleteManager(val project: Project) {
  private val logger = Logger.getInstance(CodyAutocompleteManager::class.java)
  private val currentJob = AtomicReference(CancellationToken())

  /**
   * Clears any already rendered autocomplete suggestions for the given editor and cancels any
   * pending ones.
   *
   * @param editor the editor to clear autocomplete suggestions for
   */
  @RequiresEdt
  fun clearAutocompleteSuggestions(editor: Editor) {
    // Cancel any running job
    cancelCurrentJob(editor.project)

    // Clear any existing inline elements
    disposeInlays(editor)
  }

  /**
   * Clears any already rendered autocomplete suggestions for all open editors and cancels any
   * pending ones.
   */
  @RequiresEdt
  fun clearAutocompleteSuggestions() {
    getAllOpenEditors(project).forEach { clearAutocompleteSuggestions(it) }
  }

  @RequiresEdt
  fun clearAutocompleteSuggestionsForLanguageIds(languageIds: List<String?>) =
      getAllOpenEditors(project)
          .filter { e -> getLanguage(e)?.let { l -> languageIds.contains(l.id) } ?: false }
          .forEach { clearAutocompleteSuggestions(it) }

  @RequiresEdt
  fun clearAutocompleteSuggestionsForLanguageId(languageId: String) =
      clearAutocompleteSuggestionsForLanguageIds(listOf(languageId))

  @RequiresEdt
  fun disposeInlays(editor: Editor) {
    if (editor.isDisposed) {
      return
    }
    getAllInlaysForEditor(editor)
        .filter { inlay -> inlay.renderer is CodyAutocompleteElementRenderer }
        .forEach { disposable -> Disposer.dispose(disposable) }
  }

  /**
   * Triggers auto-complete suggestions for the given editor at the specified offset.
   *
   * @param editor The editor instance to provide autocomplete for.
   * @param offset The character offset in the editor to trigger auto-complete at.
   */
  fun triggerAutocomplete(
      editor: Editor,
      offset: Int,
      triggerKind: InlineCompletionTriggerKind,
      lookupString: String? = null
  ) {
    val isTriggeredExplicitly = triggerKind == InlineCompletionTriggerKind.INVOKE

    if (editor.editorKind != EditorKind.MAIN_EDITOR && !ConfigUtil.isIntegrationTestModeEnabled()) {
      logger.warn("triggered autocomplete with non-main editor")
      return
    }

    if (isTriggeredExplicitly) CodyAgentService.withAgentRestartIfNeeded(project) {}

    val isTriggeredImplicitly = !isTriggeredExplicitly
    if (!isCodyEnabled()) {
      if (isTriggeredExplicitly) {
        logger.warn("ignoring explicit autocomplete because Cody is disabled")
      }
      return
    }
    if (!isEditorValidForAutocomplete(editor)) {
      if (isTriggeredExplicitly) {
        logger.warn("triggered autocomplete with invalid editor $editor")
      }
      return
    }
    if (CodyIdeUtil.isRD()) {
      return
    }
    if (isTriggeredImplicitly && !isImplicitAutocompleteEnabledForEditor(editor)) {
      return
    }
    val currentCommand = CommandProcessor.getInstance().currentCommandName
    if (isTriggeredImplicitly &&
        lookupString.isNullOrEmpty() &&
        isCommandExcluded(currentCommand)) {
      return
    }
    if (!CodyAuthService.getInstance(project).isActivated()) {
      if (isTriggeredExplicitly) {
        HintManager.getInstance().showErrorHint(editor, "Cody: Sign in to use autocomplete")
        CodyToolWindowContent.show(project)
      }
      return
    }

    cancelCurrentJob(project)
    val cancellationToken = CancellationToken()
    currentJob.set(cancellationToken)
    val lineNumber = editor.document.getLineNumber(offset)
    val caretPositionInLine = offset - editor.document.getLineStartOffset(lineNumber)
    val originalText = editor.document.getText(TextRange(offset - caretPositionInLine, offset))

    val originalTextTrimmed = originalText.takeLastWhile { c -> c != '.' && !c.isWhitespace() }
    if (!lookupString.isNullOrEmpty() && !lookupString.startsWith(originalTextTrimmed)) {
      logger.debug("Skipping autocompletion for lookup element due to not matching prefix")
      return
    }

    val textDocument: TextDocument = IntelliJTextDocument(editor, project)
    triggerAutocompleteAsync(
        project,
        editor,
        offset,
        textDocument,
        triggerKind,
        cancellationToken,
        lookupString,
        originalText,
        logger) { autocompleteResult ->
          processAutocompleteResult(
              editor,
              offset,
              triggerKind,
              autocompleteResult,
              cancellationToken,
              noLookup = lookupString == null)
        }
  }

  private fun processAutocompleteResult(
      editor: Editor,
      offset: Int,
      triggerKind: InlineCompletionTriggerKind,
      result: AutocompleteResult,
      cancellationToken: CancellationToken,
      noLookup: Boolean,
  ) {
    if (Thread.interrupted() || cancellationToken.isCancelled) {
      if (triggerKind == InlineCompletionTriggerKind.INVOKE) logger.warn("autocomplete canceled")
      return
    }

    if (result.inlineCompletionItems.isEmpty() && result.decoratedEditItems.isEmpty()) {
      // NOTE(olafur): it would be nice to give the user a visual hint when this happens.
      // We don't do anything now because it's unclear what would be the most idiomatic
      // IntelliJ API to use.
      if (triggerKind == InlineCompletionTriggerKind.INVOKE)
          logger.warn("autocomplete returned empty suggestions")
      return
    }
    ApplicationManager.getApplication().invokeLater {
      if (cancellationToken.isCancelled) {
        return@invokeLater
      }
      cancellationToken.dispose()
      clearAutocompleteSuggestions(editor)

      if (noLookup && result.decoratedEditItems.isNotEmpty()) {
        runInEdt {
          editor.project
              ?.getService(AutoeditManager::class.java)
              ?.showAutoedit(editor, result.decoratedEditItems.first())
        }
      } else if (result.inlineCompletionItems.isNotEmpty()) {
        // https://github.com/sourcegraph/jetbrains/issues/350
        // CodyFormatter.formatStringBasedOnDocument needs to be on a write action.
        WriteCommandAction.runWriteCommandAction(editor.project) {
          displayInlay(editor, offset, result.inlineCompletionItems)
        }
      }
    }
  }

  @RequiresEdt
  @VisibleForTesting
  fun displayInlay(
      editor: Editor,
      cursorOffset: Int,
      items: List<AutocompleteItem>,
  ) {
    val project = editor.project ?: return
    if (editor.isDisposed || project.isDisposed) {
      return
    }

    val defaultItem = items.firstOrNull() ?: return
    val range = getTextRange(editor.document, defaultItem.range) ?: return
    val originalText = editor.document.getText(range)

    val formattedCompletionText =
        if (System.getProperty("cody.autocomplete.enableFormatting") == "false") {
          defaultItem.insertText
        } else {
          CodyFormatter.formatStringBasedOnDocument(
              defaultItem.insertText, project, editor.document, range, cursorOffset)
        }

    if (formattedCompletionText.trim().isBlank()) return

    CodyAgentService.withAgent(project) { agent ->
      agent.server.autocomplete_completionSuggested(CompletionItemParams(defaultItem.id))
    }

    val startsInline =
        lineBreaks.none { separator -> formattedCompletionText.startsWith(separator) }

    val (commonTextLength, inlineCompletionText) =
        if (startsInline) {
          trimCommonPrefixAndSuffix(
              formattedCompletionText.lines().first(), originalText.lines().first())
        } else {
          0 to ""
        }
    val offset = range.startOffset + commonTextLength

    var inlay: Inlay<*>? = null
    if (startsInline && inlineCompletionText.isNotEmpty()) {
      val renderer =
          CodyAutocompleteSingleLineRenderer(
              inlineCompletionText, items, editor, AutocompleteRendererType.INLINE)
      inlay =
          editor.inlayModel.addInlineElement(offset, /* relatesToPrecedingText= */ true, renderer)
    }
    val lines = formattedCompletionText.lines()
    if (lines.size > 1) {
      val text =
          (if (startsInline) lines.drop(1) else lines).dropWhile { it.isBlank() }.joinToString("\n")
      if (text.isNotEmpty()) {
        val renderer = CodyAutocompleteBlockElementRenderer(text, items, editor)
        val inlay2 =
            editor.inlayModel.addBlockElement(
                /* offset = */ offset,
                /* relatesToPrecedingText = */ true,
                /* showAbove = */ false,
                /* priority = */ Int.MAX_VALUE,
                /* renderer = */ renderer)
        if (inlay == null) {
          inlay = inlay2
        }
      }
    }

    displayGotItTooltip(inlay, editor)
  }

  private fun displayGotItTooltip(inlay: Inlay<*>?, editor: Editor) {
    val location = inlay?.bounds?.location ?: return
    val gotit = tooltip(inlay)
    if (location.y < 150) {
      location.setLocation(location.x, location.y + getLineHeight())
    }
    try {
      gotit.show(editor.contentComponent) { _, _ -> location }
    } catch (e: Exception) {
      logger.info("Failed to display gotit tooltip", e)
    }
  }

  private fun tooltip(disposable: Disposable): GotItTooltip =
      GotItTooltip(
              "cody.autocomplete.gotIt",
              CodyBundle.getString("gotit.autocomplete.message")
                  .fmt(
                      KeymapUtil.getShortcutText("cody.acceptAutocompleteAction"),
                      KeymapUtil.getShortcutText("cody.cycleForwardAutocompleteAction"),
                      KeymapUtil.getShortcutText("cody.cycleBackAutocompleteAction")),
              disposable)
          .withHeader(CodyBundle.getString("gotit.autocomplete.header"))
          .withPosition(Balloon.Position.above)
          .withIcon(Icons.SourcegraphLogo)

  private fun getLineHeight(): Int {
    val colorsManager = EditorColorsManager.getInstance()
    val fontPreferences = colorsManager.globalScheme.fontPreferences
    val fontSize = fontPreferences.getSize(fontPreferences.fontFamily)
    val lineSpacing = fontPreferences.lineSpacing.toInt()
    val extraMargin = 4
    return fontSize + lineSpacing + extraMargin
  }

  private fun cancelCurrentJob(project: Project?) {
    currentJob.get().abort()
    project?.let { resetApplication(it) }
  }

  companion object {
    @JvmStatic
    fun getInstance(project: Project): CodyAutocompleteManager =
        project.service<CodyAutocompleteManager>()

    private val lineBreaks = listOf("\r\n", "\n", "\r")

    @VisibleForTesting
    fun trimCommonPrefixAndSuffix(completion: String, original: String): Pair<Int, String> {
      var startIndex = 0
      var endIndex = completion.length

      // Trim common prefix
      while (startIndex < completion.length &&
          startIndex < original.length &&
          completion[startIndex] == original[startIndex]) {
        startIndex++
      }

      // Trim common suffix
      while (endIndex > 0 &&
          endIndex > startIndex &&
          original.length - (completion.length - endIndex) > 0 &&
          original.length - (completion.length - endIndex) > startIndex &&
          completion[endIndex - 1] ==
              original[original.length - (completion.length - endIndex) - 1]) {
        endIndex--
      }

      return Pair(startIndex, completion.substring(startIndex, endIndex))
    }
  }
}
