package tech.devline.scropy_ui

data class AuraAction(val label: String, val command: String)

object AuraCommands {
    val packages = linkedMapOf(
        "Google Calendar" to "com.google.android.calendar",
        "Chrome" to "com.android.chrome",
        "Google Docs" to "com.google.android.apps.docs.editors.docs",
        "Google Drive" to "com.google.android.apps.docs",
        "Gemini" to "com.google.android.apps.bard",
        "Gmail" to "com.google.android.gm",
        "Google Maps" to "com.google.android.apps.maps",
        "Google Photos" to "com.google.android.apps.photos",
        "Google Sheets" to "com.google.android.apps.docs.editors.sheets",
        "Google Slides" to "com.google.android.apps.docs.editors.slides",
        "YouTube" to "com.google.android.youtube",
    )
    private fun launch(packageName: String) = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
    val apps = packages.map { (label, packageName) -> AuraAction(label, launch(packageName)) }
    const val geminiClose = "am force-stop com.google.android.apps.bard"
    val controls = listOf(
        AuraAction("戻る", "input keyevent KEYCODE_BACK"),
        AuraAction("ランチャー", "input keyevent KEYCODE_HOME"),
        AuraAction("全APPを閉じる", packages.values.joinToString("; ") { "am force-stop $it" }),
    )
    val closeAllApps get() = controls.last()
    val modes = emptyList<AuraAction>()
    fun mediaVolume(value: Int) = "cmd media_session volume --stream 3 --set ${value.coerceIn(0, 15)}"
    fun openUrl(url: String): String {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val safe = normalized.replace("'", "'\\''")
        return "am start -a android.intent.action.VIEW -p com.android.chrome -d '$safe'"
    }
}
