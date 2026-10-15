/* مدقّق تشكيل عربي للنصوص التعليمية. يتحقق من:
   1) لا حركة بلا حرف، ولا حركتان متعارضتان (فتحة+كسرة)، ولا سكون مع حركة، والشدة تتبعها حركة أو تكون في آخر الكلمة.
   2) كل حرف صامت (غير حروف المد وغير الأخير) له حركة أو سكون أو شدة.
   3) حرف المد (ا/و/ي) الساكن مسبوق بحركة مجانسة: فتحة قبل الألف، ضمة قبل الواو، كسرة قبل الياء
      (الواو والياء بعد فتحة وبسكون صريح هما حرفا لين: بَيْت، نَوْم).
   4) لا تكرار لعلامة، ولا تطويل (ـ)، ولا أرقام لاتينية داخل النص المنطوق. */
"use strict";
const FATHA = "\u064E", DAMMA = "\u064F", KASRA = "\u0650", SUKUN = "\u0652", SHADDA = "\u0651",
  TANWIN = "\u064B\u064C\u064D";
const isDiac = c => /[\u064B-\u0652\u0670]/.test(c);
const isLetter = c => /[\u0621-\u064A]/.test(c);
function lintWord(w, kind) {
  const errs = [];
  w = w.replace(/مِائ/g, "مِئ"); // ألف «مِائَة/مِائَتَان» إملائية لا مدّ
  if (/\u0640/.test(w)) errs.push("تطويل");
  if (/[0-9]/.test(w)) errs.push("رقم لاتيني");
  const ch = Array.from(w);
  for (let i = 0; i < ch.length; i++) {
    const c = ch[i];
    if (isDiac(c) && (i === 0 || !(isLetter(ch[i - 1]) || isDiac(ch[i - 1])))) errs.push("حركة بلا حرف عند " + i);
  }
  // مجموعات الحرف + علاماته
  const g = []; let cur = null;
  ch.forEach(c => { if (isLetter(c)) { cur = { l: c, d: [] }; g.push(cur); } else if (isDiac(c) && cur) cur.d.push(c); });
  g.forEach((x, i) => {
    const d = x.d, vow = d.filter(c => [FATHA, DAMMA, KASRA].includes(c) || TANWIN.includes(c));
    if (vow.length > 1) errs.push("حركتان على " + x.l);
    if (d.includes(SUKUN) && (vow.length || d.includes(SHADDA))) errs.push("سكون مع حركة/شدة على " + x.l);
    if (new Set(d).size !== d.length) errs.push("تكرار علامة على " + x.l);
    const last = i === g.length - 1, prev = g[i - 1], next = g[i + 1];
    const afterAlef = prev && prev.l === "ا" && prev.d.length === 0; // زَاي، وَاو: حرف لين بعد ألف المد
    const afterTanwin = prev && prev.d.some(c => TANWIN.includes(c)); // مَرْحَبًا
    const isMaddLetter = "اوي".includes(x.l) && d.length === 0 && prev && !prev.d.includes(SUKUN) && !afterAlef && !afterTanwin;
    if (isMaddLetter) {
      const pv = prev.d.find(c => [FATHA, DAMMA, KASRA].includes(c));
      const need = x.l === "ا" ? FATHA : x.l === "و" ? DAMMA : KASRA;
      if (pv && pv !== need) errs.push("مد غير مجانس: " + (prev.l + pv + x.l));
      if (!pv && !prev.d.includes(SHADDA)) errs.push("حرف مد بلا حركة قبله: " + prev.l + x.l);
    } else if (afterAlef || afterTanwin) { /* حرف لين/ألف تنوين: سليم */
    } else if (i === 1 && g[0].l === "ا" && x.l === "ل" && !d.length) { /* «ال» التعريف تُكتب بلا سكون */
    } else if (!d.length && !last && !"اىةءآأإ".includes(x.l) && !(x.l === "ا")) {
      // حرف صامت بلا حركة وسط الكلمة (باستثناء مدّه الذي عولج أعلاه، وألف وصل)
      if (!("وي".includes(x.l))) errs.push("حرف بلا حركة: " + x.l + " (الموضع " + i + ")");
      else if (!(prev && !prev.d.includes(SUKUN))) errs.push("حرف علة بلا حركة: " + x.l);
    }
  });
  return errs;
}
function lintText(t, kind) {
  const errs = [];
  const parts = String(t).split(/[\s،:]+/).filter(Boolean);
  parts.forEach(p => {
    // الواو العاطفة المتصلة: وَ + كلمة
    lintWord(p, kind).forEach(e => errs.push(e + " في «" + p + "»"));
  });
  return errs;
}
function lintAll(list) {
  const out = [];
  list.forEach(([kind, t]) => lintText(t, kind).forEach(e => out.push(`[${kind}] «${t}»: ${e}`)));
  return out;
}
module.exports = { lintAll, lintText, lintWord };
if (require.main === module) { const t = process.argv.slice(2).join(" "); const e = lintText(t, "word"); console.log(e.length ? e.join("\n") : "سليم"); }
