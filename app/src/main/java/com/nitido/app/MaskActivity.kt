package com.nitido.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.nitido.app.databinding.ActivityMaskBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Hands the photo and the painted mask between screens without squeezing big bitmaps into an Intent. */
object MaskHolder {
    var photo: Bitmap? = null
    var mask: Bitmap? = null
}

/** Paint the area to redraw; or pick a person or the main object with one tap. Everything runs on the phone. */
class MaskActivity : AppCompatActivity() {

    private lateinit var b: ActivityMaskBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMaskBinding.inflate(layoutInflater)
        setContentView(b.root)

        val photo = MaskHolder.photo ?: run { finish(); return }
        b.editor.setPhoto(photo, MaskHolder.mask)

        b.maskBack.setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        b.maskDone.setOnClickListener {
            if (!b.editor.hasMask()) { b.maskStatus.text = getString(R.string.mask_empty); return@setOnClickListener }
            MaskHolder.mask = b.editor.mask
            setResult(Activity.RESULT_OK)
            finish()
        }
        b.toolGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            b.editor.erasing = id == R.id.toolEraser
            b.editor.tapMode = id == R.id.toolTap
            if (id == R.id.toolTap) b.maskStatus.text = getString(R.string.mask_tap_hint)
        }
        b.editor.onTap = { x, y -> selectAt(photo, x / photo.width, y / photo.height) }
        b.brushSize.addOnChangeListener { _, v, _ -> b.editor.brushPx = v * resources.displayMetrics.density / 2f }
        b.editor.brushPx = b.brushSize.value * resources.displayMetrics.density / 2f
        b.maskUndo.setOnClickListener { b.editor.undoLast() }
        b.maskClear.setOnClickListener { b.editor.clear() }
        b.maskInvert.setOnClickListener { b.editor.invert() }
        b.selPerson.setOnClickListener { selectPerson(photo) }
        b.selObject.setOnClickListener { selectObject(photo) }
        b.selClothes.setOnClickListener { selectClass(photo, setOf(4)) }
        b.selHair.setOnClickListener { selectClass(photo, setOf(1)) }
        b.selSkin.setOnClickListener { selectClass(photo, setOf(2, 3)) }
    }

    private fun hasAsset(name: String) = try { assets.open(name).close(); true } catch (_: Throwable) { false }

    /** Reads a MediaPipe category mask (one byte per pixel). */
    private fun bytesOf(img: com.google.mediapipe.framework.image.MPImage): ByteArray {
        val buf = ByteBufferExtractor.extract(img)
        buf.rewind()
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }

    private var tapSeg: InteractiveSegmenter? = null

    /** Tap-to-select (MediaPipe Magic Touch): selects the single thing under the finger, e.g. just the shirt. */
    private fun selectAt(photo: Bitmap, nx: Float, ny: Float) {
        if (!hasAsset("magic_touch.tflite")) { b.maskStatus.text = getString(R.string.mask_missing_model); return }
        b.maskStatus.text = getString(R.string.mask_finding)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) {
                try {
                    val seg = tapSeg ?: InteractiveSegmenter.createFromOptions(this@MaskActivity,
                        InteractiveSegmenter.InteractiveSegmenterOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("magic_touch.tflite").build())
                            .setOutputCategoryMask(true)
                            .setOutputConfidenceMasks(false)
                            .build()).also { tapSeg = it }
                    val img = small(photo).copy(Bitmap.Config.ARGB_8888, false)
                    val roi = InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create(nx, ny))
                    val r = seg.segment(BitmapImageBuilder(img).build(), roi)
                    val mask = r.categoryMask().get()
                    val w = mask.width; val h = mask.height
                    val bytes = bytesOf(mask)
                    // the value under the finger is "the object", whatever number the model uses for it
                    val px = (nx * (w - 1)).roundToInt().coerceIn(0, w - 1)
                    val py = (ny * (h - 1)).roundToInt().coerceIn(0, h - 1)
                    val v = bytes[py * w + px]
                    val conf = FloatArray(w * h) { if (bytes[it] == v) 1f else 0f }
                    val share = conf.sum() / conf.size
                    if (share > 0.9f) null else Triple(conf, w, h)
                } catch (t: Throwable) {
                    android.util.Log.w("Nitido", "tap segmentation", t)
                    null
                }
            }
            if (res == null) { b.maskStatus.text = getString(R.string.mask_nothing_here); return@launch }
            b.editor.addSelection(res.first, res.second, res.third)
            b.maskStatus.text = getString(R.string.mask_selected)
        }
    }

    /**
     * Parts of a person (MediaPipe selfie multiclass): 0 background, 1 hair, 2 body skin,
     * 3 face skin, 4 clothes, 5 accessories.
     */
    private fun selectClass(photo: Bitmap, classes: Set<Int>) {
        if (!hasAsset("selfie_multiclass.tflite")) { b.maskStatus.text = getString(R.string.mask_missing_model); return }
        b.maskStatus.text = getString(R.string.mask_finding)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) {
                try {
                    val seg = ImageSegmenter.createFromOptions(this@MaskActivity,
                        ImageSegmenter.ImageSegmenterOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("selfie_multiclass.tflite").build())
                            .setRunningMode(RunningMode.IMAGE)
                            .setOutputCategoryMask(true)
                            .setOutputConfidenceMasks(false)
                            .build())
                    try {
                        val img = small(photo).copy(Bitmap.Config.ARGB_8888, false)
                        val mask = seg.segment(BitmapImageBuilder(img).build()).categoryMask().get()
                        val bytes = bytesOf(mask)
                        val conf = FloatArray(mask.width * mask.height) {
                            if ((bytes[it].toInt() and 0xFF) in classes) 1f else 0f
                        }
                        if (conf.sum() < 1f) null else Triple(conf, mask.width, mask.height)
                    } finally { seg.close() }
                } catch (t: Throwable) {
                    android.util.Log.w("Nitido", "multiclass segmentation", t)
                    null
                }
            }
            if (res == null) { b.maskStatus.text = getString(R.string.mask_none_found); return@launch }
            b.editor.addSelection(res.first, res.second, res.third)
            b.maskStatus.text = getString(R.string.mask_selected)
        }
    }

    override fun onDestroy() {
        tapSeg?.close(); tapSeg = null
        super.onDestroy()
    }

    /** Downscale for the detectors: they are fast enough at ~1024 px and the mask is scaled back up. */
    private fun small(photo: Bitmap): Bitmap {
        val long = maxOf(photo.width, photo.height)
        if (long <= 1024) return photo
        val k = 1024f / long
        return Bitmap.createScaledBitmap(photo, (photo.width * k).roundToInt(), (photo.height * k).roundToInt(), true)
    }

    private fun selectPerson(photo: Bitmap) {
        b.maskStatus.text = getString(R.string.mask_finding)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) {
                try {
                    val img = small(photo)
                    val seg = Segmentation.getClient(
                        SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
                    try {
                        val m = Tasks.await(seg.process(InputImage.fromBitmap(img, 0)))
                        val buf = m.buffer.order(ByteOrder.nativeOrder())
                        buf.rewind()
                        val conf = FloatArray(m.width * m.height)
                        buf.asFloatBuffer().get(conf)
                        Triple(conf, m.width, m.height)
                    } finally { seg.close() }
                } catch (t: Throwable) { null }
            }
            if (res == null) { b.maskStatus.text = getString(R.string.mask_none_found); return@launch }
            b.editor.addSelection(res.first, res.second, res.third)
            b.maskStatus.text = getString(R.string.mask_selected)
        }
    }

    private fun selectObject(photo: Bitmap) {
        b.maskStatus.text = getString(R.string.mask_finding)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) {
                val seg = SubjectSegmentation.getClient(
                    SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
                try {
                    // The detector comes through Google Play services the first time (needs internet once).
                    try {
                        Tasks.await(ModuleInstall.getClient(this@MaskActivity)
                            .installModules(ModuleInstallRequest.newBuilder().addApi(seg).build()))
                    } catch (_: Throwable) { }
                    val img = small(photo)
                    val r = Tasks.await(seg.process(InputImage.fromBitmap(img, 0)))
                    val fb = r.foregroundConfidenceMask ?: return@withContext null
                    fb.rewind()
                    val conf = FloatArray(img.width * img.height)
                    fb.get(conf)
                    Triple(conf, img.width, img.height)
                } catch (t: Throwable) {
                    android.util.Log.w("Nitido", "subject segmentation", t)
                    null
                } finally { seg.close() }
            }
            if (res == null) { b.maskStatus.text = getString(R.string.mask_object_failed); return@launch }
            b.editor.addSelection(res.first, res.second, res.third)
            b.maskStatus.text = getString(R.string.mask_selected)
        }
    }
}
