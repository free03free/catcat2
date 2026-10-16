package app.talkingcat;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;
import com.k2fsa.sherpa.onnx.GenerationConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** محرك النطق العربي (محلي بالكامل). */
final class EchoEngine {
    interface Callback {
        void emit(String type, String payload);
        void report(String title, String msg);
        void toast(String msg);
        void prefetchIdle();
    }

    private static final String ASSET_DIR = "models/tts-ar";

    private final Context ctx;
    private final Callback cb;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger gen = new AtomicInteger();
    private volatile AudioTrack track;
    private OfflineTts tts;
    private TextToSpeech sys;
    private volatile boolean sysReady = false;
    private volatile boolean useSystem = false; // جوجل بلا عربية في جهازك؛ المحرك المدمج هو الأساس
    private volatile int voiceId = 9;           // Supertonic: 10 أصوات (0..9)؛ الصوت 10 كان الأوضح في القياس
    private volatile int steps = 8;             // خطوات التوليد: أقل = أسرع (3 سريع، 4 متوازن، 6 أوضح)
    // إعادة صوتك على خيط مستقل: كانت تنتظر خلف تحميل نموذج النطق وتوليده (عدة ثوانٍ) فتتأخر أو تضيع
    private final ExecutorService playExec = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.locks.ReentrantLock genLock = new java.util.concurrent.locks.ReentrantLock(true); // النموذج لا يُستدعى من خيطين معًا
    private final AtomicInteger fgBusy = new AtomicInteger(0);
    private final AtomicInteger prefetchGen = new AtomicInteger(0);
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tts-prefetch"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY); return t; });
    // ذاكرة مؤقتة: الحروف والكلمات تتكرر كثيرًا، فلا نعيد توليدها
    /** مقطع صوتي مع معدل عيناته (المضمَّن 22050، والمولَّد وقت التشغيل 44100). */
    private static final class Clip { final float[] s; final int rate; Clip(float[] s, int rate) { this.s = s; this.rate = rate; } }
    private final Map<String, Clip> cache = new LinkedHashMap<String, Clip>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Clip> e) { return size() > 120; }
    };
    private volatile long lastFg = 0L;           // آخر وقت ضغط فيه الطفل (لإيقاف التجهيز الخلفي أثناء اللعب)

    void setVoice(int v) { voiceId = Math.max(0, Math.min(9, v)); }
    /** طريقة السرعة: 0 تلقائي (ذكي) · 1 فوري (الصوت الجاهز + تعديل السرعة دائمًا) · 2 أنقى جودة (توليد بالسرعة المطلوبة دائمًا). */
    private volatile int speedMode = 0;
    void setSpeedMode(int m) { speedMode = Math.max(0, Math.min(2, m)); }
    private final java.util.HashMap<Integer, java.util.HashMap<String, Integer>> preSpd = new java.util.HashMap<>();
    /** سرعة المقطع الجاهز لهذا النص في الحزمة (نسبة مئوية، مثل 90)، أو 0 إن لم يوجد. */
    private synchronized int preSpeedFor(String text) {
        org.json.JSONObject o = preFor(voiceId);
        if (o == null) return 0;
        java.util.HashMap<String, Integer> m = preSpd.get(voiceId);
        if (m == null) {
            m = new java.util.HashMap<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next(); int i = k.indexOf('|');
                if (i <= 0) continue;
                try { String t = k.substring(i + 1); if (!m.containsKey(t)) m.put(t, Integer.parseInt(k.substring(0, i))); } catch (NumberFormatException ignored) {}
            }
            preSpd.put(voiceId, m);
        }
        Integer v = m.get(text);
        return v == null ? 0 : v;
    }
    void setSteps(int v) { steps = Math.max(2, Math.min(10, v)); }
    private LoudnessEnhancer enhancer;

    void setUseSystem(boolean on) { useSystem = on; }

    /** صوت النظام العربي (Google/Samsung…): يعالج العربية أفضل من Piper ويقبل النص بلا تشكيل. */
    private void initSystemTts() {
        try {
            sys = new TextToSpeech(ctx, st -> {
                if (st != TextToSpeech.SUCCESS) return;
                int r = sys.setLanguage(new Locale("ar"));
                sysReady = r >= TextToSpeech.LANG_AVAILABLE;
            });
        } catch (Throwable ignored) {}
    }

    /** يولّد الكلام عبر صوت النظام إلى ملف WAV مؤقت ثم يقرأ العينات، لنعالجها بنفس خط الصوت. */
    private float[] synthSystem(String text, float speed, int[] outRate) {
        if (!sysReady || sys == null) return null;
        File f = new File(ctx.getCacheDir(), "sys_" + System.nanoTime() + ".wav");
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = {false};
            final String id = "u" + System.nanoTime();
            sys.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String u) {}
                @Override public void onDone(String u) { if (id.equals(u)) { ok[0] = true; done.countDown(); } }
                @Override @SuppressWarnings("deprecation") public void onError(String u) { if (id.equals(u)) done.countDown(); }
            });
            sys.setSpeechRate(Math.max(0.5f, Math.min(1.5f, speed)));
            Bundle p = new Bundle();
            if (sys.synthesizeToFile(text, p, f, id) != TextToSpeech.SUCCESS) return null;
            if (!done.await(15, TimeUnit.SECONDS) || !ok[0]) return null;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            int rate = 22050, pos = 12, dataOff = -1, dataLen = 0;
            while (pos + 8 <= b.length) {
                String tag = new String(b, pos, 4, "US-ASCII");
                int len = (b[pos+4]&255) | (b[pos+5]&255)<<8 | (b[pos+6]&255)<<16 | (b[pos+7]&255)<<24;
                if (tag.equals("fmt ")) rate = (b[pos+12]&255) | (b[pos+13]&255)<<8 | (b[pos+14]&255)<<16 | (b[pos+15]&255)<<24;
                if (tag.equals("data")) { dataOff = pos + 8; dataLen = (len <= 0 || dataOff + len > b.length) ? b.length - dataOff : len; break; }
                pos += 8 + len + (len & 1);
            }
            if (dataOff < 0 || dataLen < 2) return null;
            float[] out = new float[dataLen / 2];
            for (int i = 0; i < out.length; i++) out[i] = (short)((b[dataOff+2*i]&255) | (b[dataOff+2*i+1]<<8)) / 32768f;
            outRate[0] = rate;
            return out;
        } catch (Throwable t) { return null; }
        finally { f.delete(); }
    }

    /**
     * معالجة الصوت: رفع حتى متوسط طاقة (RMS) ثابت ثم محدِّد tanh بدل القصّ.
     * الأصوات الخام لـ Piper خافتة جدًا (RMS≈‎-40dBFS)؛ تطبيع الذروة وحده كان يترك الحروف والكلمات القصيرة خافتة.
     * lead/tail صمت قصير يمنع قصّ أول الحرف وآخره.
     */
    private static short[] process(float[] smp, int sr, float volume, boolean pad) {
        double sum = 0; for (float x : smp) sum += x * x;
        float rms = (float) Math.sqrt(sum / Math.max(1, smp.length));
        float user = Math.max(0f, Math.min(1f, volume)); // المنزلق يتحكم فعليًا: 0 = صمت، 100% = أعلى مستوى
        float gain = Math.min(0.24f / Math.max(rms, 1e-4f), 20f);
        int lead = pad ? sr * 60 / 1000 : 0, tail = pad ? sr * 140 / 1000 : 0;
        short[] pcm = new short[lead + smp.length + tail];
        for (int i = 0; i < smp.length; i++) {
            double y = softLimit(smp[i] * gain) * 0.98 * user;
            pcm[lead + i] = (short) Math.max(-32767, Math.min(32767, y * 32767));
        }
        return pcm;
    }

    /** محدِّد ناعم: خطي حتى 0.7 ثم ضغط لطيف للقمم فقط، فلا يتشوّه الكلام ولا يخفت. */
    private static double softLimit(double v) {
        double a = Math.abs(v);
        if (a <= 0.6) return v;
        double y = 0.6 + 0.38 * Math.tanh((a - 0.6) / 0.38);
        return v < 0 ? -y : y;
    }

    EchoEngine(Context ctx, Callback cb) { this.ctx = ctx; this.cb = cb; }

    /** ينسخ نموذج الصوت من assets إلى مجلد التطبيق (كان هذا مفقودًا → لا صوت أبدًا) ثم يحمّله. */
    void prepare() {
        initSystemTts();
        exec.execute(() -> {
            try {
                housekeeping();
                installAssets();
                if (canSpeak()) { getTts(); try { synthBuiltin("مَرْحَبًا", 0.9f); } catch (Throwable ignored) {} cb.emit("ttsready", "1"); }
                else cb.toast("ملفات صوت Supertonic غير موجودة داخل التطبيق. شغّل fetch-engine.sh ثم أعد البناء.");
            } catch (Throwable t) {
                cb.toast("تعذّر تجهيز الصوت: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        });
    }

    private File ttsDir() { return new File(ctx.getFilesDir(), "models/tts-ar"); }

    private static final String[] TTS_FILES = {
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin"
    };

    private boolean canSpeak() {
        File d = ttsDir();
        if (!d.isDirectory()) return false;
        for (String f : TTS_FILES) { File x = new File(d, f); if (!x.isFile() || x.length() == 0) return false; }
        return true;
    }

    private String stamp() {
        try { return String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime); }
        catch (Throwable t) { return "0"; }
    }

    private void installAssets() throws IOException {
        File d = ttsDir();
        File mark = new File(d, ".installed");
        String st = stamp();
        if (mark.isFile() && canSpeak()) {
            try (InputStream in = new java.io.FileInputStream(mark)) {
                byte[] b = new byte[64]; int n = in.read(b);
                if (n > 0 && st.equals(new String(b, 0, n))) return;
            } catch (IOException ignored) {}
        }
        String[] top = ctx.getAssets().list(ASSET_DIR);
        if (top == null || top.length == 0) return;
        cb.toast("جارٍ تجهيز صوت القط لأول مرة… لحظات ⏳");
        deleteRec(d);
        d.mkdirs();
        copyAsset(ASSET_DIR, d);
        try (OutputStream o = new FileOutputStream(mark)) { o.write(st.getBytes()); }
    }

    private void copyAsset(String path, File dest) throws IOException {
        String[] kids = ctx.getAssets().list(path);
        if (kids != null && kids.length > 0) {
            dest.mkdirs();
            for (String k : kids) copyAsset(path + "/" + k, new File(dest, k));
            return;
        }
        try (InputStream in = ctx.getAssets().open(path); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (java.io.FileNotFoundException e) {
            dest.mkdirs(); // مجلد فارغ
        }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteRec(c);
        f.delete();
    }

    private synchronized OfflineTts getTts() {
        if (tts != null) return tts;
        tts = buildTts(4);
        return tts;
    }

    private OfflineTts buildTts(int threads) {
        File d = ttsDir();
        OfflineTtsSupertonicModelConfig v = new OfflineTtsSupertonicModelConfig();
        v.setDurationPredictor(new File(d, "duration_predictor.int8.onnx").getAbsolutePath());
        v.setTextEncoder(new File(d, "text_encoder.int8.onnx").getAbsolutePath());
        v.setVectorEstimator(new File(d, "vector_estimator.int8.onnx").getAbsolutePath());
        v.setVocoder(new File(d, "vocoder.int8.onnx").getAbsolutePath());
        v.setTtsJson(new File(d, "tts.json").getAbsolutePath());
        v.setUnicodeIndexer(new File(d, "unicode_indexer.bin").getAbsolutePath());
        v.setVoiceStyle(new File(d, "voice.bin").getAbsolutePath());
        OfflineTtsModelConfig m = new OfflineTtsModelConfig();
        m.setSupertonic(v); m.setNumThreads(threads); m.setDebug(false);
        OfflineTtsConfig c = new OfflineTtsConfig(); c.setModel(m);
        return new OfflineTts(null, c);
    }

    /** يقصّ الصمت الطويل قبل الكلام وبعده (Supertonic يضيف نحو 0.4–0.6ث قبل و0.6–0.75ث بعد)، وهو أكبر سبب لإحساس التأخر. */
    private static float[] trim(float[] s, int sr) {
        float peak = 0f; for (float x : s) peak = Math.max(peak, Math.abs(x));
        if (peak < 1e-4f) return s;
        float th = Math.max(0.012f * peak, 1e-4f);
        int first = 0, last = s.length - 1;
        while (first < s.length && Math.abs(s[first]) < th) first++;
        while (last > first && Math.abs(s[last]) < th) last--;
        int from = Math.max(0, first - sr * 100 / 1000), to = Math.min(s.length, last + 1 + sr * 260 / 1000);
        if (to - from < 16) return s;
        float[] o = new float[to - from]; System.arraycopy(s, from, o, 0, o.length);
        return o;
    }

    private File cacheDir() { File d = new File(ctx.getFilesDir(), "tts-pack-v6"); d.mkdirs(); return d; }

    /** نصوص الدروس الثابتة فقط (من قائمة التجهيز المسبق) هي التي تُحفظ على القرص؛ أي كلام آخر (كلامك أنت) يبقى في الذاكرة فقط. */
    private final java.util.Set<String> lessonTexts = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** يحذف مجلد التخزين القديم (كان يحفظ كل نطق بما فيه الجمل المردَّدة) ويُبقي المجلد الجديد تحت حد حجم. */
    private void housekeeping() {
        try {
            File old = new File(ctx.getCacheDir(), "tts-v3");
            if (old.exists()) deleteRec(old);
            File[] fs = cacheDir().listFiles();
            if (fs == null) return;
            long total = 0; for (File f : fs) total += f.length();
            if (total <= 600L * 1024 * 1024) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : fs) { if (total <= 500L * 1024 * 1024) break; total -= f.length(); f.delete(); }
        } catch (Throwable ignored) {}
    }

    /** يضغط كل الأصوات المجهَّزة (ملفات pcm) في zip ويكتبها في المجرى؛ يعيد عددها. */
    int exportPack(java.io.OutputStream os) throws java.io.IOException {
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(os, 64 * 1024));
        z.setLevel(1);
        byte[] buf = new byte[64 * 1024];
        int n = 0;
        File[] fs = cacheDir().listFiles();
        if (fs != null) for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".pcm")) continue;
            z.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int r; while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
            }
            z.closeEntry(); n++;
        }
        z.finish(); z.flush();
        return n;
    }

    /** يدمج zip أصوات مصدَّرًا سابقًا في المجلد (يستبدل المتشابه)؛ يعيد عدد الملفات المدمجة. */
    int importPack(java.io.InputStream is) throws java.io.IOException {
        java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(is, 64 * 1024));
        File dir = cacheDir();
        byte[] buf = new byte[64 * 1024];
        int n = 0;
        java.util.zip.ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            String name = e.getName();
            if (e.isDirectory() || !name.matches("[0-9a-f]{1,40}\\.pcm")) continue;   // أسماء آمنة فقط
            File f = new File(dir, name), tmp = new File(dir, name + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                int r; while ((r = z.read(buf)) > 0) out.write(buf, 0, r);
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            n++;
        }
        return n;
    }

    private File cacheFile(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(); for (byte x : h) sb.append(String.format("%02x", x));
            return new File(cacheDir(), sb + ".pcm");
        } catch (Exception e) { return new File(cacheDir(), Integer.toHexString(key.hashCode()) + ".pcm"); }
    }

    private Clip readDisk(String key) {
        File f = cacheFile(key);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            int rate = in.readInt(), n = in.readInt();
            if (n <= 0 || n > 44100 * 30) return null;
            float[] o = new float[n];
            for (int i = 0; i < n; i++) o[i] = in.readShort() / 32767f;
            return new Clip(o, rate);
        } catch (Throwable t) { f.delete(); return null; }
    }

    // ===== الحزمة المضمّنة في التطبيق (assets/tts-pre): تُولَّد على الحاسوب بـ tools/pregen.py =====
    // الحزمة الجاهزة: لكل صوت مجلد tts-pre/v<رقم>/ فيه index.json وملفات m4a (AAC) تُفكّ عند الطلب
    private final java.util.HashMap<Integer, org.json.JSONObject> preIdx = new java.util.HashMap<>();
    private final java.util.HashSet<Integer> preMissing = new java.util.HashSet<>();

    private synchronized org.json.JSONObject preFor(int v) {
        org.json.JSONObject o = preIdx.get(v);
        if (o != null) return o;
        if (preMissing.contains(v)) return null;
        try (java.io.InputStream in = ctx.getAssets().open("tts-pre/v" + v + "/index.json")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            o = new org.json.JSONObject(bo.toString("UTF-8")).getJSONObject("items");
            preIdx.put(v, o); return o;
        } catch (Throwable t) { preMissing.add(v); return null; }
    }

    private static String preKey(String text, float speed) { return Math.round(speed * 100) + "|" + text; }

    private boolean hasPre(String text, float speed) {
        org.json.JSONObject o = preFor(voiceId);
        return o != null && o.has(preKey(text, speed));
    }

    private final java.util.LinkedHashMap<String, Clip> preClips =
        new java.util.LinkedHashMap<String, Clip>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Clip> e) { return size() > 120; }
        };

    private Clip readPre(String text, float speed) {
        org.json.JSONObject o = preFor(voiceId);
        if (o == null) return null;
        String k = preKey(text, speed);
        if (!o.has(k)) return null;
        String ck = voiceId + "|" + k;
        synchronized (preClips) { Clip h = preClips.get(ck); if (h != null) return h; }
        Clip c = decodeAsset("tts-pre/v" + voiceId + "/" + o.optString(k));
        if (c != null) synchronized (preClips) { preClips.put(ck, c); }
        return c;
    }

    private Clip wordFromPack(String word, float speed) {
        String bt = SyllableCutter.bothText(word);
        if (bt == null) return null;
        Clip bp = readPre(bt, speed);
        if (bp == null) return null;
        float[] cut = SyllableCutter.cutWordRelaxed(bp.s, bp.rate, 100);
        return cut == null ? null : new Clip(cut, bp.rate);
    }

    /** يفكّ مقاطع اللعبة مسبقًا في الذاكرة فيصير النطق فوريًا. */
    void warm(final String[] texts, final float[] speeds) {
        new Thread(() -> {
            for (int i = 0; i < texts.length; i++) {
                try {
                    String c = clean(texts[i]);
                    if (c.isEmpty()) continue;
                    float sp = Math.max(0.6f, Math.min(1.3f, speeds[i]));
                    readPre(c, sp);
                } catch (Throwable ignored) {}
            }
        }, "tts-warm").start();
    }

    /** يفكّ ملف m4a/AAC من الـ assets إلى عيّنات float (صوت أحادي). */
    private Clip decodeAsset(String path) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaCodec codec = null;
        try (android.content.res.AssetFileDescriptor afd = ctx.getAssets().openFd(path)) {
            ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            android.media.MediaFormat fmt = null; int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { fmt = f; track = i; break; }
            }
            if (track < 0) return null;
            ex.selectTrack(track);
            codec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int sr = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT) : 1;
            short[] buf = new short[sr * 3]; int len = 0;
            android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false; int guard = 0;
            while (!outDone && guard++ < 20000) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        java.nio.ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ex.readSampleData(ib, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(bi, 10000);
                if (oi >= 0) {
                    if (bi.size > 0) {
                        java.nio.ByteBuffer ob = codec.getOutputBuffer(oi);
                        ob.position(bi.offset); ob.limit(bi.offset + bi.size);
                        java.nio.ShortBuffer sb = ob.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                        int frames = sb.remaining() / Math.max(1, ch);
                        if (len + frames > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + frames));
                        for (int f = 0; f < frames; f++) buf[len++] = sb.get(f * Math.max(1, ch));
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((bi.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            if (len == 0) return null;
            float[] o = new float[len];
            for (int i = 0; i < len; i++) o[i] = buf[i] / 32768f;
            return new Clip(o, sr);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
            try { ex.release(); } catch (Throwable ignored) {}
        }
    }

    private void writeDisk(String key, float[] smp, int rate) {
        File f = cacheFile(key), tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(rate); out.writeInt(smp.length);
            float peak = 0f; for (float x : smp) peak = Math.max(peak, Math.abs(x));
            float k = peak > 0 ? 0.95f / peak : 1f;   // تخزين بأعلى دقة 16-بت؛ المعالجة لاحقًا تعتمد RMS فلا يتأثر المستوى
            for (float x : smp) out.writeShort((short) Math.round(x * k * 32767f));
        } catch (Throwable t) { tmp.delete(); return; }
        tmp.renameTo(f);
    }

    private boolean isCached(String key, String text, float speed) {
        if (hasPre(text, speed)) return true;
        synchronized (cache) { if (cache.containsKey(key)) return true; }
        return cacheFile(key).isFile();
    }

    private String keyOf(String text, float speed) {
        // «w2|»: يُبطل ما حُفظ على الجهاز سابقًا لكلمات الصور بالطريقة القديمة (الكلمة وحدها)
        return voiceId + "|" + steps + "|" + Math.round(speed * 100) + "|" + (SyllableCutter.wordName(text) != null ? "w2|" : "") + text;
    }

    /** مضمَّن في التطبيق ← ذاكرة ← قرص ← توليد (Supertonic، lang=ar). النتيجة مقصوصة الصمت. */
    private Clip synthBuiltin(String text, float speed) { return synthBuiltin(text, speed, null, null); }

    private Clip synthBuiltin(String text, float speed, OfflineTts own, java.util.concurrent.locks.ReentrantLock ownLock) {
        final String key = keyOf(text, speed);
        Clip pre = readPre(text, speed);
        if (pre != null) return pre;
        synchronized (cache) { Clip hit = cache.get(key); if (hit != null) return hit; }
        Clip d = readDisk(key);
        if (d != null) { synchronized (cache) { cache.put(key, d); } return d; }
        if (SyllableCutter.wordName(text) != null) {
            // كلمة صورة الدرس: نأخذها من ذيل مقطع «الحرف، الكلمة» الجاهز نفسه إن وُجد بهذه السرعة (نطق مضمون كما يسمعه الطفل)
            Clip wp = wordFromPack(text, speed);
            if (wp != null) { synchronized (cache) { cache.put(key, wp); } return wp; }
        }
        GeneratedAudio a;
        float[] syllable = null;   // مقطع (بَ، أَبْ، بَا): يُقتطع من جملة حاملة كما في tools/pregen.py
        final java.util.concurrent.locks.ReentrantLock lk = ownLock != null ? ownLock : genLock;
        lk.lock();
        try {
            synchronized (cache) { Clip hit = cache.get(key); if (hit != null) return hit; }
            OfflineTts t = own != null ? own : getTts();
            GenerationConfig g = new GenerationConfig();
            g.setSid(voiceId); g.setSpeed(speed); g.setNumSteps(steps);
            HashMap<String, String> ex = new HashMap<>(); ex.put("lang", "ar"); g.setExtra(ex);
            if (SyllableCutter.nameWord(text) != null) {
                // اسم حرف: نولّد «الاسم ، ، ، كلمته» ونقتطع الاسم فقط
                GeneratedAudio ca = null;
                for (String ph : SyllableCutter.namePhrases(text)) {
                    GeneratedAudio c1 = t.generateWithConfig(ph, g);
                    float[] raw = c1 == null ? null : c1.getSamples();
                    if (raw != null && raw.length > 0) {
                        float[] cut = SyllableCutter.cutName(raw, c1.getSampleRate());
                        if (cut != null) { ca = c1; syllable = cut; break; }
                    }
                }
                a = syllable != null ? ca : t.generateWithConfig(text, g);
            } else if (SyllableCutter.wordName(text) != null) {
                // كلمة صورة الدرس: ينطقها النموذج وحدها بحركات خاطئة أحيانًا (حَصان، ذُرا)، فنولّد «الحرف، الكلمة» ونقتطع الكلمة
                GeneratedAudio ca = null;
                String[] phs = SyllableCutter.wordPhrases(text);
                for (int q = 0; q < phs.length && syllable == null; q++) {
                    GeneratedAudio c1 = t.generateWithConfig(phs[q], g);
                    float[] raw = c1 == null ? null : c1.getSamples();
                    if (raw != null && raw.length > 0) {
                        float[] cut = SyllableCutter.cutWordRelaxed(raw, c1.getSampleRate(), q == 0 ? 100 : 150);
                        if (cut != null) { ca = c1; syllable = cut; }
                    }
                }
                a = syllable != null ? ca : t.generateWithConfig(text, g);   // فشل الاقتطاع: نولّدها وحدها
            } else if (SyllableCutter.isSyllable(text)) {
                // النموذج ينطق المقطع المنفرد بشكل رديء؛ نولّد «حرف بَ، كرر: بَ» ونأخذ آخر مقطع فقط
                GeneratedAudio ca = null;
                for (String cr : SyllableCutter.carriers(text)) {
                    GeneratedAudio c1 = t.generateWithConfig(cr, g);
                    float[] raw = c1 == null ? null : c1.getSamples();
                    if (raw != null && raw.length > 0) {
                        float[] cut = SyllableCutter.cutLast(raw, c1.getSampleRate(), SyllableCutter.maxDur(text));
                        if (cut != null) { ca = c1; syllable = cut; break; }
                    }
                }
                a = syllable != null ? ca : t.generateWithConfig(text, g);   // فشل الاقتطاع: نولّد المقطع وحده
            } else {
                a = t.generateWithConfig(text, g);
            }
        } finally { lk.unlock(); }
        float[] smp = a == null ? null : a.getSamples();
        if (smp == null || smp.length == 0) return null;
        int rate = a.getSampleRate();
        Clip c = new Clip(syllable != null ? syllable : trim(smp, rate), rate);
        synchronized (cache) { cache.put(key, c); }
        if (lessonTexts.contains(text)) writeDisk(key, c.s, c.rate);
        return c;
    }

    private final AtomicInteger prefetchJobs = new AtomicInteger(0);
    private final Object wlLock = new Object();
    private android.os.PowerManager.WakeLock wakeLock;

    /** هل ما زال تجهيز الأصوات جاريًا (أو في الانتظار)؟ */
    boolean isPrefetching() { return prefetchJobs.get() > 0; }

    /** يبقي المعالج يعمل أثناء التجهيز فقط (حتى لو خرج الطفل من التطبيق أو انطفأت الشاشة)، ويُفلَت فور الانتهاء. */
    private void holdCpu(boolean on) {
        try {
            synchronized (wlLock) {
                if (on) {
                    if (wakeLock == null) {
                        android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "talkingcat:prefetch");
                        wakeLock.setReferenceCounted(false);
                    }
                    if (!wakeLock.isHeld()) wakeLock.acquire(3L * 60 * 60 * 1000);
                } else if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    private void endPrefetchJob() {
        if (prefetchJobs.decrementAndGet() <= 0) {
            prefetchJobs.set(0);
            holdCpu(false);
            try { cb.prefetchIdle(); } catch (Throwable ignored) {}
        }
    }

    /** تجهيز مسبق في الخلفية لكل الحروف والكلمات: بعدها يصير النطق فوريًا. يتوقف مؤقتًا كلما احتاج الطفل نطقًا مباشرًا.
     *  أُسرع بتوزيع العمل على عدة خيوط (كل خيط إضافي بنسخة نموذج خاصة) وبأولوية عادية؛ النتيجة نفسها والجودة نفسها. */
    void prefetch(final String[] texts, final float[] speeds) {
        final int my = prefetchGen.incrementAndGet();
        for (String t : texts) lessonTexts.add(clean(t));
        if (prefetchJobs.getAndIncrement() == 0) holdCpu(true);
        try { bg.execute(() -> runPrefetch(my, texts, speeds)); }
        catch (Throwable t) { endPrefetchJob(); }
    }

    private void runPrefetch(final int my, String[] texts, float[] speeds) {
        try {
            housekeeping();
            final List<String> pt = new ArrayList<>();
            final List<Float> ps = new ArrayList<>();
            for (int i = 0; i < texts.length; i++) {
                String c0 = clean(texts[i]);
                if (c0.isEmpty()) continue;
                float sp0 = Math.max(0.6f, Math.min(1.3f, speeds[i]));
                if (speedMode == 1 && preSpeedFor(c0) > 0) continue;   // وضع «فوري»: يكفي المقطع الجاهز، فلا نولّد نسخة أخرى
                if (!isCached(keyOf(c0, sp0), c0, sp0)) { pt.add(c0); ps.add(sp0); }
            }
            final int total = pt.size();
            if (total == 0) return;
            cb.emit("packprog", "0/" + total);
            int cores = Runtime.getRuntime().availableProcessors();
            final int workers = cores >= 8 ? 3 : (cores >= 6 ? 2 : 1);
            final AtomicInteger next = new AtomicInteger(0), done = new AtomicInteger(0);
            Thread[] ths = new Thread[workers];
            for (int w = 0; w < workers; w++) {
                final boolean primary = (w == 0);
                ths[w] = new Thread(() -> prefetchWorker(my, pt, ps, total, next, done, primary), "tts-prefetch-" + w);
                ths[w].setPriority(Thread.NORM_PRIORITY);
                ths[w].setDaemon(true);
                ths[w].start();
            }
            for (Thread t : ths) t.join();
        } catch (Throwable ignored) {
        } finally { endPrefetchJob(); }
    }

    private void prefetchWorker(int my, List<String> pt, List<Float> ps, int total, AtomicInteger next, AtomicInteger done, boolean primary) {
        OfflineTts own = null;
        java.util.concurrent.locks.ReentrantLock ownLock = null;
        try {
            if (!primary) {
                if (!canSpeak()) return;
                try { own = buildTts(2); ownLock = new java.util.concurrent.locks.ReentrantLock(); }
                catch (Throwable t) { return; }   // تعذّر نسخ إضافية: يكمل الخيط الأساسي وحده
            }
            while (my == prefetchGen.get()) {
                int k = next.getAndIncrement();
                if (k >= total) break;
                while (my == prefetchGen.get() && (fgBusy.get() > 0 || System.currentTimeMillis() - lastFg < 1500)) Thread.sleep(300);
                if (my != prefetchGen.get() || !canSpeak()) break;
                String c = pt.get(k); float sp = ps.get(k);
                if (!isCached(keyOf(c, sp), c, sp)) synthBuiltin(c, sp, own, ownLock);
                int d = done.incrementAndGet();
                if (d % 2 == 0 || d >= total) cb.emit("packprog", Math.min(d, total) + "/" + total);
            }
        } catch (Throwable ignored) {
        } finally {
            if (own != null) { try { own.release(); } catch (Throwable ignored) {} }
        }
    }

    /** يقسم النص الطويل إلى جمل لنبدأ النطق بعد الجملة الأولى فقط. */
    private static List<String> splitSentences(String c) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i); cur.append(ch);
            boolean end = ch == '.' || ch == '!' || ch == '?' || ch == '؟' || ch == '،' || ch == '؛' || ch == ',' || ch == ';';
            if (end && cur.toString().trim().length() >= 12) { out.add(cur.toString().trim()); cur.setLength(0); }
        }
        if (cur.toString().trim().length() > 0) {
            if (!out.isEmpty() && cur.toString().trim().length() < 8) out.set(out.size() - 1, out.get(out.size() - 1) + " " + cur.toString().trim());
            else out.add(cur.toString().trim());
        }
        return out;
    }

    private static float softClip(float x) {
        float a = Math.abs(x);
        if (a <= 0.8f) return x;
        float y = 0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f);
        return x < 0 ? -y : y;
    }

    private static String clean(String text) {
        String c = text == null ? "" : text.replaceAll("[\\p{So}\\p{Cs}\\p{Cn}]", " ");
        c = c.replace("\u0640", "").replaceAll("[\"«»()\\[\\]*_#]", " ");
        return c.replaceAll("\\s+", " ").trim();
    }

    private static void fin(Runnable r) { if (r != null) r.run(); }

    /** نطق تعليمي هادئ (وضع التعلم). */
    void say(final String text, final float pitchFactor, final int delayMs) {
        say(text, pitchFactor, delayMs, 1.0f, null);
    }

    void say(final String text, final float speedValue, final int delayMs, final float volume) {
        say(text, speedValue, delayMs, volume, null);
    }

    void say(final String text, final float speedValue, final int delayMs, final float volume, final Runnable onFinished) {
        // القيمة تُستعمل كسرعة فعلية، مع تطبيق مستوى الصوت العام.
        float speed = Math.max(0.6f, Math.min(1.2f, speedValue));
        speak(text, speed, 1.0f, delayMs, Math.max(0f, Math.min(1f, volume)), onFinished);
    }

    private void speak(final String text, final float speed, final float rateMul, final int delayMs, final float volume, final Runnable onFinished) {
        final int my = gen.incrementAndGet();
        stopTrack();
        lastFg = System.currentTimeMillis();
        fgBusy.incrementAndGet();
        exec.execute(() -> {
            try {
                if (my != gen.get()) return;
                String c = clean(text);
                if (c.isEmpty()) { fin(onFinished); return; }
                float[] smp = null; int sr = 22050;
                if (useSystem) { int[] r = {0}; smp = synthSystem(c, speed, r); if (smp != null) sr = r[0]; }
                float tempoApply = 1.0f;
                if (smp == null && speedMode != 2 && !hasPre(c, speed) && !isCached(keyOf(c, speed), c, speed)) {
                    // ذكي: لا ننتظر التوليد؛ نأخذ المقطع الجاهز بسرعته ونعدّل السرعة وقت التشغيل دون تغيير النغمة
                    int ps = preSpeedFor(c);
                    if (ps > 0) {
                        Clip pc = readPre(c, ps / 100f);
                        if (pc != null) { smp = pc.s; sr = pc.rate; tempoApply = Math.max(0.6f, Math.min(1.5f, speed / (ps / 100f))); }
                    }
                }
                if (smp == null) {
                    if (!canSpeak()) { cb.toast("صوت النطق العربي غير جاهز بعد."); fin(onFinished); return; }
                    List<String> parts = splitSentences(c);
                    if (parts.size() > 1 && !hasPre(c, speed)) { speakChunks(parts, speed, rateMul, delayMs, volume, my, onFinished); return; }
                    Clip cl = synthBuiltin(c, speed); if (cl != null) { smp = cl.s; sr = cl.rate; }
                }
                if (my != gen.get()) return;
                if (smp == null || smp.length == 0) { fin(onFinished); return; }
                short[] pcm = process(smp, sr, volume, true);
                fgBusy.decrementAndGet();
                try { playPcm(pcm, Math.round(sr * rateMul), delayMs, my, Math.max(0f, Math.min(1f, volume)), onFinished, tempoApply); }
                finally { fgBusy.incrementAndGet(); }
            } catch (Throwable t) {
                cb.report("خطأ في النطق", t.getClass().getSimpleName() + ": " + t.getMessage());
                fin(onFinished);
            } finally { fgBusy.decrementAndGet(); }
        });
    }

    /** جمل متعددة: نبدأ بعد أول جملة، وتُولَّد التالية أثناء نطق السابقة. */
    private void speakChunks(List<String> parts, float speed, float rateMul, int delayMs, float volume, int my, Runnable onFinished) throws Exception {
        FutureTask<Object[]> next = startPart(parts.get(0), speed, volume);
        for (int i = 0; i < parts.size(); i++) {
            Object[] got = next.get();
            if (my != gen.get()) return;
            if (i + 1 < parts.size()) next = startPart(parts.get(i + 1), speed, volume);
            short[] pcm = got == null ? null : (short[]) got[0];
            int rate = got == null ? 44100 : (Integer) got[1];
            if (pcm != null && pcm.length > 0) {
                fgBusy.decrementAndGet();
                try { playPcm(pcm, Math.round(rate * rateMul), i == 0 ? delayMs : 0, my, Math.max(0f, Math.min(1f, volume)), i == parts.size() - 1 ? onFinished : null); }
                finally { fgBusy.incrementAndGet(); }
            } else if (i == parts.size() - 1) fin(onFinished);
        }
    }

    private FutureTask<Object[]> startPart(final String part, final float speed, final float volume) {
        FutureTask<Object[]> f = new FutureTask<>(() -> {
            Clip c = synthBuiltin(part, speed);
            return c == null ? null : new Object[]{process(c.s, c.rate, volume, true), c.rate};
        });
        Thread t = new Thread(f, "tts-part"); t.start();
        return f;
    }

    private void playPcm(short[] pcm, int rate, int delayMs, int my, float volume, Runnable onFinished) throws Exception {
        playPcm(pcm, rate, delayMs, my, volume, onFinished, 1.0f);
    }

    private void playPcm(short[] pcm, int rate, int delayMs, int my, float volume, Runnable onFinished, float tempo) throws Exception {
        try { Thread.sleep(Math.max(0, Math.min(1000, delayMs))); } catch (InterruptedException e) { return; }
        if (my != gen.get()) return;
        AudioTrack t = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
        t.write(pcm, 0, pcm.length);
        if (Math.abs(tempo - 1.0f) > 0.01f) { try { t.setPlaybackParams(new android.media.PlaybackParams().setSpeed(tempo).setPitch(1.0f)); } catch (Throwable ignored) {} }
        // لا نهمل مستوى الصوت الذي اختاره المستخدم. القيمة تُطبق على AudioTrack نفسه.
        t.setVolume(1.0f); // مستوى الصوت طُبّق داخل process()؛ لا تخفيض مزدوج هنا
        try { if (enhancer != null) enhancer.release(); enhancer = new LoudnessEnhancer(t.getAudioSessionId()); enhancer.setTargetGain(500); enhancer.setEnabled(true); } catch (Throwable ignored) { enhancer = null; }
        synchronized (this) { if (my != gen.get()) { t.release(); return; } track = t; }
        t.play();
        cb.emit("speaking", "1");
        try {
            long limit = System.currentTimeMillis() + (long) (pcm.length * 1000L / rate / Math.min(1f, tempo)) + 2000;
            while (my == gen.get() && t.getPlaybackHeadPosition() < pcm.length && System.currentTimeMillis() < limit) Thread.sleep(30);
        } finally {
            cb.emit("speaking", "0");
        }
        if (my == gen.get()) {
            stopTrack();
            fin(onFinished);
        }
    }

    private synchronized void stopTrack() {
        AudioTrack t = track; track = null;
        if (t != null) { try { t.stop(); } catch (Throwable ignored) {} try { t.release(); } catch (Throwable ignored) {} }
    }

    void stop() { gen.incrementAndGet(); stopTrack(); }
    void shutdown() { stop(); prefetchGen.incrementAndGet(); bg.shutdownNow(); playExec.shutdownNow(); exec.shutdownNow(); try { if (sys != null) sys.shutdown(); } catch (Throwable ignored) {} try { if (tts != null) tts.release(); } catch (Throwable ignored) {} }
}
