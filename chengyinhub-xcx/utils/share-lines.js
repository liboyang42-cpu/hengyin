'use strict';
/* r3-share:分享卡大标题折行(通用分享层导出图、结算分享卡共用)。
 * 中文逐字可断;连续的字母 / 数字 / 符号算一个词,整词放不下才换行,词本身比一行还宽时才逐字拆。
 * 最多 maxRows 行,最后一行放不下加省略号。measure(text) 返回像素宽度(canvas measureText().width)。纯函数,可单测。 */

function tokens(text) {
  return String(text || '').match(/[A-Za-z0-9][A-Za-z0-9._\-'’]*|\s+|[\s\S]/g) || [];
}

function fitRows(text, measure, maxW, maxRows) {
  const limit = maxRows || 2;
  const rows = [''];
  let more = false;
  const push = (piece) => {
    const i = rows.length - 1;
    if (!rows[i] || measure(rows[i] + piece) <= maxW) { rows[i] += piece; return true; }
    if (i + 1 >= limit) { more = true; return false; }
    rows.push(/^\s+$/.test(piece) ? '' : piece);
    return true;
  };
  tokens(text).every((tok) => {
    if (measure(tok) > maxW && tok.length > 1) return Array.from(tok).every(push);
    return push(tok);
  });
  const out = rows.map((r) => r.trim()).filter(Boolean);
  if (more && out.length) {
    let t = out[out.length - 1];
    while (t.length > 1 && measure(t + '…') > maxW) t = t.slice(0, -1);
    out[out.length - 1] = t + '…';
  }
  return out;
}

module.exports = { fitRows };
