'use strict';
// 角色(Cast)v1 玩家侧 —— 契约 docs/design/cast-contract-1119.md(业主 2026-11-19 批准)。
//
// ★ 正文仍是原来那条书页式故事流(.chfull__para,居中手写段落):不做气泡、不做消息卡、正文里不放头像。
//   台词(line 块)与带 who 的文字块都是普通段落,说话人的名字小而浅,画在左侧页边、与首行齐平;不再拼「某某：」。
// ★ 每个 item 仍是**一个** .chfull__para —— measureChapter 按下标对齐 chapterParas。
//   配音那一行「▶︎ 0:08」、「X会记住这件事」都长在同一个 item 里,不另起一段。
// ★ 没有 castConfig(或没开)的主题:除 who 改成页边名字外,行为与外观逐字不变 —— 本模块对它们一律原样返回。
// ★ 信任只由服务端结算(POST /api/play/run/branch 只报选了第几条);这里的本地信任只用于本局画勾,
//   结局页以服务端 /api/play/journey/ending 回的 trust 为准。
// index.js 的 UI 门禁基线按行号登记:方法都放这里,index.js 只留同一行内的挂钩。

const { applyStoryVars: applyVars } = require('./playkit-view.js');   // 台词里的 ASCII {name} 变量照旧交给它;{玩家}/{身份} 由本模块换
const cyToast = require('../../../utils/toast.js');
const audioSession = require('./audio-session.js');
const { CAST_COLORS, normalizeCast, face, routeCastRow, remember, cached } = require('../../../utils/cast-config.js');   // 两个分包共用的配置规范化与缓存

const VOICE_HIDDEN_CODES = ['CAST_TTS_NOT_CONFIGURED', 'CAST_VOICE_NONE', 'CAST_DISABLED'];
const TICKS = 5;
const PLAYER_FALLBACK = '你';
const START_WAIT_MS = 1200;   // 角色配置还没回来时,开场最多等这么久;超时照常进故事(没有角色的主题不被拖住)

const str = (v) => (v == null ? '' : String(v));
const isObj = (v) => !!v && typeof v === 'object' && !Array.isArray(v);

/* ---------------- 纯函数(单测:tests/unit/cast-play-1119.test.js) ---------------- */

/** 本局玩家档案(服务端 routeState.playerProfile / 保存回包)→ {name, identityKey, identityTitle};没有 = null */
function profileOf(raw) {
  if (!isObj(raw)) return null;
  const name = str(raw.name).trim(), identityKey = str(raw.identityKey), identityTitle = str(raw.identityTitle).trim();
  return name || identityKey ? { name, identityKey, identityTitle } : null;
}

/** C 开场起名:开了角色、要起名或选身份、本局还没有档案、也没走过任何一站(中途重进不再问) */
function needsStart(cfg, profile, progressed) {
  return !!cfg && !!(cfg.playerName || cfg.playerIdentity) && !profile && !progressed;
}

function playerName(profile) { return (profile && profile.name) || PLAYER_FALLBACK; }

/** {玩家} → 本局名字(没起名 = 你);{身份} → 选的身份。只在开了角色的主题里替换。 */
function castVars(text, profile) {
  const src = str(text);
  if (src.indexOf('{') < 0) return src;
  return src.split('{玩家}').join(playerName(profile)).split('{身份}').join((profile && profile.identityTitle) || '');
}

function findCast(cfg, id) { return (cfg && cfg.cast || []).find((c) => c.id === str(id)) || null; }

/** 台词说话人:玩家 / 角色;角色已被删 → null(正文照常显示,不带名字,不崩) */
function speakerOf(cfg, speaker, profile) {
  if (!cfg) return null;
  if (speaker === 'player') return { name: playerName(profile), castId: '', player: true };
  const c = findCast(cfg, speaker);
  return c ? { name: c.name, castId: c.id, player: false } : null;
}

/** 这句有没有声音可放:自带录音,或说话角色配了预设声音(服务端合成) */
function lineHasVoice(cfg, block) {
  if (!cfg || !block) return false;
  if (str(block.audioUrl)) return true;
  const c = findCast(cfg, block.speaker);
  return !!c && c.voice.mode === 'preset' && !!c.voice.preset;
}

