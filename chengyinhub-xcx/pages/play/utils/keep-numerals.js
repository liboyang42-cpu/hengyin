// 汉字数字(二〇〇、一千二百)不能断在两行:连续数字字符之间放 U+2060(不可见的禁断连接符)
const RUN = /[〇零一二三四五六七八九十百千万亿两]{2,}/g;
const WJ = '⁠';

function keepNumerals(text) {
  return String(text == null ? '' : text).replace(RUN, (m) => m.split('').join(WJ));
}

module.exports = { keepNumerals };
