package com.takekazex.hypertweak.util

import com.takekazex.hypertweak.settings.contract.ScopeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScopeManagerTest {
    private class Service(var packages: List<String> = emptyList()) : ScopeService {
        var unreadable = false
        var requests = emptyList<String>()
        var answer: (List<String>, ScopeService.Listener) -> Unit = { requested, listener -> listener.onScopeRequestApproved(requested) }
        override val scope: List<String> get() { check(!unreadable); return packages }
        override fun removeScope(packages: List<String>) { this.packages -= packages.toSet() }
        override fun requestScope(packages: List<String>, listener: ScopeService.Listener) {
            requests = packages
            answer(packages, listener)
        }
    }

    @Test fun unavailableServiceAndEmptyRequestsDoNotInventScopeOrRecommendations() = runBlocking {
        val manager = ScopeManager({ null }, { error("No scope, so no settings query") }, Dispatchers.Unconfined)
        assertNull(manager.currentScope())
        assertNull(manager.launcherScopeRecommendation())
        assertEquals(ScopeManager.Result.NoChange, manager.request(setOf(" ", "")))
        assertEquals(ScopeManager.Result.ServiceUnavailable, manager.request(setOf("com.miui.home")))
    }

    @Test fun recommendationsUseTheCurrentBindingAndNeverRemoveExistingScope() = runBlocking {
        val first = Service(listOf(" system ", "system", ""))
        var bound: ScopeService = first
        var needed = false
        val manager = ScopeManager({ bound }, { needed }, Dispatchers.Unconfined)
        assertEquals(setOf("system"), manager.currentScope())
        assertNull(manager.launcherScopeRecommendation())
        needed = true
        assertEquals(ScopePrompt(ScopePromptAction.RESTORE, "com.miui.home"), manager.launcherScopeRecommendation())
        bound = Service(listOf("com.miui.home"))
        assertNull(manager.launcherScopeRecommendation())
        needed = false
        assertEquals(setOf("com.miui.home"), manager.currentScope())
        first.unreadable = true
        bound = first
        assertNull(manager.currentScope())
        assertNull(manager.launcherScopeRecommendation())
    }

    @Test fun partialApprovalWinsOverLaterFailureAndRequestIsNormalized() = runBlocking {
        val service = Service().apply {
            answer = { _, listener ->
                listener.onScopeRequestApproved(listOf(" com.one "))
                listener.onScopeRequestFailed("late failure")
                listener.onScopeRequestApproved(listOf("com.one", "com.two"))
            }
        }
        val manager = ScopeManager({ service }, { false }, Dispatchers.Unconfined)
        assertEquals(ScopeManager.Result.Rejected(setOf("com.two")), manager.request(setOf(" com.one ", "com.two", "")))
        assertEquals(setOf("com.one", "com.two"), service.requests.toSet())
    }

    @Test fun failedRequestsReturnFailureWithoutTurningItIntoAnApproval() = runBlocking {
        val service = Service().apply { answer = { _, _ -> error("binder unavailable") } }
        val manager = ScopeManager({ service }, { false }, Dispatchers.Unconfined)
        assertEquals(ScopeManager.Result.Failed("binder unavailable"), manager.request(setOf("com.one")))
        service.answer = { _, listener -> listener.onScopeRequestFailed("declined") }
        assertEquals(ScopeManager.Result.Failed("declined"), manager.request(setOf("com.one")))
    }
}
