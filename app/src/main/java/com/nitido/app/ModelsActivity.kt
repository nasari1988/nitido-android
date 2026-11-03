package com.nitido.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A download or update started from the Models screen. Lives outside the screen so leaving it doesn't lose it. */
object ModelsJob {
    @Volatile var id: String? = null
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var error: String? = null
    @Volatile var cancel = false
}

/**
 * Every model the app uses, in one place: what is on the phone, how much space it takes, what
 * can be downloaded, and which ones have an update in GitHub.
 */
class ModelsActivity : AppCompatActivity() {

    private enum class Filter { ALL, LOCAL, UPDATES }

    private lateinit var list: LinearLayout
    private var filter = Filter.ALL
    private var remote: List<RemoteModel> = emptyList()
    private var remoteError = false
    private var checking = false
    private var sizes = Sizes()
    private val ui = Handler(Looper.getMainLooper())

    private data class Sizes(val create: Long = 0, val restore: Long = 0, val enhance: Long = 0)

    private val bg by lazy { ContextCompat.getColor(this, R.color.bg) }
    private val card by lazy { ContextCompat.getColor(this, R.color.stage) }
    private val fg by lazy { ContextCompat.getColor(this, R.color.fg) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.muted) }
    private val accent by lazy { ContextCompat.getColor(this, R.color.accent) }
    private val warn by lazy { ContextCompat.getColor(this, R.color.after) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(list)
        })
        render()
        refreshSizes()
        loadRemote()
    }

    override fun onResume() {
        super.onResume()
        tick()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    /** While a download runs, redraw twice a second so its progress shows. */
    private fun tick() {
        ui.removeCallbacksAndMessages(null)
        if (ModelsJob.id == null) return
        ui.postDelayed({ render(); tick() }, 500)
    }

    // ───────────────────────── data ─────────────────────────

    private fun refreshSizes() = BgWork.scope.launch {
        val s = withContext(Dispatchers.IO) {
            val app = applicationContext
            val create = ModelCatalog.local(app).sumOf { ModelCatalog.sizeOnPhone(app, it) }
            val restore = dirSize(File(filesDir, "models/${RestorePack.ID}"))
            val enhance = BUNDLED.sumOf { assetSize(it.first) } + FaceRestorer.modelFile(app).length()
            Sizes(create, restore, enhance)
        }
        sizes = s
        if (!isDestroyed) render()
    }

    private fun loadRemote() = BgWork.scope.launch {
        val r = withContext(Dispatchers.IO) {
            try { ModelCatalog.fetchRemote() } catch (t: Throwable) { null }
        }
        if (r == null) remoteError = true else { remote = r; remoteError = false }
        if (!isDestroyed) render()
    }

    private fun checkUpdates() {
        if (checking) return
        checking = true
        render()
        BgWork.scope.launch {
            withContext(Dispatchers.IO) { ModelUpdates.checkAll(applicationContext) }
            checking = false
            if (!isDestroyed) render()
        }
    }

    private fun dirSize(d: File): Long = if (!d.exists()) 0L else d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun assetSize(name: String): Long = try {
        assets.openFd(name).use { it.length }
    } catch (t: Throwable) {
        try { assets.open(name).use { s -> var n = 0L; val b = ByteArray(1 shl 16); while (true) { val k = s.read(b); if (k < 0) break; n += k }; n } } catch (t2: Throwable) { 0L }
    }

    private fun updateCount(): Int {
        val ids = ModelCatalog.local(this) + (if (RestorePack.isReady(this)) listOf(RestorePack.ID) else emptyList())
        return ids.count { ModelUpdates.saved(this, it) != null }
    }

    // ───────────────────────── actions ─────────────────────────

    private fun download(id: String, name: String, isUpdate: Boolean) {
        if (ModelsJob.id != null || BgWork.busy) {
            toastDialog(getString(R.string.models_busy)); return
        }
        ModelsJob.id = id; ModelsJob.done = 0; ModelsJob.total = 0; ModelsJob.error = null; ModelsJob.cancel = false
        val app = applicationContext
        BgWork.start(app, getString(if (isUpdate) R.string.models_updating else R.string.notif_downloading), ModelsActivity::class.java) {
            ModelsJob.cancel = true
        }
        tick()
        BgWork.scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    if (isUpdate) ModelUpdates.prepare(app, id)
                    when (id) {
                        GFPGAN -> FaceRestorer.download(app, { ModelsJob.cancel }) { d, t -> progress(app, name, d, t) }
                        else -> {
                            if (id != RestorePack.ID && id != "sd15" && SdModel.ALL.none { it.id == id }) SdModel.addCustom(app, id)
                            SdModel.download(app, id, { ModelsJob.cancel }) { d, t -> progress(app, name, d, t) }
                        }
                    }
                    ModelUpdates.clear(app, id)
                    null
                } catch (e: InterruptedException) { getString(R.string.models_cancelled) } catch (t: Throwable) { t.message ?: t.javaClass.simpleName }
            }
            ModelsJob.id = null
            ModelsJob.error = err
            BgWork.finish(app, if (err == null) getString(R.string.models_done, name) else null)
            if (!isDestroyed) {
                if (err != null) toastDialog(getString(R.string.models_failed, err))
                render(); refreshSizes()
            }
        }
    }

    private fun progress(app: android.content.Context, name: String, d: Long, t: Long) {
        ModelsJob.done = d; ModelsJob.total = t
        if (t > 0) BgWork.update(app, "$name · ${ModelCatalog.gb(d)} / ${ModelCatalog.gb(t)}", (d * 100 / t).toInt())
    }

    private fun confirmDelete(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.models_delete_title, name))
            .setMessage(R.string.models_delete_msg)
            .setNegativeButton(R.string.models_later, null)
            .setPositiveButton(R.string.models_delete) { _, _ ->
                if (id == GFPGAN) FaceRestorer.modelFile(this).delete() else SdModel.delete(this, id)
                ModelUpdates.clear(this, id)
                if (getSharedPreferences("nitido", MODE_PRIVATE).getString("model", null) == id)
                    getSharedPreferences("nitido", MODE_PRIVATE).edit().remove("model").apply()
                render(); refreshSizes()
            }
            .show()
    }

    private fun confirmUpdate(id: String, name: String, u: ModelUpdates.Update) {
        val what = u.reason.split(" · ").joinToString("\n") { "• " + it.replaceFirstChar { c -> c.uppercase() } }
        val size = if (u.full) getString(R.string.models_update_full, ModelCatalog.gb(u.bytes))
                   else getString(R.string.models_update_part, ModelCatalog.gb(u.bytes))
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(getString(R.string.models_update_msg, what, size))
            .setNegativeButton(R.string.models_later, null)
            .setPositiveButton(R.string.models_update_now) { _, _ -> download(id, name, true) }
            .show()
    }

    private fun showConvert(r: Recommendation) {
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setMessage(getString(R.string.models_convert_msg, r.id, r.link, r.precision))
            .setNegativeButton(R.string.models_later, null)
            .setNeutralButton(R.string.models_copy_link) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", r.link))
            }
            .setPositiveButton(R.string.models_open_github) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelCatalog.CONVERT_PAGE)))
            }
            .show()
    }

    private fun toastDialog(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    // ───────────────────────── drawing ─────────────────────────

    private fun render() {
        list.removeAllViews()
        header()
        storage()
        filters()

        val local = ModelCatalog.local(this)
        val job = ModelsJob.id

        // CREATE
        val createRows = mutableListOf<View>()
        for (id in local) {
            val u = ModelUpdates.saved(this, id)
            if (filter == Filter.UPDATES && u == null) continue
            val kind = when {
                SdModel.isSdxl(this, id) -> "SDXL"
                id == "pix2pix" -> "Editor"
                else -> "SD 1.5"
            }
            val shape = if (SdModel.isDynamic(this, id) || SdModel.isSdxl(this, id)) "" else " · " + getString(R.string.models_square_only)
            val sub = "$kind · ${ModelCatalog.gb(ModelCatalog.sizeOnPhone(this, id))}$shape"
            val name = ModelCatalog.displayName(id)
            createRows += when {
                job == id -> row(name, sub, progressText(), null, getString(R.string.models_stop), Btn.OUTLINE) { ModelsJob.cancel = true }
                u != null -> row(name, sub, getString(R.string.models_update_line, u.reason), warn, getString(R.string.models_update), Btn.WARN) { confirmUpdate(id, name, u) }
                else -> row(name, sub, getString(R.string.models_up_to_date), accent, getString(R.string.models_delete), Btn.OUTLINE) { confirmDelete(id, name) }
            }
        }
        if (filter == Filter.ALL) {
            // half-downloaded models (an update or download that stopped)
            File(filesDir, "models").listFiles()?.filter {
                it.isDirectory && it.name != RestorePack.ID && it.name !in local && File(it, "files.txt").exists()
            }?.forEach { d ->
                val name = ModelCatalog.displayName(d.name)
                createRows += if (job == d.name) row(name, "", progressText(), null, getString(R.string.models_stop), Btn.OUTLINE) { ModelsJob.cancel = true }
                else row(name, ModelCatalog.gb(dirSize(d)), getString(R.string.models_incomplete), warn, getString(R.string.models_continue), Btn.FILLED) { download(d.name, name, false) }
            }
            val onPhone = File(filesDir, "models").listFiles()?.map { it.name }?.toSet().orEmpty() + local
            for (r in remote.filter { it.id !in onPhone && it.id != RestorePack.ID }) {
                val name = ModelCatalog.displayName(r.id)
                createRows += if (job == r.id) row(name, "", progressText(), null, getString(R.string.models_stop), Btn.OUTLINE) { ModelsJob.cancel = true }
                else row(name, (ModelCatalog.description(r.id) ?: getString(R.string.models_converted)) + " · " + ModelCatalog.gb(r.sizeBytes),
                    getString(R.string.models_not_downloaded), muted, getString(R.string.models_download), Btn.FILLED) { download(r.id, name, false) }
            }
            val remoteIds = remote.map { it.id }.toSet()
            for (r in ModelCatalog.RECOMMENDED.filter { it.id !in remoteIds && it.id !in onPhone }) {
                if (remote.isEmpty()) break   // unknown until GitHub answers
                createRows += row(r.name, r.description, getString(R.string.models_not_converted), muted, getString(R.string.models_convert), Btn.OUTLINE) { showConvert(r) }
            }
            if (remoteError) createRows += note(getString(R.string.models_offline))
        }
        group(getString(R.string.models_group_create), createRows)

        // RESTORE
        val restoreRows = mutableListOf<View>()
        val packDir = File(filesDir, "models/${RestorePack.ID}")
        val packReady = RestorePack.isReady(this)
        val pu = if (packReady) ModelUpdates.saved(this, RestorePack.ID) else null
        if (filter != Filter.UPDATES || pu != null) {
            if (packReady || filter == Filter.ALL) {
                for ((file, label) in PACK) {
                    val f = File(packDir, file)
                    val state = if (packReady && f.exists()) ModelCatalog.mb(f.length()) else getString(R.string.models_not_downloaded)
                    restoreRows += row(label, state, null, null, null, Btn.OUTLINE, null)
                }
                val packName = getString(R.string.models_pack)
                restoreRows += when {
                    job == RestorePack.ID -> row(packName, "", progressText(), null, getString(R.string.models_stop), Btn.OUTLINE) { ModelsJob.cancel = true }
                    pu != null -> row(packName, ModelCatalog.mb(sizes.restore), getString(R.string.models_update_line, pu.reason), warn,
                        getString(R.string.models_update), Btn.WARN) { confirmUpdate(RestorePack.ID, packName, pu) }
                    packReady -> row(packName, ModelCatalog.mb(sizes.restore), getString(R.string.models_up_to_date), accent,
                        getString(R.string.models_delete), Btn.OUTLINE) { confirmDelete(RestorePack.ID, packName) }
                    else -> row(packName, getString(R.string.models_pack_size), if (packDir.exists()) getString(R.string.models_incomplete) else null, warn,
                        getString(if (packDir.exists()) R.string.models_continue else R.string.models_download), Btn.FILLED) { download(RestorePack.ID, packName, false) }
                }
            }
        }
        group(getString(R.string.models_group_restore), restoreRows)

        // ENHANCE
        val enhanceRows = mutableListOf<View>()
        if (filter != Filter.UPDATES) {
            enhanceRows += row(getString(R.string.models_bundled_enhance), getString(R.string.models_bundled), null, null, null, Btn.OUTLINE, null)
            val g = FaceRestorer.modelFile(this)
            val gName = getString(R.string.models_gfpgan)
            if (filter == Filter.ALL || g.exists()) enhanceRows += when {
                job == GFPGAN -> row(gName, "", progressText(), null, getString(R.string.models_stop), Btn.OUTLINE) { ModelsJob.cancel = true }
                g.exists() -> row(gName, ModelCatalog.mb(g.length()) + " · " + getString(R.string.models_gfpgan_note), null, null,
                    getString(R.string.models_delete), Btn.OUTLINE) { confirmDelete(GFPGAN, gName) }
                else -> row(gName, getString(R.string.models_gfpgan_off), null, null, getString(R.string.models_download), Btn.FILLED) { download(GFPGAN, gName, false) }
            }
        }
        group(getString(R.string.models_group_enhance), enhanceRows)

        // SELECTION
        if (filter != Filter.UPDATES)
            group(getString(R.string.models_group_select), listOf(row(getString(R.string.models_select_names), getString(R.string.models_select_note), null, null, null, Btn.OUTLINE, null)))
    }

    private fun progressText(): String {
        val t = ModelsJob.total
        return if (t <= 0) getString(R.string.models_starting)
        else getString(R.string.models_progress, (ModelsJob.done * 100 / t).toInt(), ModelCatalog.gb(ModelsJob.done), ModelCatalog.gb(t))
    }

    private fun header() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "‹"; textSize = 18f; minWidth = 0; minimumWidth = 0
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(text(getString(R.string.models_title), 22f, fg, bold = true).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(row)
    }

    private fun storage() {
        val box = cardBox().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val total = sizes.create + sizes.restore + sizes.enhance
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text(getString(R.string.models_space), 13f, muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(text(ModelCatalog.gb(total), 13f, fg).apply { typeface = Typeface.MONOSPACE })
        box.addView(top)

        // bar: share of the phone's storage
        val disk = (filesDir.totalSpace).coerceAtLeast(1L)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(ContextCompat.getColor(this@ModelsActivity, R.color.bg), 4f)
            clipToOutline = true
        }
        val restoreColor = warn
        val enhanceColor = 0xFF8AB4F0.toInt()
        for ((bytes, color) in listOf(sizes.create to accent, sizes.restore to restoreColor, sizes.enhance to enhanceColor)) {
            val w = (bytes.toDouble() / disk).toFloat().coerceAtLeast(if (bytes > 0) 0.01f else 0f)
            bar.addView(View(this).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(0, dp(8), w))
        }
        val used = (sizes.create + sizes.restore + sizes.enhance).toDouble() / disk
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(8), (1.0 - used).toFloat().coerceAtLeast(0f)))
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(10) })

        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, pair) in listOf(R.string.models_group_create_short to (sizes.create to accent),
                R.string.models_group_restore_short to (sizes.restore to restoreColor),
                R.string.models_group_enhance_short to (sizes.enhance to enhanceColor))) {
            legend.addView(TextView(this).apply {
                textSize = 11f; setTextColor(muted)
                val s = android.text.SpannableString("■ ${getString(label)} ${ModelCatalog.gb(pair.first)}")
                s.setSpan(android.text.style.ForegroundColorSpan(pair.second), 0, 1, 0)
                text = s
                setPadding(0, 0, dp(14), 0)
            })
        }
        box.addView(legend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        box.addView(MaterialButton(this).apply {
            text = getString(if (checking) R.string.models_checking else R.string.models_check)
            isEnabled = !checking
            setOnClickListener { checkUpdates() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        val last = ModelUpdates.lastCheck(this)
        val lastText = if (last == 0L) getString(R.string.models_never_checked) else {
            val fmt = SimpleDateFormat("d MMM HH:mm", Locale("es", "MX"))
            val n = updateCount()
            getString(R.string.models_last_check, fmt.format(Date(last)),
                if (n == 0) getString(R.string.models_all_up_to_date) else resources.getQuantityString(R.plurals.models_n_updates, n, n))
        }
        box.addView(text(lastText, 12f, muted).apply { gravity = Gravity.CENTER })
        list.addView(box, block(14))
    }

    private fun filters() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val n = updateCount()
        for ((f, label) in listOf(Filter.ALL to getString(R.string.models_filter_all),
                Filter.LOCAL to getString(R.string.models_filter_local),
                Filter.UPDATES to (getString(R.string.models_filter_updates) + if (n > 0) " ($n)" else ""))) {
            val on = f == filter
            row.addView(text(label, 13f, if (on) fg else muted, bold = on).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = round(if (on) (accent and 0x00FFFFFF) or 0x40000000 else card, 16f)
                setOnClickListener { filter = f; render() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        list.addView(row, block(14))
    }

    private fun group(title: String, rows: List<View>) {
        if (rows.isEmpty()) return
        list.addView(text(title.uppercase(), 12f, muted, bold = true).apply { letterSpacing = 0.06f }, block(18))
        val box = cardBox()
        rows.forEachIndexed { i, v ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor((muted and 0x00FFFFFF) or 0x22000000) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            box.addView(v)
        }
        list.addView(box, block(8))
    }

    private enum class Btn { FILLED, OUTLINE, WARN }

    private fun row(title: String, sub: String, status: String?, statusColor: Int?, action: String?, style: Btn, onClick: (() -> Unit)?): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f, fg, bold = true))
        if (sub.isNotEmpty()) col.addView(text(sub, 12f, muted))
        if (status != null) col.addView(text(status, 12f, statusColor ?: muted))
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null && onClick != null) {
            val b = if (style == Btn.OUTLINE) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                    else MaterialButton(this)
            b.text = action
            b.textSize = 13f
            if (style == Btn.WARN) {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(warn)
                b.setTextColor(0xFF2A1D05.toInt())
            }
            val busyOther = ModelsJob.id != null && action != getString(R.string.models_stop)
            b.isEnabled = !busyOther
            b.setOnClickListener { onClick() }
            r.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        return r
    }

    private fun note(s: String) = text(s, 12f, muted).apply { setPadding(dp(14), dp(10), dp(14), dp(12)) }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(card, 16f)
    }

    private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r.toInt()).toFloat() }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun block(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val GFPGAN = "gfpgan"
        private val BUNDLED = listOf(
            Upscaler.MODEL_FILE to "Rápido", Upscaler.MODEL_X4PLUS to "Detalle", Upscaler.MODEL_ULTRASHARP to "Ultra nítido",
            Upscaler.MODEL_SIAX to "Natural", Upscaler.MODEL_ANIME to "Anime", Upscaler.MODEL_DEBLUR to "Desenfoque",
        )
        private val PACK = listOf(
            RestorePack.FACES to "Caras · CodeFormer",
            RestorePack.DEBLUR to "Desenfoque · Restormer",
            RestorePack.COLOR to "Colorear · DDColor",
            RestorePack.DENOISE to "Grano · SCUNet",
            RestorePack.LAMA to "Rayones · LaMa",
        )
    }
}
