package com.nitido.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.roundToInt

/** Helpers for the step-by-step timelapse: turning preview bytes into bitmaps and writing an MP4. */
object TimelapseVideo {

    fun toBitmap(rgb: ByteArray, width: Int, height: Int): Bitmap {
        val px = IntArray(width * height)
        for (i in px.indices) {
            px[i] = Color.rgb(rgb[i * 3].toInt() and 0xFF, rgb[i * 3 + 1].toInt() and 0xFF, rgb[i * 3 + 2].toInt() and 0xFF)
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Writes [frames] as an H.264 video, [fps] frames per second,
     * holding the last frame for [holdLastSec] seconds.
     */
    fun writeMp4(frames: List<Bitmap>, out: File, fps: Int = 6, holdLastSec: Float = 2f) {
        // Video keeps the picture's shape: long side 512, both sides multiples of 16.
        val ref = frames.last()
        val ratio = ref.width.toFloat() / ref.height
        val vw = if (ratio >= 1f) 512 else ((512 * ratio / 16f).roundToInt() * 16).coerceAtLeast(16)
        val vh = if (ratio >= 1f) ((512 / ratio / 16f).roundToInt() * 16).coerceAtLeast(16) else 512
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var written = 0
        val frameUs = 1_000_000L / fps
        val info = MediaCodec.BufferInfo()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = Rect(0, 0, vw, vh)

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            // our own timestamps: frames were drawn as fast as possible
                            info.presentationTimeUs = written * frameUs
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                            written++
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            val hold = (holdLastSec * fps).toInt().coerceAtLeast(1)
            val sequence = frames + List(hold) { frames.last() }
            for (bmp in sequence) {
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(bmp, null, dst, paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                Thread.sleep(15)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            surface.release()
            try { if (track >= 0) muxer.stop() } catch (_: Throwable) {}
            muxer.release()
        }
    }
}
