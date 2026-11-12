// l-play(2026-11-10,业主批准 proto/game 第二组「游玩与阶段弹窗」):主题内的几张阶段弹窗 / 整屏,
//   ① 玩法开始弹窗(pop-kitstart):故事流入口卡 / 地点卡点开先看一张弹窗(封面 · 模板 · 绑定地点 · 一句说明),选「嵌入故事流 / 全屏」再开始;
//   ② 延伸任务翻页卡(pop-ext):一次一张,点卡或箭头翻到下一张;
//   ③ 本局完成屏的奖励事实(真夺到的勋章);
//   ④ 地点卡玻璃面板上的三个数字(距离 / 步行 / 开放),只写拿得到的;
//   ⑤ f-play:走完后的只读回看与选章状态(V3-07/08/09)。
// index.js 的 UI 门禁基线按行号登记,新方法都放这里,index.js 只留单行挂钩。

// 玩法类型 → 作者在玩法目录里看到的名字(pages/publish/utils/publish/node-game-catalog.js 的 label;分包之间不能 require,这里抄常用的一份)
const KIT_LABELS = {
  qa: '问答', hangman: '逐字猜词', branch: '分支剧情', accusation: '判案', sort: '排序', match: '连线', classify: '分类',
  estimate: '猜数字', pricePair: '猜图', pricepair: '猜图', hidden: '找东西', hiddenObject: '找东西', predict: '预测', random: '翻卡',
  coinFlip: '抛硬币', coinflip: '抛硬币', diceRoll: '掷骰子', diceroll: '掷骰子', album: '相册', ballShake: '弹球', ballshake: '弹球',
  reaction: '变色就点', countdown: '倒计时', stopwatch: '精准停表', note: '留言', profile: '角色建档', photoCheck: '拍照', photocheck: '拍照',
  steps: '计步挑战', quietHold: '安静挑战', quiethold: '安静挑战', compass: '罗盘指向', shout: '喊一嗓子', typeIn: '输入挑战', typein: '输入挑战',
  scan: '扫码参与', bingo: '九宫格', blindTaste: '闭眼盲品', dailySign: '今日城市签', objectCard: '拍物成卡', check: 'D20', musiccorner: '音乐角',
};
const WALK_M_PER_MIN = 80;
const chapterTrail = require('./chapter-trail.js');

function firstImg(v) { return v ? String(v).split(/[,;]/)[0].trim() : ''; }
/** 这一站玩法的类型名:试玩读草稿预览 kit,真实游玩读节点配置里启用的那一段;认不出就不写(不写「玩法」凑数) */
function kitTypeLabel(node) {
  if (!node) return '';
  const t = node.previewKit && node.previewKit.type;
  if (t && KIT_LABELS[t]) return KIT_LABELS[t];
  let cfg = node.advancedConfig;
  if (!cfg && node.advancedConfigJson) { try { cfg = JSON.parse(node.advancedConfigJson); } catch (e) { cfg = null; } }
  if (cfg && typeof cfg === 'object') {
    const key = Object.keys(cfg).find((k) => cfg[k] && cfg[k].enabled && KIT_LABELS[k]);
    if (key) return KIT_LABELS[key];
  }
  return '';
}
/** 地点卡上的数字:距离(有定位或上一站才算)、步行分钟(按 80 m/min,只在有距离时)、开放时段(节点营业时间原样) */
function sheetFacts(node, distText) {
  const out = { distText: distText || '', walkText: '', hoursText: '' };
  const m = /^([\d.]+)(km|m)$/.exec(distText || '');
  if (m) { const meters = m[2] === 'km' ? Number(m[1]) * 1000 : Number(m[1]); if (meters > 0) out.walkText = String(Math.max(1, Math.ceil(meters / WALK_M_PER_MIN))); }
  const biz = String(node && node.businessTime || '');
  if (biz && !/^\s*00:00\s*[-–~至]\s*23:59\s*$/.test(biz)) out.hoursText = biz.replace(/:00/g, '').replace(/\s*[-~至]\s*/, '–');
  // x-fix2 P-8:大字格只放时段;「公共区域观察;以现场…为准」这类说明已在地址行(metaLine)里,不挤进数字位
  if (!/^\d{1,2}(:\d{2})?–\d{1,2}(:\d{2})?$/.test(out.hoursText.trim())) out.hoursText = '';
  return out;
}
/** 翻页卡组:当前下标 + 下一张(循环) */
function nextIndex(i, n) { return n ? (((i || 0) + 1) % n) : 0; }

