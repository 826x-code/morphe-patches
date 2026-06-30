package app.morphe.patches.maps.ev

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.maps.misc.extension.sharedExtensionPatch
import app.morphe.patches.maps.shared.Constants.COMPATIBILITY_MAPS
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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

private val evBatteryBridgeResourcePatch = resourcePatch {
    execute {
        val manifest = get("AndroidManifest.xml")
        var text = manifest.readText()

        if ("app.morphe.byd.energy" !in text) {
            text = text.replace(
                "</queries>",
                """
                    <package android:name="app.morphe.byd.provider2"/>
                    <provider android:authorities="app.morphe.byd.energy;google_maps_vehicle_profile"/>
                </queries>
                """.trimIndent(),
            )
        }
        manifest.writeText(text)
    }
}

@Suppress("unused")
val evBatteryBridgePatch = bytecodePatch(
    name = "EV battery bridge (AAOS)",
    description = "Inject EvBatteryBridge hooks for AAOS Maps EV/SOC route planning. No-op on mobile.",
) {
    dependsOn(sharedExtensionPatch, evBatteryBridgeResourcePatch, resourceMappingPatch)

    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        val evPrivacyTitleId = getResourceId(
            ResourceType.STRING,
            "CAR_PRIVACY_CENTER_EV_TITLE_V3",
        ).toLong()
        val toggleSettingOffId = getResourceId(ResourceType.STRING, "TOGGLE_SETTING_OFF").toLong()
        val toggleSettingOnId = getResourceId(ResourceType.STRING, "TOGGLE_SETTING_ON").toLong()

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

        fun forceBooleanCallSites(target: MethodReference) {
            classDefForEach { classDef ->
                classDef.methods.forEach { method ->
                    val instructions = method.implementation?.instructions?.toList()
                        ?: return@forEach
                    val callIndexes = instructions.indices.filter { index ->
                        val reference = (instructions[index] as? ReferenceInstruction)?.reference
                            as? MethodReference
                        reference?.definingClass == target.definingClass &&
                            reference.name == target.name &&
                            reference.parameterTypes == target.parameterTypes &&
                            reference.returnType == target.returnType &&
                            index + 1 < instructions.size &&
                            instructions[index + 1].opcode == Opcode.MOVE_RESULT
                    }
                    if (callIndexes.isNotEmpty()) {
                        val mutableMethod = mutableClassDefBy(classDef).findMutableMethodOf(method)
                        callIndexes.asReversed().forEach { callIndex ->
                            val register =
                                (instructions[callIndex + 1] as OneRegisterInstruction).registerA
                            mutableMethod.addInstruction(
                                callIndex + 2,
                                "const/16 v$register, 0x1",
                            )
                        }
                    }
                }
            }
        }

        // The privacy-center constructor calls the native EV consent getter immediately
        // before loading the EV title. Force that same getter at every call site, matching
        // the tested 1.2.3/1.2.21 patch binary.
        var evConsentGetter: MethodReference? = null
        classDefForEach { classDef ->
            val constructor = classDef.methods.firstOrNull { method ->
                method.name == "<init>" && method.implementation?.instructions?.any {
                    (it as? WideLiteralInstruction)?.wideLiteral == evPrivacyTitleId
                } == true
            } ?: return@classDefForEach
            val instructions = constructor.implementation!!.instructions.toList()
            val titleIndex = instructions.indexOfFirst {
                (it as? WideLiteralInstruction)?.wideLiteral == evPrivacyTitleId
            }
            evConsentGetter = (titleIndex - 1 downTo 0).firstNotNullOfOrNull { index ->
                val reference = (instructions[index] as? ReferenceInstruction)?.reference
                    as? MethodReference
                reference?.takeIf { it.returnType == "Z" }
            }
        }
        evConsentGetter?.let(::forceBooleanCallSites)

        // BYD runs Maps as Android user 0. Maps' native gate explicitly disables the EV model
        // for a system user before it even checks the vehicle profile. The known-good APK
        // overrides this exact method; identify it through UserManager.isSystemUser().
        classDefForEach { classDef ->
            classDef.methods.firstOrNull { method ->
                method.returnType == "Z" && method.implementation?.instructions?.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference
                        as? MethodReference
                    reference?.definingClass == "Landroid/os/UserManager;" &&
                        reference.name == "isSystemUser" &&
                        reference.returnType == "Z"
                } == true
            }?.let { systemUserEvGate ->
                val mutableMethod = mutableClassDefBy(classDef).findMutableMethodOf(systemUserEvGate)
                mutableMethod.addInstructions(
                    0,
                    """
                        const/4 v0, 0x1
                        return v0
                    """.trimIndent(),
                )
            }
        }

        // Route Maps' native AAOS energy model loader to Provider2 and enable only this
        // factory. This is the narrow gate that controls opening /model; forcing the global
        // consent getter instead starts privileged CAR_ENERGY code and crashes sideloads.
        classDefForEach { classDef ->
            classDef.methods.firstOrNull { it.hasString("google_maps_energy") }?.let { energyFactory ->
                val instructions = energyFactory.implementation!!.instructions.toList()
                val authorityIndex = instructions.indexOfFirst { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference
                        as? StringReference
                    reference?.string == "google_maps_energy"
                }
                if (authorityIndex >= 0) {
                    val register = (instructions[authorityIndex] as OneRegisterInstruction).registerA
                    val mutableMethod = mutableClassDefBy(classDef).findMutableMethodOf(energyFactory)
                    mutableMethod.replaceInstruction(
                        authorityIndex,
                        "const-string v$register, \"app.morphe.byd.energy\"",
                    )

                    // Provider2 is exported and does not require the platform CAR_ENERGY
                    // signature permission. Passing that permission here makes the observer
                    // reject a custom-signed sideload before /model is ever opened.
                    instructions.forEachIndexed { index, instruction ->
                        val permission = (instruction as? ReferenceInstruction)?.reference
                            as? StringReference
                        if (permission?.string == "android.car.permission.CAR_ENERGY") {
                            val permissionRegister =
                                (instruction as OneRegisterInstruction).registerA
                            mutableMethod.replaceInstruction(
                                index,
                                "const/4 v$permissionRegister, 0x0",
                            )
                        }
                    }

                    val enabledParameter = energyFactory.parameterTypes.indexOf("Z")
                    if (enabledParameter >= 0) {
                        mutableMethod.addInstruction(0, "const/4 p$enabledParameter, 0x1")
                    }
                }
            }
        }

        // Cosmetic consent state only. The sideload reads Provider2 directly, so the AAOS
        // CAR_ENERGY consent does not control data access and must not start its privileged path.
        classDefForEach { classDef ->
            classDef.methods.firstOrNull { method ->
                val literals = method.implementation?.instructions
                    ?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }
                    ?.toSet()
                    ?: return@firstOrNull false
                toggleSettingOffId in literals && toggleSettingOnId in literals
            }?.let { consentSummaryMethod ->
                val instructions = consentSummaryMethod.implementation!!.instructions.toList()
                val firstToggleIndex = instructions.indexOfFirst {
                    val literal = (it as? WideLiteralInstruction)?.wideLiteral
                    literal == toggleSettingOffId || literal == toggleSettingOnId
                }
                val consentCallIndex = (firstToggleIndex - 1 downTo 0).firstOrNull { index ->
                    val reference = (instructions[index] as? ReferenceInstruction)?.reference
                        as? MethodReference
                    reference != null && reference.returnType == "Z" &&
                        reference.parameterTypes.isEmpty() &&
                        index + 1 < instructions.size &&
                        instructions[index + 1].opcode == Opcode.MOVE_RESULT
                }
                if (consentCallIndex != null) {
                    val register =
                        (instructions[consentCallIndex + 1] as OneRegisterInstruction).registerA
                    mutableClassDefBy(classDef).findMutableMethodOf(consentSummaryMethod)
                        .addInstruction(consentCallIndex + 2, "const/16 v$register, 0x1")
                }
            }

        }

        // Preserve the exact UI-state override used by the stable binary: only the second
        // boolean is forced here. The first boolean remains controlled by the consent getter.
        classDefForEach { classDef ->
            val hasEvTitle = classDef.methods.any { method ->
                method.implementation?.instructions?.any {
                    (it as? WideLiteralInstruction)?.wideLiteral == evPrivacyTitleId
                } == true
            }
            if (hasEvTitle) {
                classDef.methods.firstOrNull { method ->
                    method.parameterTypes == listOf("Z", "Z") &&
                        method.returnType.startsWith("L") && method.implementation != null
                }?.let { stateFactory ->
                    injectEntry(classDef, stateFactory, "const/4 p2, 0x1")
                }
            }
        }

        // Seed the embedded EV connector profile for BYD Sealion 7. Maps stores the OEM
        // defaults and selected compatible plugs as two immutable lists. Both lists feed
        // EmbeddedEvConnectorsDataManager and are later copied into route/search settings.
        // MENNEKES (Type 2) = 3, CCS_COMBO_2 = 6 in Maps' ConnectorType enum.
        var connectorProfileType: String? = null
        classDefForEach { classDef ->
            val hasConnectorStateMarker = classDef.methods.any { method ->
                method.hasString("defaultConnectors") && method.hasString("selectedConnectors")
            }
            if (hasConnectorStateMarker) {
                classDef.methods.firstOrNull { method ->
                    method.returnType == classDef.type &&
                        method.parameterTypes.size == 2 &&
                        (method.accessFlags and AccessFlags.STATIC.value) != 0
                }?.let { stateFactory ->
                    connectorProfileType = stateFactory.parameterTypes[1].toString()
                }
            }
        }

        var connectorProfileClass:
            com.android.tools.smali.dexlib2.iface.ClassDef? = null
        var connectorProfileConstructor: Method? = null
        var connectorListType: String? = null
        connectorProfileType?.let { profileType ->
            classDefForEach { classDef ->
                if (classDef.type == profileType) {
                    classDef.methods.firstOrNull { method ->
                        method.name == "<init>" &&
                            method.parameterTypes.size == 5 &&
                            method.parameterTypes.take(3) == List(3) { "Ljava/lang/String;" } &&
                            method.parameterTypes[3] == method.parameterTypes[4]
                    }?.let { constructor ->
                        connectorProfileClass = classDef
                        connectorProfileConstructor = constructor
                        connectorListType = constructor.parameterTypes[3].toString()
                    }
                }
            }
        }

        var twoItemConnectorListFactory: MethodReference? = null
        connectorListType?.let { listType ->
            classDefForEach { classDef ->
                if (classDef.type == listType) {
                    classDef.methods.firstOrNull { method ->
                        method.returnType == listType &&
                            method.parameterTypes ==
                                listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
                            (method.accessFlags and AccessFlags.STATIC.value) != 0
                    }?.let { factory ->
                        twoItemConnectorListFactory = factory
                    }
                }
            }
        }

        val profileClass = connectorProfileClass
        val profileConstructor = connectorProfileConstructor
        val connectorListFactory = twoItemConnectorListFactory
        if (profileClass != null && profileConstructor != null && connectorListFactory != null) {
            val factoryDescriptor =
                "${connectorListFactory.definingClass}->${connectorListFactory.name}" +
                    "(Ljava/lang/Object;Ljava/lang/Object;)${connectorListFactory.returnType}"
            mutableClassDefBy(profileClass).findMutableMethodOf(profileConstructor).addInstructions(
                0,
                """
                    const/4 p4, 0x3
                    invoke-static { p4 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                    move-result-object p4
                    const/4 p5, 0x6
                    invoke-static { p5 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                    move-result-object p5
                    invoke-static { p4, p5 }, $factoryDescriptor
                    move-result-object p4
                    move-object p5, p4
                """.trimIndent(),
            )
        }

        // If Maps has no downloaded/OEM EV profile, its connector manager keeps the profile
        // null and never reaches the profile constructor above. Seed a local SL7 profile at
        // the end of that manager's constructor so EV routing always has compatible plugs.
        classDefForEach { classDef ->
            val isEmbeddedConnectorManager = classDef.methods.any { method ->
                method.hasString("EmbeddedEvConnectorsDataManager:")
            }
            if (isEmbeddedConnectorManager) {
                classDef.methods.firstOrNull { method ->
                    method.name == "<init>" &&
                        method.parameterTypes.firstOrNull() == "Landroid/content/Context;"
                }?.let { constructor ->
                    val instructions = constructor.implementation!!.instructions.toList()
                    val mutableConstructor =
                        mutableClassDefBy(classDef).findMutableMethodOf(constructor)
                    instructions.indices.filter {
                        instructions[it].opcode == Opcode.RETURN_VOID
                    }.asReversed().forEach { returnIndex ->
                        mutableConstructor.addInstruction(
                            returnIndex,
                            "invoke-static/range { p0 .. p0 }, " +
                                "$EXT->seedSealionConnectorProfile(Ljava/lang/Object;)V",
                        )
                    }
                }
            }
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
            // -> captureFromAdsx: cari method (object, boolean) -> object, inject sebelum semua
            // return-object. Method ini punya jalur hasil valid dan jalur null; memilih return terakhir
            // saja membuat bridge selalu menangkap v0 dari jalur null.
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
                    val returnIndexes = insns.indices.filter {
                        insns[it].opcode == Opcode.RETURN_OBJECT
                    }
                    if (returnIndexes.isNotEmpty()) {
                        val mm = mutableClassDefBy(classDef).findMutableMethodOf(adsxMethod)
                        returnIndexes.asReversed().forEach { returnIndex ->
                            val reg = (insns[returnIndex] as OneRegisterInstruction).registerA
                            mm.addInstruction(
                                returnIndex,
                                "invoke-static/range { v$reg .. v$reg }, " +
                                    "$EXT->captureFromAdsx(Ljava/lang/Object;)V",
                            )
                        }
                    }
                }
            }

            // ===== HOOK 6: Lgir;->b reads lastAdsx — coalesce null adsx param with cached =====
            // Anchor: instance method, 5 params, param[3] = j$.time.Duration, returns Object,
            // param[2] = adsx type (obfuscated). Inject at entry: p3 = coalesceAdsx(p3).
            classDef.methods.firstOrNull { m ->
                m.parameterTypes.size == 5 &&
                    m.parameterTypes[2] == "Ladsx;" &&
                    m.parameterTypes[3] == "Lj\$/time/Duration;" &&
                    m.returnType == "Ljava/lang/Object;" &&
                    m.implementation != null
            }?.let { gMethod ->
                val adsxType = gMethod.parameterTypes[2]
                val mm = mutableClassDefBy(classDef).findMutableMethodOf(gMethod)
                mm.addInstructions(
                    0,
                    """
                        invoke-static/range { p3 .. p3 }, $EXT->coalesceAdsx(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object p3
                        check-cast p3, $adsxType
                    """.trimIndent(),
                )
            }
        }
    }
}
