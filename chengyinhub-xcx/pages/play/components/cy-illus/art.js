// cy-illus 插图集(2026-11-10 launch 底座 qx-base)。
// 逐字移植自 docs/delivery/questo-ui-20261109/proto/game/icons.js(业主批准的原型):
// 两层 —— 底层白色有机形(blob),上层粗黑描边 + 白填充 + 柠檬绿点缀的手绘物件(figure)。全部原创 SVG。
// 小程序不能内联 <svg>,所以两层各自出成 data URI,由组件分别做进场 / 待机的 transform 过渡。
'use strict';
var K = '#121213', L = '#CDFF71', W = '#FFFFFF';
var S = 'fill="' + W + '" stroke="' + K + '" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"';
var SK = 'fill="' + K + '" stroke="' + K + '" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"';
var SL = 'fill="' + L + '" stroke="' + K + '" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"';
var LN = 'fill="none" stroke="' + K + '" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"';
var BLOB = ['M22 58C14 36 34 16 58 14c26-2 44 10 48 34 4 22-12 44-36 50C46 104 24 92 22 58Z', 'M18 50C22 26 46 12 70 16c24 4 36 24 34 46-2 26-24 44-50 42C30 102 14 76 18 50Z', 'M26 46C30 24 52 10 74 18c20 8 32 30 28 52-4 24-28 38-52 32C28 96 20 70 26 46Z'];
// 小感叹气泡(空态里的那个)
function bang(x, y) { return '<g class="bang" transform="translate(' + x + ' ' + y + ')"><path d="M-13 -9c0-7 6-11 13-11s13 4 13 11-6 11-13 11l-5 7-1-8c-5-2-7-5-7-10z" ' + S + ' stroke-width="4"/><path d="M0-14v8M0 0v1" ' + LN + ' stroke-width="4"/></g>'; }
function star4(cx, cy, r, fill) { return '<path d="M' + cx + ' ' + (cy - r) + 'Q' + (cx + r * .18) + ' ' + (cy - r * .18) + ' ' + (cx + r) + ' ' + cy + 'Q' + (cx + r * .18) + ' ' + (cy + r * .18) + ' ' + cx + ' ' + (cy + r) + 'Q' + (cx - r * .18) + ' ' + (cy + r * .18) + ' ' + (cx - r) + ' ' + cy + 'Q' + (cx - r * .18) + ' ' + (cy - r * .18) + ' ' + cx + ' ' + (cy - r) + 'Z" fill="' + (fill || L) + '" stroke="' + K + '" stroke-width="4" stroke-linejoin="round"/>'; }
var D = {
  clue: { b: 0, f: '<path class="hd" d="M72 72 98 98" stroke="' + K + '" stroke-width="12" stroke-linecap="round"/><circle class="lens" cx="52" cy="52" r="24" ' + S + '/><path d="M38 46c2-7 8-12 15-13" ' + LN + ' stroke="' + L + '" stroke-width="6"/><circle class="pip" cx="58" cy="58" r="5" fill="' + L + '"/>' + bang(90, 26) },
  badge: { b: 1, f: '<path class="rib" d="M46 66 36 108l16-8 8 12 4-46z" ' + SL + '/><path class="rib" d="M74 66l10 42-16-8-8 12-4-46z" ' + SL + '/><circle cx="60" cy="50" r="30" ' + LN + ' stroke-dasharray="6 5"/><circle cx="60" cy="50" r="24" ' + S + '/><path d="M60 34l5 11 12 1-9 8 3 12-11-6-11 6 3-12-9-8 12-1z" fill="' + K + '"/>' },
  stamp: { b: 2, f: '<rect x="30" y="24" width="60" height="72" rx="3" ' + S + ' stroke-dasharray="5 4"/><rect x="38" y="32" width="44" height="56" rx="2" ' + LN + ' stroke-width="4"/><path class="wave" d="M40 70c6-10 12-10 18 0s12 10 18 0" ' + LN + ' stroke="' + L + '" stroke-width="7"/><circle cx="52" cy="48" r="7" fill="' + K + '"/><circle cx="52" cy="48" r="13" ' + LN + ' stroke-width="3"/>' },
  key: { b: 0, f: '<g class="kb"><path d="M52 50 96 94" stroke="' + K + '" stroke-width="11" stroke-linecap="round"/><path d="M84 82l10-10M92 92l10-10" stroke="' + K + '" stroke-width="8" stroke-linecap="round"/><circle cx="40" cy="42" r="20" ' + SL + '/><circle cx="40" cy="42" r="7" fill="' + K + '"/><path d="M28 34c3-5 8-8 14-8" ' + LN + ' stroke="' + W + '" stroke-width="5"/></g>' },
  success: { b: 1, f: '<g class="burst">' + star4(60, 56, 34) + star4(96, 30, 9, K) + star4(26, 84, 7, K) + star4(92, 86, 6, W) + '</g>' + bang(90, 60).replace('class="bang"', 'class="bang b2"') },
  fail: { b: 2, f: '<path class="cloud" d="M34 64c-10 0-16-7-16-15 0-9 8-16 18-14 4-12 16-18 28-14 10 3 15 11 15 20 10 0 16 6 16 14 0 7-6 13-16 13H34z" ' + S + '/><path class="bolt" d="M60 60 46 86h12l-6 22 22-30H62l8-18z" ' + SL + '/><circle cx="44" cy="46" r="3" fill="' + K + '"/><circle cx="64" cy="44" r="3" fill="' + K + '"/><path d="M48 56c4-3 10-3 14 0" ' + LN + ' stroke-width="4"/>' },
  wait: { b: 0, f: '<path d="M22 54h76" stroke="' + K + '" stroke-width="9" stroke-linecap="round"/><path d="M36 54c0-18 8-30 24-30s24 12 24 30" ' + SK + '/><path d="M40 42h40" stroke="' + L + '" stroke-width="6" stroke-linecap="round"/><g class="glass"><circle cx="50" cy="80" r="15" ' + S + '/><path d="M61 91l14 14" stroke="' + K + '" stroke-width="9" stroke-linecap="round"/><circle cx="54" cy="84" r="4" fill="' + L + '"/></g><g class="dots"><circle cx="86" cy="80" r="3.5" fill="' + K + '"/><circle cx="96" cy="80" r="3.5" fill="' + K + '"/><circle cx="106" cy="80" r="3.5" fill="' + K + '"/></g>' },
  team: { b: 1, f: '<g class="pl"><circle cx="34" cy="52" r="14" ' + SK + '/><path d="M12 96c0-16 10-26 22-26s22 10 22 26" ' + SK + '/></g><g class="pr"><circle cx="86" cy="52" r="14" ' + SL + '/><path d="M64 96c0-16 10-26 22-26s22 10 22 26" ' + SL + '/></g><g class="pc"><circle cx="60" cy="44" r="17" ' + S + '/><path d="M32 104c0-20 12-32 28-32s28 12 28 32" ' + S + '/><path d="M53 42h4M63 42h4" ' + LN + ' stroke-width="4"/></g>' },
  seat: { b: 2, f: '<path d="M36 26h48c6 0 10 4 10 10v30H26V36c0-6 4-10 10-10z" ' + S + '/><path d="M40 40h20" ' + LN + ' stroke="' + L + '" stroke-width="5"/><path d="M22 66h76v16c0 5-4 8-8 8H30c-4 0-8-3-8-8z" ' + SL + '/><path d="M18 60v26M102 60v26" ' + LN + ' stroke-width="7"/><path d="M34 90v16M86 90v16" ' + LN + ' stroke-width="7"/>' },
  pass: { b: 0, f: '<g class="tk" transform="rotate(-8 60 60)"><path d="M18 36h84v18a8 8 0 0 0 0 16v18H18V70a8 8 0 0 0 0-16z" ' + S + '/><path d="M68 40v44" ' + LN + ' stroke-dasharray="4 5" stroke-width="4"/><rect x="26" y="46" width="32" height="10" rx="5" fill="' + L + '"/><path d="M26 66h22M26 76h14" ' + LN + ' stroke-width="4"/><circle cx="86" cy="62" r="7" fill="' + K + '"/></g>' },
  points: { b: 1, f: '<g class="coin c3"><ellipse cx="60" cy="86" rx="30" ry="12" ' + S + '/></g><g class="coin c2"><ellipse cx="60" cy="70" rx="30" ry="12" ' + SK + '/></g><g class="coin c1"><ellipse cx="60" cy="54" rx="30" ry="12" ' + SL + '/><path d="M44 54c2-5 8-7 14-7" ' + LN + ' stroke="' + W + '" stroke-width="4"/></g>' },
  seal: { b: 2, f: '<path class="wax" d="M60 22c6 0 8 6 14 7s11-3 15 1 0 10 3 15 9 6 9 12-7 6-9 12-1 11-4 15-9 1-15 2-8 7-14 7-8-6-14-7-11 3-15-1 0-10-3-15-9-6-9-12 7-6 9-12 1-11 4-15 9-1 15-2 8-7 14-7z" ' + SL + '/><circle cx="60" cy="60" r="20" ' + S + '/><text class="n" x="60" y="68" text-anchor="middle" font-size="22" font-weight="800" fill="' + K + '" font-family="-apple-system,PingFang SC,sans-serif">__N__</text>' },
  trophy: { b: 0, f: '<path d="M38 28h44v22c0 14-10 24-22 24S38 64 38 50z" ' + S + '/><path d="M38 34c-10 0-16 6-14 14 2 7 8 10 16 10M82 34c10 0 16 6 14 14-2 7-8 10-16 10" ' + LN + '/><path d="M60 74v14M44 96h32M48 88h24" ' + LN + ' stroke-width="7"/><path d="M44 104h32" stroke="' + K + '" stroke-width="9" stroke-linecap="round"/>' + star4(60, 48, 10) },
  compass: { b: 1, f: '<circle cx="60" cy="60" r="34" ' + S + '/><circle cx="60" cy="60" r="26" ' + LN + ' stroke-dasharray="3 6" stroke-width="3"/><g class="needle"><path d="M60 36l8 24-8 24-8-24z" ' + SK + '/><path d="M60 36l8 24H52z" fill="' + L + '" stroke="' + K + '" stroke-width="4" stroke-linejoin="round"/></g><circle cx="60" cy="60" r="4" fill="' + W + '" stroke="' + K + '" stroke-width="3"/><text x="60" y="26" text-anchor="middle" font-size="12" font-weight="800" fill="' + K + '" font-family="-apple-system,sans-serif">N</text>' },
  backpack: { b: 2, f: '<path d="M36 44c0-10 10-18 24-18s24 8 24 18v54H36z" ' + S + '/><path d="M48 26v-8M72 26v-8M48 18h24" ' + LN + '/><rect x="44" y="64" width="32" height="26" rx="6" ' + SL + '/><path d="M44 54h32" ' + LN + ' stroke-width="4"/>' },
  bookmark: { b: 0, f: '<path class="flag" d="M38 20h44v84l-22-16-22 16z" ' + SL + '/><path d="M48 36h24M48 48h16" ' + LN + ' stroke-width="4"/>' },
  envelope: { b: 1, f: '<rect x="20" y="36" width="80" height="54" rx="6" ' + S + '/><path class="flap" d="M20 42l40 28 40-28" ' + LN + '/><path d="M28 86l24-22M92 86L68 64" ' + LN + ' stroke-width="4"/><circle cx="60" cy="72" r="6" fill="' + L + '"/>' },
  star: { b: 2, f: '<path class="st" d="M60 22l11 24 26 3-19 18 5 26-23-13-23 13 5-26-19-18 26-3z" ' + SL + '/><path d="M44 46c3-5 8-8 14-9" ' + LN + ' stroke="' + W + '" stroke-width="5"/>' },
  locked: { b: 0, f: '<path class="sh" d="M40 56V42c0-12 9-20 20-20s20 8 20 20v14" ' + LN + ' stroke-width="7"/><rect x="28" y="54" width="64" height="48" rx="10" ' + S + '/><circle cx="60" cy="76" r="7" fill="' + K + '"/><path d="M60 80v10" ' + LN + ' stroke-width="6"/><rect x="36" y="60" width="18" height="8" rx="4" fill="' + L + '"/>' },
  unlocked: { b: 0, f: '<path class="sh" d="M40 56V42c0-12 9-20 20-20s20 8 20 20" ' + LN + ' stroke-width="7"/><rect x="28" y="54" width="64" height="48" rx="10" ' + SL + '/><circle cx="60" cy="76" r="7" fill="' + K + '"/><path d="M60 80v10" ' + LN + ' stroke-width="6"/>' },
  hourglass: { b: 1, f: '<path d="M34 20h52M34 100h52" ' + LN + ' stroke-width="7"/><path d="M40 22c0 20 8 30 20 36 12-6 20-16 20-36M40 98c0-20 8-30 20-36 12 6 20 16 20 36" ' + S + '/><path class="sand" d="M48 90c2-8 6-12 12-14 6 2 10 6 12 14z" fill="' + L + '"/><path d="M60 58v24" ' + LN + ' stroke="' + L + '" stroke-width="3" stroke-dasharray="2 4"/>' },
  gem: { b: 2, f: '<path d="M30 48l14-20h32l14 20-30 46z" ' + SL + '/><path d="M30 48h60M44 28l16 20 16-20M60 48v46" ' + LN + ' stroke-width="4"/><path d="M38 40l6-8" ' + LN + ' stroke="' + W + '" stroke-width="5"/>' },
  sparkles: { b: 0, f: '<g class="burst">' + star4(50, 56, 24) + star4(86, 34, 11, W) + star4(84, 88, 8, K) + '</g>' },
  question: { b: 1, f: '<path d="M26 54c0-18 15-30 34-30s34 12 34 30-15 30-34 30h-6l-16 14 2-18c-9-5-14-14-14-26z" ' + S + '/><path d="M50 50c0-7 4-11 10-11s10 4 10 10c0 8-10 8-10 16" ' + LN + ' stroke-width="6"/><circle cx="60" cy="74" r="4" fill="' + L + '"/>' },
  rays: { b: 2, f: '<g class="burst">' + star4(60, 60, 30) + star4(30, 36, 8, K) + star4(92, 40, 7, W) + star4(86, 90, 9, K) + star4(28, 84, 6, W) + '</g>' },
};
var NAMES = { clue: '线索', badge: '勋章', stamp: '邮戳 / 延伸任务', key: '钥匙 / 解锁', success: '成功', fail: '失败', wait: '调查中', team: '队伍', seat: '席位', pass: '通行证', points: '积分', seal: '章节印', trophy: '名次', compass: '雷达 / 方向', backpack: '背包', bookmark: '任务', envelope: '邀请', star: '评价', locked: '未解锁', unlocked: '已解锁', hourglass: '等待', gem: '藏品', sparkles: '闪光', question: '提示', rays: '光芒' };

