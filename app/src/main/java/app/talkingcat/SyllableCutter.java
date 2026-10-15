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
    static String carrier(String t) { return t + " ، ، " + t + " ، ، " + t; }

    /** أطول مدة معقولة لمقطع واحد (ث): قصير 0.5، مد 0.85. */
    static double maxDur(String t) { return isMadd(t) ? 0.85 : 0.5; }

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
            if (best < 0) break;
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
}
