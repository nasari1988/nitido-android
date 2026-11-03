package com.nitido.app

/** Bridge to the native Stable Diffusion engine (libsdjni.so, built from MNN). */
object SdNative {
    const val BACKEND_CPU = 0
    const val BACKEND_OPENCL = 3

    interface Listener {
        fun onProgress(pct: Int)
        /** [rgb] is width×height×3 bytes (RGB): a preview of the image at the current step. */
        fun onPreview(rgb: ByteArray, width: Int, height: Int)
    }

    const val PREVIEW_NONE = 0
    const val PREVIEW_FAST = 1
    const val PREVIEW_FULL = 2

    const val SAMPLER_PLMS = 0
    const val SAMPLER_DPM = 1

    val loadError: Throwable? = try { System.loadLibrary("sdjni"); null } catch (t: Throwable) { t }

    /**
     * [mode] 0 = normal model (text-to-image, or image-to-image when [image] is given), 1 = InstructPix2Pix.
     * [image] optional 3×512×512 RGB floats in [-1, 1], channel-first.
     * [strength] 0..1 how much of the photo may change (mode 0 with a photo).
     * [guidance] how strongly to follow the prompt; [imageGuidance] how strongly to keep the photo (mode 1).
     * Returns 0 ok, 2 model failed to load, 3 generation failed, 4 photo given but encoder missing,
     * 5 size not supported by this model, 6 cancelled. [width]/[height] are multiples of 64.
     */
    @JvmStatic external fun generate(
        dir: String, cacheDir: String, backend: Int, mode: Int, dynamic: Boolean, width: Int, height: Int,
        steps: Int, seed: Int, out: String,
        prompt: String, negative: String, image: FloatArray?, strength: Float, guidance: Float,
        imageGuidance: Float,
        maskedImage: FloatArray?, latMask: FloatArray?, previewMode: Int, sampler: Int, listener: Listener
    ): Int

    /** Stops a running generation at the next step (it then returns 6). */
    @JvmStatic external fun cancel()

    @JvmStatic external fun release()

    const val OK = 0
    const val CANCELLED = 6
    const val BAD_UNET = 9
}
