package app.morphe.patches.maps.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val MAPS_PACKAGE_NAME = "com.google.android.apps.maps"
    const val MORPHE_MAPS_PACKAGE_NAME = "app.morphe.android.apps.maps"

    val COMPATIBILITY_MAPS = Compatibility(
        name = "Google Maps",
        packageName = MAPS_PACKAGE_NAME,
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0x34A853,
        // Maps is signed v2/v3 by Google with a stable cert (SHA-1 38918a45...).
        // Left empty so the patch is not signature-gated. Add the SHA-256 of the
        // signing cert here if you want strict gating.
        signatures = setOf(),
        targets = listOf(
            // Mark experimental so newer Maps builds can still be selected in Manager.
            AppTarget(version = "26.20.01.913318892", minSdk = 28, isExperimental = true),
        )
    )
}
