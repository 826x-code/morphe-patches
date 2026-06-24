package app.morphe.patches.maps.ev

import app.morphe.patcher.fingerprint.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/*
 * ============================================================================================
 *  EvBatteryBridge patch — AAOS Maps EV/SOC planning ("Arrive with X%")
 *  Pasangan extension: app/morphe/extension/shared/patches/EvBatteryBridge.java
 *
 *  BEST-EFFORT: semua fingerprint *OrNull + guard. Build ke Maps mobile / versi lain yg
 *  nama obfuscated-nya shift -> fingerprint nggak resolve -> no-op bersih, NGGAK abort build.
 *
 *  PIN ke Maps AAOS 26.03.020003.E. Lihat EV-PORT-NOTES.md untuk peta field obfuscated.
 * ============================================================================================
 */

private const val EXT = "Lapp/morphe/extension/shared/patches/EvBatteryBridge;"

// --------------------------------------------------------------------------------------------
// FINGERPRINTS  (3 string-anchor + derivasi sibling; semua rewrite-safe)
// --------------------------------------------------------------------------------------------

// ANCHOR A: Lmny;->o(Lwdu; Lnoe;)V  (remaining-distance setter). Class Lmny; juga memuat
// sibling e(Laavb; Rect; Lmse;)Laavb; untuk fixRangeMarkerInMse.
internal object MapsEvRemainingDistFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("Invalid remaining meters value:"),
)

// ANCHOR B: Lqft;->b(Lmrb; I Ljava/lang/Integer;)Lqfr;  (search arrival). Class Lqft; juga
// memuat sibling e(Lmrb; I I Ljava/lang/Integer;)Lqfr; untuk overrideSearchCardAiio.
internal object MapsEvSearchArrivalFingerprint : Fingerprint(
    strings = listOf("Invalid number for destinationIndex:"),
)

// ANCHOR C (SOLID): adsx route-state builder = Lrgw;->h(Lqgt; Z)Ladsx;.
// Method `h` sendiri TANPA string. Tapi class-nya (Lrgw;, hasil R8 merge) memuat string unik
// rewrite-safe di salah satu <init>. Kita pin CLASS via string itu, lalu cari `h` secara
// struktural: satu-satunya method (object, boolean) -> object di class tsb.
// CATATAN VERSI: pin ini bergantung pada R8 menggabungkan string + `h` ke class yang sama.
// Di versi lain bisa ke-merge beda -> kalau adsx gagal resolve, ini titik pertama yg di-recheck.
internal object MapsEvAdsxClassFingerprint : Fingerprint(
    strings = listOf("BasicClearcutControllerImpl - CountersMap init()"),
)

// --------------------------------------------------------------------------------------------
// PATCH
// --------------------------------------------------------------------------------------------

val evBatteryBridgePatch = bytecodePatch(
    name = "EV battery bridge (AAOS)",
    description = "Inject EvBatteryBridge hooks for AAOS Maps EV/SOC route planning. No-op on mobile.",
) {
    compatibleWith("com.google.android.apps.maps")

    execute {
        // ---- HOOK 1: captureRemainingDist  (entry, pass p1) ----
        MapsEvRemainingDistFingerprint.methodOrNull?.apply {
            addInstruction(
                0,
                "invoke-static { p1 }, $EXT->captureRemainingDist(Ljava/lang/Object;)V",
            )
        }

        // ---- HOOK 2: fixRangeMarkerInMse  (sibling 'e' di class Anchor A; entry, pass p3) ----
        MapsEvRemainingDistFingerprint.classDefOrNull?.methods
            ?.firstOrNull { m ->
                m.parameterTypes.size == 3 &&
                    m.parameterTypes[1] == "Landroid/graphics/Rect;"
            }
            ?.apply {
                addInstruction(
                    0,
                    "invoke-static { p3 }, $EXT->fixRangeMarkerInMse(Ljava/lang/Object;)V",
                )
            }

        // ---- HOOK 3: overrideSearchArrival  (entry, pass p1 = card, p2 = index) ----
        MapsEvSearchArrivalFingerprint.methodOrNull?.apply {
            addInstruction(
                0,
                "invoke-static { p1, p2 }, $EXT->overrideSearchArrival(Ljava/lang/Object;I)V",
            )
        }

        // ---- HOOK 4: overrideSearchCardAiio  (sibling 'e' di class Anchor B; entry, pass p1) ----
        MapsEvSearchArrivalFingerprint.classDefOrNull?.methods
            ?.firstOrNull { m ->
                m.parameterTypes.size == 4 &&
                    m.parameterTypes[1] == "I" && m.parameterTypes[2] == "I" &&
                    m.parameterTypes[3] == "Ljava/lang/Integer;"
            }
            ?.apply {
                addInstruction(
                    0,
                    "invoke-static { p1 }, $EXT->overrideSearchCardAiio(Ljava/lang/Object;)V",
                )
            }

        // ---- HOOK 5: captureFromAdsx  (RETURN injection + simpan lastAdsx) ----
        // Cari method `h` di class Anchor C: (object, boolean) -> object, satu-satunya yg cocok.
        MapsEvAdsxClassFingerprint.classDefOrNull?.methods
            ?.firstOrNull { m ->
                m.parameterTypes.size == 2 &&
                    m.parameterTypes[0].startsWith("L") &&
                    m.parameterTypes[1] == "Z" &&
                    m.returnType.startsWith("L")
            }
            ?.apply {
                // inject sebelum 'return-object vX' terakhir, pada register yg dikembalikan
                val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
                if (returnIndex >= 0) {
                    val reg = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                    addInstructions(
                        returnIndex,
                        """
                            sput-object v$reg, $EXT->lastAdsx:Ljava/lang/Object;
                            invoke-static { v$reg }, $EXT->captureFromAdsx(Ljava/lang/Object;)V
                        """.trimIndent(),
                    )
                }
            }
    }
}
