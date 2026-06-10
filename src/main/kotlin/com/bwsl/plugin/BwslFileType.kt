package com.bwsl.plugin

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object BwslFileType: LanguageFileType(BwslLanguage) {

    val MODULE_ICON: Icon = IconLoader.getIcon("/bwsl_logo_m.svg", BwslFileType::class.java)
    val PIPELINE_ICON: Icon = IconLoader.getIcon("/bwsl_logo_p.svg", BwslFileType::class.java)

    override fun getName(): String = "BWSL"
    override fun getDescription(): String = "BWSL shader file"
    override fun getDefaultExtension(): String = "bwsl"
    override fun getIcon(): Icon = MODULE_ICON
}