/** 同一个标签里重复的属性只留第一个(l-kits 2026-11-10 rig 实测:原型把 SVG 内联在 HTML 里,HTML 解析器对重复属性取第一个;
 *  这里是 data URI 的独立 SVG,走 XML 解析,重复属性 = 格式错误,整张图不渲染(Chrome naturalWidth 0)。
 *  取第一个 = 与业主在原型里看到的一致。 */
function dedupeAttrs(markup) {
  return markup.replace(/<([a-zA-Z][\w-]*)((?:\s+[\w:-]+="[^"]*")*)\s*(\/?)>/g, function (m, tag, attrs, close) {
    var seen = {}, out = '';
    attrs.replace(/\s+([\w:-]+)="([^"]*)"/g, function (a, k, v) { if (!seen[k]) { seen[k] = 1; out += ' ' + k + '="' + v + '"'; } return a; });
    return '<' + tag + out + (close ? '/' : '') + '>';
  });
}
function uri(body) {
  return 'data:image/svg+xml;charset=utf8,' + encodeURIComponent(dedupeAttrs('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120" width="120" height="120" overflow="visible">' + body + '</svg>'));
}
function has(name) { return Object.prototype.hasOwnProperty.call(D, name); }
/** 两层图:blob 底 + figure;未知名字回落「线索」,与原型 img() 一致。n 只用于「章节印」的数字。 */
function layers(name, n) {
  var key = has(name) ? name : 'clue', d = D[key];
  var label = n == null || n === '' ? '一' : String(n).replace(/[<>&"']/g, '');
  return { name: key, label: NAMES[key], blob: uri('<path d="' + BLOB[d.b] + '" fill="' + W + '"/>'), fg: uri(d.f.replace('__N__', label)) };
}
module.exports = { NAMES: NAMES, LIST: Object.keys(D), layers: layers, has: has, dedupeAttrs: dedupeAttrs };
