package pl.trikimusic.controller.core.logging

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import pl.trikimusic.controller.domain.model.TrikiBleState

@Serializable
data class ConnectionHistoryEntry(val timestampMillis: Long, val state: String, val message: String, val deviceName: String? = null)

class ConnectionHistoryStore(private val file: File, scope: CoroutineScope) {
    private val lock = Any()
    private val fileMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(ConnectionHistoryEntry.serializer())
    private val mutableEntries = MutableStateFlow<List<ConnectionHistoryEntry>>(emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    private val writes = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)
    val entries = mutableEntries.asStateFlow()
    val persistenceError = mutableError.asStateFlow()
    val ready = scope.async(Dispatchers.IO) {
        try {
            if (file.exists()) {
                if (file.length() > 1_000_000L) throw IOException("Historia przekracza dozwolony rozmiar.")
                val loaded = json.decodeFromString(serializer, file.readText())
                synchronized(lock) {
                    mutableEntries.value = (mutableEntries.value + loaded.mapNotNull(::sanitize))
                        .sortedByDescending { it.timestampMillis }.take(MAXIMUM_ENTRIES)
                }
            }
        } catch (error: Exception) {
            if (error !is IOException && error !is SerializationException && error !is IllegalArgumentException && error !is SecurityException) throw error
            mutableError.value = "Nie można odczytać historii: ${error.message}"
            try { if (file.isFile && file.length() <= 1_000_000L) file.copyTo(File(file.path + ".corrupt"), overwrite = true) }
            catch (backupError: IOException) { mutableError.value += "; kopia: ${backupError.message}" }
            catch (backupError: SecurityException) { mutableError.value += "; kopia: ${backupError.message}" }
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            ready.await()
            for (request in writes) {
                try { flush(); mutableError.value = null }
                catch (error: IOException) { mutableError.value = "Nie można zapisać historii: ${error.message}" }
                catch (error: SecurityException) { mutableError.value = "Brak dostępu do historii: ${error.message}" }
            }
        }
    }

    fun recordTransition(previous: TrikiBleState, current: TrikiBleState, reason: String? = null) {
        if (reason == null && previous.connectionState == current.connectionState && previous.errorMessage == current.errorMessage &&
            previous.selectedDevice?.address == current.selectedDevice?.address) return
        record(ConnectionHistoryEntry(System.currentTimeMillis(), current.connectionState.name,
            reason ?: current.errorMessage ?: when (current.connectionState.name) {
                "READY" -> "Połączenie gotowe; odebrano poprawne dane IMU."
                "CONNECTING", "CONNECTED" -> "Łączenie i konfiguracja GATT."
                "SCANNING", "FOUND" -> "Wyszukiwanie kontrolera Bluetooth LE."
                "WAITING_FOR_WAKE", "RECONNECTING" -> "Oczekiwanie na kontroler; automatyczne ponowne łączenie."
                "ERROR" -> "Błąd Bluetooth; szczegółowa przyczyna nie jest dostępna."
                else -> "Połączenie zakończone."
            }, current.selectedDevice?.name ?: previous.selectedDevice?.name))
    }

    fun record(entry: ConnectionHistoryEntry) {
        val safe = sanitize(entry) ?: return
        synchronized(lock) { mutableEntries.value = (listOf(safe) + mutableEntries.value).take(MAXIMUM_ENTRIES) }
        writes.trySend(Unit)
    }

    suspend fun clear() {
        ready.await()
        synchronized(lock) { mutableEntries.value = emptyList() }
        flush()
    }

    suspend fun exportJson(): String {
        ready.await()
        return withContext(Dispatchers.IO) { json.encodeToString(serializer, entries.value) }
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        ready.await()
        fileMutex.withLock {
            val parent = file.absoluteFile.parentFile ?: throw IOException("Brak katalogu historii.")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Nie można utworzyć katalogu historii.")
            val temporary = File(file.path + ".tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(json.encodeToString(serializer, entries.value).toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (error: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally {
                if (temporary.exists() && !temporary.delete()) mutableError.value = "Nie można usunąć pliku tymczasowego historii."
            }
        }
    }

    private fun sanitize(entry: ConnectionHistoryEntry): ConnectionHistoryEntry? =
        if (entry.timestampMillis < 0L || entry.message.isBlank()) null else entry.copy(
            state = entry.state.take(40), message = entry.message.take(1_000), deviceName = entry.deviceName?.take(160))

    companion object { const val MAXIMUM_ENTRIES = 200 }
}
