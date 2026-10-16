#!/usr/bin/env python3
"""
يولّد مسبقًا أصوات كل الحروف والكلمات والألوان والأرقام (1..100) من tools/lessons.json
لكل الأصوات العشرة (0..9) بجودة عالية، ويضغطها بصيغة m4a (AAC) ويضعها في app/src/main/assets/tts-pre/v<رقم>/
فتدخل داخل الـ APK وتُنطق فورًا من أول ضغطة بأي صوت يختاره المستخدم، بلا تجهيز على الجهاز.
يعمل تلقائيًا أثناء البناء من fetch-engine.sh، ويُستأنف إن انقطع (يتخطى ما تم توليده).
التحقّق من صحة النطق: كل عنصر يُولَّد حتى 6 محاولات (النموذج عشوائي: قد يخطئ في محاولة وينجح في أخرى)،
ويُنسَخ كل مقطع إلى نص بنموذج Whisper ويُقارَن بالنص المطلوب (والأرقام بقيمتها)، ويُختار المقطع الصحيح.
العناصر التي لم تجتز التحقق تُسجَّل في pack-report.txt لمراجعتها.
الاستعمال اليدوي:  python3 tools/pregen.py --steps 8 --voices 0,9 --jobs 2
"""
import argparse, difflib, glob, json, os, re, shutil, subprocess, sys, time, unicodedata
import numpy as np
from multiprocessing import get_context

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)
sys.path.insert(0, here)
from arabic_numbers import parse_ar_number  # محلّل الأرقام للتحقق بالقيمة
import hashlib
ap = argparse.ArgumentParser()
ap.add_argument("--model", default=os.path.join(root, "app/src/main/assets/models/tts-ar"))
ap.add_argument("--out", default=os.path.join(root, "app/src/main/assets/tts-pre"))
ap.add_argument("--voices", default="0,1,2,3,4,5,6,7,8,9")
ap.add_argument("--steps", type=int, default=8)
ap.add_argument("--jobs", type=int, default=os.cpu_count() or 2)
ap.add_argument("--shards", type=int, default=1, help="تقسيم عناصر كل صوت على هذا العدد من العمليات المتوازية (يُنصح بعدد أنوية المعالج)")
ap.add_argument("--bitrate", default="48k")
ap.add_argument("--budget-min", type=float, default=0, help="أقصى مدة بالدقائق في هذه الدورة (0 = بلا حد)")
ap.add_argument("--max-num", type=int, default=0, help="الاكتفاء بالأرقام حتى هذا الحد (0 = كلها حتى 100)")
ap.add_argument("--start", type=int, default=0, help="للتجربة فقط")
ap.add_argument("--limit", type=int, default=0, help="للتجربة فقط")
ap.add_argument("--asr-dir", default=os.path.join(os.environ.get("CAT_CACHE") or os.path.expanduser("~/.cache/talking-cat-engine"), "whisper-small"))
ap.add_argument("--tries", type=int, default=6, help="أقصى محاولات لكل عنصر")
ap.add_argument("--accept", type=float, default=0.92, help="أدنى درجة تشابه لقبول المقطع")
ap.add_argument("--report", default=os.path.join(root, "pack-report.txt"))
a = ap.parse_args()

FF = shutil.which("ffmpeg")
if not FF:
    import imageio_ffmpeg
    FF = imageio_ffmpeg.get_ffmpeg_exe()

def clean(t):  # نفس clean() في EchoEngine.java
    t = "".join(" " if unicodedata.category(c) in ("So", "Cs", "Cn") else c for c in t)
    t = t.replace("\u0640", "")
    t = re.sub(r'["«»()\[\]*_#]', " ", t)
    return re.sub(r"\s+", " ", t).strip()

