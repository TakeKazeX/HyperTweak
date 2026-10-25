package com.takekazex.hypertweak.settings.contract

internal interface ScopeService {
    val scope: List<String>
    fun removeScope(packages: List<String>)
    fun requestScope(packages: List<String>, listener: Listener)
    interface Listener {
        fun onScopeRequestApproved(approved: List<String>)
        fun onScopeRequestFailed(message: String)
    }
}
