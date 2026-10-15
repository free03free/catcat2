# -*- coding: utf-8 -*-
"""محلّل أرقام عربية (1..1000) من نص منطوق/مكتوب: يقبل الكلمات بأي تشكيل وبصيغ الإملاء الشائعة
(مئة/مائة/ميّة، ثلاثمئة/ثلاث مئة، اثنان/اثنين...) وكذلك الأرقام 123 و١٢٣.
يُستعمل في pregen.py للتحقق من أن الصوت المولَّد ينطق الرقم المطلوب بقيمته لا بشبهه."""
import re

_DIAC = re.compile("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")

def norm_word(t):
    t = _DIAC.sub("", t)
    for x, y in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ٱ", "ا"), ("ى", "ي"), ("ة", "ه"), ("ؤ", "و")):
        t = t.replace(x, y)
    t = t.translate(str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789"))
    return t

UNITS = {"واحد": 1, "احد": 1, "واحده": 1, "اثنان": 2, "اثنين": 2, "اثنا": 2, "اثني": 2, "ثلاثه": 3, "ثلاث": 3,
         "اربعه": 4, "اربع": 4, "خمسه": 5, "خمس": 5, "سته": 6, "ست": 6, "سبعه": 7, "سبع": 7,
         "ثمانيه": 8, "ثماني": 8, "ثمان": 8, "ثمانى": 8, "تسعه": 9, "تسع": 9}
TENS = {"عشرون": 20, "عشرين": 20, "ثلاثون": 30, "ثلاثين": 30, "اربعون": 40, "اربعين": 40, "خمسون": 50, "خمسين": 50,
        "ستون": 60, "ستين": 60, "سبعون": 70, "سبعين": 70, "ثمانون": 80, "ثمانين": 80, "تسعون": 90, "تسعين": 90}
TEN = {"عشره", "عشر"}
HUND = {"ميه": 100, "مايه": 100, "مئه": 100, "مائه": 100, "ميت": 100, "مائتان": 200, "مائتين": 200, "مايتان": 200, "ميتان": 200, "مئتان": 200,
        "مايتين": 200, "ميتين": 200, "مئتين": 200}
THOUSAND = {"الف", "الاف"}

def _tokens(text):
    out = []
    for raw in re.split(r"[\s،,.:؛؟?!\-]+", norm_word(text)):
        if not raw: continue
        cand = [raw]
        if raw.startswith("و") and len(raw) > 2: cand.append(raw[1:])
        out.append(cand)
    return out

def _classify(w):
    if w in UNITS: return ("u", UNITS[w])
    if w in TENS: return ("t", TENS[w])
    if w in TEN: return ("ten", 10)
    if w in HUND: return ("h", HUND[w])
    if w in THOUSAND: return ("k", 1000)
    # ثلاثمئة، خمسمائة ...: وحدة + مئة ملتصقتان
    for h in ("ميه", "مايه", "مئه", "مائه", "ميت"):
        if w.endswith(h) and w[:-len(h)] in UNITS and UNITS[w[:-len(h)]] >= 3:
            return ("h", UNITS[w[:-len(h)]] * 100)
    return None

def parse_ar_number(text):
    """يعيد العدد الصحيح أو None إن لم يكن النص عددًا مفهومًا."""
    s = norm_word(text).strip()
    if re.fullmatch(r"\d+", s.replace(" ", "")): return int(s.replace(" ", ""))
    total = 0; pend = None; seen = False
    toks = _tokens(text)
    if not toks: return None
    for cand in toks:
        c = None
        for w in cand:
            c = _classify(w)
            if c: break
        if not c: return None
        k, v = c; seen = True
        if k == "u":
            if pend is not None: total += pend
            pend = v
        elif k == "ten":
            pend = (pend or 0) + 10
        elif k == "t":
            total += (pend or 0) + v; pend = None
        elif k == "h":
            if v in (100, 200) and pend is not None and v == 100 and pend >= 3:  # ثلاث مئة
                total += pend * 100; pend = None
            else:
                if pend is not None: total += pend; pend = None
                total += v
        elif k == "k":
            total += (pend or 1) * 1000; pend = None
    if pend is not None: total += pend
    return total if seen else None

if __name__ == "__main__":
    import json, os, sys
    here = os.path.dirname(os.path.abspath(__file__))
    rows = json.load(open(os.path.join(here, "lessons.json"), encoding="utf-8"))
    bad = [(r[0], r[2], parse_ar_number(r[0])) for r in rows if len(r) > 2 and parse_ar_number(r[0]) != r[2]]
    nums = sum(1 for r in rows if len(r) > 2)
    print(f"{nums} رقمًا؛ أخطاء الدائرة (نص←رقم): {len(bad)}")
    for b in bad[:20]: print("  ", b)
    # صيغ يكتبها Whisper عادة
    for t, n in (("ثلاثمائة وخمسة وعشرون", 325), ("ثلاث مئة", 300), ("مئة وواحد", 101), ("مية", 100), ("اثنين وعشرين", 22),
                 ("ثمانمائة", 800), ("خمسة عشر", 15), ("أحد عشر", 11), ("ألف", 1000), ("٤٥", 45), ("تسعمئة وتسعة وتسعون", 999)):
        v = parse_ar_number(t); print(("OK  " if v == n else "FAIL"), t, v)
    sys.exit(1 if bad else 0)