def trim(s, sr):  # نفس trim() في EchoEngine.java
    pk = float(np.abs(s).max()) if len(s) else 0
    if pk < 1e-4: return s
    th = max(0.012 * pk, 1e-4)
    idx = np.where(np.abs(s) >= th)[0]
    f = max(0, int(idx[0]) - sr * 100 // 1000); t = min(len(s), int(idx[-1]) + 1 + sr * 260 // 1000)
    return s[f:t] if t - f >= 16 else s

_DIAC = re.compile("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")
def norm(t):
    t = _DIAC.sub("", t)
    for x, y in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ٱ", "ا"), ("ى", "ي"), ("ة", "ه"), ("ؤ", "و"), ("ئ", "ي")):
        t = t.replace(x, y)
    t = t.translate(str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789"))
    t = re.sub(r"[^\u0621-\u064A0-9 ]", " ", t)
    t = re.sub(r"[اه](?=\s|$)", "ه", t)
    return re.sub(r"\s+", " ", t).strip()

# تصحيحات يدوية تُدمج عند كل بناء (tools/corrections.json): redo = حذف الصوت المخزّن وإعادة توليده، alt = صيغ نطق بديلة
try: _CORR = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "corrections.json"), encoding="utf-8"))
except Exception: _CORR = {}
REDO = {clean(x) for x in _CORR.get("redo", [])}
ALT = {clean(k): [clean(x) for x in v] for k, v in _CORR.get("alt", {}).items()}
def key_text(k): return k.split("|", 1)[1] if "|" in k else k

def variants(c, num):
    """الأرقام والكلمات المفردة (أسماء الحروف وكلمات الصور): النموذج ينطق أحيانًا أوضح بلا سكون أو بلا تشكيل،
    فنجرّب الصيغ الثلاث ونختار ما يسمعه التحقق صحيحًا. عبارات «حرف أَ، كرر» و«مد:» تبقى بتشكيلها الكامل."""
    single = (" " not in c) and (":" not in c) and ("،" not in c) and not c.startswith("حرف") and not c.startswith("مد")
    if num is None and not single: return [c] + ALT.get(c, [])
    nodiac = re.sub("[\u064B-\u065F\u0670]", "", c)
    nosukun = c.replace("\u0652", "")
    return [c, nodiac, nosukun] + ALT.get(c, [])

# مقاطع الحرف: حركة (بَ بِ بُ)، سكون بعد فتحة (أَبْ)، مد (بَا بُو بِي، آ).  تطابق sylKind في speech-data.js وEchoEngine.java.
# النموذج ينطق المقطع المنفرد بشكل رديء، فنولّد جملة حاملة ثم نقتطع آخر مقطع منها فقط.
RE_SHORT = re.compile("^[\u0621-\u064A][\u064E\u064F\u0650]$")
RE_SUKUN = re.compile("^\u0623\u064E[\u0621-\u064A]\u0652$")
RE_MADD = re.compile("^([\u0621-\u064A][\u064E\u064F\u0650][\u0627\u0648\u064A]|\u0622)$")
def syl_kind(c):
    if RE_SHORT.match(c) or RE_SUKUN.match(c): return "short"
    if RE_MADD.match(c): return "madd"
    return ""
def syl_letter(c):  # الحرف الذي يجب أن يسمعه التحقق: في «أَبْ» هو الباء لا الهمزة
    return c[2] if RE_SUKUN.match(c) else ("\u0627" if c == "\u0622" else c[0])
_ORDER = "أبتثجحخدذرزسشصضطظعغفقكلمنهوي"
_NAMES = ["أَلِف","بَاء","تَاء","ثَاء","جِيم","حَاء","خَاء","دَال","ذَال","رَاء","زَاي","سِين","شِين","صَاد","ضَاد","طَاء","ظَاء","عَيْن","غَيْن","فَاء","قَاف","كَاف","لَام","مِيم","نُون","هَاء","وَاو","يَاء"]
def carriers(c):
    L = c[2] if RE_SUKUN.match(c) else c[0]
    i = _ORDER.find("أ" if L in "آإ" else L)
    base = f"{c} ، ، {c} ، ، {c}"
    if i < 0: return [base, f"{c} . {c} . {c}", f"{c} ، {c} ، {c}"]
    return [base, f"{_NAMES[i]} ، ، {c} ، ، {c} ، ، {c}", f"{c} . {c} . {c}", f"{c} ، {c} ، {c}"]
def carrier(c): return carriers(c)[0]

