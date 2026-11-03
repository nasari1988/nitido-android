package com.nitido.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.nitido.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    /** All model work happens on this one thread (the GPU delegate requires it). */
    private val aiThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var upscaler: Upscaler? = null
    private lateinit var proc: ProcessScreen

    private var source: Bitmap? = null
    private var result: Bitmap? = null
    private var sourceName = "foto"
    private var job: Job? = null
    @Volatile private var cancel = false

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        proc = ProcessScreen(this) { cancel = true }
        b.pick.setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.go.setOnClickListener { if (job?.isActive == true) cancel = true else enhance() }
        b.save.setOnClickListener { save() }
        b.create.setOnClickListener { startActivity(Intent(this, GenerateActivity::class.java)) }
        b.restore.setOnClickListener { startActivity(Intent(this, RestoreActivity::class.java)) }
        b.models.setOnClickListener { startActivity(Intent(this, ModelsActivity::class.java)) }
        b.engineGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) prepareEngine() }
        setupModelButtons()
        b.mFidelityValue.text = "${b.mFidelity.value.roundToInt()}%"
        b.mFidelity.addOnChangeListener { _, v, _ -> b.mFidelityValue.text = "${v.roundToInt()}%" }
        b.faces.setOnCheckedChangeListener { _, on ->
            b.mFidelityRow.visibility = if (on && RestorePack.file(this, RestorePack.FACES) != null) View.VISIBLE else View.GONE
        }
        b.modelGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) { updateModelNote(); prepareEngine() } }

        b.save.isEnabled = false
        b.go.isEnabled = false
        setStatus(getString(R.string.hint_pick))
        prepareEngine()
        handleShare(intent)
        CrashReport.showIfNeeded(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Photos shared from the gallery ("Compartir → Nítido"). */
    private fun handleShare(intent: Intent?) {
        intent?.getStringExtra(EXTRA_PATH)?.let { path ->
            loadImage(Uri.fromFile(java.io.File(path)))
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        loadImage(uri)
    }

    private fun selectedEngine(): Engine = when (b.engineGroup.checkedButtonId) {
        R.id.engNpu -> Engine.NPU
        R.id.engCpu -> Engine.CPU
        else -> Engine.GPU
    }

    private fun selectedScale(): Int = when (b.scaleGroup.checkedButtonId) {
        R.id.scale4 -> 4
        R.id.scale1 -> 1
        else -> 2
    }

    private fun selectedModel(): String = when (b.modelGroup.checkedButtonId) {
        R.id.mDetail -> Upscaler.MODEL_X4PLUS
        R.id.mUltra -> Upscaler.MODEL_ULTRASHARP
        R.id.mSiax -> Upscaler.MODEL_SIAX
        R.id.mAnime -> Upscaler.MODEL_ANIME
        else -> Upscaler.MODEL_FILE
    }

    /** Shows only the upscalers that came in this build, and a line about the chosen one. */
    private fun setupModelButtons() {
        listOf(R.id.mUltra to Upscaler.MODEL_ULTRASHARP, R.id.mSiax to Upscaler.MODEL_SIAX, R.id.mAnime to Upscaler.MODEL_ANIME)
            .forEach { (id, file) -> findViewById<View>(id).visibility = if (MnnTile.hasAsset(this, file)) View.VISIBLE else View.GONE }
        updateModelNote()
    }

    private fun updateModelNote() {
        b.modelNote.text = getString(when (b.modelGroup.checkedButtonId) {
            R.id.mDetail -> R.string.model_note_detail
            R.id.mUltra -> R.string.model_note_ultra
            R.id.mSiax -> R.string.model_note_siax
            R.id.mAnime -> R.string.model_note_anime
            else -> R.string.model_note_fast
        })
    }

    private fun prepareEngine() {
        val want = selectedEngine()
        val modelFile = selectedModel()
        b.engineState.text = getString(R.string.engine_loading)
        b.go.isEnabled = false
        lifecycleScope.launch {
            val used = withContext(aiThread) {
                try {
                    upscaler?.close()
                    upscaler = Upscaler(applicationContext, want, modelFile)
                    upscaler!!.engine
                } catch (t: Throwable) {
                    upscaler = null
                    null
                }
            }
            if (used == null) {
                b.engineState.text = getString(R.string.engine_failed)
                return@launch
            }
            b.engineState.text = if (used == want) getString(R.string.engine_ready, used.label)
            else getString(R.string.engine_fallback, want.label, used.label)
            b.go.isEnabled = source != null
        }
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val src = ImageDecoder.createSource(contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val long = max(info.size.width, info.size.height)
                        if (long > MAX_PREVIEW) {
                            val k = MAX_PREVIEW.toFloat() / long
                            dec.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt())
                        }
                    }
                } catch (t: Throwable) { null }
            }
            if (bmp == null) { setStatus(getString(R.string.err_open)); return@launch }
            sourceName = queryName(uri)
            source = bmp
            result = null
            b.compare.after = null
            b.compare.before = bmp
            b.save.isEnabled = false
            b.go.isEnabled = upscaler != null
            b.info.text = getString(R.string.info_in, bmp.width, bmp.height)
            setStatus(getString(R.string.hint_ready))
        }
    }

    private fun enhance() {
        if (BgWork.busy) { setStatus(getString(R.string.busy_body, BgWork.currentTitle())); return }
        val src0 = source ?: return
        val up = upscaler ?: return
        val scale = selectedScale()
        val deblur = b.deblur.isChecked
        val faces = b.faces.isChecked
        // CodeFormer (restoration pack) when it's on the phone: more faithful, less "plastic" skin
        val codeFormer = RestorePack.file(this, RestorePack.FACES)
        val fidelity = b.mFidelity.value / 100f
        if (scale == 1 && !deblur && !faces) { setStatus(getString(R.string.nothing_to_do)); return }
        val src = fitForScale(src0, scale)
        if (src !== src0) b.compare.before = src
        b.compare.after = null
        cancel = false
        setBusy(true)
        b.progress.progress = 0
        val note = if (src !== src0) getString(R.string.note_resized, src.width, src.height) + " " else ""
        val t0 = SystemClock.elapsedRealtime()
        val gpu = selectedEngine() != Engine.CPU

        // Each stage gets a share of the progress bar.
        val stages = listOfNotNull(if (deblur) "d" else null, if (scale > 1) "u" else null, if (faces) "f" else null)
        fun report(stage: String, p: Float, label: String) {
            val i = stages.indexOf(stage)
            val total = (i + p) / stages.size
            proc.update(label.trimEnd('…', '.'), i, stages.size, p, total)
            val el = (SystemClock.elapsedRealtime() - t0) / 1000f
            val left = if (total > 0.03f) ((el / total) - el).roundToInt() else -1
            BgWork.update(this@MainActivity, label, (total * 100).roundToInt())
            runOnUiThread {
                b.progress.progress = (total * 1000).roundToInt()
                setStatus(note + label)
                if (left >= 0) b.eta.text = getString(R.string.eta, (total * 100).roundToInt(), left)
            }
        }

        BgWork.start(this, getString(R.string.notif_enhancing), MainActivity::class.java) { cancel = true }
        proc.show(getString(R.string.proc_enhancing), src)
        job = lifecycleScope.launch {
            // Face model is downloaded once (~430 MB).
            if (faces && codeFormer == null && !FaceRestorer.isReady(this@MainActivity)) {
                val err = withContext(Dispatchers.IO) {
                    try {
                        FaceRestorer.download(this@MainActivity, { cancel }) { done, total ->
                            runOnUiThread { setStatus(getString(R.string.faces_dl, done / 1_000_000, total / 1_000_000)) }
                        }
                        null
                    } catch (t: Throwable) { t.message ?: t.javaClass.simpleName }
                }
                if (err != null) {
                    setBusy(false)
                    proc.hide()
                    BgWork.finish(this@MainActivity, null)
                    setStatus(if (cancel) getString(R.string.cancelled) else getString(R.string.faces_dl_error, err))
                    return@launch
                }
            }

            var facesFound = -1
            val out = withContext(aiThread) {
                try {
                    var img: Bitmap = src
                    if (deblur) {
                        val label = getString(R.string.step_deblur)
                        Upscaler(applicationContext, if (gpu) Engine.GPU else Engine.CPU, Upscaler.MODEL_DEBLUR, pad = 24).use { d ->
                            d.onTile = { proc.preview(it) }
                            img = d.upscale(img, 1, { p -> report("d", p, label) }, { cancel }) ?: return@withContext null
                        }
                        proc.preview(img, true)
                    }
                    if (scale > 1) {
                        val label = getString(R.string.step_upscale)
                        up.onTile = { proc.preview(it) }
                        img = up.upscale(img, scale, { p -> report("u", p, label) }, { cancel }) ?: return@withContext null
                        up.onTile = null
                        proc.preview(img, true)
                    }
                    if (faces) {
                        if (img === src) img = src.copy(Bitmap.Config.ARGB_8888, true)
                        else if (!img.isMutable) img = img.copy(Bitmap.Config.ARGB_8888, true)
                        FaceRestorer(applicationContext, gpu, codeFormer, fidelity).use { fr ->
                            facesFound = fr.restoreAll(img, { i, n ->
                                report("f", if (n == 0) 1f else i.toFloat() / n, getString(R.string.step_faces, minOf(i + 1, n), n))
                            }, { cancel })
                        }
                    }
                    img
                } catch (t: OutOfMemoryError) {
                    null
                } catch (t: Throwable) {
                    android.util.Log.e("Nitido", "enhance failed", t)
                    null
                }
            }
            setBusy(false)
            proc.hide()
            BgWork.finish(this@MainActivity, when {
                out != null -> getString(R.string.notif_done_image)
                cancel -> null
                else -> getString(R.string.notif_failed)
            })
            if (out == null) {
                setStatus(getString(if (cancel) R.string.cancelled else R.string.err_run))
                return@launch
            }
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000f
            result = out
            b.compare.after = out
            b.save.isEnabled = true
            b.info.text = getString(R.string.info_out, src.width, src.height, out.width, out.height)
            b.eta.text = getString(R.string.took, secs, up.engine.label)
            val faceNote = when {
                facesFound == 0 -> " " + getString(R.string.faces_none)
                facesFound > 0 -> " " + getString(R.string.faces_done, facesFound)
                else -> ""
            }
            setStatus(getString(R.string.done) + faceNote)
        }
    }

    private fun fitForScale(src: Bitmap, scale: Int): Bitmap {
        val cap = when (scale) { 4 -> MAX_IN_X4; 2 -> MAX_IN_X2; else -> MAX_IN_X1 }
        val long = max(src.width, src.height)
        if (long <= cap) return src
        val k = cap.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
    }

    private fun save() {
        val bmp = result ?: return
        b.save.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "${sourceName}_nitido_${System.currentTimeMillis() / 1000}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nitido")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    true
                } catch (t: Throwable) { false }
            }
            b.save.isEnabled = true
            setStatus(getString(if (ok) R.string.saved else R.string.err_save))
        }
    }

    private fun queryName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).substringBeforeLast('.') else null
        } ?: "foto"
    } catch (t: Throwable) { "foto" }

    private fun setBusy(busy: Boolean) {
        b.go.text = getString(if (busy) R.string.stop else R.string.enhance)
        b.pick.isEnabled = !busy
        b.save.isEnabled = !busy && result != null
        for (i in 0 until b.scaleGroup.childCount) b.scaleGroup.getChildAt(i).isEnabled = !busy
        for (i in 0 until b.engineGroup.childCount) b.engineGroup.getChildAt(i).isEnabled = !busy
        for (i in 0 until b.modelGroup.childCount) b.modelGroup.getChildAt(i).isEnabled = !busy
        b.deblur.isEnabled = !busy
        b.faces.isEnabled = !busy
        b.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        if (busy) b.eta.text = ""
    }

    private fun setStatus(s: String) { b.status.text = s }

    override fun onDestroy() {
        super.onDestroy()
        cancel = true
        val u = upscaler
        upscaler = null
        aiThread.executor.execute { u?.close() }
        aiThread.close()
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_PREVIEW = 4096
        private const val MAX_IN_X4 = 1024
        private const val MAX_IN_X2 = 2048
        private const val MAX_IN_X1 = 4096
    }
}
