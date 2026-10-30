// 主题画面风格（look）：一个主题选一种风格 + 这种风格自己的几项参数 + 转场 + 故事流字体。
//
// 存在 cms_topic.look_json，形如
//   {"style":"CYBER","params":{"neon":"YELLOW_CYAN","current":"MID","glitch":"TRANSITION"},"transition":"AUTO","storyFont":"HAND"}
// 未配置 / 读不懂的值一律回落默认（星尘 + 光圈 + 手写），老主题看到的仍是原来的样子。
//
// ⚠️ 取值域与后端 ThemeLook.java 是同一份口径，改一边必须改另一边。
// 主包已满，这份文件在 pages/play/utils 与 pages/publish/utils/publish 各留一份逐字相同的副本，
// tests/unit/theme-look.test.js 钉住一致。

var STYLES = [
  { value: 'STARDUST', label: '星尘', transition: 'IRIS',
    params: [
      { key: 'density', label: '浮尘', options: [['SPARSE', '稀'], ['NORMAL', '中'], ['DENSE', '密']], def: 'NORMAL' },
      { key: 'drift', label: '飘动', options: [['SLOW', '慢'], ['NORMAL', '中'], ['FAST', '快']], def: 'SLOW' },
      { key: 'tone', label: '光色', options: [['WHITE', '白'], ['WARM', '暖'], ['COOL', '冷']], def: 'WHITE' },
    ] },
  { value: 'PAPER', label: '纸本', transition: 'PAGE',
    params: [
      { key: 'paper', label: '纸张', options: [['KRAFT', '牛皮纸'], ['XUAN', '宣纸'], ['CARD', '深色卡纸']], def: 'KRAFT' },
      { key: 'grain', label: '纸纹', options: [['LIGHT', '轻'], ['MEDIUM', '中'], ['HEAVY', '重']], def: 'MEDIUM' },
      { key: 'stamp', label: '印章', options: [['VERIFIED', '已核实'], ['VISITED', '到此一游'], ['SEAL', '朱文方印']], def: 'VERIFIED' },
    ] },
  { value: 'SURVIVAL', label: '末世', transition: 'GUST',
    params: [
      { key: 'climate', label: '气候', options: [['WINTER', '寒冬'], ['BUNKER', '地堡'], ['HEAT', '热浪'], ['STORM', '飓风']], def: 'STORM' },
      { key: 'volatility', label: '天气', options: [['CALM', '平稳'], ['SHIFTING', '多变'], ['VIOLENT', '剧烈']], def: 'SHIFTING' },
      { key: 'edge', label: '屏幕边缘', options: [['HP', '跟随体力'], ['FIXED', '固定'], ['OFF', '关闭']], def: 'HP' },
    ] },
  { value: 'CYBER', label: '夜城', transition: 'SURGE',
    params: [
      { key: 'neon', label: '霓虹', options: [['YELLOW_CYAN', '黄青'], ['CYAN_PINK', '青粉'], ['RED_CYAN', '红青']], def: 'YELLOW_CYAN' },
      { key: 'current', label: '电流', options: [['LOW', '弱'], ['MID', '中'], ['HIGH', '强']], def: 'MID' },
      { key: 'glitch', label: '故障', options: [['TRANSITION', '只在转场'], ['OFF', '关闭']], def: 'TRANSITION' },
    ] },
];

// 转场：全部是“盖上 → 换内容 → 揭开”两段式，换内容发生在完全盖住的那一刻
var TRANSITIONS = [
  ['AUTO', '跟随风格'], ['FADE', '淡出'], ['IRIS', '光圈'], ['PAGE', '翻页'], ['WIPE', '擦除'],
  ['SHUTTER', '百叶'], ['PIXEL', '像素'], ['WARP', '跃迁'], ['GUST', '风刮'], ['STATIC', '调频'],
  ['GLITCH', '故障'], ['SURGE', '电流'], ['INK', '墨晕'], ['CURTAIN', '幕布'], ['ZOOM', '推近'],
];

var STORY_FONTS = [
  ['HAND', '手写'], ['SONG', '宋体'], ['KAI', '楷体'], ['HEI', '黑体'],
];

var DEFAULT_STYLE = 'STARDUST';

function styleOf(value) {
  for (var i = 0; i < STYLES.length; i++) if (STYLES[i].value === value) return STYLES[i];
  return null;
}
function has(list, value) {
  for (var i = 0; i < list.length; i++) if (list[i][0] === value) return true;
  return false;
}
function parse(raw) {
  if (!raw) return {};
  if (typeof raw === 'object') return raw;
  try { var v = JSON.parse(raw); return v && typeof v === 'object' ? v : {}; } catch (e) { return {}; }
}

/** 读：任何输入都给出完整、合法的 look（缺的、错的回落默认）。 */
function normalizeLook(raw) {
  var src = parse(raw);
  var style = styleOf(String(src.style || '').toUpperCase()) || styleOf(DEFAULT_STYLE);
  var given = src.params && typeof src.params === 'object' ? src.params : {};
  var params = {};
  style.params.forEach(function (p) {
    var v = String(given[p.key] || '').toUpperCase();
    params[p.key] = has(p.options, v) ? v : p.def;
  });
  var transition = String(src.transition || '').toUpperCase();
  var storyFont = String(src.storyFont || '').toUpperCase();
  return {
    style: style.value,
    params: params,
    transition: has(TRANSITIONS, transition) ? transition : 'AUTO',
    storyFont: has(STORY_FONTS, storyFont) ? storyFont : 'HAND',
  };
}

/** 写：存成紧凑 JSON；与默认完全相同时存空串，老主题不因打开过编辑器就多出一份配置。 */
function serializeLook(look) {
  var n = normalizeLook(look);
  if (JSON.stringify(n) === JSON.stringify(normalizeLook(null))) return '';
  return JSON.stringify(n);
}

/** 实际用到的转场（AUTO 换成风格默认）。 */
function resolveTransition(look) {
  var n = normalizeLook(look);
  return n.transition === 'AUTO' ? styleOf(n.style).transition : n.transition;
}

/** 挂在玩家页根节点上的类：look--cyber look-neon--yellow_cyan … story-font--kai */
function lookClass(look) {
  var n = normalizeLook(look), out = ['look--' + n.style.toLowerCase()];
  Object.keys(n.params).forEach(function (k) { out.push('look-' + k + '--' + n.params[k].toLowerCase()); });
  out.push('story-font--' + n.storyFont.toLowerCase());
  return out.join(' ');
}

module.exports = {
  STYLES: STYLES, TRANSITIONS: TRANSITIONS, STORY_FONTS: STORY_FONTS, DEFAULT_STYLE: DEFAULT_STYLE,
  normalizeLook: normalizeLook, serializeLook: serializeLook, resolveTransition: resolveTransition, lookClass: lookClass, styleOf: styleOf,
};