# اسم الحرف وحده (تَاء) ينطقه النموذج ناقصًا؛ فنولّد «الاسم ، ، ، كلمته» (مقاطع وضع الدروس) ونقتطع الاسم فقط.
NAME_WORD = {"أَلِف": "أَسَد", "بَاء": "بَقَرَة", "تَاء": "تَمْرْ", "ثَاء": "ثَعْلَب", "جِيم": "جَمَل", "حَاء": "حِصَان", "خَاء": "خَرُوف", "دَال": "دُبْ", "ذَال": "ذُرَه", "رَاء": "رُمَّان", "زَاي": "زَرَافَة", "سِين": "سَمَكَة", "شِين": "شَمْس", "صَاد": "صَارُوخ", "ضَاد": "ضِفْدَع", "طَاء": "طَائِر", "ظَاء": "ظَرْف", "عَيْن": "عِنَب", "غَيْن": "غَيْمَه", "فَاء": "فِيل", "قَاف": "قِطَّة", "كَاف": "كَلْبْ", "لَام": "لَيْمُون", "مِيم": "مَوْز", "نُون": "نَحْلَه", "هَاء": "هِلَال", "وَاو": "وَرْدَة", "يَاء": "يَد"}
NAME_WORD.update({"تَا": "تَمْرْ", "فَا": "فِيل"})  # صيغة المد المنطوقة لاسمي ت وف (انظر SAY في speech-data.js)
# كلمة الصورة وحدها (بَقَرَة) ينطقها النموذج أحيانًا خطأ منفردة، وتخرج سليمة بعد اسم حرفها («بَاء ، ، ، بَقَرَة»).
# فنولّد العبارة ونقتطع الكلمة (ما بعد أطول وقفة) ونتحقق منها بـ Whisper، وإن لم تجتز نرجع إلى توليدها وحدها.
WORD_NAME = {w: n for n, w in NAME_WORD.items()}   # التاء والفاء: الصيغة الأخيرة (تَا، فَا) هي الأصل لأنها المنطوقة فعلًا
# نص «اسم الحرف، الكلمة» الكامل لكل كلمة درس (كما في وضع الدروس: «بَاء، بَقَرَة»). كلمة الصورة وحدها تؤخذ من ذيل هذا المقطع الجاهز نفسه
BOTH_TEXT = {NAME_WORD[nm]: f"{nm}، {NAME_WORD[nm]}" for nm in _NAMES}
BOTH_WORD = {v: k for k, v in BOTH_TEXT.items()}
def decode_m4a(path):
    out = subprocess.run([FF, "-loglevel", "error", "-i", path, "-f", "f32le", "-ar", "22050", "-ac", "1", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(out, dtype="<f4").copy()
def word_phrases(c):
    n = WORD_NAME[c]
    # الأولى بصيغة «الحرف، الكلمة» نفسها التي يسمعها الطفل سليمة في التطبيق (حصان بكسرة الحاء، عنب بكسرة العين، رمان بضمة الراء، ذرة بالتاء المربوطة)
    ph = [f"{n}، {c}", f"{n} ، ، ، {c}", f"{n} ، ، {c}"]
    h = ta_to_ha(c)
    if h: ph += [f"{n}، {h}", f"{n} ، ، ، {h}"]   # بديل: الهاء بدل التاء المربوطة
    return ph
def cut_word(s, sr, gap_ms=90, min_pause_ms=150):
    """الكلمة بعد اسم الحرف: ما بعد أطول وقفة بين المقطعين إلى آخر صوت. (مقطع صالح، نجح الاقتطاع). يطابق cutWord في SyllableCutter.java."""
    fr = int(sr * 0.01); n = len(s) // fr
    if n < 5: return s, False
    e = np.sqrt((s[:n * fr].reshape(n, fr) ** 2).mean(axis=1))
    act = e > max(float(e.max()) * 0.03, 1e-4)
    idx = np.where(act)[0]
    if len(idx) == 0: return s, False
    first, last = int(idx[0]), int(idx[-1])
    best_gap = 0; word_start = -1; prev = first
    for k in idx[1:]:
        k = int(k); g = (k - prev - 1) * 10
        if g >= gap_ms and g > best_gap: best_gap = g; word_start = k
        prev = k
    if word_start < 0 or best_gap < min_pause_ms: return s, False
    dur = (last + 1 - word_start) * 0.01
    if dur < 0.2 or dur > 1.8: return s, False
    a = max(0, word_start * fr - int(sr * 0.04)); b = min(len(s), (last + 1) * fr + int(sr * 0.12))
    seg = s[a:b].copy(); f = int(sr * 0.006)
    if len(seg) > 2 * f: seg[:f] *= np.linspace(0, 1, f).astype(np.float32); seg[-f:] *= np.linspace(1, 0, f).astype(np.float32)
    return seg, True
def cut_word_relaxed(s, sr, first_pause=150):
    """يجرّب وقفات أقصر تدريجيًا قبل الاستسلام (ذُرَة كانت تفشل عند 100 مللي). تطابق cutWordRelaxed في SyllableCutter.java."""
    for pm in [first_pause, 100, 70, 50, 35]:
        if pm > first_pause: continue
        seg, ok = cut_word(s, sr, gap_ms=min(90, pm), min_pause_ms=pm)
        if ok: return seg, True
    return s, False
def ta_to_ha(c):  # الكلمات المنتهية بتاء مربوطة: بديل ينطق نهايتها هاءً (ذُرَة ← ذُرَه)
    return c[:-1] + "\u0647" if c.endswith("\u0629") else None
def name_phrases(c):
    w = NAME_WORD[c]
    return [f"{c} ، ، ، {w}", f"{c} ، ، ، ، {w}", f"{c} ، ، {w}"]
def cut_name(s, sr, gap_ms=90):
    """أول مقطع منطوق (الاسم) قبل الوقفة. يطابق cutName في SyllableCutter.java."""
    fr = int(sr * 0.01); n = len(s) // fr
    if n < 5: return s, False
    e = np.sqrt((s[:n * fr].reshape(n, fr) ** 2).mean(axis=1))
    act = e > max(float(e.max()) * 0.03, 1e-4)
    idx = np.where(act)[0]
    if len(idx) == 0: return s, False
    start = int(idx[0]); end = start; gap = 0; k = start
    while k < n:
        if act[k]: end = k; gap = 0
        else:
            gap += 1
            if gap * 10 >= gap_ms: break
        k += 1
    more = bool(act[end + 1:].any())
    a = max(0, start * fr - int(sr * 0.04)); b = min(len(s), (end + 1) * fr + int(sr * 0.10))
    seg = s[a:b].copy(); f = int(sr * 0.006)
    if len(seg) > 2 * f: seg[:f] *= np.linspace(0, 1, f).astype(np.float32); seg[-f:] *= np.linspace(1, 0, f).astype(np.float32)
    return seg, (more and 0.25 <= (end + 1 - start) * 0.01 <= 0.8)

def max_syl_dur(c): return 0.70 if syl_kind(c) == "madd" else 0.5   # أطول مدة معقولة لمقطع واحد

def cut_last(s, sr, max_dur=0.9, gap_ms=90):
    """يقتطع آخر مقطع. إن التصق مقطعان (عاعا) نقسمهما عند أعمق انخفاض في الطاقة ونأخذ الأخير. تطابق SyllableCutter.cutLast."""
    fr = int(sr * 0.01); n = len(s) // fr
    if n < 5: return s, False
    e = np.sqrt((s[:n * fr].reshape(n, fr) ** 2).mean(axis=1))
    act = e > max(float(e.max()) * 0.03, 1e-4)
    idx = np.where(act)[0]
    if len(idx) == 0: return s, False
    end = int(idx[-1]); start = end; gap = 0; k = end
    while k >= 0:
        if act[k]: start = k; gap = 0
        else:
            gap += 1
            if gap * 10 >= gap_ms: break
        k -= 1
    while (end + 1 - start) * 0.01 > max_dur:
        best = -1
        for i in range(start + 12, end - 11):
            if e[i] <= e[i - 1] and e[i] <= e[i + 1] and (end - i) * 0.01 >= 0.12:
                left = float(e[start:i].max()); right = float(e[i:end + 1].max())
                if e[i] < 0.5 * min(left, right): best = i
        if best < 0:
            best = start + (end + 1 - start) // 2 - 1   # عاعا ملتصقة: نأخذ النصف الأخير
            if (end - best) * 0.01 < 0.12: break
        start = best + 1
        while start < end and not act[start]: start += 1
    a = max(0, start * fr - int(sr * 0.04)); b = min(len(s), (end + 1) * fr + int(sr * 0.12))
    seg = s[a:b].copy(); f = int(sr * 0.006)
    if len(seg) > 2 * f: seg[:f] *= np.linspace(0, 1, f).astype(np.float32); seg[-f:] *= np.linspace(1, 0, f).astype(np.float32)
    return seg, (0.12 <= (end + 1 - start) * 0.01 <= max_dur)

# حروف يخلط بينها السمع (ذ/د، ظ/ض، ح/ه/خ ...): ظهور الحرف الخطأ في التفريغ يُنقص الدرجة
CONF = {"ذ": "دز", "ظ": "ضز", "ض": "ظد", "د": "ذ", "ز": "ذس", "ح": "هخ", "خ": "حغ", "ه": "حا", "ع": "اء", "ء": "عا", "غ": "خع",
        "ث": "ست", "س": "صث", "ص": "س", "ط": "ت", "ت": "ط", "ق": "ك", "ك": "ق", "ش": "س", "ج": "ش", "ف": "ث"}
def syl_score(c, heard):
    """درجة التحقق لمقطع (يُنطق ثلاث مرات): عدد مرات سماع الحرف المقصود، مخصومًا منه الحرف المشتبه به؛ وللمد يجب أن يتبعه حرف المد."""
    g = norm(heard).replace(" ", "")
    if not g: return 0.0
    L = norm(syl_letter(c)); cnt = g.count(L)
    if L == "ا": sc = 1.0 if (cnt >= 3 and len(g) <= 10) else 0.5   # الهمزة/الألف: العدّ وحده لا يميّز
    else:
        conf = max([g.count(x) for x in CONF.get(L, "")] or [0])
        sc = max(0.0, min(1.0, (cnt - 0.5 * conf) / 3.0))
    if syl_kind(c) == "madd" and c != "\u0622":
        v = norm(c[2])
        if not re.search(re.escape(L) + v, g): sc *= 0.6
    if len(g) > 14: sc *= 0.7   # كلام زائد (هلوسة) في التفريغ
    return sc

def score(expected, n, got):
    g = norm(got).replace(" ", "")
    if n is not None:  # رقم: يُقبل فقط إذا نُطق بقيمته تمامًا (لا بشبهه): ثلاثون ≠ ثمانون
        v = parse_ar_number(got)
        if v is not None: return 1.0 if v == n else 0.0
        return 0.0  # لم يُفهم كعدد → غير مقبول، وتُجرَّب الصيغ الأخرى
    e = norm(expected).replace(" ", "")
    if not e or not g: return 0.0
    return difflib.SequenceMatcher(None, e, g).ratio()

items = json.load(open(os.path.join(here, "lessons.json"), encoding="utf-8"))
if a.max_num: items = [r for r in items if len(r) < 3 or r[2] <= a.max_num]
if a.start or a.limit: items = items[a.start:(a.start + a.limit) if a.limit else None]
def item_key(r): return f"{int(r[1] * 100 + 0.5)}|{clean(r[0])}"
WANT = {item_key(r) for r in items}
WORDCUT = 5   # رقم طريقة توليد كلمة الصورة وحدها؛ إن اختلف عمّا في الفهرس القديم أُعيد توليد هذه الكلمات فقط
WORDKEYS = {item_key(r) for r in items if (len(r) < 3 or r[2] is None) and clean(r[0]) in WORD_NAME and not syl_kind(clean(r[0]))}

def load_idx(p):
    """يقرأ ملف فهرس (رئيسي أو جزء من تقسيم) إن كان بنفس الخطوات والصيغة، وإلا يعيد فارغًا."""
    try:
        o = json.load(open(p, encoding="utf-8"))
        if o.get("steps") == a.steps and o.get("format") == "m4a":
            it = {k: v for k, v in o.get("items", {}).items() if clean(key_text(k)) not in REDO}   # التصحيحات اليدوية: تُعاد دائمًا
            if o.get("wordcut") != WORDCUT: it = {k: v for k, v in it.items() if k not in WORDKEYS}   # كلمات الصور القديمة تُعاد
            return it, {k: v for k, v in o.get("weak", {}).items() if k in it}
    except Exception: pass
    return {}, {}

T0 = time.time()
DEADLINE = T0 + a.budget_min * 60 if a.budget_min else 0

def encode(pcm, path):
    subprocess.run([FF, "-y", "-loglevel", "error", "-f", "s16le", "-ar", "22050", "-ac", "1", "-i", "-",
                    "-c:a", "aac", "-b:a", a.bitrate, "-movflags", "+faststart", path], input=pcm, check=True)

def work(task):
    voice, shard = task
    import sherpa_onnx
    from scipy.signal import resample_poly
    d = a.model
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
            duration_predictor=f"{d}/duration_predictor.int8.onnx", text_encoder=f"{d}/text_encoder.int8.onnx",
            vector_estimator=f"{d}/vector_estimator.int8.onnx", vocoder=f"{d}/vocoder.int8.onnx",
            tts_json=f"{d}/tts.json", unicode_indexer=f"{d}/unicode_indexer.bin", voice_style=f"{d}/voice.bin"),
        num_threads=1))
    tts = sherpa_onnx.OfflineTts(cfg)
    rec = None
    w = a.asr_dir
    if os.path.exists(f"{w}/small-encoder.int8.onnx"):
        rec = sherpa_onnx.OfflineRecognizer.from_whisper(encoder=f"{w}/small-encoder.int8.onnx", decoder=f"{w}/small-decoder.int8.onnx",
                                                         tokens=f"{w}/small-tokens.txt", language="ar", task="transcribe", num_threads=1)
    else:
        print("تحذير: نموذج التحقق غير موجود؛ تُولَّد الأصوات بلا تحقّق من النطق.", flush=True)
    def transcribe(s, sr):
        x = resample_poly(s, 16000, sr).astype(np.float32) if sr != 16000 else s
        st = rec.create_stream(); st.accept_waveform(16000, x); rec.decode_stream(st)
        return st.result.text.strip()
    od = os.path.join(a.out, f"v{voice}"); os.makedirs(od, exist_ok=True)
    # كل عملية تعالج حصتها فقط (n % shards == shard) وتكتب فهرسها الجزئي؛ ويُدمج الجميع في index.json بعد الانتهاء
    import zlib
    def grp(n, r):   # كلمة الدرس ومقطع «الحرف، الكلمة» الخاص بها في العملية نفسها كي يتوفر المقطع عند اقتطاع الكلمة
        c0 = clean(r[0])
        if len(r) < 3 or r[2] is None:
            if c0 in WORD_NAME: return zlib.crc32(c0.encode("utf-8"))
            if c0 in BOTH_WORD: return zlib.crc32(BOTH_WORD[c0].encode("utf-8"))
        return n
    my_items = [r for n, r in enumerate(items) if grp(n, r) % a.shards == shard]
    is_wd = lambda r: (len(r) < 3 or r[2] is None) and clean(r[0]) in WORD_NAME and not syl_kind(clean(r[0]))
    my_items = [r for r in my_items if not is_wd(r)] + [r for r in my_items if is_wd(r)]   # الكلمات وحدها بعد مقاطعها
    mine = {item_key(r) for r in my_items}
    ip = os.path.join(od, "index.json" if a.shards == 1 else f"index.s{shard}.json")
    index = {"rate": 22050, "format": "m4a", "steps": a.steps, "voice": voice, "wordcut": WORDCUT, "items": {}, "weak": {}}
    for p in [os.path.join(od, "index.json")] + sorted(glob.glob(os.path.join(od, "index.s*.json"))):   # ما وُلّد سابقًا (حتى لو بعدد تقسيم مختلف)
        it, wk = load_idx(p)
        for k, v in it.items():
            if k in mine and os.path.exists(os.path.join(od, v)):
                index["items"][k] = v
                if k in wk: index["weak"][k] = wk[k]
    def save(): json.dump(index, open(ip, "w", encoding="utf-8"), ensure_ascii=False)
    made = 0; stopped = False
    for n, row in enumerate(my_items):
        text, sp = row[0], row[1]; num = row[2] if len(row) > 2 else None
        c = clean(text); key = f"{int(sp * 100 + 0.5)}|{c}"
        fn = hashlib.sha1(key.encode("utf-8")).hexdigest()[:12] + ".m4a"  # ثابت: لا يتلف عند تغيّر ترتيب القائمة
        if key in index["items"] and os.path.exists(os.path.join(od, index["items"][key])): continue
        if DEADLINE and time.time() > DEADLINE: stopped = True; break
        best = None
        syl = bool(syl_kind(c)) or c in NAME_WORD
        wd = (num is None) and (c in WORD_NAME) and not syl
        if wd:   # أولًا: ذيل مقطع «الحرف، الكلمة» الجاهز نفسه (النطق الذي يسمعه الطفل سليمًا)؛ إن لم يصلح فالتوليد أدناه
            bk = f"{int(sp * 100 + 0.5)}|{clean(BOTH_TEXT[c])}"
            bf = os.path.join(od, index["items"].get(bk, ""))
            if bk in index["items"] and os.path.isfile(bf):
                try:
                    seg, okc = cut_word_relaxed(decode_m4a(bf), 22050, 100)
                    if not okc: print("فشل اقتطاع الكلمة من مقطع الحرف:", c, flush=True)
                    if okc:
                        sc0 = 1.0 if not rec else score(c, num, transcribe(seg, 22050))
                        if sc0 >= 0.75: best = (seg, sc0, "")
                except Exception as ex: print("تعذّر اقتطاع", c, ex, flush=True)
        if wd:   # كلمة صورة: ثم مقتطعة من «الاسم ، ، ، الكلمة» (مضمونة النطق كما مع الحرف)، ثم وحدها كاحتياط
            ph = word_phrases(c); nodiac = re.sub("[\u064B-\u065F\u0670]", "", c)
            cands = [("cut", ph[0]), ("cut", ph[1]), ("cut", ph[0]), ("cut", ph[1]), ("cut", ph[2])] + [("cut", x) for x in ph[3:]] + [("dir", c), ("dir", nodiac)] + [("dir", x) for x in ALT.get(c, [])] + [("cut", f"{WORD_NAME[c]}، {x}") for x in ALT.get(c, [])]
        ntries = (0 if best else len(cands)) if wd else ((max(a.tries, min(10, a.tries * 2)) if syl else a.tries) if rec else 1)
        for attempt in range(1, ntries + 1):
            g = sherpa_onnx.GenerationConfig(); g.sid = voice; g.speed = float(sp); g.num_steps = a.steps; g.extra = {"lang": "ar"}
            syl = bool(syl_kind(c)); nm = c in NAME_WORD
            vs = name_phrases(c) if nm else (carriers(c) if syl else variants(c, num))
            kind, txt = cands[attempt - 1] if wd else ("", vs[(attempt - 1) % len(vs)])
            r = tts.generate(txt, g)
            raw = np.asarray(r.samples, dtype=np.float32)
            if wd and kind == "cut": s, okdur = cut_word_relaxed(raw, r.sample_rate, 100 if txt == word_phrases(c)[0] else 150)
            elif nm: s, okdur = cut_name(raw, r.sample_rate)
            elif syl: s, okdur = cut_last(raw, r.sample_rate, max_syl_dur(c))
            else: s, okdur = trim(raw, r.sample_rate), True
            if r.sample_rate != 22050: s = resample_poly(s, 22050, r.sample_rate).astype(np.float32)
            if not rec:
                if wd and not okdur: continue
                best = (s, 1.0, ""); break
            # المقطع القصير (0.2 ث) لا يستطيع Whisper سماعه منفردًا؛ نكرره ثلاثًا بفواصل للتحقق فقط (الملف المحفوظ هو المقطع وحده)
            if syl:
                gp = np.zeros(int(22050 * 0.35), dtype=np.float32); heard = transcribe(np.concatenate([s, gp, s, gp, s]), 22050)
            else: heard = transcribe(s, 22050)
            sc = (0.0 if not okdur else syl_score(c, heard)) if syl else (0.0 if (nm or wd) and not okdur else score(c, num, heard))
            if best is None or sc > best[1]: best = (s, sc, heard)
            if sc >= a.accept: break
        s = best[0]
        if best[1] < a.accept and rec: index["weak"][key] = [round(best[1], 2), best[2]]
        else: index["weak"].pop(key, None)
        pk = float(np.abs(s).max()) or 1.0
        encode((s / pk * 0.95 * 32767).astype("<i2").tobytes(), os.path.join(od, fn))
        index["items"][key] = fn; made += 1
        if made % 25 == 0:
            save(); print(f"صوت {voice}: {len(index['items'])}/{len(my_items)}  ({int(time.time()-T0)}s)", flush=True)
    save()
    print(f"صوت {voice}: {'توقّف مؤقتًا' if stopped else 'اكتمل'} {len(index['items'])}/{len(my_items)}", flush=True)
    return voice, (not stopped)

