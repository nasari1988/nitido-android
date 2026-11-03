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
import android.view.WindowManager
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nitido.app.databinding.ActivityRestoreBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "Restaurar foto antigua": one button that runs, in order,
 * scratches → color → grain → blur → colorize → enlarge → faces. All on the phone.
 */
/**
 * Everything the Restore screen is working on lives here, outside the screen, so rotating the
 * phone, turning the screen off or switching apps doesn't lose the photo or stop the work.
 * The photo and the result are also written to the app's cache, in case Android closes the app.
 */
object RestoreSession {
    var source: Bitmap? = null
    var result: Bitmap? = null
    var marks: Bitmap? = null
    var sourceName = "foto"
    var job: Job? = null
    @Volatile var cancel = false
    var busy = false
    var status = ""
    var progress = 0
    var ui: RestoreActivity? = null
    // what the processing screen shows
    var live: Bitmap? = null
    var stepName = ""
    var stepIdx = 0
    var stepCount = 1
    var stepFrac = 0f
    var totalFrac = 0f
    var piece: String? = null
    var etaSec: Float? = null
    var t0 = 0L
    val aiThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    fun srcFile(ctx: android.content.Context) = java.io.File(ctx.cacheDir, "restore_src.jpg")
    fun outFile(ctx: android.content.Context) = java.io.File(ctx.cacheDir, "restore_out.jpg")
}

class RestoreActivity : AppCompatActivity() {