/** line 块 → 一个普通正文段(kind 'text'),名字放页边 */
function lineItem(block, cfg, profile, base) {
  const who = speakerOf(cfg, block && block.speaker, profile);
  const text = castVars(block && block.content, profile).trim();
  if (!text) return null;
  return Object.assign({ kind: 'text', text, who: who ? who.name : '', whoId: who ? who.castId : '',
    lineKey: str(block.key), voice: !!who && lineHasVoice(cfg, block), dur: '' }, base || {});
}

/** 分支选项的信任变化里真有变化(且角色还在)的那几位 */
function trustMovers(cfg, option) {
  return (option && Array.isArray(option.trust) ? option.trust : [])
    .filter((t) => isObj(t) && Math.max(-2, Math.min(2, Math.trunc(Number(t.delta) || 0))) !== 0)
    .map((t) => findCast(cfg, t.castId)).filter(Boolean)
    .filter((c, i, all) => all.indexOf(c) === i);
}

/** 本地画勾用的信任累加(只用于显示;服务端口径:每条 -2..2) */
function applyTrust(trust, cfg, option) {
  const next = Object.assign({}, trust || {});
  (option && Array.isArray(option.trust) ? option.trust : []).forEach((t) => {
    if (!isObj(t) || !findCast(cfg, t.castId)) return;
    const d = Math.max(-2, Math.min(2, Math.trunc(Number(t.delta) || 0)));
    if (d) next[t.castId] = (Number(next[t.castId]) || 0) + d;
  });
  return next;
}

/** E3 行内选项:一段,编号 1–3 */
function choiceItem(block, base) {
  const opts = (Array.isArray(block && block.options) ? block.options : []).slice(0, 3)
    .map((o, i) => ({ n: i + 1, t: str(o && o.text).trim() })).filter((o) => o.t);
  if (!opts.length) return null;
  return Object.assign({ kind: 'choice', blockKey: str(block.key), q: str(block.content).trim(), opts }, base || {});
}

/** 选过之后:选中的话留在故事里,作为玩家说的一句;改了信任再跟一句「X会记住这件事」(同一段) */
function pickedItem(block, index, cfg, profile, base) {
  const option = (block && block.options || [])[index];
  const text = castVars(option && option.text, profile).trim();
  if (!text) return null;
  const movers = trustMovers(cfg, option);
  return Object.assign({ kind: 'text', text, who: playerName(profile), whoId: '', lineKey: '', voice: false, dur: '',
    remember: movers.length ? movers.map((c) => c.name).join('、') + '会记住这件事' : '' }, base || {});
}

/** 五格信任勾:0 及以下一格不亮,满 5 封顶 */
function trustTicks(value) {
  const on = Math.max(0, Math.min(TICKS, Math.trunc(Number(value) || 0)));
  const out = [];
  for (let i = 0; i < TICKS; i += 1) out.push({ i, on: i < on });
  return out;
}
function trustBand(value) { const v = Number(value) || 0; return v < 0 ? 'low' : (v >= 3 ? 'high' : 'mid'); }

/** E4 线索:到过那一章(或不限章)才露正文;没到的只给个数。服务端裁过(redacted)时下发的就是已解锁的,锁住的只有 lockedCount */
function clueView(member, reachedChapterIds) {
  const reached = new Set((reachedChapterIds || []).map(str));
  const open = [];
  let locked = Number(member && member.lockedCount) || 0;
  const trusted = !!(member && member.redacted);
  (member && member.clues || []).forEach((k) => {
    if (!k.text) return;
    if (trusted || k.chapterId == null || reached.has(k.chapterId)) open.push(k.text); else locked += 1;
  });
  return { open, locked, lockedText: locked ? '还有 ' + locked + ' 条，走到下一站再看' : '' };
}

function cardView(cfg, castId, ctx) {
  const c = findCast(cfg, castId);
  if (!c) return null;
  const c2 = ctx || {};
  return {
    show: true, id: c.id, name: c.name, identity: c.identity, face: face(c),
    voice: !!c2.voiceKey, voiceKey: c2.voiceKey || '',
    trustLabel: c.name + '对你', ticks: trustTicks((c2.trust || {})[c.id]), color: c.color,
    clues: clueView(c, c2.reached),
  };
}

