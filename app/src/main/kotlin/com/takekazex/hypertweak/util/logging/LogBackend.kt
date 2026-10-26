package com.takekazex.hypertweak.util.logging

/** App-only persistence capability. Injected processes never receive one. */
internal interface AppLogSink {
    fun rotateSession(token: String)
    fun append(process: String, lines: List<String>)
}

/** Storage-independent log controls, rebound by the process bootstrap after classloader replacement. */
internal class LogBackend {
    private data class Binding(val level: () -> Int, val sink: AppLogSink?)
    @Volatile private var binding = Binding({ 4 }, null)

    fun bind(level: () -> Int, sink: AppLogSink? = null) { binding = Binding(level, sink) }
    fun level(): Int = binding.level()
    fun rotateSession(token: String) { binding.sink?.rotateSession(token) }
    fun append(process: String, lines: List<String>) { binding.sink?.append(process, lines) }
    fun retire() { binding = Binding({ 4 }, null) }
}
