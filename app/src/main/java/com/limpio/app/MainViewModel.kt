package com.limpio.app

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Phase {
    data object Idle : Phase
    data class Scanning(val count: Int, val stage: String = "", val done: Int = 0, val total: Int = 0) : Phase
    data object Ready : Phase
    data class Cleaning(val done: Int, val total: Int) : Phase
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    var result by mutableStateOf<ScanResult?>(null)
        private set
    var selection by mutableStateOf<Map<Category, Set<String>>>(emptyMap())
        private set
    var openCategory by mutableStateOf<Category?>(null)
    var showQuick by mutableStateOf(false)
    var quickSelection by mutableStateOf<Set<String>>(emptySet())
        private set
    var message by mutableStateOf<String?>(null)
    var scannedAt = 0L
        private set
    var scannedVersion = -1
        private set

    /** Si estás en la pantalla principal (no revisando una lista). */
    val onHome: Boolean get() = openCategory == null && !showQuick

    /** Avance de la comparación de videos en segundo plano (null = no está corriendo). */
    var similarProgress by mutableStateOf<Phase.Scanning?>(null)
        private set
    private var scanJob: kotlinx.coroutines.Job? = null
    private var similarJob: kotlinx.coroutines.Job? = null

    var storageTotal by mutableStateOf(0L)
        private set
    var storageFree by mutableStateOf(0L)
        private set

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        try {
            val st = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            storageTotal = st.totalBytes
            storageFree = st.availableBytes
        } catch (_: Exception) {
        }
    }

    fun scan() {
        if (phase is Phase.Scanning || phase is Phase.Cleaning) return
        similarJob?.cancel()
        similarProgress = null
        val scanner = Scanner(getApplication())
        val version = StorageChanges.version
        phase = Phase.Scanning(0)
        scanJob = viewModelScope.launch {
            val ticker = launch {
                while (isActive) {
                    phase = Phase.Scanning(
                        scanner.scanned.get(), scanner.stage,
                        scanner.stepDone.get(), scanner.stepTotal.get()
                    )
                    delay(150)
                }
            }
            val r = try {
                scanner.scan()
            } catch (e: kotlinx.coroutines.CancellationException) {
                ticker.cancel()
                throw e
            } catch (e: Exception) {
                ticker.cancel()
                message = "Error al analizar: ${e.message}"
                phase = if (result != null) Phase.Ready else Phase.Idle
                return@launch
            }
            ticker.cancel()
            result = r
            selection = r.items.mapValues { (_, list) ->
                list.filter { it.preselected }.map { it.path }.toSet()
            }
            quickSelection = quickCleanItems().map { it.path }.toSet()
            scannedAt = System.currentTimeMillis()
            scannedVersion = version
            phase = Phase.Ready
            refreshStorage()
            startSimilar(scanner)
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        phase = if (result != null) Phase.Ready else Phase.Idle
    }

    fun cancelSimilar() {
        similarJob?.cancel()
        similarProgress = null
    }

    /** Videos parecidos: corre después, sin bloquear el resto de los resultados. */
    private fun startSimilar(scanner: Scanner) {
        if (scanner.similarInput.size < 2) return
        similarJob = viewModelScope.launch {
            similarProgress = Phase.Scanning(0, "Preparando videos")
            val ticker = launch {
                while (isActive) {
                    similarProgress = Phase.Scanning(
                        0, scanner.stage, scanner.stepDone.get(), scanner.stepTotal.get()
                    )
                    delay(200)
                }
            }
            try {
                val found = scanner.similarVideos()
                    .filter { File(it.path).exists() }
                    .groupBy { it.group }.values.filter { it.size > 1 }.flatten()
                val r = result ?: return@launch
                result = r.copy(items = r.items + (Category.SIMILAR_VIDEOS to found))
                selection = selection + (Category.SIMILAR_VIDEOS to emptySet())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "No se pudieron comparar los videos: ${e.message}"
            } finally {
                ticker.cancel()
                similarProgress = null
            }
        }
    }

    fun toggleQuick(path: String) {
        quickSelection = if (path in quickSelection) quickSelection - path else quickSelection + path
    }

    fun setAllQuick(on: Boolean) {
        quickSelection = if (on) quickCleanItems().map { it.path }.toSet() else emptySet()
    }

    // ---------- Selección ----------

    fun isSelected(cat: Category, path: String) = selection[cat]?.contains(path) == true

    fun toggle(cat: Category, path: String) {
        val cur = selection[cat].orEmpty()
        selection = selection + (cat to if (path in cur) cur - path else cur + path)
    }

    fun setAll(cat: Category, on: Boolean) {
        val all = result?.items?.get(cat).orEmpty().map { it.path }.toSet()
        selection = selection + (cat to if (on) all else emptySet())
    }

    fun selectedItems(cat: Category): List<CleanItem> {
        val sel = selection[cat].orEmpty()
        return result?.items?.get(cat).orEmpty().filter { it.path in sel }
    }

    fun totalSize(cat: Category) = result?.items?.get(cat).orEmpty().sumOf { it.size }

    /** Lo que borra la limpieza rápida: lo preseleccionado de las categorías seguras. */
    fun quickCleanItems(): List<CleanItem> =
        result?.items.orEmpty()
            .filterKeys { it.safe }
            .values.flatten()
            .filter { it.preselected }
            .distinctBy { it.path }

    // ---------- Borrado ----------

    fun quickClean() = delete(quickCleanItems().filter { it.path in quickSelection })

    fun deleteSelected(cat: Category) = delete(selectedItems(cat))

    private fun delete(items: List<CleanItem>) {
        if (items.isEmpty() || phase is Phase.Cleaning) return
        phase = Phase.Cleaning(0, items.size)
        viewModelScope.launch {
            var freed = 0L
            var failed = 0
            val deleted = HashSet<String>()
            withContext(Dispatchers.IO) {
                items.forEachIndexed { i, item ->
                    val ok = FileOps.deletePermanently(File(item.path))
                    if (ok) {
                        freed += item.size
                        deleted += item.path
                    } else failed++
                    if (i % 20 == 0) phase = Phase.Cleaning(i, items.size)
                }
                // Avisar a la galería para que no muestre archivos fantasma.
                FileOps.rescan(getApplication(), deleted)
            }
            removeFromResult(deleted)
            // Nuestros propios borrados ya se reflejan en la lista: no hace falta reanalizar.
            StorageChanges.bump()
            scannedVersion = StorageChanges.version
            phase = Phase.Ready
            refreshStorage()
            message = buildString {
                append("Eliminaste permanentemente ${formatSize(getApplication(), freed)}")
                if (failed > 0) append(" · $failed elementos no se pudieron borrar")
            }
        }
    }

    private fun removeFromResult(deleted: Set<String>) {
        val r = result ?: return
        if (deleted.isEmpty()) return
        val dirPrefixes = deleted.map { "$it/" }
        fun gone(p: String) = p in deleted || dirPrefixes.any { p.startsWith(it) }

        val newItems = r.items.mapValues { (cat, list) ->
            val kept = list.filterNot { gone(it.path) }
            if (cat in GROUPED_CATEGORIES) {
                // Un grupo con una sola copia ya no es duplicado.
                kept.groupBy { it.group }.values.filter { it.size > 1 }.flatten()
            } else kept
        }
        result = r.copy(items = newItems)
        selection = selection.mapValues { (cat, sel) ->
            val valid = newItems[cat].orEmpty().map { it.path }.toSet()
            sel.filter { it in valid }.toSet()
        }
        val quickValid = quickCleanItems().map { it.path }.toSet()
        quickSelection = quickSelection.filter { it in quickValid }.toSet()
    }
}