    lateinit var b: ActivityRestoreBinding
    lateinit var proc: ProcessScreen
    private val S = RestoreSession
    private val aiThread get() = S.aiThread
    private var source: Bitmap? get() = S.source; set(v) { S.source = v }
    private var result: Bitmap? get() = S.result; set(v) { S.result = v }
    private var marks: Bitmap? get() = S.marks; set(v) { S.marks = v }
    private var sourceName: String get() = S.sourceName; set(v) { S.sourceName = v }
    private var job: Job? get() = S.job; set(v) { S.job = v }
    private var cancel: Boolean get() = S.cancel; set(v) { S.cancel = v }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri)
    }

    private val maskLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) { marks = MaskHolder.mask; updateUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRestoreBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.back.setOnClickListener { finish() }
        b.pick.setOnClickListener { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        b.go.setOnClickListener {
            if (job?.isActive == true) { cancel = true; if (SdNative.loadError == null) SdNative.cancel() } else restore()
        }
        b.save.setOnClickListener { save() }
        b.packDownload.setOnClickListener { if (job?.isActive == true) cancel = true else downloadPack() }
        b.marks.setOnClickListener {
            val photo = source ?: return@setOnClickListener
            MaskHolder.photo = photo
            MaskHolder.mask = marks
            maskLauncher.launch(Intent(this, MaskActivity::class.java))
        }
        b.marksClear.setOnClickListener { marks = null; updateUi() }
        b.optFaces.setOnCheckedChangeListener { _, _ -> updateUi() }
        b.optDetail.setOnCheckedChangeListener { _, _ -> updateUi() }
        b.optDeblur.setOnCheckedChangeListener { _, _ -> updateUi() }
        for (sw in listOf(b.optCrop, b.optColor, b.optDenoise, b.optColorize, b.optGpu)) sw.setOnCheckedChangeListener { _, _ -> updateUi() }
        b.scaleGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) updateUi() }
        b.sharpValue.text = "${b.sharpAmount.value.roundToInt()}%"
        b.sharpAmount.addOnChangeListener { _, v, _ -> b.sharpValue.text = "${v.roundToInt()}%" }
        b.detailValue.text = "${b.detailAmount.value.roundToInt()}%"
        b.detailAmount.addOnChangeListener { _, v, _ -> b.detailValue.text = "${v.roundToInt()}%" }
        b.fidelityValue.text = "${b.fidelity.value.roundToInt()}%"
        b.fidelity.addOnChangeListener { _, v, _ -> b.fidelityValue.text = "${v.roundToInt()}%" }
        proc = ProcessScreen(this) { cancel = true; if (SdNative.loadError == null) SdNative.cancel() }
        S.ui = this
        if (source == null) reloadFromDisk() else render()
    }

    override fun onDestroy() {
        if (S.ui === this) S.ui = null
        super.onDestroy()
    }

    /** Shows whatever the session holds: photo, result, progress, messages. */
    fun render() {
        b.compare.before = source
        b.compare.after = result
        b.status.text = S.status
        b.go.text = getString(if (S.busy) R.string.stop else R.string.restore_go)
        b.go.isEnabled = source != null
        b.progress.visibility = if (S.busy) View.VISIBLE else View.INVISIBLE
        b.progress.progress = S.progress
        b.pick.isEnabled = !S.busy
        b.save.isEnabled = !S.busy && result != null
        b.packDownload.isEnabled = !S.busy
        if (S.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateUi()
        if (S.busy) b.marks.isEnabled = false
        if (S.busy) {
            proc.show(getString(R.string.proc_restoring), S.live ?: source, S.t0)
            proc.update(S.stepName, S.stepIdx, S.stepCount, S.stepFrac, S.totalFrac, S.piece, S.etaSec)
        } else proc.hide()
    }

    /** Sends the picture being worked on to the processing screen. */
    fun livePreview(bmp: Bitmap, force: Boolean = false) {
        if (force) S.live = bmp
        S.ui?.proc?.preview(bmp, force)
    }

    private fun reloadFromDisk() {
        render()
        val app = applicationContext
        lifecycleScope.launch {
            val (src, out) = withContext(Dispatchers.IO) {
                fun read(f: java.io.File) = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path) else null
                read(RestoreSession.srcFile(app)) to read(RestoreSession.outFile(app))
            }
            if (src == null || source != null) return@launch
            source = src
            result = out
            sourceName = getSharedPreferences("nitido", MODE_PRIVATE).getString("restore_name", "foto") ?: "foto"
            render()
        }
    }

    /** Remembers the photo (or result) on disk, in the background. */
    private fun persist(bmp: Bitmap, file: java.io.File) {
        BgWork.scope.launch(Dispatchers.IO) {
            try {
                val tmp = java.io.File(file.path + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it) }
                tmp.renameTo(file)
            } catch (_: Throwable) { }
        }
    }

    private fun updateUi() {
        val ready = RestorePack.isReady(this)
        fun has(n: String) = RestorePack.file(this, n) != null
        val partial = ready && listOf(RestorePack.DENOISE, RestorePack.DEBLUR, RestorePack.FACES, RestorePack.LAMA, RestorePack.COLOR).any { !has(it) }
        b.packCard.visibility = if (!ready || partial) View.VISIBLE else View.GONE
        b.packInfo.text = getString(if (partial) R.string.restore_pack_partial else R.string.restore_pack_info)
        if (job?.isActive != true) b.packDownload.text = getString(if (partial) R.string.restore_pack_update else R.string.restore_pack_download)
        b.optDenoise.isEnabled = has(RestorePack.DENOISE)
        if (!has(RestorePack.DENOISE)) b.optDenoise.isChecked = false
        b.optColorize.isEnabled = has(RestorePack.COLOR)
        if (!has(RestorePack.COLOR)) b.optColorize.isChecked = false
        b.marks.isEnabled = source != null && has(RestorePack.LAMA)
        b.marks.text = getString(if (marks != null) R.string.restore_marks_edit else R.string.restore_marks)
        b.marksClear.visibility = if (marks != null) View.VISIBLE else View.GONE
        b.sharpRow.visibility = if (b.optDeblur.isChecked) View.VISIBLE else View.GONE
        b.estimate.text = estimateText() ?: ""
        val sd = SdDetail.pickModel(this)
        b.optDetail.isEnabled = sd != null
        if (sd == null) b.optDetail.isChecked = false
        b.detailRow.visibility = if (b.optDetail.isChecked) View.VISIBLE else View.GONE
        b.detailHint.text = if (sd == null) getString(R.string.restore_detail_none)
            else getString(R.string.restore_detail_hint, ModelCatalog.displayName(sd))
        val faceModel = has(RestorePack.FACES)
        b.fidelityRow.visibility = if (b.optFaces.isChecked && faceModel) View.VISIBLE else View.GONE
    }

    private fun setStatus(s: String) {
        S.status = s
        S.ui?.let { a -> a.runOnUiThread { if (!a.isDestroyed) a.b.status.text = s } }
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val long = max(info.size.width, info.size.height)
                        if (long > 4096) {
                            val k = 4096f / long
                            dec.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt())
                        }
                    }.copy(Bitmap.Config.ARGB_8888, false)
                } catch (t: Throwable) { null }
            }
            if (bmp == null) { setStatus(getString(R.string.err_open)); return@launch }
            sourceName = try {
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0).substringBeforeLast('.') else null
                } ?: "foto"
            } catch (t: Throwable) { "foto" }
            source = bmp
            result = null
            marks = null
            RestoreSession.outFile(applicationContext).delete()
            persist(bmp, RestoreSession.srcFile(applicationContext))
            getSharedPreferences("nitido", MODE_PRIVATE).edit().putString("restore_name", sourceName).apply()
            b.compare.resetView()
            render()
            val gray = withContext(Dispatchers.Default) { RestoreTools.isGrayscale(bmp) }
            if (gray && b.optColorize.isEnabled) b.optColorize.isChecked = true
            setStatus(getString(if (gray) R.string.restore_loaded_bw else R.string.restore_loaded))
            updateUi()
        }
    }

    private fun downloadPack() {
        cancel = false
        b.packDownload.text = getString(R.string.stop)
        val app = applicationContext
        job = BgWork.scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    RestorePack.download(app, { cancel }) { done, total ->
                        setStatus(app.getString(R.string.restore_pack_dl, done / 1_000_000, total / 1_000_000))
                    }
                    null
                } catch (t: Throwable) { t.message ?: t.javaClass.simpleName }
            }
            setStatus(when {
                err == null -> getString(R.string.restore_pack_ready)
                cancel -> getString(R.string.cancelled)
                err.contains("no encontrado") -> getString(R.string.restore_pack_missing)
                else -> getString(R.string.restore_pack_error, err)
            })
            S.ui?.render()
        }
    }

    /** The steps the current options will run, in order (same codes as the progress steps). */
    private fun plannedStages(): List<String> {
        val scale = when (b.scaleGroup.checkedButtonId) { R.id.rScale1 -> 1; R.id.rScale4 -> 4; else -> 2 }
        val faces = b.optFaces.isChecked && (RestorePack.file(this, RestorePack.FACES) != null || FaceRestorer.isReady(this))
        return listOfNotNull(
            "m".takeIf { marks != null && RestorePack.file(this, RestorePack.LAMA) != null }, "c".takeIf { b.optColor.isChecked },
            "n".takeIf { b.optDenoise.isChecked }, "k".takeIf { b.optColorize.isChecked }, "d".takeIf { b.optDeblur.isChecked },
            "s".takeIf { b.optDetail.isChecked && SdDetail.pickModel(this) != null }, "u".takeIf { scale > 1 }, "f".takeIf { faces })
    }

    private fun workMp(scale: Int): Float {
        val s = source ?: return 0f
        val cap = when (scale) { 4 -> 1024; 2 -> 1600; else -> 2400 }
        val k = minOf(1f, cap.toFloat() / max(s.width, s.height))
        return s.width * k * s.height * k / 1e6f
    }

    private fun estimateText(): String? {
        if (source == null) return null
        val scale = when (b.scaleGroup.checkedButtonId) { R.id.rScale1 -> 1; R.id.rScale4 -> 4; else -> 2 }
        val stages = plannedStages()
        if (stages.isEmpty()) return null
        val gpu = b.optGpu.isChecked
        val sec = StageTimes.estimate(this, stages, gpu, workMp(scale), scale)
        val learned = stages.all { StageTimes.learned(this, it, gpu) }
        return getString(if (learned) R.string.restore_estimate else R.string.restore_estimate_first, StageTimes.format(sec))
    }

    private fun restore(confirmed: Boolean = false) {
        if (BgWork.busy) { setStatus(getString(R.string.busy_body, BgWork.currentTitle())); return }
        if (!confirmed) {
            val (level, charging) = StageTimes.battery(this)
            if (level < 30 && !charging) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.battery_title, level))
                    .setMessage(getString(R.string.battery_body,
                        StageTimes.format(StageTimes.estimate(this, plannedStages(), b.optGpu.isChecked,
                            workMp(when (b.scaleGroup.checkedButtonId) { R.id.rScale1 -> 1; R.id.rScale4 -> 4; else -> 2 }),
                            when (b.scaleGroup.checkedButtonId) { R.id.rScale1 -> 1; R.id.rScale4 -> 4; else -> 2 }))))
                    .setPositiveButton(R.string.battery_go) { _, _ -> restore(true) }
                    .setNegativeButton(R.string.battery_wait, null)
                    .show()
                return
            }
        }
        val src0 = source ?: return
        val gpu = b.optGpu.isChecked
        val scale = when (b.scaleGroup.checkedButtonId) { R.id.rScale1 -> 1; R.id.rScale4 -> 4; else -> 2 }
        val doMarks = marks != null && RestorePack.file(this, RestorePack.LAMA) != null
        val doColor = b.optColor.isChecked
        val doDenoise = b.optDenoise.isChecked
        val doDeblur = b.optDeblur.isChecked
        val doColorize = b.optColorize.isChecked
        val doFaces = b.optFaces.isChecked
        val detailModel = if (b.optDetail.isChecked) SdDetail.pickModel(this) else null
        val detailStrength = b.detailAmount.value / 100f
        val sharpAmount = b.sharpAmount.value / 100f
        val fidelity = b.fidelity.value / 100f
        val codeFormer = RestorePack.file(this, RestorePack.FACES)
        val useFaces = doFaces && (codeFormer != null || FaceRestorer.isReady(this))
        var markMask = marks

        // big photos are reduced first: old prints don't hold more detail than this
        val cap = when (scale) { 4 -> 1024; 2 -> 1600; else -> 2400 }
        val long = max(src0.width, src0.height)
        var src = if (long > cap) Bitmap.createScaledBitmap(src0, src0.width * cap / long, src0.height * cap / long, true) else src0
        // the strips of scanner / table around the print go first, so they don't upset the colors
        if (b.optCrop.isChecked) {
            val r = RestoreTools.borderCrop(src)
            if (r.width() < src.width || r.height() < src.height) {
                markMask = markMask?.let { mm ->
                    val k = mm.width.toFloat() / src.width
                    Bitmap.createBitmap(mm, (r.left * k).toInt(), (r.top * k).toInt(),
                        (r.width() * k).toInt().coerceAtMost(mm.width - (r.left * k).toInt()),
                        (r.height() * k).toInt().coerceAtMost(mm.height - (r.top * k).toInt()))
                }
                src = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
            }
        }
        val mp = src.width * src.height / 1e6f

        val stages = listOfNotNull(
            "m".takeIf { doMarks }, "c".takeIf { doColor }, "n".takeIf { doDenoise }, "k".takeIf { doColorize },
            "d".takeIf { doDeblur }, "s".takeIf { detailModel != null }, "u".takeIf { scale > 1 }, "f".takeIf { useFaces })
        if (stages.isEmpty()) { setStatus(getString(R.string.restore_nothing)); return }
        val t0 = SystemClock.elapsedRealtime()
        // measured time of each step, to make the next estimates better
        val took = HashMap<String, Long>()
        // expected seconds of each step on this phone (learned), for the time left
        val expect = stages.associateWith { st ->
            StageTimes.perMp(applicationContext, st, gpu) * (if (st == "f") mp * scale * scale else mp)
        }
        var curStage: String? = null
        var curSince = 0L
        fun report(stage: String, p: Float, label: String, piece: String? = null) {
            if (stage != curStage) {
                val now = SystemClock.elapsedRealtime()
                curStage?.let { took[it] = (took[it] ?: 0L) + (now - curSince) }
                curStage = stage; curSince = now
            }
            val idx = stages.indexOf(stage)
            val total = (idx + p) / stages.size
            BgWork.update(applicationContext, label, (total * 100).roundToInt())
            S.progress = (total * 1000).roundToInt()
            S.status = label
            S.stepName = label.trimEnd('…', '.'); S.stepIdx = idx; S.stepCount = stages.size
            S.stepFrac = p; S.totalFrac = total; S.piece = piece
            // time left = rest of this step + the steps after it, scaled by how this run compares
            // with what was expected so far
            val now = SystemClock.elapsedRealtime()
            val doneExpected = stages.take(idx).sumOf { (expect[it] ?: 0f).toDouble() }.toFloat() + p * (expect[stage] ?: 0f)
            val ratio = if (doneExpected > 20f) ((now - t0) / 1000f / doneExpected).coerceIn(0.3f, 3f) else 1f
            val left = ((1 - p) * (expect[stage] ?: 0f) + stages.drop(idx + 1).sumOf { (expect[it] ?: 0f).toDouble() }.toFloat()) * ratio
            S.etaSec = left
            S.ui?.proc?.update(S.stepName, idx, stages.size, p, total, piece, left)
        }
        fun Upscaler.live(): Upscaler { onTile = { bmp -> S.ui?.livePreview(bmp) }; return this }

        cancel = false
        S.busy = true
        S.progress = 0
        S.t0 = SystemClock.elapsedRealtime()
        S.live = src
        S.stepName = ""; S.stepFrac = 0f; S.totalFrac = 0f; S.piece = null
        S.etaSec = StageTimes.estimate(applicationContext, stages, gpu, mp, scale)
        render()
        BgWork.start(this, getString(R.string.notif_restoring), RestoreActivity::class.java) {
            cancel = true
            if (SdNative.loadError == null) SdNative.cancel()
        }
        val appCtx = applicationContext
        job = BgWork.scope.launch {
            var facesFound = -1
            var error: String? = null
            val out = withContext(aiThread) {
                try {
                    var img: Bitmap = src
                    if (doMarks) {
                        val label = appCtx.getString(R.string.step_marks)
                        report("m", 0f, label)
                        img = RestoreTools.removeMarks(applicationContext, RestorePack.file(applicationContext, RestorePack.LAMA)!!,
                            img, markMask!!, gpu, { p -> report("m", p, label) }, { cancel }) ?: return@withContext null
                    }
                    if (doColor) {
                        report("c", 0f, appCtx.getString(R.string.step_color))
                        img = RestoreTools.fixColor(img, saturate = !doColorize,
                            gentle = doColorize || RestoreTools.isGrayscale(img) || RestoreTools.isSepia(img))
                        S.ui?.livePreview(img, true)
                    }
                    if (doDenoise) {
                        val label = appCtx.getString(R.string.step_denoise)
                        Upscaler(applicationContext, if (gpu) Engine.GPU else Engine.CPU,
                            RestorePack.file(applicationContext, RestorePack.DENOISE)!!.absolutePath, pad = 32, precise = true).live().use { d ->
                            img = d.upscale(img, 1, { p -> report("n", p, label) }, { cancel }) ?: return@withContext null
                        }
                        S.ui?.livePreview(img, true)
                    }
                    if (doColorize) {
                        report("k", 0f, appCtx.getString(R.string.step_colorize))
                        img = RestoreTools.colorize(applicationContext, RestorePack.file(applicationContext, RestorePack.COLOR)!!, img, gpu)
                        S.ui?.livePreview(img, true)
                    }
                    // Faces are later restored from this clean version (grain removed, colors fixed),
                    // not from the sharpened one: sharpening turns blurry skin into blotches.
                    val faceSource = img
                    if (doDeblur) {
                        val label = appCtx.getString(R.string.step_deblur)
                        val before = img
                        // Restormer (trained on out-of-focus photos) when the pack has it, else NAFNet
                        val restormer = RestorePack.file(applicationContext, RestorePack.DEBLUR)
                        val deblur = if (restormer != null)
                            Upscaler(applicationContext, if (gpu) Engine.GPU else Engine.CPU, restormer.absolutePath, pad = 32, precise = true)
                        else Upscaler(applicationContext, if (gpu) Engine.GPU else Engine.CPU, Upscaler.MODEL_DEBLUR, pad = 24)
                        deblur.live().use { d ->
                            img = d.upscale(img, 1, { p -> report("d", p, label) }, { cancel }) ?: return@withContext null
                        }
                        if (sharpAmount < 0.99f) img = RestoreTools.mix(before, img, sharpAmount)
                        S.ui?.livePreview(img, true)
                    }
                    if (detailModel != null) {
                        img = SdDetail.redraw(applicationContext, img, detailModel, detailStrength, gpu,
                            { i, n -> report("s", if (n == 0) 1f else i.toFloat() / n, appCtx.getString(R.string.step_detail_short), appCtx.getString(R.string.proc_piece, minOf(i + 1, n), n)) },
                            { cancel }) ?: return@withContext null
                        S.ui?.livePreview(img, true)
                    }
                    if (scale > 1) {
                        val label = appCtx.getString(R.string.step_upscale)
                        val model = if (MnnTile.hasAsset(applicationContext, Upscaler.MODEL_SIAX)) Upscaler.MODEL_SIAX else Upscaler.MODEL_X4PLUS
                        Upscaler(applicationContext, if (gpu) Engine.GPU else Engine.CPU, model).live().use { u ->
                            img = u.upscale(img, scale, { p -> report("u", p, label) }, { cancel }) ?: return@withContext null
                        }
                        S.ui?.livePreview(img, true)
                    }
                    if (useFaces) {
                        if (!img.isMutable || img === src) img = img.copy(Bitmap.Config.ARGB_8888, true)
                        FaceRestorer(applicationContext, gpu, codeFormer, fidelity).use { fr ->
                            facesFound = fr.restoreAll(img, { i, n ->
                                report("f", if (n == 0) 1f else i.toFloat() / n, appCtx.getString(R.string.step_faces, minOf(i + 1, n), n))
                            }, { cancel }, from = faceSource)
                        }
                    }
                    img
                } catch (t: OutOfMemoryError) {
                    error = "memoria insuficiente"; null
                } catch (t: Throwable) {
                    android.util.Log.e("Nitido", "restore failed", t)
                    error = t.message ?: t.javaClass.simpleName; null
                }
            }
            S.busy = false
            BgWork.finish(appCtx, when {
                out != null -> appCtx.getString(R.string.notif_done_image)
                cancel -> null
                else -> appCtx.getString(R.string.notif_failed)
            })
            if (out == null) {
                S.status = if (cancel) appCtx.getString(R.string.cancelled) else appCtx.getString(R.string.restore_failed, error ?: "?")
                S.ui?.render()
                return@launch
            }
            result = out
            persist(out, RestoreSession.outFile(appCtx))
            curStage?.let { took[it] = (took[it] ?: 0L) + (SystemClock.elapsedRealtime() - curSince) }
            for ((st, ms) in took) StageTimes.record(appCtx, st, gpu, ms / 1000f, mp)
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000
            val notes = mutableListOf(appCtx.getString(R.string.restore_done, secs, out.width, out.height))
            if (facesFound == 0) notes += appCtx.getString(R.string.faces_none)
            if (facesFound > 0) notes += appCtx.getString(R.string.faces_done, facesFound)
            if (doFaces && !useFaces) notes += appCtx.getString(R.string.restore_no_face_model)
            S.status = notes.joinToString(" ")
            S.ui?.render()
        }
    }

    private fun save() {
        val bmp = result ?: return
        b.save.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "${sourceName}_restaurada_${System.currentTimeMillis() / 1000}.jpg")
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
}
