package com.bwsl.plugin

import com.intellij.lang.Language

object BwslLanguage : Language("BWSL") {
    @Suppress("unused")
    private fun readResolve(): Any = BwslLanguage
}
