package tech.devline.scropy_ui.adb

import java.io.IOException

class DeviceIdentityException(message: String) : IOException(message)

internal fun checkedDeviceSerial(actual: String, expected: String?): String {
    val serial = actual.trim()
    if (serial.isBlank()) throw DeviceIdentityException("端末のシリアル番号を確認できません。USB で接続し直してください。")
    if (!expected.isNullOrBlank() && serial != expected.trim()) {
        throw DeviceIdentityException("別の AURA に接続されています。接続を中止しました。USB で対象端末を登録し直してください。")
    }
    return serial
}

internal fun savedDeviceSerial(serial: String?, id: String): String? =
    serial?.takeIf { it.isNotBlank() }
        ?: id.takeIf { it.startsWith("usb-wifi-") }?.removePrefix("usb-wifi-")?.takeIf { it.isNotBlank() }

internal fun requireSavedSerial(serial: String?): String = serial?.takeIf { it.isNotBlank() }
    ?: throw DeviceIdentityException("この保存済み端末はシリアル番号が未登録です。USB または新規接続から登録し直してください。")
