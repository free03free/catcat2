package app.talkingcat;

import java.util.regex.Pattern;

/**
 * مقاطع الحروف (بَ، أَبْ، بَا، آ) لا يُحسن النموذج نطقها منفردة، فنولّد جملة حاملة («حرف بَ، كرر: بَ» أو «مد: بَا»)
 * ثم نقتطع آخر مقطع منها فقط. هذا هو الأسلوب نفسه في tools/pregen.py (cut_last)، فيتطابق ما يُولَّد على الجهاز
 * (عند تغيير الصوت أو السرعة) مع الحزمة الجاهزة. لا اعتماد على أندرويد هنا؛ مُختبَر بـ SyllableCutterTest.
 */
final class SyllableCutter {
    private SyllableCutter() {}

    // نفس الأنماط في assets/speech-data.js (sylKind) وtools/pregen.py (syl_kind)
    private static final Pattern SHORT = Pattern.compile("^[\\u0621-\\u064A][\\u064E\\u064F\\u0650]$");
    private static final Pattern SUKUN = Pattern.compile("^\\u0623\\u064E[\\u0621-\\u064A]\\u0652$");
    private static final Pattern MADD = Pattern.compile("^([\\u0621-\\u064A][\\u064E\\u064F\\u0650][\\u0627\\u0648\\u064A]|\\u0622)$");

    static boolean isShort(String t) { return t != null && (SHORT.matcher(t).matches() || SUKUN.matcher(t).matches()); }
    static boolean isMadd(String t) { return t != null && MADD.matcher(t).matches(); }
    static boolean isSyllable(String t) { return isShort(t) || isMadd(t); }

    // بلا أي كلمة («حرف»، «كرر»، «مد») كي لا تتسرب إلى الصوت: ثلاثة مقاطع بينها فواصل، ونأخذ الأخير
    static String carrier(String t) { return carriers(t)[0]; }

    private static final String[] NAMES = {"أَلِف","بَاء","تَاء","ثَاء","جِيم","حَاء","خَاء","دَال","ذَال","رَاء","زَاي","سِين","شِين","صَاد","ضَاد","طَاء","ظَاء","عَيْن","غَيْن","فَاء","قَاف","كَاف","لَام","مِيم","نُون","هَاء","وَاو","يَاء"};
    private static final String ORDER = "أبتثجحخدذرزسشصضطظعغفقكلمنهوي";

    /** صيغ الجملة الحاملة؛ تطابق carriers في tools/pregen.py. الصيغة المسبوقة باسم الحرف تُصلح نطق ت وف. */
    static String[] carriers(String t) {
        String L = isSukunForm(t) ? t.substring(2, 3) : t.substring(0, 1);
        int i = ORDER.indexOf(L.equals("آ") || L.equals("إ") ? "أ" : L);
        String nm = i >= 0 ? NAMES[i] : null;
        String base = t + " ، ، " + t + " ، ، " + t;
        if (nm == null) return new String[]{base, t + " . " + t + " . " + t, t + " ، " + t + " ، " + t};
        return new String[]{base, nm + " ، ، " + t + " ، ، " + t + " ، ، " + t, t + " . " + t + " . " + t, t + " ، " + t + " ، " + t};
    }
    private static boolean isSukunForm(String t) { return t != null && SUKUN.matcher(t).matches(); }

    /** أطول مدة معقولة لمقطع واحد (ث): قصير 0.5، مد 0.85. */
    static double maxDur(String t) { return isMadd(t) ? 0.70 : 0.5; }

    static float[] cutLast(float[] s, int sr) { return cutLast(s, sr, 0.9); }

