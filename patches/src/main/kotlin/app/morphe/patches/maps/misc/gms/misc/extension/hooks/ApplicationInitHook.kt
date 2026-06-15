package app.morphe.patches.maps.misc.extension.hooks

import app.morphe.patches.all.misc.extension.ExtensionHook
import app.morphe.patches.maps.misc.gms.MapsApplicationOnCreateFingerprint
import app.morphe.patches.maps.misc.gms.MapsMainActivityOnCreateFingerprint

// Set the extension context from the Application.onCreate (runs before the activity).
internal val mapsApplicationInitHook = ExtensionHook(MapsApplicationOnCreateFingerprint)

// Set the extension context from the main activity onCreate as well.
internal val mapsActivityOnCreateHook = ExtensionHook(MapsMainActivityOnCreateFingerprint)
