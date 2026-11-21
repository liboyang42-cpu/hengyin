// 玩法外壳(2026-11-21 业主:「游戏要顺滑,主题要一致自然」):玩家端一站玩法的「壳」读什么、怎么说。
//   ① 抬头:整屏玩法的左上(返回钮右侧那一行)写「第N站」(路线全程站序,与地点卡角标同号)+ 章名小字;站名只在正文标题写一次 —— 玩家知道自己还在这一章、这一站里;
//   ② 引子:这一站自己的一句钩子(hookText / lead / description / 故事首句 / kit lead),故事字体一行,
//      让玩法读起来是故事的下一拍,不是一张跳出来的黑屏;
//   ③ 「继续故事」只在这一站玩完(readyForBase)之后出现,玩的过程中屏上唯一的主按钮是玩法自己的。
// 只读页面已有的字段(节点 / 章节 / kit),不造新字段、不发请求;缺什么就不写什么,老主题回落到原样。
// 纯函数单测见 tests/unit/play-game-shell.test.js;index.js 的 UI 门禁基线按行号登记,挂钩只放 story-kit-card.js。

const { stationLabel, playerTitle } = require('./play-stage-pops.js');
const { FADE_SWAP_MS: FADE_MS } = require('../../../utils/motion.js');   // 交叉淡切 200ms(--cy-motion-fade-swap 的 JS 镜像)

/* 与 index.js 的 NODE_DESC_FALLBACK 同一句:那是「没写描述」时的占位,不是这一站的话,不当引子 */
const NODE_DESC_FALLBACK = '走到这里，留意四周——故事就藏在细节里。';
const LEAD_MAX = 40;
const EXTENSION_NUM = 100;   // 编号 ≥ 100 的是延伸站(story-kit-card.js storyTasks 同一口径),不占主线站序

function str(v) { return v == null ? '' : String(v); }
/** 一行:换行 / 连续空白压成一个空格 */
function oneLine(v) { return str(v).replace(/\s+/g, ' ').trim(); }
/** 一句:取到第一个句末标点(含);太长就截断加省略号 */
function firstSentence(v) {
  const s = oneLine(v);
  if (!s) return '';
  const m = s.match(/^[^。！？!?]*[。！？!?]?/);
  const sentence = (m && m[0]) || s;
  return Array.from(sentence).length <= LEAD_MAX ? sentence : Array.from(sentence).slice(0, LEAD_MAX - 1).join('') + '…';
}

/** 这一站在整条路线里是第几站(从 1 起),与地点卡封面角标「第 N 站」同一个号:
 *  地图上按「地点」数(有坐标的站依次编号,buildChapter 已记在 node.placeNum),跨章连续,不按章重新数;
 *  故事玩法(没坐标)借它绑定的那个地点的号;延伸站 / 认不出 / 自由探索没号 → 0(只写章名,不编号) */
function stationIndex(node, nodes) {
  if (!node || node.nodeId == null) return 0;
  if (Number(node.num) >= EXTENSION_NUM) return 0;
  const list = nodes || [];
  const placeOf = (x) => {
    if (!x) return 0;
    if (Number(x.placeNum) > 0) return Number(x.placeNum);
    if (!(x.lat && x.lng)) return 0;
    const i = list.filter((n) => n && n.lat && n.lng).findIndex((n) => String(n.nodeId) === String(x.nodeId));
    return i >= 0 ? i + 1 : 0;
  };
  const own = placeOf(node);
  if (own) return own;
  if (node.placeNodeId == null) return 0;
  return placeOf(list.find((n) => n && String(n.nodeId) === String(node.placeNodeId)));
}

/** 抬头两段:station「第N站」(路线全程站序,与地点卡角标同号;认不出就写站名),chapter 章名(小字另起,没有章名退章序「第一章」)。
 *  站名 / 玩法名由正文标题写一次,抬头不再重复。 */
function stationHead(node, chapter, nodes) {
  if (!node) return { station: '', chapter: '' };
  const n = stationIndex(node, nodes);
  const station = n ? '第' + n + '站' : stationLabel(playerTitle(node.name || node.gameTitle || ''));
  const ch = chapter || {};
  const sameChapter = node.chapterId == null || ch.id == null || String(ch.id) === String(node.chapterId);
  const chapterName = sameChapter && !ch.ending ? oneLine(ch.name || ch.idxLabel || '') : '';
  return { station, chapter: chapterName === station ? '' : chapterName };
}