/** E1 登场人物:角色一行一位,玩家最后(细线之后) */
function playbill(cfg, profile, routeName) {
  if (!cfg) return null;
  return {
    route: str(routeName),
    rows: cfg.cast.map((c) => ({ id: c.id, name: c.name, identity: c.identity })),
    player: { name: (profile && profile.name) || PLAYER_FALLBACK, identity: (profile && profile.identityTitle) || '' },
  };
}

/** 结局按信任挑角色:唯一最高且 > 0(与服务端 CastRunRules.pickByTrust 同口径);否则 '' */
function leadingCast(cfg, trust) {
  let best = null, bestV = 0, tie = false;
  (cfg && cfg.cast || []).forEach((c) => {
    const v = Number((trust || {})[c.id]) || 0;
    if (v <= 0) return;
    if (!best || v > bestV) { best = c; bestV = v; tie = false; } else if (v === bestV) tie = true;
  });
  return best && !tie ? best.name : '';
}

/** E6「他们怎么看你」:信任 ≠ 0 的每位一段(作者按档写的收尾句,没写就只有名字与勾),脚注说结局 */
function endingView(cfg, trust, meta) {
  if (!cfg) return null;
  const rows = cfg.cast.map((c) => ({ c, v: Number((trust || {})[c.id]) || 0 })).filter((r) => r.v !== 0)
    .map((r) => ({ id: r.c.id, name: r.c.name, line: r.c.endingLines[trustBand(r.v)] || '', ticks: trustTicks(r.v), color: r.c.color }));
  const m = meta || {};
  const count = Math.max(0, Math.trunc(Number(m.endingCount) || 0));
  const forName = (findCast(cfg, m.endingFor) || {}).name || (m.endingFor === undefined ? leadingCast(cfg, trust) : '');
  const foot = (count > 1 ? '这条线有 ' + count + ' 种结局。' : '') + (forName ? '这一次，是' + forName + '的结局。' : '');
  return rows.length || foot ? { rows, foot } : null;
}

/** 首段首字下沉:第一个字 + 其余 */
function dropCap(text) {
  const chars = Array.from(str(text));
  return chars.length > 1 ? { drop: chars[0], rest: chars.slice(1).join('') } : null;
}

/** 页脚「章名 · 第x站 / 共y站」 */
function footerText(chapterName, at, total) {
  const name = str(chapterName).trim();
  if (!total) return name;
  return (name ? name + ' · ' : '') + '第' + Math.max(1, Math.min(total, at)) + '站 / 共' + total + '站';
}

function fmtDur(sec) {
  const s = Math.round(Number(sec) || 0);
  return s > 0 ? Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') : '';
}

/** 配音读不到时是否只是「没有可放的」(不出错误提示,收起播放入口) */
function voiceHidden(res) {
  const code = res && res.data && res.data.errorCode;
  return VOICE_HIDDEN_CODES.indexOf(code) >= 0;
}

/** 故事流整理(开了角色才动):{玩家}/{身份}、本章首个正文段首字下沉、名字点不了的玩家行 */
function castParas(items, cfg, profile) {
  if (!cfg) return items;
  let dropped = false;
  return items.map((item) => {
    if (item.kind !== 'text') return item;
    const next = Object.assign({}, item, { text: castVars(item.text, profile) });
    if (!dropped && !next.who && !next.cond) {
      dropped = true;
      const cap = dropCap(next.text);
      if (cap) Object.assign(next, cap);
    }
    return next;
  });
}

/** /api/play/nodes 回包里有没有角色的痕迹:本局档案,或任一章的台词块 */
function castHints(d) {
  if (!d) return false;
  if (isObj(d.routeState) && isObj(d.routeState.playerProfile)) return true;
  return (Array.isArray(d.chapters) ? d.chapters : []).some((c) => c && Array.isArray(c.blocks) && c.blocks.some((b) => b && b.type === 'line'));
}

/* ---------------- 页面挂钩(pages/play/index.js) ---------------- */
const data = {
  cast: { on: false, footer: '', start: { show: false, in: false, step: 'name', more: false, name: '', pick: '', identities: [], busy: false },
    bill: { show: false, in: false, route: '', rows: [], player: null }, card: { show: false, in: false }, ending: null },
};

