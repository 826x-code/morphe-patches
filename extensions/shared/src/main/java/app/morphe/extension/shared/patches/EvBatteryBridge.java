package app.morphe.extension.shared.patches;

import android.content.Context;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * EvBatteryBridge — rekonstruksi dari APK Thai (Maps AAOS 26.03.020003.E).
 *
 * Class ini di-decompile dari extension DEX build Thai (NON-obfuscated, ada debug info).
 * Ini setengah "extension" dari patch EV. Setengah lainnya = patch Kotlin (build-time)
 * yang nyuntik panggilan ke 5 method public static di bawah ke dalam method Maps
 * yang ter-obfuscate. Lihat EV-PORT-NOTES.md.
 *
 * ====================================================================================
 * PENTING — KETERIKATAN VERSI:
 *   Semua nama field/method 1-huruf di reflection ("a","b","c","d","e","f","h","k","p")
 *   itu nama obfuscated Maps yang DI-PIN ke 26.03. Tiap update Maps, nama ini SHIFT.
 *   Kalau ganti versi Maps: re-map nama-nama ini (lihat panduan di EV-PORT-NOTES.md).
 * ====================================================================================
 *
 * CATATAN REKONSTRUKSI:
 *   - getContext, setCurrentLevel, loadCorrectionConfig, whToBucket, computeArrivalWh,
 *     computeRangeM, roundedArrivalWh, captureFromAdsx, captureRemainingDist, logRoute:
 *     rekonstruksi tingkat tinggi-keyakinan dari smali.
 *   - fixRangeMarkerInMse, overrideSearchArrival, overrideSearchCardAiio:
 *     reflection walk yang intricate (manipulasi bit-flag di overlay map). Faithful,
 *     TAPI verifikasi empiris di HU sangat disarankan (smali aslinya 200+ instruksi).
 *   - Semua hook dibungkus try/catch(Throwable) best-effort: kalau reflection gagal
 *     (mis. nama field shift di versi lain), hook diam-diam no-op, Maps tetap jalan.
 *     Ini pilihan port yang aman; smali asli mengandalkan exception propagate ke caller.
 */
public final class EvBatteryBridge {

    private static final String TAG = "EvBridge";

    // content provider authority milik provider2 (app.morphe.byd.provider2 -> EnergyModelProvider)
    private static final String PROVIDER_URI = "content://app.morphe.byd.energy";
    private static final long REMAINING_STALE_MS = 4000L;

    // ---- data model (static state) ----
    private static boolean bypassCorrection;
    public  static long    cachedRemainingDistM;
    private static long    cachedRemainingTs;
    private static float[] configBucketRates;     // 10 rate Wh/km per bucket SOC (10% tiap bucket)
    private static float   configLearnedRate = 150.0f; // rate fallback linear (Wh/km)
    private static long    lastProviderReadTs;
    public  static Object  lastAdsx;              // cache route-state object terakhir
    public  static long    lastCapacityWh;        // kapasitas baterai (Wh)
    public  static int     lastCurrentLevelWh;    // SOC sekarang (Wh) -> sumber angka di layar
    public  static float   lastDrivingWhPerKm = 150.0f; // konsumsi terukur (Wh/km)
    public  static long    lastLevelWh;
    public EvBatteryBridge() {}

