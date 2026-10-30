package tech.devline.scropy_ui

import java.net.URLEncoder

data class AuraAction(val label: String, val command: String)

object AuraCommands {
    const val geminiClose = "am force-stop com.google.android.googlequicksearchbox"
    private val mapsXrPreparation = "am force-stop com.google.android.apps.immersiveviewxr; su 0 sh -c 'p=/data/user/0/com.google.android.apps.immersiveviewxr/shared_prefs/com.google.android.apps.immersiveviewxr.v2.playerprefs.xml; if test -f \"\$p\"; then sed -i -E \"/name=\\\"(Locomotion|StreetView|Walkabout)OnboardingCompleted\\\"/s/value=\\\"[0-9]+\\\"/value=\\\"1\\\"/\" \"\$p\"; fi'"
    val mapsXrNormal = "$mapsXrPreparation && am start -n com.google.android.apps.immersiveviewxr/.ImmersiveViewXrActivity"
    val mapsXrGuide = "am force-stop com.google.android.apps.immersiveviewxr; su 0 sh -c 'p=/data/user/0/com.google.android.apps.immersiveviewxr/shared_prefs/com.google.android.apps.immersiveviewxr.v2.playerprefs.xml; test -f \"\$p\" && cp -p \"\$p\" \"\$p.aura-guide-backup\" && sed -i -E \"/name=\\\"(Locomotion|StreetView|Walkabout)OnboardingCompleted\\\"/s/value=\\\"[0-9]+\\\"/value=\\\"0\\\"/\" \"\$p\"' && am start -n com.google.android.apps.immersiveviewxr/.ImmersiveViewXrActivity"

    val apps = listOf(
        AuraAction("YouTube XR", "am start -n com.google.android.apps.youtube.xr/com.google.android.apps.youtube.vr.activities.YouTubeVrPanel2Activity"),
        AuraAction("Google Maps 2D", "am start -n com.google.android.apps.maps/com.google.android.maps.MapsActivity"),
        AuraAction("Google Maps XR", mapsXrNormal),
        AuraAction("Gemini", "su 0 am start -n com.google.android.googlequicksearchbox/com.google.android.apps.search.assistant.surfaces.voice.robin.xr.main.XrModeActivity -f 0x10000000"),
    )

    val controls = listOf(
        AuraAction("戻る", "input keyevent KEYCODE_BACK"),
        AuraAction("ランチャー", "su 0 am broadcast -a SYSTEM_INTENT_ACTION_LAUNCHER"),
        AuraAction("アプリを整列", "su 0 am broadcast -a ix.moohan.sysui.KEYBOARD_SHORTCUT --es SHORTCUT TIDY_UP_PANELS"),
        AuraAction("中心をリセット", "su 0 am broadcast -a ix.moohan.sysui.KEYBOARD_SHORTCUT --es SHORTCUT RECENTER"),
        AuraAction("全APPを閉じる", "for p in \$(am stack list | sed -n 's#.*taskId=[0-9]*: \\([^/]*\\)/.*#\\1#p' | sort -u); do [ \"\$p\" = com.android.systemui ] || am stop-app --user current \"\$p\"; done"),
        AuraAction("Gmail・Docs・YouTube・フォト・Maps 2D", "am start -n com.google.android.gm/.ConversationListActivityGmail; am start -n com.google.android.apps.docs.editors.docs/com.google.android.apps.docs.app.NewMainProxyActivity; am start -n com.google.android.apps.youtube.xr/com.google.android.apps.youtube.vr.activities.YouTubeVrPanel2Activity; am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.google.android.apps.photos; am start -n com.google.android.apps.maps/com.google.android.maps.MapsActivity"),
    )

    val modes = emptyList<AuraAction>()

    val quickDestinations = listOf(
        AuraAction("XREAL 日本公式", openUrl("https://www.xreal.com/jp/aura")),
        AuraAction("東京タワー", mapsXrSearch("東京タワー")),
        AuraAction("竹芝ポートシティ", mapsXrSearch("東京ポートシティ竹芝")),
        AuraAction("東京スカイツリー", mapsXrSearch("東京スカイツリー")),
    )

    val appPanelActions = apps.take(2) + listOf(AuraAction("初回ガイド", mapsXrGuide)) +
        quickDestinations.subList(1, 3) + apps.drop(3)
    val shortcutDestinations = listOf(quickDestinations.first(), quickDestinations.last())
    val closeAllApps get() = controls.first { it.label == "全APPを閉じる" }

    fun mapsXrSearch(place: String): String {
        require(place.isNotBlank()) { "検索する場所を指定してください。" }
        val query = URLEncoder.encode(place.trim(), "UTF-8")
        return "$mapsXrPreparation && am start -n com.google.android.apps.immersiveviewxr/.ImmersiveViewXrActivity -a android.intent.action.VIEW -d 'geo:0,0?q=$query'"
    }

    fun mediaVolume(value: Int) = "su 0 cmd media_session volume --stream 3 --set ${value.coerceIn(0, 15)}"

    // XrHeadsetHal and FramesHostService both reported levels 0,1,2,3,4.
    val transparencyLabels = listOf("0 全透", "1段", "2段", "3段", "4 全黒")
    private val standardPassthroughAlphas = listOf("1.0", "0.7", "0.5", "0.3", "0.1")

    fun transparency(level: Int): String {
        // Same SystemUI manager used by the physical button, with measured alpha
        // values. It updates compositor dimming and hardware together. This is
        // absolute selection, not key injection or a separate hardware override.
        require(level in standardPassthroughAlphas.indices) { "調光の段階が正しくありません。" }
        return "su 0 am broadcast -a com.android.systemui.xr.SET_PASSTHROUGH_DIMMING_ALPHA -p com.android.systemui --ef com.android.systemui.xr.PASSTHROUGH_OPACITY ${standardPassthroughAlphas[level]}"
    }

    fun openUrl(url: String): String {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val safe = normalized.replace("'", "'\\''")
        return "am start -n com.android.chrome/com.google.android.apps.chrome.Main -a android.intent.action.VIEW -d '$safe'"
    }
}
