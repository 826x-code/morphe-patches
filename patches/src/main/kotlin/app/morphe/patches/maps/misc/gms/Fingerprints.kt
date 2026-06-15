package app.morphe.patches.maps.misc.gms

import app.morphe.patcher.Fingerprint

/**
 * GmmActivity.onCreate(Bundle). The declared launcher activity
 * com.google.android.maps.MapsActivity extends an obfuscated base ("Lmrl;" in 26.20.x)
 * whose onCreate carries these stable trace tags. Anchored on strings so it survives
 * obfuscation/version drift.
 */
internal object MapsMainActivityOnCreateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    strings = listOf(
        "GmmActivity.onCreate",
        "GmmActivity:onCreate:body",
    )
)

/**
 * GoogleMapsApplication.onCreate(). The Application onCreate lives in an obfuscated
 * base ("Lmvs;" in 26.20.x). Anchored on stable trace strings.
 */
internal object MapsApplicationOnCreateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf(
        "GoogleMapsApplication.onCreate",
        "Application creation",
    )
)
