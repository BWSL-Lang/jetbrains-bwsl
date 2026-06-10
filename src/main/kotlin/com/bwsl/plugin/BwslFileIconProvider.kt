package com.bwsl.plugin

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class BwslFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.fileType != BwslFileType) return null
        val content = String(file.contentsToByteArray(), file.charset)
        return if (Regex("\\bpipeline\\b").containsMatchIn(content)) BwslFileType.PIPELINE_ICON else BwslFileType.MODULE_ICON
    }
}
