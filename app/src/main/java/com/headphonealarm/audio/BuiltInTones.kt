package com.headphonealarm.audio

import android.content.Context
import androidx.annotation.RawRes
import com.headphonealarm.R

/**
 * 内置铃声。以 `android.resource://包名/raw/资源名` 形式保存，
 * 使用**资源名**而非资源 ID，保证应用升级后仍可正确解析。
 */
object BuiltInTones {

    data class Tone(val key: String, val name: String, val description: String, @RawRes val resId: Int)

    val ALL: List<Tone> = listOf(
        Tone("tone_gentle", "晨曦", "柔和渐起的钟琴音", R.raw.tone_gentle),
        Tone("tone_pulse", "脉冲", "清晰有力的电子脉冲", R.raw.tone_pulse),
        Tone("tone_chime", "风铃", "轻盈的风铃回响", R.raw.tone_chime),
        Tone("tone_echo", "回响", "深沉的木质马林巴", R.raw.tone_echo)
    )

    fun uriFor(context: Context, tone: Tone): String =
        "android.resource://${context.packageName}/raw/${tone.key}"

    fun find(key: String): Tone? = ALL.firstOrNull { it.key == key }
}
