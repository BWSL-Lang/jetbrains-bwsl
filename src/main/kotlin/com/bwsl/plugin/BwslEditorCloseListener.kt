package com.bwsl.plugin

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile

/**
 * Lets go of the raw tree of a BWSL file's AST when its last editor closes. The raw tree is what completion, the
 * inspections and the hints read, and is ten times the size of the JSON, so only open files keep it (see
 * [BwslAstCache.update]); opening the file again compiles it again.
 */
class BwslEditorCloseListener : FileEditorManagerListener {
    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (file.extension == "bwsl" && !source.isFileOpen(file)) BwslAstCache.dropRaw(file.path)
    }
}
