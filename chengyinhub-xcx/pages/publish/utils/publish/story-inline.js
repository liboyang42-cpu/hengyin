// 故事流段落的排版(2026-11-10 r2-create,对标 X Articles 编辑器工具栏)。
// 两层,都只挂在**普通正文段**上(叙事块 / 角色台词不挂):
//   style —— 整段样式:标题 / 副标题 / 引用 / 项目列表 / 编号列表;不写 = 正文。
//   marks —— 段内加粗 / 斜体 / 删除线,[{from,to,type}] 的字符区间(左闭右开)。
// 正文 content 始终是纯文字:章节摘要投影、内容安全、玩家旧版本都只读 content,不会看到任何标记符号。
// 与服务端 ChapterFlowCompiler 同一份名单与上限。

var STYLES = ['h1', 'h2', 'quote', 'ul', 'ol'];
var STYLE_LABELS = { body: '正文', h1: '标题', h2: '副标题' };
var MARK_TYPES = ['bold', 'italic', 'strike'];
var MAX_MARKS = 200;

function isStyle(value) { return STYLES.indexOf(value) >= 0; }

function normalizeStyle(value) { return isStyle(value) ? value : ''; }

// 区间清洗:越界截断、空区间丢掉、同类型重叠/相邻合并、按起点排序。
function normalizeMarks(marks, length) {
  var len = Math.max(0, Number(length) || 0);
  var byType = {};
  (Array.isArray(marks) ? marks : []).forEach(function (mark) {
    if (!mark || MARK_TYPES.indexOf(mark.type) < 0) return;
    var from = Math.max(0, Math.min(len, Math.floor(Number(mark.from))));
    var to = Math.max(0, Math.min(len, Math.floor(Number(mark.to))));
    if (!(to > from)) return;
    (byType[mark.type] = byType[mark.type] || []).push({ from: from, to: to });
  });
  var out = [];
  MARK_TYPES.forEach(function (type) {
    var list = (byType[type] || []).sort(function (a, b) { return a.from - b.from; });
    var current = null;
    list.forEach(function (range) {
      if (current && range.from <= current.to) { current.to = Math.max(current.to, range.to); return; }
      current = { from: range.from, to: range.to, type: type };
      out.push(current);
    });
  });
  out.sort(function (a, b) { return a.from - b.from || MARK_TYPES.indexOf(a.type) - MARK_TYPES.indexOf(b.type); });
  return out.slice(0, MAX_MARKS);
}

// 文字改了之后把区间跟过去:按「公共前缀 + 公共后缀」找出被替换的那一截,
// 截前不动、截后整体平移;落在被替换区里的端点收到替换区边上(新打的字不继承格式)。
function remapMarks(marks, before, after) {
  var a = String(before == null ? '' : before);
  var b = String(after == null ? '' : after);
  if (!Array.isArray(marks) || !marks.length) return [];
  if (a === b) return normalizeMarks(marks, b.length);
  var prefix = 0;
  var max = Math.min(a.length, b.length);
  while (prefix < max && a.charCodeAt(prefix) === b.charCodeAt(prefix)) prefix++;
  var suffix = 0;
  while (suffix < max - prefix && a.charCodeAt(a.length - 1 - suffix) === b.charCodeAt(b.length - 1 - suffix)) suffix++;
  var oldEnd = a.length - suffix;
  var delta = b.length - a.length;
  // 起点:在改动之前不动、在改动之后平移;终点同理但「正好在改动起点」不动 ——
  // 所以紧贴格式区两端新打的字都不带格式,打在格式区中间的字带格式。
  function map(pos, isEnd) {
    if (pos < prefix || (isEnd && pos === prefix)) return pos;
    if (pos >= oldEnd) return pos + delta;
    return isEnd ? prefix : prefix + (b.length - suffix - prefix);
  }
  return normalizeMarks(marks.map(function (mark) {
    return { from: map(mark.from, false), to: map(mark.to, true), type: mark.type };
  }), b.length);
}

// [from,to) 这一段是不是整段都有 type
function covered(marks, from, to, type) {
  if (!(to > from)) return false;
  var list = normalizeMarks(marks, Number.MAX_SAFE_INTEGER).filter(function (m) { return m.type === type; });
  var pos = from;
  for (var i = 0; i < list.length && pos < to; i++) {
    if (list[i].from > pos) return false;
    if (list[i].to > pos) pos = list[i].to;
  }
  return pos >= to;
}

