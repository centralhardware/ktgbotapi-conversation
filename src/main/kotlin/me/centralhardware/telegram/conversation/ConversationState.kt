package me.centralhardware.telegram.conversation

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which users currently have a running conversation, so a bot can refuse to start a
 * second concurrent flow for the same user and cancel a running one on demand.
 *
 * The state is keyed by an opaque user id ([Long]) and a caller-defined [type] tag, keeping the
 * registry independent of any concrete bot's set of conversations.
 */
class ConversationState<T : Any> {

    private val active = ConcurrentHashMap<Long, Info<T>>()

    data class Info<T : Any>(val type: T, val job: Job)

    fun hasActive(userId: Long): Boolean = active.containsKey(userId)

    /** Register a conversation for [userId]; returns null if one was already running. */
    fun start(userId: Long, type: T, job: Job): T? {
        if (hasActive(userId)) return null
        active[userId] = Info(type, job)
        return type
    }

    fun end(userId: Long) {
        active.remove(userId)
    }

    /** Cancel the running conversation for [userId], if any. Returns true when one was cancelled. */
    fun cancel(userId: Long): Boolean {
        val info = active.remove(userId) ?: return false
        info.job.cancel()
        return true
    }
}
