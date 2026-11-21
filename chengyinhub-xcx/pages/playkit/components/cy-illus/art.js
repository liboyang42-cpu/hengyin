// cy-illus 插图集(2026-11-10 launch 底座 qx-base)。
// 逐字移植自 docs/delivery/questo-ui-20261109/proto/game/icons.js(业主批准的原型):
// 两层 —— 底层白色有机形(blob),上层粗黑描边 + 白填充 + 柠檬绿点缀的手绘物件(figure)。全部原创 SVG。
// 小程序不能内联 <svg>,所以各层各自出成 data URI,由组件分别做进场 / 待机的 transform 过渡。
// l-iconsB 2026-11-11:风格 B v2 十枚由 art-b.js 出图;D-118 2026-11-12:另外 15 枚也补画进 art-b.js,旧画法(白色有机形底 + 单色手绘)整体删除。
// 名字与 layers() 用法不变,所有调用处自动换图;未知名字回落「线索」。
'use strict';
var B = require('./art-b.js');
var NAMES = B.NAMES;

/* l-play 2026-11-10 修:原型里 LN + ' stroke="…"' 这种写法会在同一个标签上出现两个同名属性,HTML 解析器容忍(取第一个),
   但 <image> 加载 SVG 走 XML 解析,重复属性 = 不合法 → 物件层整张不出(只剩白色底形)。这里按 HTML 的取法去重(保留第一个),画面与原型一致。 */
function dedupeAttrs(svg) {
  return svg.replace(/<([a-zA-Z][\w-]*)((?:\s+[\w:-]+="[^"]*")*)\s*(\/?)>/g, function (m, tag, attrs, close) {
    var seen = {}, out = '';
    attrs.replace(/\s+([\w:-]+)="([^"]*)"/g, function (am, k, v) { if (!seen[k]) { seen[k] = 1; out += ' ' + k + '="' + v + '"'; } return am; });
    return '<' + tag + out + (close ? '/' : '') + '>';
  });
}
function uri(body) {
  return 'data:image/svg+xml;charset=utf8,' + encodeURIComponent(dedupeAttrs('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120" width="120" height="120" overflow="visible">' + body + '</svg>'));
}
function has(name) { return B.has(name); }
var ORDER = ['clue', 'badge', 'stamp', 'key', 'success', 'fail', 'wait', 'team', 'seat', 'pass', 'points', 'seal', 'trophy', 'compass', 'backpack', 'bookmark', 'envelope', 'star', 'locked', 'unlocked', 'hourglass', 'gem', 'sparkles', 'question', 'rays'];
var B_CACHE = {};
/** 地影单独作 blob(bare 时不画),其余整枚作 fg(给只要一张图的宿主,如分享卡);layers 给组件逐层做动作。 */
function layersB(key) {
  if (B_CACHE[key]) return B_CACHE[key];
  var ls = B.split(key), gd = '', fg = '';
  ls.forEach(function (l) { var svg = l.parts.map(function (p) { return p.svg; }).join(''); if (l.k === 'gd') gd += svg; else fg += l.g ? '<g class="' + l.k + '">' + svg + '</g>' : svg; });
  B_CACHE[key] = {
    name: key, label: NAMES[key], b2: true, blob: uri(gd), fg: uri(fg),
    layers: ls.map(function (l, i) { return { id: i, k: l.k || '_', g: l.g, parts: l.parts.map(function (p, j) { return { id: j, k: p.k || '_', src: uri(p.svg) }; }) }; }),
  };
  return B_CACHE[key];
}
/** 未知名字回落「线索」。 */
function layers(name) { return layersB(has(name) ? name : 'clue'); }
module.exports = { NAMES: NAMES, LIST: ORDER, B_LIST: B.LIST, layers: layers, has: has, isB: B.has, dedupeAttrs: dedupeAttrs };
