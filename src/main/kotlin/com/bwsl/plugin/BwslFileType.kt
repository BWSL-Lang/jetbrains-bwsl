package com.bwsl.plugin

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object BwslFileType: LanguageFileType(BwslLanguage) {

    override fun getName(): String = "BWSL"
    override fun getDescription(): String = "BWSL shader file"
    override fun getDefaultExtension(): String = "bwsl"
    override fun getIcon(): Icon? = null
}
