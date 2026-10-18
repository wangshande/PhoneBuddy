package com.deskbuddy
import java.io.File

interface SpeechEngine {
    interface Callback {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onLevel(level: Float) {}
    }
    val hasModel: Boolean
    val isRunning: Boolean
    val needsModel: Boolean get() = true
    val configurationError: String? get() = null
    fun loadModel(dir: File)
    fun start(callback: Callback): Boolean
    fun stop()
    fun release()
}