if __name__ == "__main__":
    voices = [int(x) for x in a.voices.split(",") if x.strip() != ""]
    os.makedirs(a.out, exist_ok=True)
    done_mark = os.path.join(a.out, ".complete")
    if os.path.exists(done_mark): os.remove(done_mark)
    tasks = [(v, k) for v in voices for k in range(max(1, a.shards))]
    with get_context("fork").Pool(max(1, min(a.jobs, len(tasks)))) as pool:
        res = pool.map(work, tasks, chunksize=1)
    # دمج الفهارس الجزئية في index.json لكل صوت، وحذف ما لم يعد مطلوبًا
    for v in voices:
        od = os.path.join(a.out, f"v{v}")
        if not os.path.isdir(od): continue
        parts = sorted(glob.glob(os.path.join(od, "index.s*.json")))
        items_m, weak_m = {}, {}
        for p in [os.path.join(od, "index.json")] + parts:
            it, wk = load_idx(p); items_m.update(it); weak_m.update(wk)
        if not (a.start or a.limit):
            for k in [k for k in items_m if k not in WANT]: items_m.pop(k, None); weak_m.pop(k, None)
            used = set(items_m.values())
            for f in os.listdir(od):
                if f.endswith(".m4a") and f not in used:
                    try: os.remove(os.path.join(od, f))
                    except OSError: pass
        json.dump({"rate": 22050, "format": "m4a", "steps": a.steps, "voice": v, "wordcut": WORDCUT, "items": items_m, "weak": weak_m},
                  open(os.path.join(od, "index.json"), "w", encoding="utf-8"), ensure_ascii=False)
        for p in parts:
            try: os.remove(p)
            except OSError: pass
    size = 0
    for v in voices:
        vd = os.path.join(a.out, f"v{v}")
        if os.path.isdir(vd): size += sum(os.path.getsize(os.path.join(vd, f)) for f in os.listdir(vd))
    lines = []
    for v in voices:
        ipath = os.path.join(a.out, f"v{v}", "index.json")
        if os.path.exists(ipath):
            ix = json.load(open(ipath, encoding="utf-8"))
            wk = ix.get("weak", {})
            lines.append(f"== صوت {v+1} (رقم {v}): {len(ix.get('items', {}))} عنصرًا، منها {len(wk)} لم يجتز التحقق")
            for k, (sc, heard) in sorted(wk.items()): lines.append(f"   {sc:.2f}  المطلوب: {k.split('|',1)[1]}   |   سمعه النظام: {heard}")
    try: open(a.report, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    except Exception: pass
    print("\n".join(l for l in lines if l.startswith("==")))
    ok = all(r[1] for r in res)
    if ok and not a.limit:
        open(done_mark, "w").write("ok")
        print(f"اكتمل: {len(voices)} أصوات ({round(size/1e6,1)} MB)")
    else:
        print(f"توقّف مؤقتًا بسبب حدّ الوقت ({round(size/1e6,1)} MB حتى الآن). أعد البناء لإكمال الباقي.")
