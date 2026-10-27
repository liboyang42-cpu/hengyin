// P-41(2026-10-27):玩法规则一步一条。
// 存量模板(含官方模板库种子)把整份规则写成一行「1. … 2. … 3. …」,编辑页按换行拆步骤时
// 只拆出一步、里面挤着四条。这里在换行之外,再按行内从 1 起连续递增的序号拆开,
// 并去掉每步开头的序号 —— 编辑页自己画序号圆点,不去掉就会印成「① 1. …」。
// 序号可以跨行接续(「1. a\n2. b」)。不是从 1 起连续的数字(「2.5 公里」「第 3 站」)不拆。

// 序号:行首或句读 / 空白之后的 1~2 位数字 + 「.」「、」「．」「)」「）」,后面不能紧跟数字(排除小数)
var MARK = /(^|[\s。；;，,：:！!？?])(\d{1,2})\s*[.、．)）](?!\d)\s*/g;
var TRAILING_SEP = /[\s；;，,：:]+$/;

function splitLine(line, state) {
  var hits = [];
  var m;
  MARK.lastIndex = 0;
  while ((m = MARK.exec(line))) {
    var num = Number(m[2]);
    if (num === state.want) {
      hits.push({ start: m.index + m[1].length, end: MARK.lastIndex });
      state.want += 1;
    }
  }
  if (!hits.length) return [line];
  var out = [];
  var head = line.slice(0, hits[0].start).replace(TRAILING_SEP, '').trim();
  if (head) out.push(head);
  for (var i = 0; i < hits.length; i++) {
    var piece = line.slice(hits[i].end, i + 1 < hits.length ? hits[i + 1].start : line.length);
    piece = piece.replace(TRAILING_SEP, '').trim();
    if (piece) out.push(piece);
  }
  return out.length ? out : [line];
}

/** 规则原文 → 步骤数组(至少一项,空规则给一个空步骤) */
function splitRuleSteps(text) {
  var steps = [];
  var state = { want: 1 };
  String(text == null ? '' : text).split('\n').forEach(function (raw) {
    var line = raw.trim();
    if (!line) return;
    splitLine(line, state).forEach(function (one) { steps.push(one); });
  });
  return steps.length ? steps : [''];
}

module.exports = { splitRuleSteps: splitRuleSteps };