// 工具栏 B / I / S:选区整段已有就去掉,否则补满(与 X / 常见编辑器一致)。
function toggleMark(marks, from, to, type, length) {
  if (MARK_TYPES.indexOf(type) < 0) throw new Error('不支持的文字格式');
  var start = Math.max(0, Math.min(from, to));
  var end = Math.min(Number(length) || 0, Math.max(from, to));
  if (!(end > start)) return normalizeMarks(marks, length);
  var list = normalizeMarks(marks, length);
  if (!covered(list, start, end, type)) return normalizeMarks(list.concat([{ from: start, to: end, type: type }]), length);
  var out = [];
  list.forEach(function (mark) {
    if (mark.type !== type || mark.to <= start || mark.from >= end) { out.push(mark); return; }
    if (mark.from < start) out.push({ from: mark.from, to: start, type: type });
    if (mark.to > end) out.push({ from: end, to: mark.to, type: type });
  });
  return normalizeMarks(out, length);
}

// 一段拆成多段(回车 / 粘贴):parts 是各段文字,starts 是各段在原文里的起点。
function sliceMarks(marks, start, end) {
  return normalizeMarks((marks || []).map(function (mark) {
    return { from: Math.max(mark.from, start) - start, to: Math.min(mark.to, end) - start, type: mark.type };
  }), end - start);
}

// 退格合并:后一段的区间平移到前一段末尾。
function concatMarks(prevMarks, prevLength, nextMarks, nextLength) {
  var shifted = (nextMarks || []).map(function (mark) {
    return { from: mark.from + prevLength, to: mark.to + prevLength, type: mark.type };
  });
  return normalizeMarks((prevMarks || []).concat(shifted), prevLength + nextLength);
}

// 静态渲染:按区间端点切成小段,每段带三种格式的开关。
function segments(content, marks) {
  var text = String(content == null ? '' : content);
  var list = normalizeMarks(marks, text.length);
  if (!list.length) return text ? [{ text: text, bold: false, italic: false, strike: false }] : [];
  var cuts = [0, text.length];
  list.forEach(function (mark) { cuts.push(mark.from, mark.to); });
  cuts = cuts.filter(function (v, i, arr) { return arr.indexOf(v) === i; }).sort(function (a, b) { return a - b; });
  var out = [];
  for (var i = 0; i < cuts.length - 1; i++) {
    var from = cuts[i];
    var to = cuts[i + 1];
    if (!(to > from)) continue;
    var on = { bold: false, italic: false, strike: false };
    list.forEach(function (mark) { if (mark.from <= from && mark.to >= to) on[mark.type] = true; });
    out.push({ text: text.slice(from, to), bold: on.bold, italic: on.italic, strike: on.strike });
  }
  return out;
}

// 字数:只数正文段与台词里的字,不数空白。
function wordCount(blocks) {
  var total = 0;
  (Array.isArray(blocks) ? blocks : []).forEach(function (block) {
    if (!block || (block.type !== 'text' && block.type !== 'voice' && block.type !== 'reveal')) return;
    total += String(block.content == null ? '' : block.content).replace(/\s+/g, '').length;
  });
  return total;
}

// 列表编号:连续的 ol 段从 1 往下数,中间隔了别的块就重新数。
function listNumbers(blocks) {
  var out = [];
  var n = 0;
  (Array.isArray(blocks) ? blocks : []).forEach(function (block) {
    if (block && block.type === 'text' && block.style === 'ol') { n += 1; out.push(n); }
    else { n = 0; out.push(0); }
  });
  return out;
}

module.exports = {
  STYLES: STYLES,
  STYLE_LABELS: STYLE_LABELS,
  MARK_TYPES: MARK_TYPES,
  MAX_MARKS: MAX_MARKS,
  isStyle: isStyle,
  normalizeStyle: normalizeStyle,
  normalizeMarks: normalizeMarks,
  remapMarks: remapMarks,
  covered: covered,
  toggleMark: toggleMark,
  sliceMarks: sliceMarks,
  concatMarks: concatMarks,
  segments: segments,
  wordCount: wordCount,
  listNumbers: listNumbers,
};
