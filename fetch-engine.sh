#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
V=1.13.8
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
A=app/src/main/assets/models
CACHE="${CAT_CACHE:-$HOME/.cache/talking-cat-engine}"
# إن لم يوجد مجلد التنزيلات الجديد وكان في ‎~/.cache مجلد تنزيلات سابق بالنمط *-engine فنعيد استعماله بدل التنزيل من جديد
if [ ! -d "$CACHE" ]; then
  for d in "$HOME"/.cache/*-engine; do
    if [ -d "$d" ] && [ "$d" != "$CACHE" ]; then mv "$d" "$CACHE" 2>/dev/null || cp -a "$d" "$CACHE"; echo "أُعيد استعمال التنزيلات السابقة من: $d"; break; fi
  done
fi
T="$CACHE/tmp"
mkdir -p app/libs app/src/main/jniLibs/arm64-v8a "$A" "$CACHE" "$T"
need(){ command -v "$1" >/dev/null 2>&1; }
for c in curl unzip bzip2 tar; do need "$c" || { echo "ينقص الأمر: $c. ثبّته ثم أعد المحاولة."; exit 1; }; done
dl(){ [ -s "$2" ]&&return 0; echo "تنزيل: $1"; local i=0 nonet=0 rc=0
  # تنزيل قابل للاستئناف: الملف الجزئي يبقى ويُكمَّل من حيث انقطع (حتى بين محاولات البناء)
  while [ $i -lt 40 ]; do
    i=$((i+1))
    curl -fL -C - --retry 1 --connect-timeout 20 -o "$2.part" "$1" && { mv "$2.part" "$2"; return 0; }
    rc=$?
    # 6 = تعذّر حل اسم الخادم، 7 = تعذّر الاتصال، 28 = انتهت المهلة، 22 = رفض الخادم/رابط غير موجود (403/404).
    # كلها لا تُحَلّ بإعادة المحاولة، وكانت 40 محاولة تضيّع 8 دقائق قبل الفشل.
    if [ $rc -eq 6 ] || [ $rc -eq 7 ] || [ $rc -eq 28 ] || [ $rc -eq 22 ]; then nonet=$((nonet+1)); else nonet=0; fi
    if [ $nonet -ge 3 ]; then
      echo ""
      echo "✘ تعذّر الوصول إلى هذا الرابط (لا إنترنت لهذا الخادم، أو الخادم محجوب، أو الرابط غير موجود)."
      echo "  إمّا فعّل الإنترنت للبناء، أو نزّل الملف التالي يدويًا وضعه باسمه الدقيق ثم أعد البناء:"
      echo "    الرابط : $1"
      echo "    احفظه في: $2"
      exit 1
    fi
    echo "انقطع التنزيل، إعادة المحاولة ($i/40) من نفس الموضع…"; sleep 3
  done
  exit 1; }
dl "$GH/v$V/sherpa-onnx-$V.aar" "$CACHE/sherpa-$V.aar"
rm -rf "$T/aar";mkdir -p "$T/aar"
unzip -oq "$CACHE/sherpa-$V.aar" 'classes.jar' 'jni/arm64-v8a/*' -d "$T/aar"
cp "$T/aar/classes.jar" app/libs/sherpa-onnx.jar
cp "$T/aar/jni/arm64-v8a/"*.so app/src/main/jniLibs/arm64-v8a/
dl "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar" "$CACHE/kotlin-stdlib.jar"
cp "$CACHE/kotlin-stdlib.jar" app/libs/kotlin-stdlib.jar
model(){ F="$CACHE/$(basename "$1")"; dl "$1" "$F"; rm -rf "$A/$2";mkdir -p "$A/$2";tar xjf "$F" -C "$A/$2" --strip-components=1; }
# صوت النطق العربي: Supertonic 3 (31 لغة منها العربية، محلي بالكامل، 10 أصوات).
# أدق في قياس إعادة التعرّف من أصوات Piper العربية (ar_JO) التي كانت مستعملة سابقًا.
TT="sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
TF="$CACHE/$TT.tar.bz2"
dl "$GH/tts-models/$TT.tar.bz2" "$TF" || { echo "تعذّر تنزيل صوت Supertonic."; exit 1; }
rm -rf "$A/tts-ar"; mkdir -p "$A/tts-ar"
tar xjf "$TF" -C "$A/tts-ar" --strip-components=1
for f in duration_predictor.int8.onnx text_encoder.int8.onnx vector_estimator.int8.onnx vocoder.int8.onnx tts.json unicode_indexer.bin voice.bin; do
  [ -s "$A/tts-ar/$f" ] || { echo "ملف ناقص في نموذج النطق: $f"; exit 1; }
done

# تنظيف نماذج التعرف على الكلام القديمة (Whisper) إن بقيت من إصدارات سابقة؛ لم يعد التطبيق يستعملها
rm -rf "$A"/asr-*
touch app/libs/.engine-supertonic
echo "تم تجهيز محرك النطق العربي (Supertonic 3)."

# ===== توليد أصوات الحروف والأرقام (1..1000) والألوان أثناء البناء، لتُنطق فورًا بلا تجهيز على الجهاز =====
# يُستأنف بين البناءات (يتخطى ما تم)، وبحدّ وقت لكل بناء. غيّر الحدّ: PREGEN_MINUTES=40 ، ولتعطيله: SKIP_PREGEN=1
PRE=app/src/main/assets/tts-pre
PC="$CACHE/tts-pre"
if [ "${SKIP_PREGEN:-0}" != "1" ] && [ ! -f "$PRE/.complete" ]; then
  mkdir -p "$PRE" "$PC"
  # استرجاع ما وُلّد سابقًا (مثلًا بعد إعادة استيراد المشروع)
  ls -d "$PC"/v[0-9] >/dev/null 2>&1 && cp -a "$PC"/. "$PRE"/
  rm -f "$PRE/.failed"
  PY=$(command -v python3 || command -v python || true)
  if [ -z "$PY" ]; then
    echo "بايثون غير موجود: سيُولَّد الصوت وقت التشغيل بدل الحزمة الجاهزة."; echo "no python" > "$PRE/.failed"
  else
    "$PY" -c "import sherpa_onnx, numpy, scipy, imageio_ffmpeg" 2>/dev/null || \
      "$PY" -m pip install -q "sherpa-onnx==$V" numpy scipy imageio-ffmpeg 2>/dev/null || \
      "$PY" -m pip install -q --break-system-packages "sherpa-onnx==$V" numpy scipy imageio-ffmpeg || true
    if "$PY" -c "import sherpa_onnx, numpy, scipy, imageio_ffmpeg" 2>/dev/null; then
      W="$CACHE/whisper-small"
      if [ ! -s "$W/small-encoder.int8.onnx" ]; then
        echo "تنزيل نموذج التحقق من صحة النطق (مرة واحدة)…"
        if dl "$GH/asr-models/sherpa-onnx-whisper-small.tar.bz2" "$CACHE/whisper-small.tar.bz2"; then
          mkdir -p "$W" && tar xjf "$CACHE/whisper-small.tar.bz2" -C "$W" --strip-components=1 --wildcards '*.int8.onnx' '*-tokens.txt' || true
        fi
        rm -f "$CACHE/whisper-small.tar.bz2"
      fi
      # مزامنة قائمة النصوص مع speech-data.js (يلتقط أي تصحيح في SAY) قبل التوليد؛ تُتخطّى إن لم تتوفر node
      if command -v node >/dev/null 2>&1; then node tools/gen_lessons.js || echo "تحذير: تعذّر تحديث lessons.json، تُستعمل النسخة الموجودة"; fi
      echo "توليد الأصوات الجاهزة (حدّ ${PREGEN_MINUTES:-25} دقيقة في هذا البناء)…"
      "$PY" tools/pregen.py --voices "${PREGEN_VOICES:-9,1,3,6,7}" --steps "${PREGEN_STEPS:-8}" --budget-min "${PREGEN_MINUTES:-25}" --shards "${PREGEN_SHARDS:-1}" --tries "${PREGEN_TRIES:-6}" --asr-dir "$W" || { echo "تعذّر توليد الأصوات الجاهزة؛ سيُولَّد الصوت وقت التشغيل."; echo "pregen failed" > "$PRE/.failed"; }
    else
      echo "تعذّر تثبيت sherpa-onnx لبايثون؛ سيُولَّد الصوت وقت التشغيل. (احذف $PRE/.failed لإعادة المحاولة)"; echo "pip failed" > "$PRE/.failed"
    fi
  fi
  # حفظ نسخة في الكاش (بدون ملفات العلامات)
  cp -a "$PRE"/v[0-9] "$PC"/ 2>/dev/null || true
fi
