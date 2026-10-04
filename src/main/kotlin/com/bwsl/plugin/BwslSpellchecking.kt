package com.bwsl.plugin

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.BundledDictionaryProvider
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer

/**
 * What the spell checker looks at in BWSL: the words in comments and in string literals. Names
 * (identifiers, functions, types) are left alone. BWSL's comments and strings are plain tokens, not
 * the PSI comments the platform's own strategy recognises, so they are handed to the text tokenizer
 * here, which splits them into words and keeps `https://...` and the like out of the check.
 */
class BwslSpellcheckingStrategy : SpellcheckingStrategy() {

    override fun getTokenizer(element: PsiElement): Tokenizer<*> =
        when (element.node?.elementType) {
            BwslTokenTypes.LINE_COMMENT, BwslTokenTypes.BLOCK_COMMENT, BwslTokenTypes.STRING_LIT -> TEXT_TOKENIZER
            else -> EMPTY_TOKENIZER
        }
}

/** Words the shading and graphics vocabulary uses, which an English dictionary would flag in a comment. */
class BwslBundledDictionaryProvider : BundledDictionaryProvider {

    override fun getBundledDictionaries(): Array<String> = arrayOf("bwsl.dic")
}
