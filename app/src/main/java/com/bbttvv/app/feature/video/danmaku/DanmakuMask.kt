package com.bbttvv.app.feature.video.danmaku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.widget.FrameLayout
import com.bytedance.danmaku.render.engine.DanmakuView
import com.bbttvv.app.core.util.Logger
import com.caverock.androidsvg.SVG
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Bilibili 的预计算弹幕蒙版。
 *
 * .webmask 的每帧是 Base64 SVG，.mobmask 的每帧是 1 bit/pixel 图像。这里只缓存
 * 当前 segment 的压缩数据，并按当前时间解码一帧，避免长视频一次性展开所有图片。
 */
class DanmakuMask private constructor(
    private val archive: WebMaskArchive,
    private val type: Type,
) {
    enum class Type { Web, Mob }

    data class Frame(
        val startMs: Long,
        val endMs: Long,
        val bitmap: Bitmap,
    )

    private var cachedSegmentIndex = -1
    private var cachedWebFrames: List<WebMaskArchive.Frame> = emptyList()
    private var cachedMobFrames: List<WebMaskArchive.MobFrame> = emptyList()
    private var cachedFrameIndex = -1
    private var cachedFrame: Frame? = null

    val segmentCount: Int get() = archive.segments.size

    @Synchronized
    fun frameAt(positionMs: Long): Frame? {
        val segmentIndex = archive.segmentIndexAt(positionMs)
        if (segmentIndex < 0) return null
        if (segmentIndex != cachedSegmentIndex) {
            cachedSegmentIndex = segmentIndex
            cachedFrameIndex = -1
            cachedFrame = null
            runCatching {
                if (type == Type.Web) {
                    cachedWebFrames = archive.readFrames(segmentIndex)
                    cachedMobFrames = emptyList()
                } else {
                    cachedMobFrames = archive.readMobFrames(segmentIndex)
                    cachedWebFrames = emptyList()
                }
            }.onFailure { error ->
                cachedWebFrames = emptyList()
                cachedMobFrames = emptyList()
                Logger.w(
                    "DanmakuMask",
                    "Invalid ${type.name.lowercase()}mask segment=$segmentIndex: ${error.message}",
                )
            }
        }

        val firstFrameIndex = if (type == Type.Web) {
            cachedWebFrames.indexOfFirst { positionMs < it.timeMs }
        } else {
            cachedMobFrames.indexOfFirst { positionMs < it.timeMs }
        }
        val segmentEnd = archive.segments.getOrNull(segmentIndex + 1)?.startMs
        val frameIndex = if (firstFrameIndex >= 0) {
            firstFrameIndex
        } else if (segmentEnd != null && positionMs < segmentEnd) {
            if (type == Type.Web) cachedWebFrames.lastIndex else cachedMobFrames.lastIndex
        } else {
            -1
        }
        if (frameIndex < 0) return null
        if (frameIndex == cachedFrameIndex) return cachedFrame

        val segmentStart = archive.segments[segmentIndex].startMs
        val startTime = if (type == Type.Web) {
            cachedWebFrames.getOrNull(frameIndex - 1)?.timeMs ?: segmentStart
        } else {
            cachedMobFrames.getOrNull(frameIndex - 1)?.timeMs ?: segmentStart
        }
        val recordEnd = if (type == Type.Web) {
            cachedWebFrames[frameIndex].timeMs
        } else {
            cachedMobFrames[frameIndex].timeMs
        }
        val endTime = maxOf(
            recordEnd,
            segmentEnd ?: recordEnd,
        ).coerceAtLeast(startTime + 1L)
        val bitmap = runCatching {
            if (type == Type.Web) {
                renderSvg(decodeSvg(cachedWebFrames[frameIndex].dataUri))
            } else {
                renderMob(cachedMobFrames[frameIndex])
            }
        }.onFailure { error ->
            Logger.w("DanmakuMask", "Mask frame decode failed type=${type.name}: ${error.message}")
        }.getOrNull() ?: return null

        cachedFrameIndex = frameIndex
        return Frame(startTime, endTime, bitmap).also { cachedFrame = it }
    }

    private fun decodeSvg(dataUri: String): String {
        val encoded = dataUri.substringAfter(',', missingDelimiterValue = dataUri)
            .replace("\n", "")
            .replace("\r", "")
        return Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
    }

    private fun renderSvg(svgText: String): Bitmap {
        val svg = SVG.getFromString(svgText)
        val viewBox = VIEW_BOX.find(svgText)?.groupValues?.get(1)?.trim()?.split(Regex("[ ,]+"))
        val fallbackWidth = viewBox?.getOrNull(2)?.toFloatOrNull() ?: 1920f
        val fallbackHeight = viewBox?.getOrNull(3)?.toFloatOrNull() ?: 1080f
        val width = svg.documentWidth.takeIf { it.isFinite() && it > 0f } ?: fallbackWidth
        val height = svg.documentHeight.takeIf { it.isFinite() && it > 0f } ?: fallbackHeight
        val bitmap = Bitmap.createBitmap(width.roundToInt().coerceIn(1, 4096), height.roundToInt().coerceIn(1, 4096), Bitmap.Config.ARGB_8888)
        if (svg.documentWidth <= 0f) svg.setDocumentWidth(bitmap.width.toFloat())
        if (svg.documentHeight <= 0f) svg.setDocumentHeight(bitmap.height.toFloat())
        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun renderMob(frame: WebMaskArchive.MobFrame): Bitmap {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(frame.width * frame.height) { index ->
            val byteIndex = index / 8
            val bitOffset = 7 - (index % 8)
            if ((frame.image[byteIndex].toInt() ushr bitOffset and 1) == 0) {
                Color.BLACK
            } else {
                Color.TRANSPARENT
            }
        }
        bitmap.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        return bitmap
    }

    companion object {
        fun fromBinary(binary: ByteArray, maskUrl: String = ""): DanmakuMask? = runCatching {
            val normalized = maskUrl.lowercase()
            val type = if (normalized.contains("mobmask")) Type.Mob else Type.Web
            DanmakuMask(WebMaskArchive.parse(binary), type)
        }.getOrNull()

        private val VIEW_BOX = Regex("\\bviewBox\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
    }
}

/** Host that applies the mask only to the danmaku child. */
internal class DanmakuMaskHostView(context: Context) : FrameLayout(context) {
    val danmakuView = DanmakuView(context)
    var frame: DanmakuMask.Frame? = null
        set(value) {
            field = value
            invalidate()
        }
    var maskEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var videoAspectRatio: Float = 16f / 9f
        set(value) {
            field = value
            invalidate()
        }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        isFilterBitmap = true
    }

    init {
        addView(danmakuView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val frame = frame
        if (!maskEnabled || frame == null || width <= 0 || height <= 0) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        val destination = resolveVideoRect(width, height, videoAspectRatio)
        canvas.drawBitmap(frame.bitmap, null, destination, maskPaint)
        canvas.restoreToCount(save)
    }

    private fun resolveVideoRect(width: Int, height: Int, aspectRatio: Float): RectF {
        val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        val viewAspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        return if (aspect > viewAspect) {
            val dstWidth = width.toFloat()
            val dstHeight = dstWidth / aspect
            RectF(0f, (height - dstHeight) / 2f, dstWidth, (height + dstHeight) / 2f)
        } else {
            val dstHeight = height.toFloat()
            val dstWidth = dstHeight * aspect
            RectF((width - dstWidth) / 2f, 0f, (width + dstWidth) / 2f, dstHeight)
        }
    }
}
