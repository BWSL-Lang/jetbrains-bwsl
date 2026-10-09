package com.bwsl.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The command the Compile action runs. */
class BwslCompileCommandTest {

    @Test
    fun testTheCommandHasTheFileTheFormatAndTheModulePaths() {
        val command = buildCompileCommand("bwslc", "a.bwsl", BwslOutputFormat.METAL, false, listOf("m1", "m2"))

        assertEquals(listOf("bwslc", "a.bwsl", "-metal", "-modules", "m1", "-modules", "m2"), command)
    }

    @Test
    fun testDebugNamesAreAddedOnlyWhenTheSettingIsOn() {
        assertTrue("-debug-names" in buildCompileCommand("bwslc", "a.bwsl", BwslOutputFormat.SPIRV_ONLY, true, emptyList()))
        assertFalse("-debug-names" in buildCompileCommand("bwslc", "a.bwsl", BwslOutputFormat.SPIRV_ONLY, false, emptyList()))
    }

    @Test
    fun testTheSettingIsOffByDefault() {
        assertFalse(BwslSettings.State().emitDebugNames)
    }

    @Test
    fun testTheCompilerAcceptsTheFlag() {
        val compiler = System.getProperty("bwslc.path") ?: return
        val process = ProcessBuilder(compiler, "-h").redirectErrorStream(true).start()
        val help = process.inputStream.bufferedReader().readText()

        assertTrue(help.contains("-debug-names"), "the compiler under test no longer lists -debug-names")
    }
}