    /** يقتطع آخر مقطع منطوق من الصوت. إن التصق مقطعان («عاعا») يُقسَمان عند أعمق انخفاض في الطاقة ويؤخذ الأخير.
     *  يعيد null إن لم تكن المدة معقولة (0.12–maxDur) فيُعاد التوليد بلا حامل. يطابق cut_last في tools/pregen.py. */
    static float[] cutLast(float[] s, int sr, double maxDur) {
        final int gapMs = 90;
        int fr = (int) (sr * 0.01);
        if (fr < 1) return null;
        int n = s.length / fr;
        if (n < 5) return null;
        double[] e = new double[n];
        double emax = 0;
        for (int i = 0; i < n; i++) {
            double acc = 0;
            for (int j = 0; j < fr; j++) { double v = s[i * fr + j]; acc += v * v; }
            e[i] = Math.sqrt(acc / fr);
            if (e[i] > emax) emax = e[i];
        }
        double th = Math.max(emax * 0.03, 1e-4);
        int end = -1;
        for (int i = n - 1; i >= 0; i--) if (e[i] > th) { end = i; break; }
        if (end < 0) return null;
        int start = end, gap = 0, k = end;
        while (k >= 0) {
            if (e[k] > th) { start = k; gap = 0; }
            else { gap++; if (gap * 10 >= gapMs) break; }
            k--;
        }
        while ((end + 1 - start) * 0.01 > maxDur) {
            int best = -1;
            for (int i = start + 12; i < end - 11; i++) {
                if (e[i] <= e[i - 1] && e[i] <= e[i + 1] && (end - i) * 0.01 >= 0.12) {
                    double left = 0, right = 0;
                    for (int q = start; q < i; q++) left = Math.max(left, e[q]);
                    for (int q = i; q <= end; q++) right = Math.max(right, e[q]);
                    if (e[i] < 0.5 * Math.min(left, right)) best = i;
                }
            }
            if (best < 0) {
                // لا انخفاض واضح بين المقطعين (عاعا ملتصقة): نأخذ النصف الأخير
                best = start + (end + 1 - start) / 2 - 1;
                if ((end - best) * 0.01 < 0.12) break;
            }
            start = best + 1;
            while (start < end && e[start] <= th) start++;
        }
        int a = Math.max(0, start * fr - (int) (sr * 0.04));
        int b = Math.min(s.length, (end + 1) * fr + (int) (sr * 0.12));
        if (b <= a) return null;
        float[] seg = java.util.Arrays.copyOfRange(s, a, b);
        int f = (int) (sr * 0.006);
        if (seg.length > 2 * f) {
            for (int i = 0; i < f; i++) {
                float g = f == 1 ? 1f : (float) i / (f - 1);
                seg[i] *= g;
                seg[seg.length - 1 - i] *= g;
            }
        }
        double dur = (end + 1 - start) * 0.01;
        return (dur >= 0.12 && dur <= maxDur) ? seg : null;
    }

    // اسم الحرف وحده (تَاء) ينطقه النموذج ناقصًا؛ نولّد «الاسم ، ، ، كلمته» ونقتطع الاسم فقط. يطابق name_phrases/cut_name في tools/pregen.py
    private static final String[][] NAME_WORD = {
        {"أَلِف", "أَسَد"},
        {"بَاء", "بَقَرَة"},
        {"تَاء", "تَمْرْ"},
        {"ثَاء", "ثَعْلَب"},
        {"جِيم", "جَمَل"},
        {"حَاء", "حِصَان"},
        {"خَاء", "خَرُوف"},
        {"دَال", "دُبْ"},
        {"ذَال", "ذُرَه"},
        {"رَاء", "رُمَّان"},
        {"زَاي", "زَرَافَة"},
        {"سِين", "سَمَكَة"},
        {"شِين", "شَمْس"},
        {"صَاد", "صَارُوخ"},
        {"ضَاد", "ضِفْدَع"},
        {"طَاء", "طَائِر"},
        {"ظَاء", "ظَرْف"},
        {"عَيْن", "عِنَب"},
        {"غَيْن", "غَيْمَه"},
        {"فَاء", "فِيل"},
        {"قَاف", "قِطَّة"},
        {"كَاف", "كَلْبْ"},
        {"لَام", "لَيْمُون"},
        {"مِيم", "مَوْز"},
        {"نُون", "نَحْلَه"},
        {"هَاء", "هِلَال"},
        {"وَاو", "وَرْدَة"},
        {"يَاء", "يَد"}
    };
    static String nameWord(String t) {
        if (t == null) return null;
        if ("تَا".equals(t)) return "تَمْرْ";
        if ("فَا".equals(t)) return "فِيل";   // صيغة المد المنطوقة لاسمي ت وف (SAY في speech-data.js)
        for (String[] r : NAME_WORD) if (r[0].equals(t)) return r[1];
        return null;
    }
    static String[] namePhrases(String name) {
        String w = nameWord(name);
        return new String[]{name + " ، ، ، " + w, name + " ، ، ، ، " + w, name + " ، ، " + w};
    }

    /** أول مقطع منطوق (الاسم) قبل الوقفة؛ null إن لم يُعثر على وقفة أو كانت المدة غير معقولة (0.25–0.8). */
    static float[] cutName(float[] s, int sr) {
        final int gapMs = 90;
        int fr = (int) (sr * 0.01);
        if (fr < 1) return null;
        int n = s.length / fr;
        if (n < 5) return null;
        double[] e = new double[n];
        double emax = 0;
        for (int i = 0; i < n; i++) {
            double acc = 0;
            for (int j = 0; j < fr; j++) { double v = s[i * fr + j]; acc += v * v; }
            e[i] = Math.sqrt(acc / fr);
            if (e[i] > emax) emax = e[i];
        }
        double th = Math.max(emax * 0.03, 1e-4);
        int start = -1;
        for (int i = 0; i < n; i++) if (e[i] > th) { start = i; break; }
        if (start < 0) return null;
        int end = start, gap = 0, k = start;
        while (k < n) {
            if (e[k] > th) { end = k; gap = 0; }
            else { gap++; if (gap * 10 >= gapMs) break; }
            k++;
        }
        boolean more = false;
        for (int i = end + 1; i < n; i++) if (e[i] > th) { more = true; break; }
        double dur = (end + 1 - start) * 0.01;
        if (!more || dur < 0.25 || dur > 0.8) return null;
        int a = Math.max(0, start * fr - (int) (sr * 0.04));
        int b = Math.min(s.length, (end + 1) * fr + (int) (sr * 0.10));
        float[] seg = java.util.Arrays.copyOfRange(s, a, b);
        int f = (int) (sr * 0.006);
        if (seg.length > 2 * f) {
            for (int i = 0; i < f; i++) {
                float g = f == 1 ? 1f : (float) i / (f - 1);
                seg[i] *= g;
                seg[seg.length - 1 - i] *= g;
            }
        }
        return seg;
    }

