package com.limpio.app

import android.app.Application
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume
import kotlin.random.Random
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

enum class TestStatus(val label: String) { OK("Correcta"), FAIL("Falla"), WARN("Aviso"), SKIP("Omitida") }

data class TestResult(val name: String, val status: TestStatus, val detail: String, val ms: Long)

/**
 * Autoprueba en el teléfono. Trabaja SOLO en una carpeta temporal propia
 * (Download/ArchivosAutoprueba) que se borra al final. De tus archivos solo lee.
 */
class SelfTestViewModel(app: Application) : AndroidViewModel(app) {
    var running by mutableStateOf(false)
        private set
    var current by mutableStateOf("")
        private set
    var results by mutableStateOf<List<TestResult>>(emptyList())
        private set
    var finishedAt by mutableStateOf<Long?>(null)
        private set

    private val ctx: Context get() = getApplication()
    private val sandbox = File(Protection.rootPath, "Download/ArchivosAutoprueba")
    val total = 20

    fun run() {
        if (running) return
        running = true
        results = emptyList()
        finishedAt = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { sandbox.deleteRecursively(); sandbox.mkdirs() }
                runAll()
            } finally {
                withContext(Dispatchers.IO) {
                    sandbox.deleteRecursively()
                    FileOps.rescan(ctx, listOf(sandbox.absolutePath))
                }
                current = ""
                running = false
                finishedAt = System.currentTimeMillis()
            }
        }
    }

    private suspend fun test(name: String, block: suspend () -> Pair<TestStatus, String>) {
        current = name
        val start = System.currentTimeMillis()
        val (status, detail) = try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Throwable) {
            TestStatus.FAIL to "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
        results = results + TestResult(name, status, detail, System.currentTimeMillis() - start)
    }

    private fun ok(d: String = "") = TestStatus.OK to d
    private fun fail(d: String) = TestStatus.FAIL to d
    private fun check(cond: Boolean, okText: String, failText: String) = if (cond) ok(okText) else fail(failText)

    private suspend fun runAll() {
        test("Permiso de todos los archivos") {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
            check(granted && sandbox.canWrite(), "Concedido", "Sin permiso de escritura")
        }

        val data = Random(42).nextBytes(1 shl 20)
        test("Escribir y leer un archivo") {
            val f = File(sandbox, "prueba.bin"); f.writeBytes(data)
            check(f.readBytes().contentEquals(data), "1 MB escrito y leído sin errores", "Lo leído no coincide")
        }

        test("Copiar, mover y renombrar") {
            val src = File(sandbox, "prueba.bin")
            val dirA = File(sandbox, "A").apply { mkdirs() }
            val dirB = File(sandbox, "B").apply { mkdirs() }
            val c1 = FileOps.copy(src, dirA)
            val c2 = FileOps.copy(src, dirA) // mismo nombre → "(1)"
            val moved = c1?.let { FileOps.move(it, dirB) }
            val renamed = File(dirB, "renombrado.bin")
            val okRename = moved?.renameTo(renamed) == true
            when {
                c1 == null || c2 == null -> fail("No se pudo copiar")
                c2.name != "prueba (1).bin" -> fail("Nombre de copia inesperado: ${c2.name}")
                moved == null -> fail("No se pudo mover")
                !okRename -> fail("No se pudo renombrar")
                !renamed.readBytes().contentEquals(data) -> fail("El contenido cambió")
                else -> ok("Copia con (1), mover y renombrar correctos")
            }
        }

        test("Comprimir y extraer ZIP") {
            val zip = File(sandbox, "prueba.zip")
            val okZip = ZipOps.zip(listOf(File(sandbox, "A"), File(sandbox, "B")), zip)
            val out = File(sandbox, "extraido")
            val n = ZipOps.unzip(zip, out)
            val back = File(out, "B/renombrado.bin")
            check(okZip && n == 2 && back.readBytes().contentEquals(data), "$n archivos ida y vuelta", "ZIP=$okZip, extraídos=$n")
        }

        test("Borrado permanente") {
            val d = File(sandbox, "extraido")
            check(FileOps.deletePermanently(d) && !d.exists(), "La carpeta desapareció", "Sigue existiendo")
        }

        test("Protección del sistema") {
            val r = Protection.rootPath
            val bad = listOfNotNull(
                if (!Protection.isProtected("$r/DCIM")) "DCIM" else null,
                if (!Protection.isProtected("$r/Android/media")) "Android/media" else null,
                if (!Protection.isProtected("$r/Pictures/.nomedia")) ".nomedia" else null,
                if (!Protection.isHiddenSystem("$r/Android/data/x")) "Android/data" else null,
                if (Protection.isProtected(File(sandbox, "prueba.bin").absolutePath)) "archivo normal bloqueado" else null,
            )
            check(bad.isEmpty(), "Carpetas del sistema bloqueadas, archivos normales libres", "Mal: ${bad.joinToString()}")
        }

        test("Duplicados idénticos") {
            val dir = File(sandbox, "dup").apply { mkdirs() }
            val bytes = Random(7).nextBytes(300_000)
            val a = File(dir, "original.jpg").apply { writeBytes(bytes); setLastModified(1_000_000_000_000) }
            val b = File(dir, "copia con otro nombre.jpg").apply { writeBytes(bytes) }
            val c = File(dir, "distinto.jpg").apply { writeBytes(Random(8).nextBytes(bytes.size)) }
            val res = DuplicateFinder.find(listOf(a, b, c).map { FileRec(it.absolutePath, it.length(), it.lastModified()) })
            check(
                res.size == 2 && res.none { it.path == c.absolutePath },
                "Encontró la copia y descartó el distinto",
                "Resultado inesperado: ${res.size} elementos"
            )
        }

        val photo = File(sandbox, "foto_autoprueba_${System.currentTimeMillis()}.jpg")
        test("Fotos parecidas (reenviada / recomprimida)") {
            val dir = File(sandbox, "fotos").apply { mkdirs() }
            fun scene(seed: Int, w: Int, h: Int): Bitmap {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                val r = Random(seed)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                cv.drawColor(0xFF000000.toInt() or r.nextInt(0xFFFFFF))
                repeat(14) {
                    p.color = 0xFF000000.toInt() or r.nextInt(0xFFFFFF)
                    cv.drawCircle(r.nextFloat() * w, r.nextFloat() * h, (0.05f + r.nextFloat() * 0.25f) * w, p)
                }
                return bmp
            }
            fun save(b: Bitmap, f: File, q: Int) { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, q, it) }; b.recycle() }
            val orig = File(dir, "IMG_original.jpg"); save(scene(1, 1600, 1200), orig, 95)
            val wa = File(dir, "IMG-WA0001.jpg"); save(scene(1, 800, 600), wa, 55)
            val other = File(dir, "otra.jpg"); save(scene(2, 1600, 1200), other, 95)
            orig.copyTo(photo)
            val recs = listOf(orig, wa, other).map { FileRec(it.absolutePath, it.length(), it.lastModified()) }
            val res = SimilarMedia(ctx, Progress()).similarPhotos(recs)
            val group = res.map { it.path }.toSet()
            when {
                group != setOf(orig.absolutePath, wa.absolutePath) -> fail("Agrupó: ${res.map { it.name }}")
                Keep.pick(Keep.applyRule(res, KeepRule.BEST, true, false), KeepRule.BEST) != orig.absolutePath ->
                    fail("No eligió la de mejor calidad")
                else -> ok("Detectó la recomprimida y conservaría la original")
            }
        }

        test("Miniaturas y lectura de imágenes") {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.absolutePath, o)
            check(o.outWidth == 1600 && o.outHeight == 1200, "1600 × 1200 leído", "Leyó ${o.outWidth} × ${o.outHeight}")
        }

        test("Base de medios y búsqueda") {
            val uri = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Uri?> { c ->
                    MediaScannerConnection.scanFile(ctx, arrayOf(photo.absolutePath), null) { _, u -> if (c.isActive) c.resume(u) }
                }
            }
            val found = MediaQueries.search(ctx, photo.nameWithoutExtension).any { it.path == photo.absolutePath }
            when {
                uri == null -> TestStatus.WARN to "Android tardó en registrar la foto"
                !found -> fail("La búsqueda no encontró la foto registrada")
                else -> ok("Registrada y encontrada por nombre")
            }
        }

        test("Organizar (simulado, no mueve nada)") {
            val expect = mapOf("a.mp3" to "Music", "b.mp4" to "Movies", "c.jpg" to "Pictures", "d.pdf" to "Documents", "e.zip" to "Documents/Comprimidos", "f.apk" to "Download/Instaladores")
            val bad = expect.filter { (name, target) -> OrgRule.entries.firstOrNull { it.kind == FileTypes.kindOf(name) }?.target != target }
            check(bad.isEmpty(), "Cada tipo va a su carpeta", "Mal clasificados: ${bad.keys}")
        }

        test("Lectura de video") {
            val v = MediaQueries.category(ctx, BrowseCategory.VIDEOS).firstOrNull()
            if (v == null) TestStatus.SKIP to "No hay videos en el teléfono" else {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(v.path)
                    val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val frame = mmr.getFrameAtTime(d * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    check(d > 0 && frame != null, "Duración ${d / 1000} s y miniatura leídas (solo lectura)", "No se pudo leer ${v.name}")
                } finally { mmr.release() }
            }
        }

        test("Reproducción de audio (tono de prueba)") {
            val wav = File(sandbox, "tono.wav")
            writeTone(wav, seconds = 2)
            withContext(Dispatchers.Main) {
                val p = ExoPlayer.Builder(ctx).build()
                try {
                    p.volume = 0f
                    p.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
                    p.prepare()
                    val ready = awaitReady(p)
                    val d = p.duration
                    check(ready && d in 1800..2200, "Audio preparado: ${d} ms", "No se pudo preparar (listo=$ready, duración=$d)")
                } finally { p.release() }
            }
        }

        test("Música en segundo plano (servicio)") {
            val okConn = withContext(Dispatchers.Main) { Music.awaitConnected(ctx) }
            check(okConn, "El servicio de música respondió", "El servicio no respondió a tiempo")
        }

        test("Reproductor de video") {
            val v = MediaQueries.category(ctx, BrowseCategory.VIDEOS).firstOrNull()
            if (v == null) TestStatus.SKIP to "No hay videos en el teléfono" else withContext(Dispatchers.Main) {
                val p = ExoPlayer.Builder(ctx).build()
                try {
                    p.volume = 0f
                    p.setMediaItem(videoItem(v.path))
                    p.prepare()
                    val ready = awaitReady(p)
                    val w = p.videoFormat?.width ?: 0
                    val h = p.videoFormat?.height ?: 0
                    check(ready && w > 0, "Preparado ${w}×$h (solo lectura, sin sonido)", "No se pudo preparar ${v.name}")
                } finally { p.release() }
            }
        }

        test("Recordar posición de video") {
            val fake = File(sandbox, "pelicula.mp4").absolutePath
            VideoPositions.save(ctx, fake, 754_000, 6_000_000)
            val got = VideoPositions.get(ctx, fake)
            VideoPositions.save(ctx, fake, 0, 0)
            check(got == 754_000L && VideoPositions.get(ctx, fake) == 0L, "Guarda y olvida la posición", "Leído: $got")
        }

        test("Subtítulos automáticos") {
            val vid = File(sandbox, "pelicula.mp4").apply { writeText("x") }
            File(sandbox, "pelicula.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nHola\n")
            val sub = MediaUtil.findSubtitle(vid)
            val cfg = videoItem(vid.absolutePath).localConfiguration?.subtitleConfigurations.orEmpty()
            check(sub?.name == "pelicula.srt" && cfg.size == 1, "Encontró pelicula.srt y lo agregó al video", "No los encontró")
        }

        test("Fondo de pantalla para el vidrio") {
            val d = try { WallpaperManager.getInstance(ctx).drawable } catch (e: Exception) { null }
            if (d != null) ok("Se puede leer tu fondo") else TestStatus.WARN to "No se pudo leer: se usan colores suaves"
        }

        test("Velocidad de lectura (Descargas y Cámara)") {
            var n = 0
            val t0 = System.currentTimeMillis()
            listOf("Download", "DCIM").forEach { File(Protection.rootPath, it).walkTopDown().forEach { f -> if (f.isFile) n++ } }
            val ms = System.currentTimeMillis() - t0
            ok("$n archivos en $ms ms")
        }

        test("Análisis de limpieza (solo lectura)") {
            val r = Scanner(ctx).scan()
            val junk = r.items.filterKeys { it.safe }.values.flatten().sumOf { it.size }
            ok("${r.filesScanned} archivos en ${"%.1f".format(r.durationMs / 1000.0)} s · basura segura: ${formatSize(ctx, junk)}")
        }
    }

    private suspend fun awaitReady(p: ExoPlayer): Boolean = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Boolean> { c ->
            if (p.playbackState == Player.STATE_READY) { c.resume(true); return@suspendCancellableCoroutine }
            val l = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && c.isActive) { p.removeListener(this); c.resume(true) }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (c.isActive) { p.removeListener(this); c.resume(false) }
                }
            }
            p.addListener(l)
        }
    } ?: false

    /** Tono de 440 Hz en WAV (PCM 16 bits, mono, 44.1 kHz). */
    private fun writeTone(f: File, seconds: Int) {
        val rate = 44_100
        val n = rate * seconds
        val data = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) data.putShort((Math.sin(2 * Math.PI * 440 * i / rate) * 8000).toInt().toShort())
        f.writeBytes(data.array())
    }

    fun report(): String {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        val okCount = results.count { it.status == TestStatus.OK }
        return buildString {
            appendLine("Autoprueba de Archivos $version")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(df.format(Date(finishedAt ?: System.currentTimeMillis())))
            appendLine("Resultado: $okCount de ${results.size} correctas")
            appendLine()
            results.forEach { r ->
                val mark = when (r.status) { TestStatus.OK -> "✔"; TestStatus.FAIL -> "✘"; TestStatus.WARN -> "!"; TestStatus.SKIP -> "–" }
                appendLine("$mark ${r.name} (${r.ms} ms): ${r.detail}")
            }
        }
    }
}