    /**
     * Seeds Maps' embedded EV connector manager when no OEM profile is available.
     * Connector enum values: MENNEKES/Type 2 = 3, CCS_COMBO_2 = 6.
     */
    public static void seedSealionConnectorProfile(Object manager) {
        if (manager == null) return;
        try {
            for (Field managerField : manager.getClass().getDeclaredFields()) {
                Class<?> profileType = managerField.getType();
                Constructor<?> profileConstructor = null;
                Class<?> connectorListType = null;

                for (Constructor<?> constructor : profileType.getDeclaredConstructors()) {
                    Class<?>[] parameters = constructor.getParameterTypes();
                    if (parameters.length == 5
                            && parameters[0] == String.class
                            && parameters[1] == String.class
                            && parameters[2] == String.class
                            && parameters[3] == parameters[4]
                            && java.util.Collection.class.isAssignableFrom(parameters[3])) {
                        profileConstructor = constructor;
                        connectorListType = parameters[3];
                        break;
                    }
                }
                if (profileConstructor == null || connectorListType == null) continue;

                Method twoItemFactory = null;
                for (Method method : connectorListType.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == connectorListType
                            && parameters.length == 2
                            && parameters[0] == Object.class
                            && parameters[1] == Object.class) {
                        twoItemFactory = method;
                        break;
                    }
                }
                if (twoItemFactory == null) continue;

                twoItemFactory.setAccessible(true);
                Object connectors = twoItemFactory.invoke(null, Integer.valueOf(3), Integer.valueOf(6));
                profileConstructor.setAccessible(true);
                Object profile = profileConstructor.newInstance(
                        "byd_sealion_7",
                        "byd_sealion_7",
                        "BYD Sealion 7",
                        connectors,
                        connectors
                );
                managerField.setAccessible(true);
                managerField.set(manager, profile);
                return;
            }
            Log.w(TAG, "Unable to locate embedded EV connector profile field");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to seed Sealion 7 connector profile", t);
        }
    }

    // =====================================================================================
    // SISI BACA / COMPUTE (logic murni — tidak menyentuh class Maps)
    // =====================================================================================

    /** Ambil application Context tanpa di-pass, via reflection ActivityThread. */
    private static Context getContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Dipanggil dari captureFromAdsx untuk menyimpan SOC + kapasitas live. */
    public static void setCurrentLevel(int currentLevelWh, int capacityWh) {
        lastCurrentLevelWh = currentLevelWh;
        lastLevelWh        = (long) currentLevelWh;
        lastCapacityWh     = (long) capacityWh;
    }

    /**
     * Baca config koreksi dari provider2 via ContentResolver.call("correction_config").
     * Bundle yang diharapkan dari provider2:
     *   bypass        : boolean
     *   learned_rate  : float   (>0 dipakai; <=0 fallback ke lastDrivingWhPerKm)
     *   rate_bucket_0..rate_bucket_9 : float (default -1.0; >0 berarti bucket itu valid)
     */
    private static void loadCorrectionConfig() {
        Context ctx = getContext();
        if (ctx == null) return;
        refreshEnergyModel(ctx);
        try {
            Bundle b = ctx.getContentResolver().call(
                    Uri.parse(PROVIDER_URI), "correction_config", null, null);
            if (b == null) return;

            bypassCorrection = b.getBoolean("bypass", false);

            float lr = b.getFloat("learned_rate", 0f);
            if (lr > 0f) configLearnedRate = lr;
            else         configLearnedRate = lastDrivingWhPerKm;

            float[] buckets = new float[10];
            boolean any = false;
            for (int i = 0; i < 10; i++) {
                buckets[i] = b.getFloat("rate_bucket_" + i, -1f);
                if (buckets[i] > 0f) any = true;
            }
            if (any) configBucketRates = buckets;
        } catch (Throwable ignored) {
        }
    }

    /** Read SOC and capacity directly from Provider2's protobuf /model endpoint. */
    private static void refreshEnergyModel(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProviderReadTs < 1000L) return;
        lastProviderReadTs = now;

        try (InputStream input = ctx.getContentResolver().openInputStream(
                Uri.parse(PROVIDER_URI + "/model"));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) return;
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            byte[] model = output.toByteArray();

            int[] pos = {0};
            while (pos[0] < model.length) {
                long tag = readVarint(model, pos, model.length);
                int field = (int) (tag >>> 3);
                int wireType = (int) (tag & 7);
                if (wireType == 2) {
                    int length = (int) readVarint(model, pos, model.length);
                    int end = pos[0] + length;
                    if (length < 0 || end < pos[0] || end > model.length) return;
                    if (field == 1 && parseBatteryModel(model, pos[0], end)) return;
                    pos[0] = end;
                } else {
                    skipField(model, pos, model.length, wireType);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean parseBatteryModel(byte[] data, int start, int end) {
        int levelWh = -1;
        int capacityWh = -1;
        int[] pos = {start};
        while (pos[0] < end) {
            long tag = readVarint(data, pos, end);
            int field = (int) (tag >>> 3);
            int wireType = (int) (tag & 7);
            if (wireType == 2) {
                int length = (int) readVarint(data, pos, end);
                int valueEnd = pos[0] + length;
                if (length < 0 || valueEnd < pos[0] || valueEnd > end) return false;
                if (field == 3) levelWh = readWrappedInt(data, pos[0], valueEnd);
                if (field == 4) capacityWh = readWrappedInt(data, pos[0], valueEnd);
                pos[0] = valueEnd;
            } else {
                skipField(data, pos, end, wireType);
            }
        }
        if (levelWh >= 0 && capacityWh >= 1000 && levelWh <= capacityWh) {
            setCurrentLevel(levelWh, capacityWh);
            return true;
        }
        return false;
    }

    private static int readWrappedInt(byte[] data, int start, int end) {
        int[] pos = {start};
        long tag = readVarint(data, pos, end);
        if ((tag >>> 3) != 1 || (tag & 7) != 0) return -1;
        long value = readVarint(data, pos, end);
        return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
    }

    private static long readVarint(byte[] data, int[] pos, int end) {
        long value = 0;
        for (int shift = 0; shift < 64 && pos[0] < end; shift += 7) {
            int current = data[pos[0]++] & 0xff;
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("Invalid protobuf varint");
    }

    private static void skipField(byte[] data, int[] pos, int end, int wireType) {
        switch (wireType) {
            case 0:
                readVarint(data, pos, end);
                return;
            case 1:
                pos[0] += 8;
                break;
            case 5:
                pos[0] += 4;
                break;
            default:
                throw new IllegalArgumentException("Unsupported protobuf wire type");
        }
        if (pos[0] > end) throw new IllegalArgumentException("Truncated protobuf field");
    }

    /** Petakan energi Wh ke index bucket SOC 0..9 (tiap bucket = 10% kapasitas). */
    private static int whToBucket(double wh, int cap) {
        double step = cap / 10.0;
        int bucket = (int) Math.ceil(wh / step) - 1;
        if (bucket < 0) bucket = 0;
        if (bucket > 9) return 9;
        return bucket;
    }

    /**
     * Hitung Wh tersisa setelah menempuh distM meter, dari currentWh dengan kapasitas capWh.
     * Model per-bucket: tiap 10% SOC punya rate sendiri (configBucketRates); fallback linear
     * pakai configLearnedRate kalau bucket tidak valid.
     *
     * @param currentWh SOC sekarang (Wh)
     * @param distM     jarak rute (meter)
     * @param capWh     kapasitas (Wh); <=0 -> pakai lastCapacityWh
     */
    private static int computeArrivalWh(int currentWh, int distM, int capWh) {
        int cap = (capWh > 0) ? capWh : (int) lastCapacityWh;

        // fallback linear kalau kapasitas tidak diketahui
        if (cap <= 0) {
            float km = distM / 1000.0f;
            int consumed = (int) (km * configLearnedRate);
            return Math.max(0, currentWh - consumed);
        }

        float[] rates = configBucketRates;
        double remainingWh = currentWh;
        double kmRemaining = distM / 1000.0;

        for (int i = 0; i < 11; i++) {
            if (kmRemaining <= 0.001) break;
            if (remainingWh <= 0) break;

            int bucket = whToBucket(remainingWh, cap);
            float rate;
            if (rates != null && rates[bucket] > 0f) rate = rates[bucket];
            else rate = configLearnedRate;

            double bucketLowerWh = (bucket * cap) / 10.0;       // batas bawah bucket ini
            double whInSegment   = remainingWh - bucketLowerWh; // energi di segmen bucket aktif
            double kmCoverable   = whInSegment / rate;          // km yg bisa ditempuh segmen ini

            if (kmCoverable >= kmRemaining) {
                // energi segmen ini cukup untuk sisa jarak
                remainingWh -= kmRemaining * rate;
                kmRemaining = 0;
            } else {
                // habiskan segmen ini, lanjut ke bucket bawah
                remainingWh = bucketLowerWh - 0.01;
                kmRemaining -= kmCoverable;
            }
        }
        return (int) Math.max(0.0, remainingWh);
    }

    /**
     * Hitung total jangkauan (meter) dari currentWh dengan kapasitas capWh.
     * Jumlahkan km yg bisa ditempuh tiap bucket SOC dari sekarang sampai 0.
     */
    private static int computeRangeM(int currentWh, int capWh) {
        int cap = (capWh > 0) ? capWh : (int) lastCapacityWh;

        if (cap <= 0) {
            // fallback linear
            float km = currentWh / configLearnedRate;
            return (int) (km * 1000.0f);
        }

        float[] rates = configBucketRates;
        double remainingWh = currentWh;
        double kmTotal = 0;

        for (int i = 0; i < 11; i++) {
            if (remainingWh <= 0) break;

            int bucket = whToBucket(remainingWh, cap);
            float rate;
            if (rates != null && rates[bucket] > 0f) rate = rates[bucket];
            else rate = configLearnedRate;

            double bucketLowerWh = (bucket * cap) / 10.0;
            double whInSegment   = remainingWh - bucketLowerWh;
            kmTotal += whInSegment / rate;

            remainingWh = bucketLowerWh - 0.01;
        }
        return (int) (kmTotal * 1000.0);
    }

    /**
     * Snap arrival Wh ke persen bulat, tanpa melebihi SOC sekarang / kapasitas.
     * @param arrivalWh Wh saat tiba (hasil computeArrivalWh)
     * @param capWh     kapasitas
     * @param currentWh SOC sekarang
     */
    private static int roundedArrivalWh(int arrivalWh, int capWh, int currentWh) {
        if (capWh <= 0) return arrivalWh;
        if (arrivalWh <= 0) return arrivalWh;

        int pct = (int) Math.round(arrivalWh * 100.0 / capWh);

        if (currentWh > 0) {
            int curPct = (int) (((long) currentWh * 100) / capWh);
            if (pct >= curPct) {
                // jangan biarkan arrival% > current%; selesai tanpa snap
                return arrivalWh;
            }
        }
        int snapped = (pct * capWh + 99) / 100;   // ceil(pct% * cap)
        if (arrivalWh >= snapped) return arrivalWh;
        if (snapped > capWh) return arrivalWh;
        return snapped;
    }

    /**
     * Kirim telemetry rute ke provider2 via call("log_correction") untuk learning rate.
     * @param consumedWh Wh terpakai (currentWh - arrivalWh)
     * @param arrivalWh  Wh saat tiba
     * @param capWh      kapasitas
     * @param distM      jarak (meter)
     */
    private static void logRoute(int consumedWh, int arrivalWh, int capWh, int distM) {
        if (capWh <= 0) return;
        Context ctx = getContext();
        if (ctx == null) return;

        int depWh = (lastCurrentLevelWh > 0) ? lastCurrentLevelWh : capWh;

        Bundle b = new Bundle();
        b.putLong("ts", System.currentTimeMillis());
        b.putFloat("dep_pct", depWh * 100.0f / capWh);
        b.putFloat("arrival_pct", arrivalWh * 100.0f / capWh);
        b.putInt("consumed_wh", consumedWh);
        b.putInt("dist_m", distM);
        b.putFloat("rate", configLearnedRate);
        b.putBoolean("bypass", bypassCorrection);

        ctx.getContentResolver().call(
                Uri.parse(PROVIDER_URI), "log_correction", null, b);
    }

    // =====================================================================================
    // SISI HOOK (dipanggil DARI method Maps obfuscated; nama field 1-huruf di-pin ke 26.03)
    // =====================================================================================

    /**
     * HOOK: route-state ("adsx"). Baca SOC live + kapasitas + konsumsi dari objek route-state.
     * Peta field (26.03):
     *   adsx.c.d.c (int)   = current level Wh
     *   adsx.c.e.c (int)   = capacity Wh
     *   adsx.d.c.c (float) = driving Wh/km
     */

    /**
     * HOOK 6 helper: dipanggil dari Lgir;->b. Kalau adsx yang dilempar Maps null,
     * substitusi dengan lastAdsx cache (yang di-set captureFromAdsx). Replikasi
     * null-coalesce yang di build Thai di-inline di Lgir;->b.
     */
    public static Object coalesceAdsx(Object adsx) {
        return (adsx != null) ? adsx : lastAdsx;
    }

    public static void captureFromAdsx(Object adsx) {
        if (adsx == null) return;
        lastAdsx = adsx;
        try {
            Object c = getField(adsx, "c");
            if (c != null) {
                Object cd = getField(c, "d");
                Object ce = getField(c, "e");
                if (cd != null && ce != null) {
                    int level = (Integer) getField(cd, "c");
                    int cap   = (Integer) getField(ce, "c");
                    setCurrentLevel(level, cap);
                }
            }
            Object d = getField(adsx, "d");
            if (d != null) {
                Object dc = getField(d, "c");
                if (dc != null) {
                    Object dcc = getField(dc, "c");
                    if (dcc != null) {
                        float rate = (Float) dcc;
                        if (rate > 0f) lastDrivingWhPerKm = rate;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * HOOK: remaining-distance. Panggil o.d() lalu baca field "k" (int meter tersisa).
     * Peta (26.03): o.d().k (int).
     */
    public static void captureRemainingDist(Object o) {
        if (o == null) return;
        try {
            Object r = o.getClass().getMethod("d").invoke(o);
            if (r == null) return;
            int k = (Integer) getField(r, "k");
            if (k >= 0) {
                cachedRemainingDistM = k;
                cachedRemainingTs = SystemClock.elapsedRealtime();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * HOOK: range marker di "Mse". Tulis ulang flag marker jangkauan pada overlay map.
     * INTRICATE — verifikasi empiris. Peta (26.03):
     *   mse.c() -> container; container.d() -> count; container.e(i) -> item
     *   item.f -> obj; obj.b -> Object[] elemen; obj.a.h -> context fallback
     *   elem.d (declared) -> target flag holder; elem.a.c -> marker; marker.e.b/.c -> flags/value
     */
    public static void fixRangeMarkerInMse(Object mse) {
        if (mse == null) return;
        try {
            loadCorrectionConfig();
            if (bypassCorrection) return;
            if (lastCurrentLevelWh <= 0) return;
            if (configLearnedRate <= 0f) return;

            int rangeM = computeRangeM(lastCurrentLevelWh, (int) lastCapacityWh);

            Object container = mse.getClass().getMethod("c").invoke(mse);
            if (container == null) return;
            int count = (Integer) container.getClass().getMethod("d").invoke(container);
            Method itemAt = container.getClass().getMethod("e", int.class);

            for (int i = 0; i < count; i++) {
                Object item = itemAt.invoke(container, i);
                if (item == null) continue;
                Object f = getField(item, "f");
                if (f == null) continue;
                Object[] elems = (Object[]) getField(f, "b");
                if (elems == null) continue;

                // konteks fallback: f.a.h
                Object ctxFallback = null;
                Object fa = getField(f, "a");
                if (fa != null) ctxFallback = getField(fa, "h");

                for (Object el : elems) {
                    if (el == null) continue;
                    Field declD = el.getClass().getDeclaredField("d");
                    declD.setAccessible(true);
                    Object flagHolder = declD.get(el);
                    if (flagHolder == null) continue;

                    // marker = el.a.c (atau fallback ctxFallback)
                    Object marker = null;
                    Object ea = getField(el, "a");
                    if (ea != null) marker = getField(ea, "c");
                    if (marker == null) marker = ctxFallback;

                    int markerVal = 0;
                    if (marker != null) {
                        Object e2 = getField(marker, "e");
                        if (e2 != null) {
                            int flags = (Integer) getField(e2, "b");
                            if ((flags & 1) != 0) {
                                markerVal = (Integer) getField(e2, "c");
                            }
                        }
                    }

                    Field declB = flagHolder.getClass().getDeclaredField("b");
                    declB.setAccessible(true);
                    int curFlag = declB.getInt(flagHolder);

                    if (markerVal > 0 && markerVal <= rangeM) {
                        // dalam jangkauan: clear bit (and -5)
                        declB.setInt(flagHolder, curFlag & -5);
                    } else {
                        // di luar jangkauan: set c & d ke rangeM, set bit (or 12)
                        Field declC = flagHolder.getClass().getDeclaredField("c");
                        Field declD2 = flagHolder.getClass().getDeclaredField("d");
                        declC.setAccessible(true);
                        declD2.setAccessible(true);
                        declC.setInt(flagHolder, rangeM);
                        declD2.setInt(flagHolder, rangeM);
                        declB.setInt(flagHolder, curFlag | 12);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * HOOK: "Arrive with X%" pada kartu search arrival.
     * INTRICATE — verifikasi empiris. Peta (26.03):
     *   card.b -> Object[]; card.b[idx] = entry
     *   entry.a.c (fallback card.a.h) -> section; section.e -> route
     *   entry.e (slot) -> kalau null, instantiate defpackage.ahxm; isi d/e/f
     *   entry.d flags: clear -5 / set 12; card.a.h.p menyimpan energi terpakai
     * @param idx index entry dalam array card.b
     */
    public static void overrideSearchArrival(Object card, int idx) {
        if (card == null) return;
        try {
            loadCorrectionConfig();
            if (bypassCorrection) return;
            if (lastCurrentLevelWh <= 0) return;
            if (configLearnedRate <= 0f) return;

            Object[] arr = (Object[]) getField(card, "b");
            if (arr == null || idx < 0 || idx >= arr.length) return;
            Object entry = arr[idx];
            if (entry == null) return;

            Object section = getField(getField(entry, "a"), "c");
            if (section == null) section = getField(getField(card, "a"), "h");
            if (section == null) return;
            Object route = getField(section, "e");
            if (route == null) return;

            // distInt = route.b (flags &1) -> route.c
            int flags0 = (Integer) getField(route, "b");
            if ((flags0 & 1) == 0) return;
            int distInt = (Integer) getField(route, "c");

            // pakai cachedRemainingDistM bila fresh (<4000ms) & valid
            int distM = distInt;
            long age = SystemClock.elapsedRealtime() - cachedRemainingTs;
            if (age < REMAINING_STALE_MS
                    && cachedRemainingDistM >= 0
                    && cachedRemainingDistM <= distInt) {
                distM = (int) cachedRemainingDistM;
            }
            if (distM <= 0) return;

            int arrivalWh = computeArrivalWh(lastCurrentLevelWh, distM, (int) lastCapacityWh);
            arrivalWh = roundedArrivalWh(arrivalWh, (int) lastCapacityWh, lastCurrentLevelWh);

            // slot e: kalau null, buat instance defpackage.ahxm
            Object slot = getField(entry, "e");
            if (slot == null) {
                Class<?> ahxm = Class.forName("defpackage.ahxm");
                Constructor<?> ctor = ahxm.getDeclaredConstructor();
                ctor.setAccessible(true);
                slot = ctor.newInstance();
                setField(entry, "e", slot);
            }
            // Kedua field wajib diisi; build lama keliru menulis field d dua kali.
            setField(slot, "d", Integer.valueOf(arrivalWh));
            setField(slot, "e", Integer.valueOf(arrivalWh));

            int capWh = (int) lastCapacityWh;
            if (capWh <= 0) capWh = 44900;          // fallback kapasitas
            setField(slot, "f", Integer.valueOf(capWh));

            // slot.b flags: (&-513)|28
            int sf = (Integer) getField(slot, "b");
            sf = (sf & -513) | 28;
            setField(slot, "b", Integer.valueOf(sf));

            // Holder range berada pada entry, bukan pada route.
            Object rd = getField(entry, "d");
            if (rd != null) {
                int rdFlag = (Integer) getField(rd, "b");
                if (arrivalWh > 0) {
                    setField(rd, "b", Integer.valueOf(rdFlag & -5));
                } else {
                    int rangeM = computeRangeM(lastCurrentLevelWh, (int) lastCapacityWh);
                    setField(rd, "c", Integer.valueOf(rangeM));
                    setField(rd, "d", Integer.valueOf(rangeM));
                    setField(rd, "b", Integer.valueOf(rdFlag | 12));
                }
            }

            // Holder konsumsi berada pada card.a.h.p.
            Object cardSection = getField(getField(card, "a"), "h");
            if (cardSection != null) {
                Object p = getField(cardSection, "p");
                if (p != null) {
                    setField(p, "c", Integer.valueOf(lastCurrentLevelWh - arrivalWh));
                    int pf = (Integer) getField(p, "b");
                    setField(p, "b", Integer.valueOf(pf | 1));
                }
            }

            logRoute(lastCurrentLevelWh - arrivalWh, arrivalWh, capWh, distM);
        } catch (Throwable ignored) {}
    }

    /**
     * HOOK: kartu search "aiio". Override arrival% + range pada entri kartu.
     * INTRICATE — verifikasi empiris. Peta (26.03):
     *   aiio.a.h -> sect ; sect.e -> route ; route.b (flags &1) -> route.c (dist int)
     *   sect.p.c = consumed, sect.p.b |= 1
     *   aiio.b -> Object[]; tiap el.d -> seg ; seg.b flag: arrival>0 -> &-5 ; else c/d=range, |12
     */
    public static void overrideSearchCardAiio(Object aiio) {
        if (aiio == null) return;
        try {
            loadCorrectionConfig();
            if (bypassCorrection) return;
            if (lastCurrentLevelWh <= 0) return;
            if (configLearnedRate <= 0f) return;

            Object sect = getField(getField(aiio, "a"), "h");
            if (sect == null) return;
            Object route = getField(sect, "e");
            if (route == null) return;

            int flags0 = (Integer) getField(route, "b");
            if ((flags0 & 1) == 0) return;
            int distInt = (Integer) getField(route, "c");
            if (distInt <= 0) return;

            int arrivalWh = computeArrivalWh(lastCurrentLevelWh, distInt, (int) lastCapacityWh);
            arrivalWh = roundedArrivalWh(arrivalWh, (int) lastCapacityWh, lastCurrentLevelWh);
            int consumed = lastCurrentLevelWh - arrivalWh;

            // Konsumsi tersimpan di section, bukan di route.e.
            Object p = getField(sect, "p");
            if (p != null) {
                setField(p, "c", Integer.valueOf(consumed));
                int pf = (Integer) getField(p, "b");
                setField(p, "b", Integer.valueOf(pf | 1));
            }

            Object[] arr = (Object[]) getField(aiio, "b");
            if (arr == null) return;
            for (Object el : arr) {
                if (el == null) continue;
                Object seg = getField(el, "d");
                if (seg == null) continue;
                int segFlag = (Integer) getField(seg, "b");
                if (arrivalWh > 0) {
                    setField(seg, "b", Integer.valueOf(segFlag & -5));
                } else {
                    int rangeM = computeRangeM(lastCurrentLevelWh, (int) lastCapacityWh);
                    setField(seg, "c", Integer.valueOf(rangeM));
                    setField(seg, "d", Integer.valueOf(rangeM));
                    setField(seg, "b", Integer.valueOf(segFlag | 12));
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---- helper reflection ----
    private static Object getField(Object o, String name) throws Exception {
        if (o == null) return null;
        Field f = o.getClass().getField(name);
        return f.get(o);
    }

    private static void setField(Object o, String name, Object val) throws Exception {
        Field f = o.getClass().getField(name);
        f.set(o, val);
    }
}