const data = {
  kitStart: { show: false, nodeId: '', origin: 'story', cover: '', title: '', type: '', place: '', arrived: false, lede: '', mode: 'embed', modes: true },
  extDeck: { idx: 0, flipping: false },
};

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁只扫模块里 `const X = {` 的键
  const api = {
    /** 故事流入口卡 / 地点卡「开始任务」→ 先看玩法开始弹窗。origin=story 的入口沿用 startStoryGame 的前置(不是故事玩法 / 已完成就直接走老路) */
    openKitStart(e) {
      const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
      const origin = ds.origin || 'story';
      const pk = this.data.playKit || {};
      // 入口卡(要整屏的玩法 / 分支):分支直接进岔路弹窗;其余先看弹窗再整屏(origin=card 没有嵌入可选)
      if (origin === 'card' && pk.pop) { this.expandPlayKit(); return; }
      const node = origin === 'sheet' ? (this.data.sheet && this.data.sheet.node)
        : origin === 'card' ? (this.data.advancedPlay && this.data.advancedPlay.node)
        : (this.data.nodes || []).find((n) => String(n.nodeId) === String(ds.nodeid));
      if (!node) return;
      if (origin === 'story') {
        const entry = (this.data.chapterParas || []).find((p) => p.kind === 'game' && String(p.nodeId) === String(node.nodeId));
        if (!entry || !this._isStoryGame(node) || node.done) { this.startStoryGame(e); return; }
      }
      const place = origin === 'story' ? ((this.data.nodes || []).find((n) => String(n.nodeId) === String(node.placeNodeId)) || null) : null;
      const story = String(node.story || node.hookText || '');
      const sentence = story.match(/^[^。！？!?]*[。！？!?]?/)[0];
      this.setData({ kitStart: {
        show: true, nodeId: String(node.nodeId), origin,
        cover: firstImg(node.imgUrl), title: node.gameTitle || node.name || (pk.kit && pk.kit.title) || '玩法', type: origin === 'card' ? (pk.cardLabel || kitTypeLabel(node)) : kitTypeLabel(node),
        place: origin === 'sheet' ? (node.lat ? node.name || '' : '') : (place ? place.name || '' : ''), arrived: !!(place ? place.arrived || place.done : node.arrived),
        lede: sentence.length <= 40 ? sentence : sentence.slice(0, 38) + '…',
        mode: 'embed', modes: origin === 'story',
      } });
    },
    closeKitStart() { this.setData({ 'kitStart.show': false }); },
    pickKitMode(e) { this.setData({ 'kitStart.mode': (e.currentTarget.dataset.mode === 'full') ? 'full' : 'embed' }); },
    /** 「开始」:故事流入口按所选方式开(全屏 = 同一个 kit 盖整屏,退出回到流里);地点卡入口照旧 startGame(本就是整屏) */
    confirmKitStart() {
      const ks = this.data.kitStart || {};
      if (!ks.show) return;
      this.setData({ 'kitStart.show': false });
      if (ks.origin === 'sheet') { this.startGame(); return; }
      if (ks.origin === 'card') { this.expandPlayKit(); return; }
      this._kitStartFull = ks.mode === 'full' ? ks.nodeId : '';
      this.startStoryGame({ currentTarget: { dataset: { nodeid: ks.nodeId } } });
    },
    /** 延伸任务翻页卡:点卡 / 箭头翻到下一张(绕竖轴;减弱动态直接切) */
    flipExtTask() {
      const rows = (this.data.storyTasks && this.data.storyTasks.rows) || [];
      const deck = this.data.extDeck || {};
      if (deck.flipping || rows.length < 2) return;
      const idx = nextIndex(deck.idx, rows.length);
      if (this.data.reducedMotion) { this.setData({ extDeck: { idx, flipping: false } }); return; }
      this.setData({ extDeck: { idx: deck.idx || 0, next: idx, flipping: true } });
      clearTimeout(this._extFlipT);
      this._extFlipT = setTimeout(() => { if (!this._playDisposed) this.setData({ extDeck: { idx, flipping: false } }); }, 520);
    },
    /** 「去做」:当前这张任务卡 → 打开那一站的地点卡(沿用 openStoryTask) */
    goExtTask() {
      const rows = (this.data.storyTasks && this.data.storyTasks.rows) || [];
      const row = rows[(this.data.extDeck || {}).idx || 0];
      if (!row) return;
      this.openStoryTask({ currentTarget: { dataset: { nodeid: row.nodeId } } });
    },
    /** 本局完成屏的奖励:只写这一局真夺到的勋章(_wonMedals,与完成页勋章堆同口径);没有就不写 */
    _endingReward() {
      const won = (this._wonMedals || []).filter((m) => m && m.name);
      const first = won[0];
      return { reward: first ? { name: first.name, img: first.img || '', count: won.length } : null };
    },
    /** 地点卡玻璃面板上的数字(见 sheetFacts) */
    _sheetFacts(node) { return sheetFacts(node, typeof this._distText === 'function' ? this._distText(node) : ''); },
    /** f-play:地点卡开着时定位每一跳都重算距离 / 步行 —— 开卡时算一次的话,走到站上还写着出发时的「1.7km · 22 分钟」。文字没变不 setData */
    _refreshSheetFacts() {
      const sh = this.data.sheet || {};
      const node = sh.show && sh.node;
      if (!node || !node.lat || !node.lng) return;
      const f = this._sheetFacts(node);
      if (f.distText === node.distText && f.walkText === node.walkText) return;
      this.setData({ 'sheet.node.distText': f.distText, 'sheet.node.walkText': f.walkText });
    },
    /** f-play V3-09:选章每一行的状态,与 onPickChapter 放行同一判据(chapterReadState):completed 写「已完成」、locked 压暗不可点 */
    _chapterPickStates() {
      return (this.data.chapterCards || []).map((c) => chapterTrail.chapterReadState(c, this.data.nodes, this._historyNodes));
    },
    /** f-play V3-07/08:真实局走完后,完成页「继续」回到故事流的只读回看(最后一章已完成的那章,停在末尾,往上滑读完整个故事;
     *  多章时「…」里有「切换章节」换章)。不是走完的局 / 自由探索 / 一章都没读完时返回 false,照旧离开。 */
    _reviewAfterFinish() {
      const d = this.data;
      if (!d.allDone || d.isPreview || d.isMock || Number(d.mode) === 2 || typeof this.onPickChapter !== 'function') return false;
      const idx = this._chapterPickStates().lastIndexOf('completed');
      if (idx < 0) return false;
      this.onPickChapter({ currentTarget: { dataset: { idx } } });
      clearTimeout(this._reviewEndT);
      // 停在末尾:滚到最后一段(scroll-into-view 认段落 id,与 wxml 的 chapter-game- / chapter-block- 同一口径)
      this._reviewEndT = setTimeout(() => {
        const paras = this.data.chapterParas || [];
        const last = paras[paras.length - 1];
        if (this._playDisposed || !this.data.chapterFull || !last) return;
        this.setData({ chapterGameAnchor: last.nodeId ? 'chapter-game-' + last.nodeId : 'chapter-block-' + last.i });
      }, 700);
      return true;
    },
  };
  return api;
}

module.exports = { KIT_LABELS, kitTypeLabel, sheetFacts, nextIndex, data, methods };
