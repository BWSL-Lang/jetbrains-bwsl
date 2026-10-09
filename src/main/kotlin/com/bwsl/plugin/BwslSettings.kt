package com.bwsl.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "BwslSettings", storages = [Storage("bwsl.xml")])
@Service(Service.Level.APP)
class BwslSettings : PersistentStateComponent<BwslSettings.State> {

    data class State(
        var compilerPath: String = "",
        var modulePaths: MutableList<String> = mutableListOf(),
        var outputFormat: String = BwslOutputFormat.SPIRV_ONLY.name,
        var outputDirectory: String = "",
        /** Whether the compile action passes `-debug-names`, so the SPIR-V keeps the names of variables and functions. */
        var emitDebugNames: Boolean = false,
        var checkForCompilerUpdates: Boolean = true,
        /** When the newest release was last asked for, in epoch milliseconds. */
        var lastCompilerUpdateCheck: Long = 0,
        /** A release the user chose not to be reminded about. */
        var skippedCompilerVersion: String = ""
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    var compilerPath: String
        get() = state.compilerPath
        set(value) { state.compilerPath = value }

    var modulePaths: MutableList<String>
        get() = state.modulePaths
        set(value) { state.modulePaths = value }

    var outputFormat: String
        get() = state.outputFormat
        set(value) { state.outputFormat = value }

    var outputDirectory: String
        get() = state.outputDirectory
        set(value) { state.outputDirectory = value }

    var emitDebugNames: Boolean
        get() = state.emitDebugNames
        set(value) { state.emitDebugNames = value }

    var checkForCompilerUpdates: Boolean
        get() = state.checkForCompilerUpdates
        set(value) { state.checkForCompilerUpdates = value }

    var lastCompilerUpdateCheck: Long
        get() = state.lastCompilerUpdateCheck
        set(value) { state.lastCompilerUpdateCheck = value }

    var skippedCompilerVersion: String
        get() = state.skippedCompilerVersion
        set(value) { state.skippedCompilerVersion = value }

    companion object {
        fun getInstance(): BwslSettings =
            ApplicationManager.getApplication().getService(BwslSettings::class.java)
    }
}
