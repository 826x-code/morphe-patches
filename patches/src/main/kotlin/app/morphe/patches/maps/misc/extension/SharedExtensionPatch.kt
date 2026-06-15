package app.morphe.patches.maps.misc.extension

import app.morphe.patches.all.misc.extension.sharedExtensionPatch
import app.morphe.patches.maps.misc.extension.hooks.mapsActivityOnCreateHook
import app.morphe.patches.maps.misc.extension.hooks.mapsApplicationInitHook

// "maps" => the extensions/maps module (which pulls in extensions/shared = GmsCoreSupport).
// If the build complains that "maps" is unknown, try listOf("shared") and delete the
// extensions/maps module (the shared extension already contains everything Maps needs).
val sharedExtensionPatch = sharedExtensionPatch(
    listOf("maps"),
    mapsApplicationInitHook,
    mapsActivityOnCreateHook,
)
