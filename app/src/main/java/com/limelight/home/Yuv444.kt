package com.limelight.home

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.preference.PreferenceManager

/**
 * YUV 4:4:4 for HEVC (Settings, Picture): full colour detail for every pixel instead of one colour sample per 2x2
 * pixels, so small text and coloured edges stay sharp. The PC must have it too (Sunshine with 4:4:4 on); when it
 * doesn't, the stream simply stays 4:2:0. Android lists no HEVC RExt profile, so a decoder counts as able when its
 * hardware HEVC decoder offers the YUV 4:4:4 output format.
 */
object Yuv444 {
    const val PREF = "checkbox_yuv444"

    /**
     * 4:4:4 goes with PyroWave only (Settings' PyroWave play style turns both on): a checkbox_yuv444 left on by an
     * older build, from when it was a setting of its own, counts as off while PyroWave is off.
     */
    @JvmStatic
    fun wanted(ctx: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).let { p ->
        p.getBoolean(PREF, false) && p.getBoolean(Pyrowave.PREF, false)
    }

    private var able: Boolean? = null

    /** This device has an HEVC decoder that shows 4:4:4. */
    @JvmStatic
    fun deviceCan(): Boolean = able ?: try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
                    info.getCapabilitiesForType("video/hevc").colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV444Flexible)
        }
    } catch (e: Exception) {
        false
    }.also { able = it }
}