/** 正文标题只写一次:玩法名(作者没写就用站名),去掉路线名前缀、「第N关」、站号前缀;
 *  站名后面括号里的「角色 · 地点」备注不进标题 */
function stationTitle(node, kit) {
  const n = node || {};
  const raw = n.gameTitle || n.name || (kit && kit.title) || '';
  return stationLabel(playerTitle(raw));
}
/** 去掉末尾的全角 / 半角括号备注,用来比两行是不是说的同一个站 */
function titleCore(t) { return oneLine(t).replace(/\s*[（(][^（）()]*[）)]\s*$/, ''); }
/** 地点行与标题说的是同一个站(一个包含另一个)⇒ 重复,不写 */
function sameStation(a, b) {
  const x = titleCore(stationLabel(playerTitle(a)));
  const y = titleCore(b);
  return !!(x && y) && (x.indexOf(y) >= 0 || y.indexOf(x) >= 0);
}

/** 引子:这一站自己的一句话。顺序 = 钩子 → 作者写的引语 → 描述(占位句不算)→ 故事首句 → 玩法 lead;与题面 / 标题同句的不重复 */
function storyLead(node, kit) {
  const n = node || {};
  const k = kit || {};
  const same = [k.title, k.question, k.prompt, n.gameTitle, n.name].map(oneLine).filter(Boolean);
  const candidates = [n.hookText, n.lead, n.description !== NODE_DESC_FALLBACK ? n.description : '', n.story, k.lead];
  for (let i = 0; i < candidates.length; i++) {
    const line = firstSentence(candidates[i]);
    if (line && same.indexOf(line) < 0 && same.indexOf(line.replace(/[。！？!?]$/, '')) < 0) return line;
  }
  return '';
}

/** 整屏玩法的抬头 + 引子;什么都拿不到时给 null(台面上整块不出,老样子) */
function shellHead(node, chapter, nodes, kit) {
  const head = stationHead(node, chapter, nodes);
  const lead = storyLead(node, kit);
  if (!head.station && !head.chapter && !lead) return null;
  return {
    station: head.station, chapter: head.chapter, lead,
    label: [head.station, head.chapter, lead].filter(Boolean).join('，'),
  };
}

/** 故事流里那颗「继续故事」:玩完(readyForBase)才出;没出时整颗不占交互、读屏也跳过 */
function continueSlot(playKit, advancedPlay) {
  const pk = playKit || {};
  const ready = !!(pk.readyForBase && advancedPlay && advancedPlay.show);
  const short = !!(pk.kit && (pk.kit.type === 'qa' || pk.kit.type === 'hangman'));
  return { ready, label: short ? '继续' : '继续故事' };
}

/** 结果屏的收尾钮:回到故事流时说「继续故事」,其余(没开故事流的整屏)说「继续」 */
function verdictCta(chapterFull) {
  return chapterFull ? '继续故事' : '继续';
}

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁只扫模块里 `const X = {` 的键
  const api = {
    /** 会话视图刷新后 playKit 的新状态补上外壳抬头(只给整屏那一份用;内嵌在故事流里时故事就在上面,台面不画) */
    _withShellHead(state) {
      const node = this.data.advancedPlay && this.data.advancedPlay.node;
      const head = state && state.kit ? shellHead(node, this.data.chapter, this.data.nodes, state.kit) : null;
      return Object.assign({}, state, { head });
    },
    /** 从整屏玩法 / 结果屏回到故事流:故事流淡入(动效 token 的交叉淡切 200ms;减弱动态 = 直接出现) */
    _storyCrossFade() {
      if (!this.data.chapterFull || this.data.reducedMotion || typeof this.animate !== 'function') return;
      try {
        this.animate('.chfull', [{ opacity: 0 }, { opacity: 1 }], FADE_MS, () => {
          if (!this._playDisposed && typeof this.clearAnimation === 'function') this.clearAnimation('.chfull', { opacity: true }, () => {});
        });
      } catch (e) { /* 基础库不支持 animate:直接出现,与减弱动态同 */ }
    },
  };
  return api;
}

module.exports = { NODE_DESC_FALLBACK, LEAD_MAX, stationIndex, stationHead, stationTitle, sameStation, storyLead, shellHead, continueSlot, verdictCta, methods };
