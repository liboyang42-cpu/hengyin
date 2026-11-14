// 汉字数字(二〇〇、一千二百)不能断在两行:连续数字字符之间放 U+2060(不可见的禁断连接符)
const RUN = /[〇零一二三四五六七八九十百千万亿两]{2,}/g;
const WJ = '⁠';

function keepNumerals(text) {
  return String(text == null ? '' : text).replace(RUN, (m) => m.split('').join(WJ));
}

/* 判定 / 反馈 / 结果文字:句间连着的多个空格并成一个,再保护汉字数字不断行 */
function tidyText(text) {
  return keepNumerals(String(text == null ? '' : text).replace(/[ \t]{2,}/g, ' '));
}

module.exports = { keepNumerals, tidyText };
