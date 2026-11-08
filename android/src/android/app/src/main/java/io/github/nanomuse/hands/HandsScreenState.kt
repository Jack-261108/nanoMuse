package io.github.nanomuse.hands

/** A new virtual display has no app or frame; that is not a protected app screen. */
internal enum class HandsScreenState {
    EMPTY, WAITING_FOR_FRAME, PROTECTED, READY;

    fun allows(action: HandsAction): Boolean = this == READY ||
        (this == EMPTY && (action is HandsAction.OpenApp || action is HandsAction.AskUser || action is HandsAction.Status))

    companion object {
        fun classify(
            virtual: Boolean,
            hasTask: Boolean,
            hasAppWindow: Boolean,
            hasFrame: Boolean,
            black: Boolean,
        ): HandsScreenState = when {
            virtual && !hasTask && !hasAppWindow -> EMPTY
            !hasFrame -> WAITING_FOR_FRAME
            black && (!virtual || hasAppWindow) -> PROTECTED
            black -> WAITING_FOR_FRAME
            else -> READY
        }
    }
}
