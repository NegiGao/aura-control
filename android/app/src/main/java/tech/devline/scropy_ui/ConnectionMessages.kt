package tech.devline.scropy_ui

import android.util.Log

internal fun japaneseConnectionError(error: Throwable): String {
    Log.w("AuraConnection", "Connection failed", error)
    val detail = error.message.orEmpty()
    if (Regex("[ぁ-んァ-ヶ]").containsMatchIn(detail)) return detail
    return when {
        detail.contains("auth", true) || detail.contains("pair", true) ->
            "端末で USB デバッグを許可してください。ファームウェア更新後は USB → Wi-Fi を再実行してください。"
        detail.contains("refused", true) -> "接続ポートが閉じています。USB → Wi-Fi で設定し直してください。"
        detail.contains("timed out", true) || detail.contains("timeout", true) ->
            "接続がタイムアウトしました。同じ Wi-Fi と端末の認証を確認してください。"
        else -> "接続に失敗しました。USB デバッグの許可、Wi-Fi、IP アドレスとポートを確認してください。"
    }
}
