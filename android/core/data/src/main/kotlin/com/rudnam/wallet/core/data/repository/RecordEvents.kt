package com.rudnam.wallet.core.data.repository

import com.rudnam.wallet.core.model.RecordInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What happened to a record, said once the sheet that did it has closed (the app's snackbar, with Undo). */
sealed interface RecordEvent {
    data class Added(val amount: Long) : RecordEvent

    /** Kept on the phone until there's a connection. */
    data object Queued : RecordEvent

    /** A record to review was confirmed. */
    data object Confirmed : RecordEvent

    /** A record was changed; [before] puts it back. */
    data class Changed(val id: Long, val before: RecordInput) : RecordEvent

    /** A record was deleted; [restorable]: the server keeps it a day (one not sent yet is simply gone). */
    data class Deleted(val id: Long, val restorable: Boolean) : RecordEvent
}

@Singleton
class RecordEvents @Inject constructor() {
    private val events = MutableSharedFlow<RecordEvent>(extraBufferCapacity = 8)

    val all: SharedFlow<RecordEvent> = events.asSharedFlow()

    fun send(event: RecordEvent) {
        events.tryEmit(event)
    }
}
