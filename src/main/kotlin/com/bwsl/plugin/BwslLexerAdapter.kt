package com.bwsl.plugin

import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

private val INTRINSIC_NAMES = setOf(
    "abs", "acos", "all", "any", "asin", "atan", "ceil", "clamp", "cos", "cross",
    "degrees", "distance", "dot", "exp", "exp2", "floor", "fmod", "frac",
    "inversesqrt", "length", "lerp", "log", "log2", "max", "min", "mix", "mod",
    "normalize", "pow", "radians", "reflect", "refract", "round", "saturate",
    "sign", "sin", "smoothstep", "sqrt", "step", "tan", "trunc"
)

class BwslLexerAdapter : LexerBase() {
    private val flex = FlexAdapter(_BwslLexer(null))
    private val queue = ArrayDeque<Tok>()

    private var current = Tok(null, 0, 0)
    private var bufSeq: CharSequence = ""
    private var bufEnd = 0

    private data class Tok(val type: IElementType?, val start: Int, val end: Int)

    private var prevSignificantType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        bufSeq = buffer
        bufEnd = endOffset
        flex.start(buffer, startOffset, endOffset, initialState)
        queue.clear()
        advance()
    }

    // BWSL uses only YYINITIAL so state is always 0; safe for incremental re-lexing.
    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = current.type
    override fun getTokenStart(): Int = current.start
    override fun getTokenEnd(): Int = current.end
    override fun getBufferSequence(): CharSequence = bufSeq
    override fun getBufferEnd(): Int = bufEnd

    override fun advance() {
        if (current.type != null && current.type != TokenType.WHITE_SPACE) {
            prevSignificantType = current.type
        }

        val raw = dequeue()
        if (raw.type != BwslTokenTypes.IDENTIFIER) { current = raw; return }

        val name = bufSeq.substring(raw.start, raw.end)
        val hasReceiver = prevSignificantType == BwslTokenTypes.DOT
        current = when {
            prevSignificantType == BwslTokenTypes.KW_MODULE ||
            prevSignificantType == BwslTokenTypes.KW_SUBMODULE -> raw.copy(type = BwslTokenTypes.MODULE_NAME)
            prevSignificantType == BwslTokenTypes.KW_EXTENDS -> raw.copy(type = BwslTokenTypes.MODULE_QUALIFIER)
            // "name :: (" is a function declaration; "Name::Thing" / "Name::func(...)" is a
            // module qualifier, "Name" should be tagged MODULE_QUALIFIER.
            nextNonWhitespace(0)?.type == BwslTokenTypes.COLONCOLON ->
                if (nextNonWhitespace(1)?.type == BwslTokenTypes.LPAREN)
                    raw.copy(type = BwslTokenTypes.FUNCTION_DECLARATION)
                else raw.copy(type = BwslTokenTypes.MODULE_QUALIFIER)
            nextNonWhitespace(0)?.type == BwslTokenTypes.LPAREN ->
                raw.copy(type = if (name in INTRINSIC_NAMES && (!hasReceiver || name == "length"))
                                    BwslTokenTypes.INTRINSIC_CALL
                                else BwslTokenTypes.FUNCTION_CALL)
            else -> raw
        }
    }

    /** Returns the (skip+1)-th non-whitespace token after the current position (0-based). */
    private fun nextNonWhitespace(skip: Int): Tok? {
        var i = 0
        var found = 0
        while (true) {
            while (queue.size <= i) {
                val tok = drainFlex()
                queue.addLast(tok)
                if (tok.type == null) return null
            }
            val tok = queue[i]
            if (tok.type != TokenType.WHITE_SPACE) {
                if (found == skip) return tok
                found++
            }
            i++
        }
    }

    private fun dequeue(): Tok = if (queue.isNotEmpty()) queue.removeFirst() else drainFlex()

    private fun drainFlex(): Tok {
        val tok = Tok(flex.tokenType, flex.tokenStart, flex.tokenEnd)
        if (tok.type != null) flex.advance()
        return tok
    }
}