    /** كلمة صورة الدرس (بَقَرَة) ← اسم حرفها لعبارة «الحرف، الكلمة»؛ null إن لم تكن كلمة درس. يطابق WORD_NAME في tools/pregen.py. */
    static String wordName(String t) {
        if (t == null) return null;
        if ("تَمْرْ".equals(t)) return "تَا";
        if ("فِيل".equals(t)) return "فَا";
        for (String[] r : NAME_WORD) if (r[1].equals(t)) return r[0];
        return null;
    }
    /** «اسم الحرف، الكلمة» الكامل كما في وضع الدروس (بَاء، بَقَرَة)؛ null إن لم تكن كلمة درس. يطابق BOTH_TEXT في tools/pregen.py. */
    static String bothText(String w) {
        if (w == null) return null;
        for (String[] r : NAME_WORD) if (r[1].equals(w)) return r[0] + "، " + w;
        return null;
    }
    /** عبارات الحامل: الأولى بصيغة «الحرف، الكلمة» نفسها التي ينطقها النموذج سليمة. يطابق word_phrases. */
    static String[] wordPhrases(String w) {
        String n = wordName(w);
        if (w.endsWith("\u0629")) {   // بديل: الهاء بدل التاء المربوطة (ذُرَة ← ذُرَه)
            String h = w.substring(0, w.length() - 1) + "\u0647";
            return new String[]{n + "، " + w, n + " ، ، ، " + w, n + " ، ، " + w, n + "، " + h, n + " ، ، ، " + h};
        }
        return new String[]{n + "، " + w, n + " ، ، ، " + w, n + " ، ، " + w};
    }

    /** الكلمة بعد اسم الحرف: ما بعد أطول وقفة (minPauseMs فأكثر) إلى آخر صوت؛ null إن لم تُوجد وقفة أو كانت المدة غير معقولة (0.2–1.8 ث). يطابق cut_word في tools/pregen.py. */
    /** يجرّب وقفات أقصر تدريجيًا (تطابق cut_word_relaxed في pregen.py). */
    static float[] cutWordRelaxed(float[] s, int sr, int firstPauseMs) {
        int[] ps = {150, 100, 70, 50, 35};
        for (int pm : ps) {
            if (pm > firstPauseMs) continue;
            float[] r = cutWord(s, sr, pm, Math.min(90, pm));
            if (r != null) return r;
        }
        return null;
    }

    static float[] cutWord(float[] s, int sr, int minPauseMs) { return cutWord(s, sr, minPauseMs, 90); }

    static float[] cutWord(float[] s, int sr, int minPauseMs, int gapMs) {
        int fr = (int) (sr * 0.01);
        if (fr < 1) return null;
        int n = s.length / fr;
        if (n < 5) return null;
        double[] e = new double[n];
        double emax = 0;
        for (int i = 0; i < n; i++) {
            double acc = 0;
            for (int j = 0; j < fr; j++) { double v = s[i * fr + j]; acc += v * v; }
            e[i] = Math.sqrt(acc / fr);
            if (e[i] > emax) emax = e[i];
        }
        double th = Math.max(emax * 0.03, 1e-4);
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) if (e[i] > th) { if (first < 0) first = i; last = i; }
        if (first < 0) return null;
        int bestGap = 0, wordStart = -1, prev = first;
        for (int k = first + 1; k <= last; k++) {
            if (e[k] <= th) continue;
            int g = (k - prev - 1) * 10;
            if (g >= gapMs && g > bestGap) { bestGap = g; wordStart = k; }
            prev = k;
        }
        if (wordStart < 0 || bestGap < minPauseMs) return null;
        double dur = (last + 1 - wordStart) * 0.01;
        if (dur < 0.2 || dur > 1.8) return null;
        int a = Math.max(0, wordStart * fr - (int) (sr * 0.04));
        int b = Math.min(s.length, (last + 1) * fr + (int) (sr * 0.12));
        if (b <= a) return null;
        float[] seg = java.util.Arrays.copyOfRange(s, a, b);
        int f = (int) (sr * 0.006);
        if (seg.length > 2 * f) {
            for (int i = 0; i < f; i++) {
                float g = f == 1 ? 1f : (float) i / (f - 1);
                seg[i] *= g;
                seg[seg.length - 1 - i] *= g;
            }
        }
        return seg;
    }
}
