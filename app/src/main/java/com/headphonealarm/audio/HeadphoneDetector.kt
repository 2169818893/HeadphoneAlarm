package com.headphonealarm.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 耳机（含蓝牙 / USB / Type-C）识别工具。
 *
 * 通过 [AudioManager.getDevices] 枚举当前所有**输出**设备并判断类型，
 * 不使用已废弃的 `isWiredHeadsetOn`（在部分机型上恒为 false）。
 */
class HeadphoneDetector(context: Context) {

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前可用的耳机类型集合（按系统版本动态构建） */
    val headphoneTypes: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        // 不带麦克风的 Type-C 耳机会报成 TYPE_USB_DEVICE（而非 TYPE_USB_HEADSET），
        // 不补上会被当作「没插耳机」而静默。
        add(AudioDeviceInfo.TYPE_USB_DEVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(AudioDeviceInfo.TYPE_HEARING_AID)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    fun isHeadphone(device: AudioDeviceInfo?): Boolean =
        device != null && device.isSink && device.type in headphoneTypes

    /**
     * 查找一个最适合作为闹钟输出的耳机设备。
     * 优先有线（路由最稳定），其次蓝牙，最后其它类型。
     */
    @Suppress("InlinedApi") // 新类型常量在编译期内联，低版本运行时 getDevices 不会返回它们
    fun findHeadphone(): AudioDeviceInfo? {
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink }
                .toList()
        }.getOrDefault(emptyList())

        val priority = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        for (type in priority) {
            outputs.firstOrNull { it.type == type }?.let { return it }
        }
        return outputs.firstOrNull { it.type in headphoneTypes }
    }

    fun isConnected(): Boolean = findHeadphone() != null

    /** 诊断用：列出当前所有输出设备的可读名称与类型，便于排查「耳机识别不到」 */
    fun describeOutputs(): String {
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())
        if (outputs.isEmpty()) return "无可用输出设备"
        return outputs.joinToString("、") { "${it.productName}(type=${it.type})" }
    }

    /**
     * 注册耳机插拔监听。返回的 [Registration] 必须在停止时调用 [Registration.release]，
     * 否则会持有 Context 造成泄漏。
     */
    fun register(callback: Callback): Registration {
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (addedDevices.any { it.isSink && it.type in headphoneTypes }) {
                    callback.onHeadphoneConnected()
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.isSink && it.type in headphoneTypes }) {
                    callback.onHeadphoneDisconnected()
                }
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        return Registration { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    interface Callback {
        fun onHeadphoneConnected()
        fun onHeadphoneDisconnected() = Unit
    }

    fun interface Registration {
        fun release()
    }
}
