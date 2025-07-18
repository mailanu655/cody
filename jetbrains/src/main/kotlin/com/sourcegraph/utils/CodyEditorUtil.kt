package com.sourcegraph.utils

import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.Language
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.io.createFile
import com.sourcegraph.cody.agent.protocol_extensions.toBoundedOffset
import com.sourcegraph.cody.agent.protocol_extensions.toOffsetRange
import com.sourcegraph.cody.agent.protocol_generated.Range
import com.sourcegraph.config.ConfigUtil
import com.sourcegraph.utils.ThreadingUtil.runInEdtAndGet
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.*

object CodyEditorUtil {
  private val logger = Logger.getInstance(CodyEditorUtil::class.java)

  const val VIM_EXIT_INSERT_MODE_ACTION = "VimInsertExitModeAction"

  private const val VIM_MOTION_COMMAND = "Motion"
  private const val UP_COMMAND = "Up"
  private const val DOWN_COMMAND = "Down"
  private const val LEFT_COMMAND = "Left"
  private const val RIGHT_COMMAND = "Right"
  private const val MOVE_CARET_COMMAND = "Move Caret"
  private const val DELETE_COMMAND = "Delete"
  private const val ESCAPE_COMMAND = "Escape"

  @JvmStatic private val KEY_EDITOR_SUPPORTED = Key.create<Boolean>("cody.editorSupported")

  /**
   * Hints whether the editor wants autocomplete. Setting this value to false provides a hint to
   * disable autocomplete. If absent, assumes editors want autocomplete.
   */
  @JvmStatic val KEY_EDITOR_WANTS_AUTOCOMPLETE = Key.create<Boolean>("cody.editorWantsAutocomplete")

  @JvmStatic
  fun getTextRange(document: Document, range: Range): TextRange? {
    val (start, end) = range.toOffsetRange(document) ?: return null
    return TextRange.create(start, end)
  }

  @JvmStatic
  fun getAllOpenEditors(project: Project): Set<Editor> {
    return FileEditorManager.getInstance(project)
        .allEditors
        .toList()
        .filterIsInstance<TextEditor>()
        .map { fileEditor: FileEditor -> (fileEditor as TextEditor).editor }
        .toSet()
  }

  @JvmStatic
  fun getSelectedEditors(project: Project): Set<Editor> {
    if (project.isDisposed) return emptySet()
    return FileEditorManager.getInstance(project).selectedTextEditorWithRemotes.toSet()
  }

  @JvmStatic
  fun getEditorForDocument(project: Project, document: Document): Editor? {
    return getAllOpenEditors(project).find { it.document == document }
  }

  @JvmStatic
  fun isEditorInstanceSupported(editor: Editor): Boolean {
    return editor.project != null &&
        !editor.isViewer &&
        !editor.isOneLineMode &&
        (editor.editorKind == EditorKind.MAIN_EDITOR ||
            ConfigUtil.isIntegrationTestModeEnabled()) &&
        editor !is EditorWindow &&
        editor !is ImaginaryEditor &&
        (editor !is EditorEx || !editor.isEmbeddedIntoDialogWrapper) &&
        KEY_EDITOR_WANTS_AUTOCOMPLETE[editor] != false
  }

  @JvmStatic
  private fun isEditorSupported(editor: Editor): Boolean {
    if (editor.isDisposed) {
      return false
    }
    val fromCache = KEY_EDITOR_SUPPORTED[editor]
    if (fromCache != null) {
      return fromCache
    }
    val isSupported =
        isEditorInstanceSupported(editor) && CodyProjectUtil.isProjectSupported(editor.project)
    KEY_EDITOR_SUPPORTED[editor] = isSupported
    return isSupported
  }

  @JvmStatic
  @RequiresEdt
  fun isEditorValidForAutocomplete(editor: Editor?): Boolean {
    return editor != null &&
        !editor.isDisposed &&
        editor.document.isWritable &&
        CodyProjectUtil.isProjectAvailable(editor.project) &&
        isEditorSupported(editor)
  }

  @JvmStatic
  fun isImplicitAutocompleteEnabledForEditor(editor: Editor): Boolean {
    return ConfigUtil.isCodyEnabled() &&
        ConfigUtil.isCodyAutocompleteEnabled() &&
        !isLanguageBlacklisted(editor)
  }

  @JvmStatic
  fun getLanguage(editor: Editor): Language? {
    val project = editor.project ?: return null

    return CodyLanguageUtil.getLanguage(project, editor.document)
  }

  @JvmStatic
  fun isLanguageBlacklisted(editor: Editor): Boolean {
    val language = getLanguage(editor) ?: return false
    return ConfigUtil.getBlacklistedAutocompleteLanguageIds().contains(language.id)
  }

  @JvmStatic
  fun isCommandExcluded(command: String?): Boolean {
    return (command.isNullOrEmpty() ||
        command.contains(VIM_MOTION_COMMAND) ||
        command == UP_COMMAND ||
        command == DOWN_COMMAND ||
        command == LEFT_COMMAND ||
        command == RIGHT_COMMAND ||
        command == DELETE_COMMAND ||
        command == ESCAPE_COMMAND ||
        command.contains(MOVE_CARET_COMMAND))
  }

  @JvmStatic fun getVirtualFile(editor: Editor): VirtualFile? = editor.virtualFile

  @JvmStatic
  fun showDocument(
      project: Project,
      vf: VirtualFile,
      selection: Range? = null,
      preserveFocus: Boolean? = false
  ): Boolean {
    try {
      val descriptor =
          if (selection == null) {
            OpenFileDescriptor(project, vf)
          } else {
            OpenFileDescriptor(
                project,
                vf,
                selection.start.line.toInt(),
                /* logicalColumn= */ selection.start.character.toInt())
          }
      runInEdtAndGet { descriptor.navigate(/* requestFocus= */ preserveFocus != true) }
      return true
    } catch (e: Exception) {
      logger.error("Cannot switch view to file ${vf.path}", e)
      return false
    }
  }

  private fun fromVSCodeURI(uriString: String): Path? {
    if (!uriString.startsWith("file:")) {
      logger.warn("Unsupported file URIs scheme: $uriString")
      return null
    }

    return URI(uriString).toPath()
  }

  fun findFile(uriString: String): VirtualFile? {
    val path = fromVSCodeURI(uriString) ?: return null
    return VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)
  }

  fun createFileOrUseExisting(
      project: Project,
      uriString: String,
      content: String? = null,
      overwrite: Boolean = false
  ): VirtualFile? {
    val path = fromVSCodeURI(uriString) ?: return null

    if (overwrite || path.notExists()) {
      path.parent.createDirectories()
      path.deleteIfExists()
      path.createFile()
      val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

      content?.let {
        WriteCommandAction.runWriteCommandAction(project) { vf?.setBinaryContent(it.toByteArray()) }
      }
      return vf
    }
    return VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)
  }

  @JvmStatic
  @RequiresEdt
  fun selectAndScrollToRange(project: Project, uri: String, range: Range, shouldScroll: Boolean) {
    val vf = findFile(uri) ?: return
    val textEditor = getSelectedEditors(project).find { it.virtualFile == vf } ?: return
    textEditor.selectionModel.setSelection(
        range.start.toBoundedOffset(textEditor.document),
        range.end.toBoundedOffset(textEditor.document))
    if (shouldScroll) {
      textEditor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }
  }
}
