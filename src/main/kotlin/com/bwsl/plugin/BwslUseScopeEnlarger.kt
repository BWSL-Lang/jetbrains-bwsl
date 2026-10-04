package com.bwsl.plugin

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UseScopeEnlarger

/**
 * A module file can live outside the project (a shared `-modules` directory), and the platform
 * limits a search for usages of something declared in such a file to that file itself. Its users
 * are the indexed BWSL files ([collectIndexedFiles]): the project's own, and the files in the module
 * paths, so widen the scope to those.
 */
class BwslUseScopeEnlarger : UseScopeEnlarger() {

    override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
        if (element.language != BwslLanguage) return null
        val project = element.project
        return GlobalSearchScope.projectScope(project)
            .uniteWith(GlobalSearchScope.filesScope(project, collectIndexedFiles(project)))
    }
}