function methods(req) {
  const storeKey = (page) => 'cast-run:' + str((page._routeState || {}).sessionId || page.data.topicId);
  const api = {
    /** /api/play/nodes 成功后:取本局档案,并读主题的角色配置(主题详情已读过就用缓存) */
    _applyCast(d) {
      const route = (d && d.routeState) || {};
      if (route.playerProfile !== undefined || !this._castProfile) this._castProfile = profileOf(route.playerProfile);
      const topicId = str(d && d.topicId) || str(this.data.topicId);
      if (!topicId || this.data.isPreview || this.data.isMock) return;
      if (d && d.castConfig !== undefined) { this._castSet(remember(topicId, d.castConfig)); return; }   // 游玩回包自己带了就用它
      const hit = cached(topicId);
      if (hit) { this._castSet(hit.cfg); return; }
      // 没在详情页读过:只有回包里真有角色的痕迹(台词块 / 本局档案)才补读一次主题信息 ——
      // 没有角色的主题开局请求与今天逐条一致,不多一次查询、不多等一拍
      if (!castHints(d) || this._castLoading === topicId) return;
      this._castFetch(topicId, false);
    },
    /** 读主题信息里的 castConfig。refresh=true:只换内存里的配置(线索随本局进度解锁),读失败保留原配置、不重铺正文 */
    _castFetch(topicId, refresh) {
      this._castLoading = topicId;
      this._castReady = req('/api/topic/info-to-user', 'POST', { id: topicId }, undefined, true).then((res) => {
        if (this._castLoading !== topicId) return;
        this._castLoading = '';
        const ok = res && (res.code == 200 || res.code == '200') && isObj(res.data);
        if (!refresh) { this._castSet(ok ? remember(topicId, res.data.castConfig) : null); return; }
        const cfg = ok ? remember(topicId, res.data.castConfig) : null;
        if (cfg && this._cast) this._cast = cfg;
      }).catch(() => { if (this._castLoading === topicId) this._castLoading = ''; });
      return this._castReady;
    },
    _castSet(cfg) {
      const was = !!this._cast;
      this._cast = cfg || null;
      if (!cfg && !was) return;
      this.setData({ 'cast.on': !!cfg });
      if (this.data.chapterFull && !this.data.cast.start.show) this.openChapterFull();   // 已在读:按角色样式重铺(与刷新同一路径)
    },
    _castOn() { return !!this._cast; },
    /** 本局在本机记下的:选过的分支、显示用信任、看过人物表。按局号分开,换一局自动换一份 */
    _castMine() {
      const key = storeKey(this);
      if (this._castLocalKey !== key) {
        let v = {};
        try { v = wx.getStorageSync(key); } catch (e) { v = {}; }
        this._castLocal = isObj(v) ? v : {};
        this._castLocalKey = key;
      }
      return this._castLocal;
    },
    _castWrite(patch) {
      this._castLocal = Object.assign({}, this._castMine(), patch);
      try { wx.setStorageSync(this._castLocalKey, this._castLocal); } catch (e) { /* 存不上就算了:下次重选服务端会回原选项 */ }
    },
    /** openChapterFull 块循环里的 line / branch:返回 'stop' = 故事流到此为止 */
    _castBlock(b, items, ctx) {
      const base = { mood: ctx.mood, odd: ctx.odd, cond: ctx.cond || '' };
      if (b.type === 'line') {
        const item = lineItem(Object.assign({}, b, { content: ctx.vars ? applyVars(b.content, ctx.vars) : b.content }), this._cast, this._castProfile, base);
        if (item) items.push(item);
        return '';
      }
      if (!this._cast || ctx.readOnly) return '';   // 没开角色:分支块照旧不出(行为不变)
      const key = str((this.data.chapter || {}).id) + ':' + str(b.key);
      const picked = (this._castMine().choices || {})[key];
      if (picked == null) { const item = choiceItem(b, base); if (item) { items.push(item); return 'stop'; } return ''; }
      const item = pickedItem(b, picked, this._cast, this._castProfile, base);
      if (item) items.push(item);
      const to = str(((b.options || [])[picked] || {}).to);
      return to && to !== 'continue' ? 'stop' : '';
    },
    /** chapterParas 定稿前:{玩家}/{身份}、首字下沉、页脚 */
    _castParas(items) {
      if (!this._cast) { if (this.data.cast.footer) this.setData({ 'cast.footer': '' }); return items; }
      const chapter = this.data.chapter || {};
      const own = (this.data.nodes || []).filter((n) => String(n.chapterId) === String(chapter.id));
      const footer = chapter.ending ? '' : footerText(chapter.name, own.filter((n) => n.done).length + 1, own.length);
      if (footer !== this.data.cast.footer) this.setData({ 'cast.footer': footer });
      return castParas(items, this._cast, this._castProfile);
    },
    /** _openStory 前置:C 起名 → E1 登场人物 → 正文。返回 true = 已接管,做完调 next */
    _castGate(next) {
      if (this.data.isPreview || this._chapterReadCursor) return false;
      const c = this.data.cast;
      if (c.start.show || (c.bill.show && !c.bill.menu)) { this._castAfter = next; return true; }   // 起名 / 人物表还开着:刷新回包不越过它们开正文
      if (this._castGated) return false;
      if (this._castLoading && !this._castWaited) {
        this._castWaited = true;   // 只等一次:超时就照常进故事,配置晚到只重铺正文
        Promise.race([this._castReady, new Promise((r) => setTimeout(r, START_WAIT_MS))]).then(() => next());
        return true;
      }
      this._castGated = true;
      // 中途重进(走过站 / 已有档案 / 本机看过人物表)不再问名字,也不再放人物表
      const resumed = (this.data.nodes || []).some((n) => n.done) || !!this._castProfile || !!this._castMine().billSeen;
      if (!this._cast || resumed) return false;
      this._castAfter = next;
      if (!needsStart(this._cast, this._castProfile, resumed)) { this._castShowBill(); return true; }
      const cfg = this._cast;
      const app = typeof getApp === 'function' ? getApp() : null;
      const user = (app && app.globalData && app.globalData.userInfo) || {};
      const nick = str(user.nickName || user.nickname).slice(0, 12);
      this.setData({ 'cast.start': { show: true, in: !!this.data.reducedMotion, step: cfg.playerName ? 'name' : 'identity', more: cfg.playerName && cfg.playerIdentity, name: nick, pick: '', identities: cfg.identities, busy: false } },
        () => { if (!this.data.reducedMotion) setTimeout(() => this.setData({ 'cast.start.in': true }), 20); });
      return true;
    },
    onCastName(e) { this.setData({ 'cast.start.name': str(e && e.detail && e.detail.value).slice(0, 12) }); },
    pickCastIdentity(e) {
      const key = str(e && e.currentTarget && e.currentTarget.dataset.key);
      this.setData({ 'cast.start.pick': this.data.cast.start.pick === key ? '' : key });
    },
    /** 「出发」:名字可以空(= 你);要选身份就先到身份那一屏 */
    submitCastStart() {
      const st = this.data.cast.start;
      if (!st.show || st.busy) return;
      if (st.step === 'name' && st.more) { this.setData({ 'cast.start.step': 'identity', 'cast.start.more': false }); return; }
      const name = str(st.name).trim();
      const identity = (this._cast && this._cast.identities || []).find((x) => x.key === st.pick) || null;
      const local = profileOf({ name, identityKey: identity ? identity.key : '', identityTitle: identity ? identity.title : '' });
      const runId = (this._routeState || {}).sessionId;
      const done = (profile) => {
        this._castProfile = profile || local || { name: '', identityKey: '', identityTitle: '' };
        this.setData({ 'cast.start.show': false, 'cast.start.busy': false });
        this._castShowBill();
      };
      if (!local || !runId) { done(local); return; }
      this.setData({ 'cast.start.busy': true });
      req('/api/play/run/profile', 'POST', { runId, name: name || PLAYER_FALLBACK, identityKey: identity ? identity.key : '' }, { 'content-type': 'application/json' }, true).then((res) => {
        if (res && (res.code == 200 || res.code == '200')) { done(profileOf(res.data && res.data.playerProfile)); return; }
        this.setData({ 'cast.start.busy': false });
        cyToast((res && res.msg) || '名字没存上，请换一个再试');
      }).catch(() => { this.setData({ 'cast.start.busy': false }); cyToast('网络不太好，请再试一次'); });
    },
    _castShowBill(fromMenu) {
      const bill = playbill(this._cast, this._castProfile, this.data.topicName);
      if (!bill) { this._castContinue(); return; }
      this.setData({ 'cast.bill': Object.assign({ show: true, in: !!this.data.reducedMotion, menu: !!fromMenu }, bill), sessionToolsOpen: false },
        () => { if (!this.data.reducedMotion) setTimeout(() => this.setData({ 'cast.bill.in': true }), 20); });
    },
    /** ⋯ 菜单「人物表」 */
    openCastBill() { this._castShowBill(true); },
    /** 「开场」/ 收起人物表 */
    closeCastBill() {
      const menu = this.data.cast.bill.menu;
      this.setData({ 'cast.bill.show': false });
      if (menu) return;
      this._castWrite({ billSeen: true });
      this._castContinue();
    },
    _castContinue() { const next = this._castAfter; this._castAfter = null; if (typeof next === 'function') next(); },
    /** 点页边名字 → E4 角色卡 */
    openCastCard(e) {
      const i = Number(e && e.currentTarget && e.currentTarget.dataset.i);
      const item = (this.data.chapterParas || [])[i];
      if (!item || !item.whoId || !this._cast) return;
      const cards = this.data.chapterCards || [];
      const at = cards.findIndex((c) => String(c.id) === String((this.data.chapter || {}).id));
      const reached = (at >= 0 ? cards.slice(0, at + 1) : cards.filter((c) => String(c.id) === String((this.data.chapter || {}).id))).map((c) => c.id);
      const voiceKey = item.voice && !this._castVoiceOff ? item.lineKey : '';
      const card = cardView(this._cast, item.whoId, { trust: this._castMine().trust, reached, voiceKey });
      if (!card) return;
      this.setData({ 'cast.card': Object.assign(card, { in: !!this.data.reducedMotion }) },
        () => { if (!this.data.reducedMotion) setTimeout(() => this.setData({ 'cast.card.in': true }), 20); });
      // 服务端按本局进度裁线索:缓存的配置可能是开局前读的,还有锁住的就补读一次,读到后原卡就地刷新线索
      const topicId = str(this.data.topicId);
      const chapterKey = topicId + ':' + str((this.data.chapter || {}).id);
      if (!card.clues.locked || !topicId || this._castLoading || this._castCluesAt === chapterKey) return;
      this._castCluesAt = chapterKey;
      this._castFetch(topicId, true).then(() => {
        const shown = this.data.cast.card;
        if (!shown || !shown.show || shown.id !== card.id) return;
        const fresh = cardView(this._cast, card.id, { trust: this._castMine().trust, reached, voiceKey });
        if (fresh) this.setData({ 'cast.card.clues': fresh.clues });
      });
    },
    closeCastCard() { this.setData({ 'cast.card.show': false }); },
    /** 角色卡上的播放:放刚点的那一句 */
    playCastCardVoice() { const key = this.data.cast.card.voiceKey; if (key) this._castPlay(key); },
    /** 正文里「▶︎ 0:08」 */
    playCastLine(e) {
      const item = (this.data.chapterParas || [])[Number(e && e.currentTarget && e.currentTarget.dataset.i)];
      if (item && item.voice && item.lineKey) this._castPlay(item.lineKey);
    },
    /** 点了才去要地址(GET /api/topic/cast/voice),拿到就放;不自动播 */
    _castPlay(blockKey) {
      if (this._castVoiceOff) return;
      const chapterId = (this.data.chapter || {}).id;
      const play = (url) => {
        if (!this._castAudio) {
          this._castAudio = audioSession.register(wx.createInnerAudioContext(), { kind: audioSession.VOICE });
          this._castAudio.onCanplay(() => this._castDur());
          this._castAudio.onError(() => cyToast('这句暂时放不了'));
        }
        const a = this._castAudio;
        if (this._castPlayingKey === blockKey && a.paused === false) { a.pause(); return; }
        this._castPlayingKey = blockKey;
        if (a.src !== url) a.src = url;
        a.play();
      };
      const urls = this._castUrls = this._castUrls || {};
      if (urls[blockKey]) { play(urls[blockKey]); return; }
      req('/api/topic/cast/voice', 'GET', { topicId: this.data.topicId, chapterId, blockKey, runId: (this._routeState || {}).sessionId || '' }, undefined, true).then((res) => {
        const url = res && (res.code == 200 || res.code == '200') && res.data && res.data.url;
        if (url) { urls[blockKey] = url; play(url); return; }
        if (voiceHidden(res)) { this._castHideVoice(res.data.errorCode); return; }
        cyToast((res && res.msg) || '配音暂时生成不了，请稍后再试');
      }).catch(() => cyToast('网络不太好，请再试一次'));
    },
    /** 没开配音 / 这一句没声音:收起播放入口,不提示错误 */
    _castHideVoice(code) {
      if (code !== 'CAST_VOICE_NONE') this._castVoiceOff = true;
      const key = this._castPlayingKey;
      const paras = (this.data.chapterParas || []).map((p) => (p.voice && (this._castVoiceOff || p.lineKey === key) ? Object.assign({}, p, { voice: false }) : p));
      this.setData({ chapterParas: paras, 'cast.card.voice': false });
    },
    _castDur() {
      const a = this._castAudio, key = this._castPlayingKey;
      const dur = fmtDur(a && a.duration);
      if (!dur || !key) return;
      const paras = this.data.chapterParas || [];
      if (!paras.some((p) => p.lineKey === key && p.dur !== dur)) return;
      this.setData({ chapterParas: paras.map((p) => (p.lineKey === key ? Object.assign({}, p, { dur }) : p)) });
    },
    _castStopVoice() {
      if (!this._castAudio) return;
      try { this._castAudio.stop(); audioSession.unregister(this._castAudio); this._castAudio.destroy(); } catch (e) { /* 已销毁 */ }
      this._castAudio = null;
    },
    /** E3 行内选项:报第几条给服务端(信任它来算),选中的话留在流里,其余收起 */
    pickCastChoice(e) {
      const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
      const paras = this.data.chapterParas || [];
      const item = paras[Number(ds.i)];
      const index = Number(ds.n) - 1;
      if (!item || item.kind !== 'choice' || item.busy || !(index >= 0)) return;
      const chapter = this.data.chapter || {};
      const block = ((chapter.blocks) || []).find((b) => b && b.type === 'branch' && str(b.key) === item.blockKey);
      const runId = (this._routeState || {}).sessionId;
      if (!block) return;
      const settle = (chosen) => {
        const option = (block.options || [])[chosen];
        const choices = Object.assign({}, this._castMine().choices, { [str(chapter.id) + ':' + item.blockKey]: chosen });
        this._castWrite({ choices, trust: applyTrust(this._castMine().trust, this._cast, option) });
        this.openChapterFull();   // 重铺:选中的那句换上,后面接着读(或在这里收住)
      };
      if (!runId) { settle(index); return; }
      this.setData({ chapterParas: paras.map((p) => (p === item ? Object.assign({}, p, { busy: true, picked: index + 1 }) : p)) });
      req('/api/play/run/branch', 'POST', { runId, chapterId: chapter.id, blockKey: item.blockKey, optionIndex: index }, { 'content-type': 'application/json' }, true).then((res) => {
        const d = res && (res.code == 200 || res.code == '200') ? res.data : null;
        if (d) { settle(Number.isInteger(Number(d.optionIndex)) ? Number(d.optionIndex) : index); return; }
        this.setData({ chapterParas: (this.data.chapterParas || []).map((p) => (p.kind === 'choice' && p.blockKey === item.blockKey ? Object.assign({}, p, { busy: false, picked: 0 }) : p)) });
        cyToast((res && res.msg) || '没选上，请再试一次');
      }).catch(() => {
        this.setData({ chapterParas: (this.data.chapterParas || []).map((p) => (p.kind === 'choice' && p.blockKey === item.blockKey ? Object.assign({}, p, { busy: false, picked: 0 }) : p)) });
        cyToast('网络不太好，请再试一次');
      });
    },
    /** 结局读口回包(/api/play/journey/ending):服务端信任 → 完成页「他们怎么看你」 */
    _castEnding(d) {
      if (!this._cast || !d) return;
      const trust = isObj(d.trust) ? d.trust : this._castMine().trust;
      const ch = d.chapter || (d.story && d.story.chapter) || {};
      const endingFor = d.endingFor !== undefined ? d.endingFor : ch.endingFor;
      this.setData({ 'cast.ending': endingView(this._cast, trust, { endingCount: d.endingCount, endingFor }) });
    },
  };
  return api;
}

module.exports = {
  CAST_COLORS, normalizeCast, profileOf, needsStart, playerName, castVars, speakerOf, lineHasVoice, lineItem,
  trustMovers, applyTrust, choiceItem, pickedItem, trustTicks, trustBand, clueView, cardView, playbill, routeCastRow,
  leadingCast, endingView, dropCap, footerText, fmtDur, voiceHidden, castParas, castHints, remember, cached, data, methods,
};
