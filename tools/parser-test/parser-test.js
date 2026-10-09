// Mirrors NotificationParser.java's pattern table 1:1 (same regex semantics).
// Run: node parser-test.js
const SKIP = [
  /קיבלת/, /התקבל/, /זוכה/, /זיכוי/, /הועבר אליך/, /received/i,
  /ביטול/, /מבוטל/, /נדח/, /refund/i, /reversed/i, /declined/i,
];
const AMOUNT = [
  /₪\s?([\d,]+(?:\.\d{1,2})?)/,
  /([\d,]+(?:\.\d{1,2})?)\s?₪/,
  /ש"ח\s?([\d,]+(?:\.\d{1,2})?)/,
  /([\d,]+(?:\.\d{1,2})?)\s?ש"ח/,
  /NIS\s?([\d,]+(?:\.\d{1,2})?)/i,
  /([\d,]+(?:\.\d{1,2})?)\s?NIS/i,
];
const MERCHANT = [
  /(?:^|\s)בחנות\s+([\p{L}\p{N}&.'’\- ]{2,40}?)(?:\s*[,.]|\s+מסתיים|\s*$)/u,
  /(?:^|\s)אצל\s+([\p{L}\p{N}&.'’\- ]{2,40}?)(?:\s*[,.]|\s+בסך|\s*$)/u,
  /(?:^|\s)ב[־\-]?(?!סך(?![\p{L}\p{N}])|כרטיס(?![\p{L}\p{N}])|חנות(?![\p{L}\p{N}]))([\p{L}\p{N}&.'’\- ]{2,40}?)(?:\s*[,.]|\s+בסך|\s+בכרטיס|\s+מסתיים|\s*$)/u,
  /(?:^|\s)ל[־\-]?([\p{L}\p{N}&.'’\- ]{2,40}?)(?:\s*[,.]|\s*$)/u,
  /(?:^|\s)at\s+([\p{L}\p{N}&.'’\- ]{2,40}?)(?:\s*[,.]|\s*$)/iu,
];
const CARD = [
  /[*xX]{2,}\s?(\d{4})/,
  /מסתיים ב[־\-]?\s?(\d{4})/,
  /ending in\s?(\d{4})/i,
  /כרטיס\s?(\d{4})/,
];

function parse(title, text) {
  const blob = [title, text].filter(Boolean).join('\n');
  if (!blob.trim()) return { skipped: false, confident: false };
  if (SKIP.some(p => p.test(blob))) return { skipped: true, confident: false };
  let amount = null, amountEnd = -1;
  for (const p of AMOUNT) {
    const m = p.exec(blob);
    if (m) { amount = parseFloat(m[1].replace(/,/g, '')); amountEnd = m.index + m[0].length; break; }
  }
  // merchant preposition follows the amount in charge notifications
  const merchSpace = amountEnd >= 0 ? blob.slice(amountEnd) : blob;
  let merchant = null;
  for (const p of MERCHANT) {
    const m = merchSpace.match(p);
    if (m) { merchant = m[1].trim().replace(/[\s,.]+$/, ''); if (merchant.length >= 2) break; merchant = null; }
  }
  let cardLast4 = null;
  for (const p of CARD) { const m = blob.match(p); if (m) { cardLast4 = m[1]; break; } }
  return { amount, merchant, cardLast4, confident: amount != null && merchant != null };
}

const cases = [
  // [title, text, expected amount, expected merchant, expectSkipped]
  ['ישראכרט', 'חויבת בסך 152.90 ₪ בשופרסל בכרטיס ****4582', 152.90, 'שופרסל', false],
  ['Cal', 'בוצע חיוב בסך ₪1,234.56 ב־AMAZON', 1234.56, 'AMAZON', false],
  ['max', 'חיוב 75 ש"ח אצל קפה גרג', 75, 'קפה גרג', false],
  ['בנק לאומי', 'חויב חשבונך בסך 2,500 ₪', 2500, null, false],   // no merchant -> inbox
  ['bit', 'קיבלת 50 ₪ מישראל ישראלי', null, null, true],        // incoming -> skip
  ['הפועלים', 'בוצעה עסקה בסך NIS 320.00 at ZARA', 320, 'ZARA', false],
  ['PayBox', 'שילמת 45.5 ₪ לדני כהן', 45.5, 'דני כהן', false],
  ['Discount', 'Your card ending in 9912 was charged', null, null, false], // -> inbox
  ['ישראכרט', 'חויבת בסך 99.90 ₪ בחנות נייקי מסתיים ב־7744', 99.90, 'נייקי', false],
  ['Cal', 'ביטול עסקה בסך 200 ₪ בשופרסל', null, null, true],    // refund -> skip
  ['Max', 'העסקה בסך 60 ₪ בקפה נדחתה', null, null, true],       // declined -> skip
];

let fail = 0;
for (const [title, text, expAmt, expMerch, expSkip] of cases) {
  const r = parse(title, text);
  let ok;
  if (expSkip) {
    ok = r.skipped === true;
  } else {
    const okAmt = expAmt === null ? r.amount == null : r.amount === expAmt;
    const okMerch = expMerch === null ? true : r.merchant === expMerch;
    const okConf = expAmt !== null && expMerch !== null ? r.confident === true : true;
    ok = okAmt && okMerch && okConf && !r.skipped;
  }
  if (!ok) fail++;
  console.log(ok ? 'PASS' : 'FAIL', JSON.stringify(text), '->', JSON.stringify(r));
}
console.log(fail === 0 ? '\nAll parser tests passed.' : `\n${fail} FAILURES`);
process.exit(fail === 0 ? 0 : 1);