@Composable
fun SelfTestScreen(vm: SelfTestViewModel, onExit: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(enabled = !vm.running) { onExit() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit, enabled = !vm.running) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            Text("Autoprueba", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Column(Modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Revisa en tu teléfono las funciones principales. Usa una carpeta temporal que se " +
                            "borra al terminar: tus archivos solo se leen, nunca se mueven ni se borran.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (vm.running) {
                        LinearProgressIndicator(
                            progress = { vm.results.size / vm.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("${vm.current}…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.run() }) { Text(if (vm.results.isEmpty()) "Iniciar autoprueba" else "Repetir") }
                            if (vm.results.isNotEmpty()) {
                                OutlinedButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, vm.report())
                                    ctx.startActivity(Intent.createChooser(send, "Compartir reporte").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }) {
                                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Compartir reporte")
                                }
                            }
                        }
                    }
                    if (!vm.running && vm.results.isNotEmpty()) {
                        val ok = vm.results.count { it.status == TestStatus.OK }
                        val fails = vm.results.count { it.status == TestStatus.FAIL }
                        Text(
                            if (fails == 0) "Todo bien: $ok de ${vm.results.size} correctas"
                            else "$fails falla(s). Comparte el reporte para revisarlo.",
                            fontWeight = FontWeight.SemiBold,
                            color = if (fails == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            items(vm.results, key = { it.name }) { r ->
                val (icon, color) = when (r.status) {
                    TestStatus.OK -> Icons.Filled.CheckCircle to Color(0xFF2E9E5B)
                    TestStatus.FAIL -> Icons.Filled.Error to MaterialTheme.colorScheme.error
                    TestStatus.WARN -> Icons.Filled.Warning to Color(0xFFE0A100)
                    TestStatus.SKIP -> Icons.Filled.RemoveCircle to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(Modifier.fillMaxWidth().glass().padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(icon, r.status.label, tint = color)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.SemiBold)
                        Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${r.ms} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}
