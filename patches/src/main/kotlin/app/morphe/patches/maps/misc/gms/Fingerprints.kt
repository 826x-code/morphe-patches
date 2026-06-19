package app.morphe.patches.maps.misc.gms

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

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

/**
 * Maps' isGooglePlayServicesAvailable(Context, int):int  (0 = SUCCESS / ConnectionResult.SUCCESS).
 *
 * The standard Morphe GooglePlayUtilityFingerprint does NOT match Maps — it requires the strings
 * "This should never happen." / "MetadataValueReader", which Maps' method does not contain
 * (Maps uses "com.google.app.id", "com.google.android.gms.version", "android.hardware.type.embedded"
 * and throws GooglePlayServices*ManifestValueException). Because that fingerprint is optional in the
 * shared builder, it is silently skipped on Maps and the method is never forced to return 0 -> Maps
 * believes GMS is unavailable, causing the "Install Google Play services" prompt, idle GPS loss, and
 * broken account sign-in.
 *
 * We match it here with Maps-specific, rewrite-safe strings (neither contains "com.google.android.gms",
 * so the GMS->microG string rewrite never alters them) and force it to return 0.
 */
internal object MapsPlayServicesAvailabilityFingerprint : Fingerprint(
    returnType = "I",
    strings = listOf(
        "com.google.app.id",
        "android.hardware.type.embedded",
    )
)
