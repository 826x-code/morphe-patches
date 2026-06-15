package app.morphe.patches.maps.misc.gms

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.packagename.changePackageNamePatch
import app.morphe.patches.all.misc.packagename.setOrGetFallbackPackageName
import app.morphe.patches.maps.misc.extension.sharedExtensionPatch
import app.morphe.patches.maps.shared.Constants.COMPATIBILITY_MAPS
import app.morphe.patches.maps.shared.Constants.MAPS_PACKAGE_NAME
import app.morphe.patches.maps.shared.Constants.MORPHE_MAPS_PACKAGE_NAME
import app.morphe.patches.shared.misc.gms.gmsCoreSupportPatch
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val GMS_CORE_VENDOR_GROUP_ID = "app.revanced"

// Google Maps signing certificate SHA-1 (stable across versions).
private const val MAPS_SPOOFED_PACKAGE_SIGNATURE = "38918a453d07199354f8b19af05ec6562ced5788"

@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = MAPS_PACKAGE_NAME,
    toPackageName = MORPHE_MAPS_PACKAGE_NAME,
    // Maps has no "prime" method and no Cast context (unlike YouTube/Music).
    primeMethodFingerprint = null,
    earlyReturnFingerprints = setOf(),
    // NOTE: the shared builder ALWAYS returns ServiceCheckFingerprint early and, if present,
    // GooglePlayUtilityFingerprint early(0). In Maps 26.20.x these matched "Lbijd;" b()/c()
    // exactly, so no extra earlyReturnFingerprints are needed.
    mainActivityOnCreateFingerprint = MapsMainActivityOnCreateFingerprint,
    extensionPatch = sharedExtensionPatch,
    gmsCoreSupportResourcePatchFactory = ::mapsGmsCoreSupportResourcePatch,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(COMPATIBILITY_MAPS)
}

/**
 * Screen-less GmsCore resource patch for Maps: manifest rename + spoof metadata only.
 * (Maps has no Morphe settings screen, so we skip the preference-screen additions and
 * linkHandlingPatch used by the shared gmsCoreSupportResourcePatch.)
 *
 * Mirrors app.morphe.patches.shared.misc.gms.gmsCoreSupportResourcePatch
 * (patchManifest + addSpoofingMetadata).
 */
private fun mapsGmsCoreSupportResourcePatch() = resourcePatch {
    dependsOn(changePackageNamePatch)

    execute {
        fun Node.adoptChild(tagName: String, block: Element.() -> Unit) {
            val child = ownerDocument.createElement(tagName)
            child.block()
            appendChild(child)
        }

        val packageName = setOrGetFallbackPackageName(MORPHE_MAPS_PACKAGE_NAME)

        // --- patchManifest() ---
        val transformations = mapOf(
            "package=\"$MAPS_PACKAGE_NAME" to "package=\"$packageName",
            "android:authorities=\"$MAPS_PACKAGE_NAME" to "android:authorities=\"$packageName",
            "$MAPS_PACKAGE_NAME.permission.C2D_MESSAGE" to "$packageName.permission.C2D_MESSAGE",
            "$MAPS_PACKAGE_NAME.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to
                "$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "com.google.android.c2dm" to "$GMS_CORE_VENDOR_GROUP_ID.android.c2dm",
            "</queries>" to "<package android:name=\"$GMS_CORE_VENDOR_GROUP_ID.android.gms\"/></queries>",
        )
        val manifest = get("AndroidManifest.xml")
        manifest.writeText(
            transformations.entries.fold(manifest.readText()) { acc, (from, to) -> acc.replace(from, to) }
        )

        // --- addSpoofingMetadata() ---
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0)
            application.adoptChild("meta-data") {
                setAttribute("android:name", "$GMS_CORE_VENDOR_GROUP_ID.android.gms.SPOOFED_PACKAGE_NAME")
                setAttribute("android:value", MAPS_PACKAGE_NAME)
            }
            application.adoptChild("meta-data") {
                setAttribute("android:name", "$GMS_CORE_VENDOR_GROUP_ID.android.gms.SPOOFED_PACKAGE_SIGNATURE")
                setAttribute("android:value", MAPS_SPOOFED_PACKAGE_SIGNATURE)
            }
            application.adoptChild("meta-data") {
                setAttribute("android:name", "app.revanced.MICROG_PACKAGE_NAME")
                setAttribute("android:value", "$GMS_CORE_VENDOR_GROUP_ID.android.gms")
            }
        }
    }
}
