package com.bbttvv.app.feature.live

import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.SeparableConvolution
import kotlin.math.abs

internal const val DEFAULT_LIVE_ENHANCEMENT_MAX_QN = 250
internal const val LIVE_VIDEO_ENHANCEMENT_OPTION_KEY = "video_enhancement"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object LiveVideoSharpening : SeparableConvolution() {
    // Apply [-0.10, 1.20, -0.10] in each axis at the source resolution.
    private val kernel = object : ConvolutionFunction1D {
        override fun domainStart() = -1.0f
        override fun domainEnd() = 1.0f

        override fun value(samplePosition: Float): Float = when {
            abs(samplePosition) < 0.5f -> 1.20f
            abs(samplePosition) <= 1.0f -> -0.10f
            else -> 0.0f
        }
    }

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D = kernel
}
