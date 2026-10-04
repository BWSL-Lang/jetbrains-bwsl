package com.bwsl.plugin

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UseScopeEnlarger

/**
 * A module file can live outside the project (a shared `-modules` directory), and the platform
 * limits a search for usages of something declared in such a file to that file itself. Its users
 * are the project's own BWSL files, so widen the scope to the project.
 */
class BwslUseScopeEnlarger : UseScopeEnlarger() {

    override fun getAdditionalUseScope(element: PsiElement): SearchScope? =
        if (element.language == BwslLanguage) GlobalSearchScope.projectScope(element.project) else null
}
