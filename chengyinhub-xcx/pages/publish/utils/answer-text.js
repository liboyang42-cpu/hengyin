// 答案文本共享规则(E-A1~E-A3,E-A7)。服务端权威实现是 AnswerNormalizer.java,
// 这里只给作者编辑页预览与「多个答案」拆分用;两边的向量见 tests/unit/answer-text-vectors.test.js。
// 不用 \p{..}:低版本 iOS JSCore 不支持,统一写成显式区间。

/** 多个合法写法的分隔符:、 半角逗号 全角逗号 半角分号 全角分号。必须与 AnswerNormalizer.SEPARATOR_REGEX 一致。 */
const SEPARATOR_CHARS = '、,，;；';
const SEPARATOR = new RegExp('[' + SEPARATOR_CHARS + ']');

// \p{Z} + 空白 + \p{Cc} + \p{Cf}(含 NBSP、U+3000、零宽字符、BOM),以及常见 \p{P}。
// 用码点区间生成字符类,源码里不出现不可见字符。
const esc = (ch) => ch.replace(/[\\\]\[\-^]/g, '\\$&');
const part = (r) => esc(String.fromCharCode(r[0])) + (r.length > 1 ? '-' + esc(String.fromCharCode(r[1])) : '');
const STRIP_ALL = new RegExp('[\\s' + [
  [0x00, 0x1F], [0x7F, 0x9F], [0xA0], [0xAD], [0x600, 0x605], [0x61C], [0x6DD], [0x70F], [0x1680], [0x180E],
  [0x2000, 0x200F], [0x2028, 0x202F], [0x205F, 0x206F], [0x3000], [0xFEFF],
].map(part).join('') + ']+', 'g');
const EDGE_PUNCT = '[' + '!"#%&\'()*,-./:;?@[\\]_{}'.split('').map(esc).join('') + [
  [0xA1], [0xA7], [0xAB], [0xB6], [0xB7], [0xBB], [0xBF], [0x2010, 0x2027], [0x2030, 0x205E],
  [0x3001, 0x3003], [0x3008, 0x3011], [0x3014, 0x301F], [0x30FB],
].map(part).join('') + ']';
const LEADING = new RegExp('^' + EDGE_PUNCT + '+');
const TRAILING = new RegExp(EDGE_PUNCT + '+$');

function normalize(raw) {
  if (raw === null || raw === undefined) return '';
  let text = String(raw);
  if (typeof text.normalize === 'function') text = text.normalize('NFKC');
  return text.replace(STRIP_ALL, '').replace(LEADING, '').replace(TRAILING, '').toLowerCase();
}

function equivalent(a, b) {
  const left = normalize(a);
  const right = normalize(b);
  return !!left && (left === right || (!SEPARATOR.test(left)
    && left === right.replace(new RegExp('[' + SEPARATOR_CHARS + ']', 'g'), '')));
}

/** 按共享分隔符拆成多个写法(去首尾空格、去空段)。 */
function split(text) {
  return String(text === null || text === undefined ? '' : text)
    .split(SEPARATOR).map((one) => one.trim()).filter(Boolean);
}

/** 整串或任一分段与输入归一后相等。 */
function matchesAnyOf(authored, submitted) {
  return equivalent(authored, submitted) || split(authored).some((one) => equivalent(one, submitted));
}

module.exports = { SEPARATOR_CHARS, SEPARATOR, normalize, equivalent, split, matchesAnyOf };
