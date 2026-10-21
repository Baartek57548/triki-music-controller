package pl.trikimusic.controller.core.logging

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.trikimusic.controller.domain.model.TrikiBleState
import pl.trikimusic.controller.domain.model.TrikiConnectionState

class ConnectionHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `failed write is visible while event stays in memory`() = runBlocking {
        val blocked = temporary.newFile()
        val job = SupervisorJob()
        val store = ConnectionHistoryStore(File(blocked, "history.json"), CoroutineScope(job + Dispatchers.Default))
        try {
            store.ready.await()
            store.record(ConnectionHistoryEntry(System.currentTimeMillis(), "READY", "Połączono."))
            withTimeout(5_000L) { store.persistenceError.first { it != null } }
            assertNotNull(store.persistenceError.value)
            assertEquals(1, store.entries.value.size)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `history bounds persists exports clears and ignores telemetry`() = runBlocking {
        val file = File(temporary.newFolder(), "connection-history.json")
        var job = SupervisorJob()
        var store = ConnectionHistoryStore(file, CoroutineScope(job + Dispatchers.Default))
        try {
            store.ready.await()
            val state = TrikiBleState(connectionState = TrikiConnectionState.READY)
            store.recordTransition(TrikiBleState(), state)
            repeat(500) { store.recordTransition(state, state.copy(decodedFrames = it.toLong())) }
            assertEquals(1, store.entries.value.size)
            repeat(250) { store.record(ConnectionHistoryEntry(System.currentTimeMillis(), "CONNECTING", "Próba $it", "Triki")) }
            assertEquals(200, store.entries.value.size)
            assertEquals("Próba 249", store.entries.value.first().message)
            store.flush()
            assertEquals(200, Json.parseToJsonElement(store.exportJson()).jsonArray.size)
        } finally { job.cancelAndJoin() }
        job = SupervisorJob()
        store = ConnectionHistoryStore(file, CoroutineScope(job + Dispatchers.Default))
        try {
            store.ready.await()
            assertEquals(200, store.entries.value.size)
            store.clear()
        } finally { job.cancelAndJoin() }
        job = SupervisorJob()
        store = ConnectionHistoryStore(file, CoroutineScope(job + Dispatchers.Default))
        try { store.ready.await(); assertTrue(store.entries.value.isEmpty()) }
        finally { job.cancelAndJoin() }
    }

    @Test
    fun `corrupt history is preserved and does not block recovery`() = runBlocking {
        val file = File(temporary.newFolder(), "connection-history.json").apply { writeText("{invalid") }
        val job = SupervisorJob()
        val store = ConnectionHistoryStore(file, CoroutineScope(job + Dispatchers.Default))
        try {
            store.ready.await()
            assertNotNull(store.persistenceError.value)
            assertTrue(store.entries.value.isEmpty())
            assertEquals(1, file.parentFile!!.listFiles()!!.count { it.name.endsWith(".corrupt") })
            store.recordTransition(TrikiBleState(), TrikiBleState(), "Ręczne rozłączenie.")
            store.flush()
            assertEquals(1, Json.parseToJsonElement(file.readText()).jsonArray.size)
        } finally { job.cancelAndJoin() }
    }
}
