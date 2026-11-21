package io.github.nanomuse.skills

import kotlinx.coroutines.CancellationException

/**
 * Pure optimistic toggle and delete outcome transitions for skill management UI.
 * Tracks per-skill `(enabled, seq)` entries so rapid taps stay responsive
 * and only the latest in-flight toggle may clear or roll back the pending entry.
 */
data class PendingSkillToggle(
    val enabled: Boolean,
    val seq: Int,
)

data class ToggleOutcome(
    val rolledBack: Boolean,
    val shouldNotifyFailure: Boolean,
    val error: Throwable? = null,
)

data class DeleteUiOutcome(
    val shouldNotifyFailure: Boolean,
    val shouldNavigateBack: Boolean,
    val restoreDeletedFlag: Boolean,
    val error: Throwable? = null,
)

object PendingSkillToggles {
    fun displayEnabled(
        pending: Map<String, PendingSkillToggle>,
        skillId: String,
        persistedEnabled: Boolean,
    ): Boolean = pending[skillId]?.enabled ?: persistedEnabled

    fun beginToggle(
        pending: MutableMap<String, PendingSkillToggle>,
        skillId: String,
        enabled: Boolean,
    ): Int {
        val nextSeq = (pending[skillId]?.seq ?: 0) + 1
        pending[skillId] = PendingSkillToggle(enabled = enabled, seq = nextSeq)
        return nextSeq
    }

    /** Clear or roll back only if [seq] is still the latest in-flight toggle for [skillId]. */
    fun finishToggle(
        pending: MutableMap<String, PendingSkillToggle>,
        skillId: String,
        seq: Int,
    ): Boolean {
        if (pending[skillId]?.seq != seq) return false
        pending.remove(skillId)
        return true
    }

    /**
     * Await a queued toggle write without swallowing [CancellationException], and only
     * roll back / request error notification when [seq] is still the latest tap.
     */
    suspend fun awaitToggleOutcome(
        pending: MutableMap<String, PendingSkillToggle>,
        skillId: String,
        seq: Int,
        awaitWrite: suspend () -> Unit,
    ): ToggleOutcome {
        val error = try {
            awaitWrite()
            null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            t
        }
        val isLatest = finishToggle(pending, skillId, seq)
        return ToggleOutcome(
            rolledBack = error != null && isLatest,
            shouldNotifyFailure = error != null && isLatest,
            error = error,
        )
    }

    fun shouldNotifyToggleFailure(
        outcome: ToggleOutcome,
        isScopeActive: Boolean,
    ): Boolean = isScopeActive && outcome.shouldNotifyFailure

    /** Await a queued delete write, rethrowing [CancellationException] on UI teardown. */
    suspend fun awaitDeleteError(awaitDelete: suspend () -> Unit): Throwable? = try {
        awaitDelete()
        null
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        t
    }

    fun resolveDeleteUiOutcome(
        error: Throwable?,
        isScopeActive: Boolean,
        alreadyNavigatedBack: Boolean = false,
    ): DeleteUiOutcome {
        if (error != null) {
            return DeleteUiOutcome(
                shouldNotifyFailure = isScopeActive && !alreadyNavigatedBack,
                shouldNavigateBack = false,
                restoreDeletedFlag = !alreadyNavigatedBack,
                error = error,
            )
        }
        return DeleteUiOutcome(
            shouldNotifyFailure = false,
            shouldNavigateBack = isScopeActive && !alreadyNavigatedBack,
            restoreDeletedFlag = false,
            error = null,
        )
    }
}
