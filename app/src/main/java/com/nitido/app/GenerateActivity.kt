package com.nitido.app

import android.content.ContentValues
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.nitido.app.databinding.ActivityGenerateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class GenerateActivity : AppCompatActivity() {

    private lateinit var b: ActivityGenerateBinding
    private lateinit var proc: ProcessScreen
    private var job: Job? = null
    @Volatile private var cancel = false
    private var lastImage: File? = null
    private var base: Bitmap? = null   // photo to start from (any shape), if any
    private lateinit var model: SdModelInfo
    private val frames = mutableListOf<Bitmap>()   // one preview per step, then the final image
    private val handler = Handler(Looper.getMainLooper())
    private var playing: Runnable? = null

    private val prefs by lazy { getSharedPreferences("nitido", MODE_PRIVATE) }

    private var zoneMask: Bitmap? = null   // painted area to redraw (same size as base), if any

    private val maskLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            zoneMask = MaskHolder.mask
            updatePhotoUi()
            base?.let { showZonePreview(it) }
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadBase(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGenerateBinding.inflate(layoutInflater)
        setContentView(b.root)
        proc = ProcessScreen(this) { stopWork() }

        val saved = prefs.getString("model", null)
        model = SdModel.byId(this, saved ?: if (SdModel.isReady(this, "sd15")) "sd15" else null)

        b.back.setOnClickListener { finish() }
        b.modelPick.setOnClickListener { chooseModel() }
        b.download.setOnClickListener { if (job?.isActive == true) cancel = true else download() }
        b.generate.setOnClickListener { if (job?.isActive == true) stopWork() else generate() }
        b.save.setOnClickListener { save() }
        b.upscale.setOnClickListener {
            val f = lastImage ?: return@setOnClickListener
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PATH, f.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        b.useResult.setOnClickListener {
            val f = lastImage ?: return@setOnClickListener
            BitmapFactory.decodeFile(f.absolutePath)?.let { setBase(it) }
        }
        b.pickBase.setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.clearBase.setOnClickListener { setBase(null) }
        b.paintZone.setOnClickListener {
            val photo = base ?: return@setOnClickListener
            MaskHolder.photo = photo
            MaskHolder.mask = zoneMask
            maskLauncher.launch(Intent(this, MaskActivity::class.java))
        }
        b.clearZone.setOnClickListener { zoneMask = null; updatePhotoUi(); base?.let { b.result.setImageBitmap(it) } }
        b.deleteModel.setOnClickListener {
            if (job?.isActive == true) return@setOnClickListener
            if (SdNative.loadError == null) SdNative.release()
            SdModel.delete(this, model.id)
            if (SdModel.customIds(this).contains(model.id)) {
                SdModel.removeCustom(this, model.id)
                model = SdModel.ALL.first()
                prefs.edit().putString("model", model.id).apply()
            }
            refreshModel()
        }

        bindSlider(b.steps, b.stepsValue) { it.roundToInt().toString() }
        b.steps.addOnChangeListener { _, v, fromUser ->
            if (fromUser && v > 50) b.genStatus.text = getString(R.string.sd_steps_note)
        }
        bindSlider(b.strength, b.strengthValue) { "${it.roundToInt()}%" }
        bindSlider(b.guidance, b.guidanceValue) { String.format(Locale.US, "%.1f", it) }
        bindSlider(b.keep, b.keepValue) { String.format(Locale.US, "%.1f", it) }

        b.refine.setOnClickListener { if (job?.isActive == true) stopWork() else refine() }
        b.refine.isEnabled = false
        b.playTimelapse.setOnClickListener { if (playing != null) stopPlayback() else playTimelapse() }
        b.saveTimelapse.setOnClickListener { saveTimelapse() }

        b.save.isEnabled = false
        b.upscale.isEnabled = false
        b.useResult.isEnabled = false
        b.playTimelapse.isEnabled = false
        b.saveTimelapse.isEnabled = false
        refreshModel()

        // show the last result again if this screen was closed while working in the background
        prefs.getString("last_result", null)?.let { File(it) }?.takeIf { it.exists() }?.let { f ->
            lastImage = f
            b.result.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
            b.save.isEnabled = true; b.upscale.isEnabled = true; b.useResult.isEnabled = true; b.refine.isEnabled = true
        }
        askBackgroundPermissions()
        CrashReport.showIfNeeded(this)
    }

    // ---------- background ----------

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Notification permission (Android 13+) and, once, a tip about Xiaomi's battery saver. */
    private fun askBackgroundPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!prefs.getBoolean("bg_tip_shown", false)) {
            prefs.edit().putBoolean("bg_tip_shown", true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bg_tip_title)
                .setMessage(R.string.bg_tip_body)
                .setPositiveButton(R.string.bg_tip_open) { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (_: Throwable) { }
                }
                .setNegativeButton(R.string.bg_tip_later, null)
                .show()
        }
    }

    private fun stopWork() {
        cancel = true
        if (SdNative.loadError == null) SdNative.cancel()
        b.genStatus.text = getString(R.string.stopping)
    }

    private fun startBg(titleRes: Int) {
        BgWork.start(this, getString(titleRes), GenerateActivity::class.java) {
            cancel = true
            if (SdNative.loadError == null) SdNative.cancel()
        }
    }

    // ---------- size ----------

    /** Output size for a new picture: square, or 512×768 / 768×512 on models that allow it. */
    private fun chosenSize(dynamic: Boolean, sdxl: Boolean): Pair<Int, Int> = when {
        !dynamic -> 512 to 512
        sdxl -> when (b.formatGroup.checkedButtonId) {
            R.id.fmtPortrait -> 896 to 1152
            R.id.fmtLandscape -> 1152 to 896
            else -> 1024 to 1024
        }
        b.bigSize.isChecked -> when (b.formatGroup.checkedButtonId) {
            // SD 1.5 "large": ~1.5–2× the pixels; fine-tuned models handle it well
            R.id.fmtPortrait -> 640 to 960
            R.id.fmtLandscape -> 960 to 640
            else -> 768 to 768
        }
        else -> when (b.formatGroup.checkedButtonId) {
            R.id.fmtPortrait -> 512 to 768
            R.id.fmtLandscape -> 768 to 512
            else -> 512 to 512
        }
    }

    /** Size that matches a photo's shape: sides multiples of 64, long side up to 768. */
    private fun sizeForPhoto(w: Int, h: Int, sdxl: Boolean): Pair<Int, Int> {
        val ratio = w.toFloat() / h
        val big = !sdxl && b.bigSize.isChecked
        val sq = if (sdxl) 1024 else if (big) 768 else 512
        val long = if (sdxl) 1024 else if (big) 960 else 768   // SDXL: keep photo edits at most 1024 px for memory
        val minSide = if (sdxl) 768 else 384
        if (ratio in 0.85f..1.18f) return sq to sq
        return if (ratio > 1f) long to ((long / ratio / 64f).roundToInt() * 64).coerceIn(minSide, long)
        else ((long * ratio / 64f).roundToInt() * 64).coerceIn(minSide, long) to long
    }

    // ---------- timelapse ----------

    private fun playTimelapse() {
        if (frames.isEmpty()) return
        b.playTimelapse.text = getString(R.string.sd_stop_play)
        var i = 0
        val r = object : Runnable {
            override fun run() {
                b.result.setImageBitmap(frames[i])
                i++
                if (i < frames.size) handler.postDelayed(this, 160) else stopPlayback()
            }
        }
        playing = r
        handler.post(r)
    }

    private fun stopPlayback() {
        playing?.let { handler.removeCallbacks(it) }
        playing = null
        b.playTimelapse.text = getString(R.string.sd_play)
        frames.lastOrNull()?.let { b.result.setImageBitmap(it) }
    }

    private fun saveTimelapse() {
        if (frames.size < 2) return
        val list = frames.toList()
        b.saveTimelapse.isEnabled = false
        b.genStatus.text = getString(R.string.sd_making_video)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(cacheDir, "timelapse_${System.currentTimeMillis()}.mp4")
                    TimelapseVideo.writeMp4(list, tmp)
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "nitido_timelapse_${System.currentTimeMillis() / 1000}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Nitido")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                    contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    tmp.delete()
                    true
                } catch (t: Throwable) { false }
            }
            b.saveTimelapse.isEnabled = true
            b.genStatus.text = getString(if (ok) R.string.sd_video_saved else R.string.sd_video_error)
        }
    }

    private fun bindSlider(s: Slider, label: TextView, fmt: (Float) -> String) {
        label.text = fmt(s.value)
        s.addOnChangeListener { _, v, _ -> label.text = fmt(v) }
    }

    // ---------- models ----------

    private fun chooseModel() {
        if (job?.isActive == true) return
        b.modelPick.isEnabled = false
        b.modelDesc.text = getString(R.string.cat_loading)
        lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) {
                try { Result.success(ModelCatalog.fetchRemote()) } catch (t: Throwable) { Result.failure(t) }
            }
            b.modelPick.isEnabled = true
            b.modelDesc.text = model.description
            showCatalog(remote.getOrNull(), remote.exceptionOrNull())
        }
    }

    /** One list with three groups: on the phone, converted in GitHub, and recommended to convert. */
    private fun showCatalog(remote: List<RemoteModel>?, error: Throwable?) {
        val dp = resources.displayMetrics.density
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sd_model)
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .create()

        fun header(text: String) = list.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.muted))
            textSize = 12f
            isAllCaps = true
            letterSpacing = 0.08f
            setPadding((12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
        })
        fun row(title: String, sub: String, current: Boolean = false, onClick: () -> Unit) = list.addView(TextView(this).apply {
            text = android.text.SpannableStringBuilder().apply {
                val start = length
                append(if (current) "● $title" else title)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
                append("\n")
                val s2 = length
                append(sub)
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.muted)), s2, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.88f), s2, length, 0)
            }
            setTextColor(getColor(R.color.fg))
            textSize = 15f
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { dialog.dismiss(); onClick() }
        })

        // 1) on the phone
        val local = ModelCatalog.local(this)
        header(getString(R.string.cat_on_phone))
        if (local.isEmpty()) row(getString(R.string.cat_none), "") {}
        for (id in local) {
            val info = SdModel.byId(this, id)
            val extra = listOfNotNull(
                ModelCatalog.description(id),
                if (SdModel.isSdxl(this, id)) "SDXL" else null,
                SdModel.lora(this, id)?.let { getString(R.string.cat_lora) },
                if (SdModel.isDynamic(this, id)) getString(R.string.cat_formats) else null,
                SdModel.precision(this, id)?.let { getString(R.string.cat_bits, it) },
                ModelCatalog.gb(ModelCatalog.sizeOnPhone(this, id)),
            ).joinToString(" · ")
            row("${info.name}  ($id)", extra, current = id == model.id) { useModel(id) }
        }

        // 2) converted in GitHub, not on the phone yet
        header(getString(R.string.cat_in_github))
        when {
            remote == null -> row(getString(R.string.cat_offline), error?.message ?: "") {}
            else -> {
                val pending = remote.filter { it.id !in local }
                if (pending.isEmpty()) row(getString(R.string.cat_all_downloaded), "") {}
                for (r in pending) {
                    val extra = listOfNotNull(ModelCatalog.description(r.id), ModelCatalog.gb(r.sizeBytes),
                        if (r.date.isNotEmpty()) getString(R.string.cat_converted_on, r.date) else null).joinToString(" · ")
                    row("${ModelCatalog.displayName(r.id)}  (${r.id})", extra) { useModel(r.id) }
                }
            }
        }

        // 3) recommended, not converted yet
        val have = (remote?.map { it.id } ?: emptyList()) + local
        val recs = ModelCatalog.RECOMMENDED.filter { it.id !in have }
        if (recs.isNotEmpty()) {
            header(getString(R.string.cat_recommended))
            for (r in recs) row(r.name, r.description + " · " + getString(R.string.cat_tap_to_convert)) { showConvertHelp(r) }
        }

        header(getString(R.string.cat_other))
        row(getString(R.string.sd_add_model), getString(R.string.cat_add_by_name)) { addModel() }
        dialog.show()
    }

    private fun useModel(id: String) {
        if (SdModel.ALL.none { it.id == id }) SdModel.addCustom(this, id)
        selectModel(SdModel.byId(this, id))
    }

    /** How to convert a recommended model, with the exact link and name ready to copy. */
    private fun showConvertHelp(r: Recommendation) {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        val msg = getString(R.string.cat_convert_steps, r.link, r.id, r.precision)
        MaterialAlertDialogBuilder(this)
            .setTitle(r.name)
            .setMessage(msg)
            .setPositiveButton(R.string.cat_open_github) { _, _ ->
                clip.setPrimaryClip(android.content.ClipData.newPlainText("enlace", r.link))
                android.widget.Toast.makeText(this, R.string.cat_link_copied, android.widget.Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelCatalog.CONVERT_PAGE))) } catch (_: Throwable) { }
            }
            .setNeutralButton(R.string.cat_copy_link) { _, _ ->
                clip.setPrimaryClip(android.content.ClipData.newPlainText("enlace", r.link))
                android.widget.Toast.makeText(this, R.string.cat_link_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun selectModel(m: SdModelInfo) {
        model = m
        prefs.edit().putString("model", model.id).apply()
        refreshModel()
    }

    private fun addModel() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.sd_add_hint)
            setSingleLine()
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sd_add_title)
            .setMessage(R.string.sd_add_message)
            .setView(box)
            .setPositiveButton(R.string.sd_add_ok) { _, _ ->
                val id = SdModel.cleanId(input.text?.toString().orEmpty())
                if (id.isEmpty()) return@setPositiveButton
                SdModel.addCustom(this, id)
                selectModel(SdModel.byId(this, id))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshModel() {
        val ready = SdModel.isReady(this, model.id)
        b.modelPick.text = getString(R.string.sd_model_button, model.name)
        b.modelDesc.text = model.description
        b.setupCard.visibility = if (ready) View.GONE else View.VISIBLE
        b.createPanel.visibility = if (ready) View.VISIBLE else View.GONE
        b.deleteModel.visibility = if (ready) View.VISIBLE else View.GONE
        b.deleteModel.text = getString(R.string.sd_delete_model, model.name)
        if (!ready) {
            b.setupTitle.text = getString(R.string.sd_setup_title, model.name)
            b.dlStatus.text = getString(R.string.sd_dl_intro, model.approxMb)
        }
        val editor = model.mode == 1
        val dyn = ready && SdModel.isDynamic(this, model.id)
        b.formatGroup.isEnabled = dyn
        for (i in 0 until b.formatGroup.childCount) b.formatGroup.getChildAt(i).isEnabled = dyn
        if (!dyn) b.formatGroup.check(R.id.fmtSquare)
        b.formatNote.visibility = if (ready && !dyn && !editor) View.VISIBLE else View.GONE
        val canBig = dyn && !editor && !SdModel.isSdxl(this, model.id)
        b.bigSize.visibility = if (canBig) View.VISIBLE else View.GONE
        if (!canBig) b.bigSize.isChecked = false
        b.formatRow.visibility = if (editor) View.GONE else View.VISIBLE
        b.promptLayout.hint = getString(if (editor) R.string.sd_instruction_hint else R.string.sd_prompt_hint)
        b.negativeLayout.visibility = if (editor) View.GONE else View.VISIBLE
        updatePhotoUi()
    }

    private fun download() {
        cancel = false
        val m = model
        b.download.text = getString(R.string.stop)
        b.dlProgress.visibility = View.VISIBLE
        b.modelPick.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        BgWork.start(this, getString(R.string.notif_downloading), GenerateActivity::class.java) { cancel = true }
        job = BgWork.scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    SdModel.download(this@GenerateActivity, m.id, { cancel }) { done, total ->
                        if (total > 0) BgWork.update(this@GenerateActivity,
                            getString(R.string.sd_dl_progress, done / 1_000_000, total / 1_000_000), (done * 100 / total).toInt())
                        runOnUiThread {
                            val p = if (total > 0) done.toFloat() / total else 0f
                            b.dlProgress.progress = (p * 1000).roundToInt()
                            b.dlStatus.text = getString(R.string.sd_dl_progress, done / 1_000_000, total / 1_000_000)
                        }
                    }
                    null
                } catch (e: InterruptedException) {
                    getString(R.string.sd_dl_paused)
                } catch (t: Throwable) {
                    getString(R.string.sd_dl_error, t.message ?: t.javaClass.simpleName)
                }
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            BgWork.finish(this@GenerateActivity, if (err == null) getString(R.string.notif_done_dl) else null)
            b.download.text = getString(R.string.sd_download)
            b.dlProgress.visibility = View.INVISIBLE
            b.modelPick.isEnabled = true
            if (err != null) b.dlStatus.text = err else { model = SdModel.byId(this@GenerateActivity, m.id); refreshModel() }
        }
    }

    // ---------- photo ----------

    /** Load a photo (long side up to 2048 px). It is fitted into 512×512 at generation time. */
    private fun loadBase(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val long = maxOf(info.size.width, info.size.height)
                        if (long > 2048) {
                            val k = 2048f / long
                            dec.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt())
                        }
                    }
                    src.copy(Bitmap.Config.ARGB_8888, false)
                } catch (t: Throwable) { null }
            }
            if (bmp == null) b.genStatus.text = getString(R.string.err_open) else setBase(bmp)
        }
    }

    private fun setBase(bmp: Bitmap?) {
        base = bmp
        zoneMask = null
        b.baseThumb.setImageBitmap(bmp)
        if (bmp != null) b.result.setImageBitmap(bmp)
        updatePhotoUi()
    }

    private fun updatePhotoUi() {
        val has = base != null
        val editor = model.mode == 1
        b.baseThumb.visibility = if (has) View.VISIBLE else View.GONE
        b.clearBase.visibility = if (has) View.VISIBLE else View.GONE
        b.pickBase.text = getString(
            when {
                has -> R.string.sd_change_base
                editor -> R.string.sd_pick_base_required
                else -> R.string.sd_pick_base
            })
        val inpaintModel = model.mode == 2
        val zone = has && zoneMask != null
        b.zoneRow.visibility = if (has) View.VISIBLE else View.GONE
        b.clearZone.visibility = if (zone) View.VISIBLE else View.GONE
        b.paintZone.text = getString(if (zone) R.string.paint_zone_edit else R.string.paint_zone)
        b.strengthRow.visibility = if (has && !editor && (!inpaintModel || zone)) View.VISIBLE else View.GONE
        b.keepRow.visibility = if (editor) View.VISIBLE else View.GONE
        b.photoHint.visibility = if (has || editor || inpaintModel) View.VISIBLE else View.GONE
        b.photoHint.text = getString(when {
            editor -> R.string.sd_keep_hint
            zone -> R.string.paint_zone_on
            inpaintModel -> R.string.sd_need_mask
            else -> R.string.sd_strength_hint
        })
        b.promptLayout.helperText = getString(
            when {
                editor -> R.string.sd_instruction_help
                zone -> R.string.sd_inpaint_prompt_help
                has -> R.string.sd_edit_prompt_help
                else -> R.string.sd_prompt_help
            })
    }

    // ---------- zone (inpainting) ----------

    /** Everything a zone edit needs: the piece of the photo to work on and its masks. */
    private class ZonePlan(
        val crop: Rect, val w: Int, val h: Int, val box: Rect?,
        val image: FloatArray, val masked: FloatArray?, val latMask: FloatArray, val softCrop: Bitmap,
    )

    /** Soft-edged copy of a mask (blurred by shrinking and growing it back). */
    private fun soften(mask: Bitmap, factor: Int = 8): Bitmap {
        val small = Bitmap.createScaledBitmap(mask, maxOf(1, mask.width / factor), maxOf(1, mask.height / factor), true)
        return Bitmap.createScaledBitmap(small, mask.width, mask.height, true)
    }

    private fun alphas(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) px[i] = px[i] ushr 24
        return px
    }

    /**
     * Cuts out the painted area with some margin, scales it to the model's size (so small areas get
     * full detail: "inpaint only masked"), and prepares the photo, masked photo and masks.
     */
    private fun planZone(photo: Bitmap, mask: Bitmap, dynamic: Boolean, sdxl: Boolean, inpaintModel: Boolean, change: Float): ZonePlan? {
        val q = 4
        val sm = Bitmap.createScaledBitmap(mask, maxOf(1, mask.width / q), maxOf(1, mask.height / q), true)
        val a = alphas(sm)
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (y in 0 until sm.height) for (x in 0 until sm.width) if (a[y * sm.width + x] > 20) {
            if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        x0 *= q; y0 *= q; x1 = (x1 + 1) * q; y1 = (y1 + 1) * q
        val pad = maxOf(32, (maxOf(x1 - x0, y1 - y0) * 0.25f).roundToInt())
        var rect = Rect(maxOf(0, x0 - pad), maxOf(0, y0 - pad), minOf(photo.width, x1 + pad), minOf(photo.height, y1 + pad))
        if (rect.width() * rect.height() > 0.6f * photo.width * photo.height) rect = Rect(0, 0, photo.width, photo.height)

        val cropPhoto = Bitmap.createBitmap(photo, rect.left, rect.top, rect.width(), rect.height())
        val cropMask = Bitmap.createBitmap(mask, rect.left, rect.top, rect.width(), rect.height())
        val work: Bitmap; val workMask: Bitmap; val box: Rect?; val w: Int; val h: Int
        if (dynamic) {
            val sz = sizeForPhoto(rect.width(), rect.height(), sdxl)
            w = sz.first; h = sz.second
            work = Bitmap.createScaledBitmap(cropPhoto, w, h, true)
            workMask = Bitmap.createScaledBitmap(cropMask, w, h, true)
            box = null
        } else {
            val (sq, r) = letterbox(cropPhoto)
            work = sq; box = r; w = 512; h = 512
            workMask = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            Canvas(workMask).drawBitmap(cropMask, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val image = toModelInput(work)
        val soft = alphas(soften(workMask, 16))
        var masked: FloatArray? = null
        if (inpaintModel) {
            // The photo the model "sees" around the zone. At 100% change the painted area is hidden
            // (gray), so the model invents something new; with less change it still sees what was
            // there, so it modifies it (same shape, new color/material) instead of replacing it.
            val hard = alphas(workMask)
            val n = w * h
            val keepOld = 1f - change.coerceIn(0f, 1f)
            masked = image.copyOf()
            for (i in 0 until n) if (hard[i] > 127) {
                masked[i] *= keepOld; masked[n + i] *= keepOld; masked[2 * n + i] *= keepOld
            }
        }
        // latent-size mask, a little wider than painted so edges blend
        val lat = Bitmap.createScaledBitmap(soften(workMask, 8), w / 8, h / 8, true)
        val la = alphas(lat)
        val latMask = FloatArray(la.size) { minOf(1f, la[it] / 255f * 1.6f) }
        val softCrop = soften(cropMask, 12)
        return ZonePlan(rect, w, h, box, image, masked, latMask, softCrop)
    }

    /** Puts the redrawn piece back into the full photo, only where painted, with soft edges. */
    private fun compositeZone(photo: Bitmap, plan: ZonePlan, out: Bitmap): Bitmap {
        val piece = Bitmap.createScaledBitmap(unbox(out, plan.box), plan.crop.width(), plan.crop.height(), true)
        val res = photo.copy(Bitmap.Config.ARGB_8888, true)
        val cw = plan.crop.width(); val ch = plan.crop.height()
        val orig = IntArray(cw * ch); res.getPixels(orig, 0, cw, plan.crop.left, plan.crop.top, cw, ch)
        val neu = IntArray(cw * ch); piece.getPixels(neu, 0, cw, 0, 0, cw, ch)
        val am = alphas(plan.softCrop)
        for (i in orig.indices) {
            val t = minOf(1f, am[i] / 255f * 1.3f)
            if (t <= 0f) continue
            val o = orig[i]; val n = neu[i]
            fun mix(s: Int) = (((o shr s) and 0xFF) * (1 - t) + ((n shr s) and 0xFF) * t).roundToInt()
            orig[i] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        res.setPixels(orig, 0, cw, plan.crop.left, plan.crop.top, cw, ch)
        return res
    }

    /** Shows the photo with the painted area tinted, so the user sees what will change. */
    private fun showZonePreview(photo: Bitmap) {
        val m = zoneMask ?: return
        val v = photo.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(v).drawBitmap(m, 0f, 0f, Paint().apply { alpha = 110 })
        b.result.setImageBitmap(v)
    }

    /**
     * The models work on 512×512. Instead of cutting the photo, fit all of it inside the square
     * and fill the borders with a soft, blurred stretch of the same photo (so the AI sees matching
     * colors there). Returns the square and where the photo sits in it.
     */
    private fun letterbox(src: Bitmap): Pair<Bitmap, Rect> {
        val k = 512f / maxOf(src.width, src.height)
        val w = (src.width * k).roundToInt().coerceIn(1, 512)
        val h = (src.height * k).roundToInt().coerceIn(1, 512)
        val rect = Rect((512 - w) / 2, (512 - h) / 2, (512 - w) / 2 + w, (512 - h) / 2 + h)
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val tiny = Bitmap.createScaledBitmap(src, 12, 12, true)
        c.drawBitmap(tiny, null, Rect(0, 0, 512, 512), p)
        tiny.recycle()
        c.drawBitmap(src, null, rect, p)
        return out to rect
    }

    /** Cut the photo's area back out of a square [bmp] (any size) using [rect] from [letterbox]. */
    private fun unbox(bmp: Bitmap, rect: Rect?): Bitmap {
        if (rect == null || (rect.width() >= 512 && rect.height() >= 512)) return bmp
        val k = bmp.width / 512f
        val l = (rect.left * k).roundToInt(); val t = (rect.top * k).roundToInt()
        val w = (rect.width() * k).roundToInt().coerceIn(1, bmp.width - l)
        val h = (rect.height() * k).roundToInt().coerceIn(1, bmp.height - t)
        return Bitmap.createBitmap(bmp, l, t, w, h)
    }

    /** Channel-first RGB floats in [-1, 1], as the VAE encoder expects. */
    private fun toModelInput(bmp: Bitmap): FloatArray {
        val n = bmp.width * bmp.height
        val px = IntArray(n)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val f = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = px[i]
            f[i] = ((p shr 16) and 0xFF) / 127.5f - 1f
            f[n + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
            f[2 * n + i] = (p and 0xFF) / 127.5f - 1f
        }
        return f
    }

    // ---------- generate ----------

    // ---------- prompt ----------

    /** Reads the prompt and the "avoid" text. Weights like (red dress:1.3) are handled by the engine. */
    private fun withPrompts(then: (prompt: String, negative: String) -> Unit) {
        val prompt = b.prompt.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) { b.genStatus.text = getString(R.string.sd_need_prompt); return }
        val negative = if (model.mode == 1) "" else b.negative.text?.toString()?.trim().orEmpty()
        then(prompt, negative)
    }

    /** Only one job at a time: if one is still running (maybe from before), say so and offer to stop it. */
    private fun busyGuard(): Boolean {
        if (!BgWork.busy) return false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.busy_title)
            .setMessage(getString(R.string.busy_body, BgWork.currentTitle()))
            .setPositiveButton(R.string.stop) { _, _ -> BgWork.onStop?.invoke(); if (SdNative.loadError == null) SdNative.cancel() }
            .setNegativeButton(R.string.busy_wait, null)
            .show()
        return true
    }

    private fun generate() { if (!busyGuard()) withPrompts { p, n -> generate(p, n) } }

    private fun generate(prompt: String, negative: String) {
        val m = model
        if (m.mode == 1 && base == null) { b.genStatus.text = getString(R.string.sd_need_photo); return }
        if (m.mode == 2 && (base == null || zoneMask == null)) { b.genStatus.text = getString(R.string.sd_need_mask); return }
        SdNative.loadError?.let { b.genStatus.text = getString(R.string.sd_failed, it.toString()); return }

        val steps = b.steps.value.roundToInt()
        val gpu = b.backendGroup.checkedButtonId != R.id.sdCpu
        val seed = Random.nextInt(1, Int.MAX_VALUE)
        val out = File(cacheDir, "sd_${System.currentTimeMillis()}.jpg")
        val dynamic = SdModel.isDynamic(this, m.id)
        val sdxl = SdModel.isSdxl(this, m.id)
        var size = chosenSize(dynamic, sdxl)
        var box: Rect? = null
        val photoForZone = base
        val zone = if (photoForZone != null && zoneMask != null)
            planZone(photoForZone, zoneMask!!, dynamic, sdxl, m.mode == 2, b.strength.value / 100f) else null
        if (photoForZone != null && zoneMask != null && zone == null) { b.genStatus.text = getString(R.string.mask_empty); return }
        if (zone != null) { size = zone.w to zone.h; box = zone.box }
        val input = if (zone != null) zone.image else base?.let { photo ->
            if (dynamic) {
                // newer models: work directly at the photo's shape
                size = sizeForPhoto(photo.width, photo.height, sdxl)
                toModelInput(Bitmap.createScaledBitmap(photo, size.first, size.second, true))
            } else {
                // older square-only models: fit the whole photo inside 512×512
                val (sq, r) = letterbox(photo)
                box = r
                toModelInput(sq)
            }
        }
        val (outW, outH) = size
        val strength = b.strength.value / 100f
        val guidance = b.guidance.value
        val keep = b.keep.value
        val previewMode = if (b.hqTimelapse.isChecked) SdNative.PREVIEW_FULL else SdNative.PREVIEW_FAST
        cancel = false
        stopPlayback()
        frames.clear()
        base?.let { frames.add(it) }
        b.playTimelapse.isEnabled = false
        b.saveTimelapse.isEnabled = false

        b.generate.text = getString(R.string.stop)
        b.modelPick.isEnabled = false
        b.save.isEnabled = false
        b.upscale.isEnabled = false
        b.useResult.isEnabled = false
        b.refine.isEnabled = false
        b.genProgress.progress = 0
        b.genProgress.visibility = View.VISIBLE
        b.genStatus.text = getString(R.string.sd_loading)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val t0 = SystemClock.elapsedRealtime()
        startBg(R.string.notif_generating)
        proc.show(getString(R.string.proc_generating), base)
        proc.update(getString(R.string.proc_generating), 0, 1, 0f, 0f)

        job = BgWork.scope.launch {
            var usedSampler = sampler()
            var fellBack = false
            val code = withContext(Dispatchers.Default) {
              var attempt = 0
              var c: Int
              do {
                c = try {
                    SdModel.ensureEncoder(this@GenerateActivity, m.id)
                    GpuMeter.track(gpu) { SdNative.generate(
                        SdModel.dir(this@GenerateActivity, m.id).absolutePath,
                        cacheDir.absolutePath,
                        if (gpu) SdNative.BACKEND_OPENCL else SdNative.BACKEND_CPU,
                        m.mode, dynamic, outW, outH, steps, seed, out.absolutePath, prompt, negative,
                        input, strength, guidance, keep, zone?.masked, zone?.latMask, previewMode, usedSampler,
                        object : SdNative.Listener {
                            override fun onProgress(pct: Int) {
                                val el = (SystemClock.elapsedRealtime() - t0) / 1000
                                val txt = getString(R.string.sd_progress, pct, el)
                                BgWork.update(this@GenerateActivity, txt, pct)
                                proc.update(getString(R.string.proc_generating), 0, 1, pct / 100f, pct / 100f)
                                runOnUiThread {
                                    b.genProgress.progress = pct * 10
                                    b.genStatus.text = txt
                                }
                            }

                            override fun onPreview(rgb: ByteArray, width: Int, height: Int) {
                                val bmp = unbox(TimelapseVideo.toBitmap(rgb, width, height), box)
                                proc.preview(bmp, true)
                                runOnUiThread {
                                    frames.add(bmp)
                                    b.result.setImageBitmap(bmp)
                                }
                            }
                        }
                    ) }
                } catch (t: Throwable) {
                    -1
                }
                // numbers overflowed with DPM++: try once more with the classic method
                if (c == SdNative.BAD_UNET && usedSampler == SdNative.SAMPLER_DPM && attempt == 0 && !cancel) {
                    usedSampler = SdNative.SAMPLER_PLMS
                    fellBack = true
                    runOnUiThread { frames.clear(); base?.let { frames.add(it) }; b.genStatus.text = getString(R.string.sd_retry_plms) }
                    attempt++
                    continue
                }
                break
              } while (true)
              c
            }
            val ok = code == 0 && out.exists() && out.length() > 0
            proc.hide()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            b.generate.text = getString(R.string.sd_generate)
            b.modelPick.isEnabled = true
            b.genProgress.visibility = View.INVISIBLE
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000
            if (ok && zone != null && photoForZone != null) {
                // paste the redrawn area back into the whole photo
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(out.absolutePath)?.let { piece ->
                        val full = compositeZone(photoForZone, zone, piece)
                        out.outputStream().use { full.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    }
                }
            } else if (ok && box != null) {
                // keep only the photo's own area, at its own shape
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(out.absolutePath)?.let { sq ->
                        val cut = unbox(sq, box)
                        out.outputStream().use { cut.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    }
                }
            }
            BgWork.finish(this@GenerateActivity, when {
                ok -> getString(R.string.notif_done_image)
                code == SdNative.CANCELLED -> null
                else -> getString(R.string.notif_failed)
            })
            if (ok) {
                b.result.setImageBitmap(BitmapFactory.decodeFile(out.absolutePath))
                lastImage = out
                prefs.edit().putString("last_result", out.absolutePath).apply()
                b.save.isEnabled = true
                b.upscale.isEnabled = true
                b.useResult.isEnabled = true
                b.refine.isEnabled = true
                b.playTimelapse.isEnabled = frames.size > 1
                b.saveTimelapse.isEnabled = frames.size > 1
                b.genStatus.text = getString(R.string.sd_done, secs, if (gpu) "GPU" else "CPU", seed) +
                    (if (fellBack) "\n" + getString(R.string.sd_used_plms) else "")
            } else {
                val why = when (code) {
                    2 -> getString(R.string.sd_err_load)
                    3 -> getString(R.string.sd_err_run)
                    4 -> getString(R.string.sd_no_encoder)
                    5 -> getString(R.string.sd_err_size)
                    6 -> getString(R.string.cancelled)
                    7 -> getString(R.string.sd_bad_text)
                    8 -> getString(R.string.sd_bad_photo)
                    9 -> getString(R.string.sd_bad_unet, if (gpu) "GPU" else "CPU")
                    10 -> getString(R.string.sd_bad_decode)
                    else -> getString(R.string.sd_err_internal, code)
                }
                b.genStatus.text = getString(R.string.sd_failed, why)
            }
        }
    }

    private fun sampler() =
        if (b.samplerGroup.checkedButtonId == R.id.smpPlms) SdNative.SAMPLER_PLMS else SdNative.SAMPLER_DPM

    // ---------- refine detail ----------

    /**
     * "Hires fix" by tiles: enlarge the last result ×2 with Real-ESRGAN, then redraw it in
     * overlapping 512×512 pieces with the same prompt and little change, and blend the pieces.
     * Each piece gets the model's full resolution, so real detail appears (skin, hair, texture).
     */
    private fun refine() { if (!busyGuard()) withPrompts { p, n -> refine(p, n) } }

    private fun refine(prompt: String, negative: String) {
        val m = model
        val f = lastImage ?: return
        if (m.mode == 1) { b.genStatus.text = getString(R.string.sd_refine_editor); return }
        SdNative.loadError?.let { b.genStatus.text = getString(R.string.sd_failed, it.toString()); return }
        val steps = b.steps.value.roundToInt().coerceAtMost(60)
        val guidance = b.guidance.value
        val gpu = b.backendGroup.checkedButtonId != R.id.sdCpu
        val smp = sampler()
        val seed0 = Random.nextInt(1, Int.MAX_VALUE / 2)
        val dynamic = SdModel.isDynamic(this, m.id)
        // SDXL redraws pieces of 768 px: at 1024 its photo reader needs more memory than the phone has
        val T = if (SdModel.isSdxl(this, m.id)) 768 else 512
        val overlap = OVERLAP * T / 512
        cancel = false

        setRefineBusy(true)
        b.genStatus.text = getString(R.string.sd_refine_upscaling)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val t0 = SystemClock.elapsedRealtime()
        startBg(R.string.notif_refining)
        proc.show(getString(R.string.proc_refining), BitmapFactory.decodeFile(f.absolutePath))
        proc.update(getString(R.string.sd_refine_upscaling).trimEnd('…', '.'), 0, 2, 0f, 0f)

        job = BgWork.scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    val src = BitmapFactory.decodeFile(f.absolutePath) ?: return@withContext null
                    // Free the image model's GPU memory first: having it and Real-ESRGAN on the GPU
                    // at the same time can run the phone out of memory.
                    if (SdNative.loadError == null) SdNative.release()
                    System.gc()
                    // 1) enlarge ×2 (Real-ESRGAN, on its own thread for the GPU delegate)
                    var big = java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
                        try {
                            ex.submit<Bitmap?> {
                                Upscaler(applicationContext, if (gpu) Engine.GPU else Engine.CPU).use { up ->
                                    up.upscale(src, 2, {}, { cancel })
                                }
                            }.get()
                        } finally { ex.shutdown() }
                    } ?: return@withContext null
                    if (minOf(big.width, big.height) < T) {
                        val k = T.toFloat() / minOf(big.width, big.height)
                        big = Bitmap.createScaledBitmap(big, (big.width * k).roundToInt(), (big.height * k).roundToInt(), true)
                    }
                    val W = big.width; val H = big.height
                    val srcPx = IntArray(W * H); big.getPixels(srcPx, 0, W, 0, 0, W, H)
                    val acc = FloatArray(W * H * 3); val wsum = FloatArray(W * H)
                    val xs = tilePositions(W, T, overlap); val ys = tilePositions(H, T, overlap)
                    val total = xs.size * ys.size
                    var n = 0
                    val tileFile = File(cacheDir, "refine_tile.jpg")
                    val dir = SdModel.dir(this@GenerateActivity, m.id).absolutePath
                    SdModel.ensureEncoder(this@GenerateActivity, m.id)
                    for (ty in ys) for (tx in xs) {
                        if (cancel) return@withContext null
                        n++
                        val el = (SystemClock.elapsedRealtime() - t0) / 1000
                        val txt = getString(R.string.sd_refine_tile, n, total, el)
                        BgWork.update(this@GenerateActivity, txt, (n - 1) * 100 / total)
                        runOnUiThread {
                            b.genStatus.text = txt
                            b.genProgress.progress = ((n - 1) * 1000) / total
                        }
                        val pieceTxt = getString(R.string.proc_piece, n, total)
                        val redraw = getString(R.string.proc_refining)
                        val tile = Bitmap.createBitmap(big, tx, ty, T, T)
                        val code = GpuMeter.track(gpu) { SdNative.generate(
                            dir, cacheDir.absolutePath, if (gpu) SdNative.BACKEND_OPENCL else SdNative.BACKEND_CPU,
                            0, dynamic, T, T, steps, seed0 + n, tileFile.absolutePath, prompt, negative,
                            toModelInput(tile), REFINE_STRENGTH, guidance, 1.5f, null, null, SdNative.PREVIEW_NONE, smp,
                            object : SdNative.Listener {
                                override fun onProgress(pct: Int) {
                                    runOnUiThread { b.genProgress.progress = ((n - 1) * 1000 + pct * 10) / total }
                                    val frac = (n - 1 + pct / 100f) / total
                                    proc.update(redraw, 1, 2, frac, 0.1f + 0.9f * frac, pieceTxt)
                                }
                                override fun onPreview(rgb: ByteArray, width: Int, height: Int) {}
                            }) }
                        if (code == SdNative.CANCELLED || cancel) return@withContext null
                        if (code != 0) throw IllegalStateException("código $code")
                        val out = BitmapFactory.decodeFile(tileFile.absolutePath) ?: throw IllegalStateException("sin imagen")
                        val px = IntArray(T * T); out.getPixels(px, 0, T, 0, 0, T, T)
                        for (y in 0 until T) for (x in 0 until T) {
                            // feather toward tile edges, except where the tile touches the picture edge
                            val dl = if (tx == 0) 1e4f else x.toFloat()
                            val dr = if (tx + T >= W) 1e4f else (T - 1 - x).toFloat()
                            val dt = if (ty == 0) 1e4f else y.toFloat()
                            val db = if (ty + T >= H) 1e4f else (T - 1 - y).toFloat()
                            val w = (minOf(minOf(dl, dr), minOf(dt, db)) / overlap).coerceIn(0.02f, 1f)
                            val i = (ty + y) * W + (tx + x)
                            val c = px[y * T + x]
                            acc[i * 3] += ((c shr 16) and 0xFF) * w
                            acc[i * 3 + 1] += ((c shr 8) and 0xFF) * w
                            acc[i * 3 + 2] += (c and 0xFF) * w
                            wsum[i] += w
                        }
                        runOnUiThread { b.result.setImageBitmap(out) }   // the piece just redrawn
                        proc.preview(out, true)
                    }
                    val outPx = IntArray(W * H)
                    for (i in 0 until W * H) {
                        val w = wsum[i]
                        outPx[i] = if (w <= 0f) srcPx[i] else android.graphics.Color.rgb(
                            (acc[i * 3] / w).roundToInt().coerceIn(0, 255),
                            (acc[i * 3 + 1] / w).roundToInt().coerceIn(0, 255),
                            (acc[i * 3 + 2] / w).roundToInt().coerceIn(0, 255))
                    }
                    val done = Bitmap.createBitmap(outPx, W, H, Bitmap.Config.ARGB_8888)
                    val file = File(cacheDir, "sd_refined_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { done.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    file to done
                } catch (t: Throwable) {
                    android.util.Log.e("Nitido", "refine failed", t)
                    runOnUiThread { b.genStatus.text = getString(R.string.sd_refine_failed, t.message ?: t.javaClass.simpleName) }
                    null
                }
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            proc.hide()
            setRefineBusy(false)
            b.genProgress.visibility = View.INVISIBLE
            BgWork.finish(this@GenerateActivity, when {
                result != null -> getString(R.string.notif_done_image)
                cancel -> null
                else -> getString(R.string.notif_failed)
            })
            if (result != null) {
                lastImage = result.first
                prefs.edit().putString("last_result", result.first.absolutePath).apply()
                b.result.setImageBitmap(result.second)
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000
                b.genStatus.text = getString(R.string.sd_refine_done, result.second.width, result.second.height, secs)
            } else if (cancel) {
                b.genStatus.text = getString(R.string.cancelled)
            }
        }
    }

    /** Start positions of [t]-px tiles covering [len] with at least [overlap] px of overlap. */
    private fun tilePositions(len: Int, t: Int, overlap: Int): List<Int> {
        if (len <= t) return listOf(0)
        val n = Math.ceil((len - t).toDouble() / (t - overlap)).toInt() + 1
        return (0 until n).map { ((len - t) * it.toFloat() / (n - 1)).roundToInt() }
    }

    private fun setRefineBusy(busy: Boolean) {
        b.generate.isEnabled = !busy
        b.modelPick.isEnabled = !busy
        b.refine.text = getString(if (busy) R.string.stop else R.string.sd_refine)
        b.save.isEnabled = !busy
        b.upscale.isEnabled = !busy
        b.useResult.isEnabled = !busy
        b.genProgress.progress = 0
        b.genProgress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    }

    private fun save() {
        val f = lastImage ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@withContext false
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "nitido_ia_${System.currentTimeMillis() / 1000}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nitido")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    true
                } catch (t: Throwable) { false }
            }
            b.genStatus.text = getString(if (ok) R.string.saved else R.string.err_save)
        }
    }

    companion object {
        private const val REFINE_STRENGTH = 0.32f
        private const val OVERLAP = 96
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        // work keeps going in the background; it can be stopped from the notification
    }
}
