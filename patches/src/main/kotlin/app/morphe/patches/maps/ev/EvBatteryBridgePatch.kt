package app.morphe.patches.maps.ev

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * ============================================================================================
 *  EvBatteryBridge patch — AAOS Maps EV/SOC planning ("Arrive with X%")
 *  Pasangan extension: app/morphe/extension/shared/patches/EvBatteryBridge.java
 *
 *  Ditulis pakai API fork (classDefForEach + mutableClassDefBy + findMutableMethodOf),
 *  TANPA sistem Fingerprint — biar cocok sama API yang dipakai ChangePackageNamePatch.kt.
 *
 *  BEST-EFFORT: cuma inject kalau method ketemu. Di Maps mobile / versi lain yg nama
 *  obfuscated-nya shift -> nggak ketemu -> no-op bersih, NGGAK abort build.
 *
 *  PIN ke Maps AAOS 26.03.020003.E. Peta field obfuscated: lihat EV-PORT-NOTES.md.
 * ============================================================================================
 */

private const val EXT = "Lapp/morphe/extension/shared/patches/EvBatteryBridge;"

@Suppress("unused")
val evBatteryBridgePatch = bytecodePatch(
    name = "EV battery bridge (AAOS)",
    description = "Inject EvBatteryBridge hooks for AAOS Maps EV/SOC route planning. No-op on mobile.",
) {
    compatibleWith("com.google.android.apps.maps")

    execute {
        // --- helper: apakah method memuat const-string yang mengandung `needle` ---
        fun Method.hasString(needle: String): Boolean {
            val impl = implementation ?: return false
            return impl.instructions.any { ins ->
                (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                    runCatching {
                        ((ins as ReferenceInstruction).reference as StringReference).string.contains(needle)
                    }.getOrDefault(false)
            }
        }

        // --- helper: inject invoke-static di awal method (entry) ---
        fun injectEntry(classDef: com.android.tools.smali.dexlib2.iface.ClassDef, method: Method, smali: String) {
            val mm = mutableClassDefBy(classDef).findMutableMethodOf(method)
            mm.addInstruction(0, smali)
        }

        classDefForEach { classDef ->
            // ===== ANCHOR A: class memuat "Invalid remaining meters value:" =====
            // -> captureRemainingDist (method dgn string itu) + fixRangeMarkerInMse (sibling Rect)
            val remainingDistMethod = classDef.methods.firstOrNull {
                it.returnType == "V" && it.hasString("Invalid remaining meters value:")
            }
            if (remainingDistMethod != null) {
                // HOOK 1: captureRemainingDist (entry, pass p1)
                injectEntry(
                    classDef, remainingDistMethod,
                    "invoke-static/range { p1 .. p1 }, $EXT->captureRemainingDist(Ljava/lang/Object;)V",
                )
                // HOOK 2: fixRangeMarkerInMse (sibling: 3 param, param[1] = Rect; entry, pass p3)
                classDef.methods.firstOrNull { m ->
                    m.parameterTypes.size == 3 &&
                        m.parameterTypes[1] == "Landroid/graphics/Rect;"
                }?.let { mseMethod ->
                    injectEntry(
                        classDef, mseMethod,
                        "invoke-static/range { p3 .. p3 }, $EXT->fixRangeMarkerInMse(Ljava/lang/Object;)V",
                    )
                }
            }

            // ===== ANCHOR B: class memuat "Invalid number for destinationIndex:" =====
            // -> overrideSearchArrival (method dgn string) + overrideSearchCardAiio (sibling 2-int)
            val searchArrivalMethod = classDef.methods.firstOrNull {
                it.hasString("Invalid number for destinationIndex:")
            }
            if (searchArrivalMethod != null) {
                // HOOK 3: overrideSearchArrival (entry, pass p1 = card, p2 = index)
                injectEntry(
                    classDef, searchArrivalMethod,
                    "invoke-static/range { p1 .. p2 }, $EXT->overrideSearchArrival(Ljava/lang/Object;I)V",
                )
                // HOOK 4: overrideSearchCardAiio (sibling: 4 param I,I,Integer; entry, pass p1)
                classDef.methods.firstOrNull { m ->
                    m.parameterTypes.size == 4 &&
                        m.parameterTypes[1] == "I" && m.parameterTypes[2] == "I" &&
                        m.parameterTypes[3] == "Ljava/lang/Integer;"
                }?.let { aiioMethod ->
                    injectEntry(
                        classDef, aiioMethod,
                        "invoke-static/range { p1 .. p1 }, $EXT->overrideSearchCardAiio(Ljava/lang/Object;)V",
                    )
                }
            }

            // ===== ANCHOR C: class memuat string Clearcut unik (R8-merged dgn adsx builder) =====
            // -> captureFromAdsx: cari method (object, boolean) -> object, inject SEBELUM return-object
            val hasAdsxAnchor = classDef.methods.any {
                it.hasString("BasicClearcutControllerImpl - CountersMap init()")
            }
            if (hasAdsxAnchor) {
                classDef.methods.firstOrNull { m ->
                    m.parameterTypes.size == 2 &&
                        m.parameterTypes[0].startsWith("L") &&
                        m.parameterTypes[1] == "Z" &&
                        m.returnType.startsWith("L") &&
                        m.implementation != null
                }?.let { adsxMethod ->
                    val insns = adsxMethod.implementation!!.instructions.toList()
                    val returnIndex = insns.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
                    if (returnIndex >= 0) {
                        val reg = (insns[returnIndex] as OneRegisterInstruction).registerA
                        val mm = mutableClassDefBy(classDef).findMutableMethodOf(adsxMethod)
                        mm.addInstructions(
                            returnIndex,
                            """
                                sput-object v$reg, $EXT->lastAdsx:Ljava/lang/Object;
                                invoke-static/range { v$reg .. v$reg }, $EXT->captureFromAdsx(Ljava/lang/Object;)V
                            """.trimIndent(),
                        )
                    }
                }
            }
        }
    }
}
