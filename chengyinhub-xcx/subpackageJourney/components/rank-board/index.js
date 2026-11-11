'use strict';

const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');
const motion = require('../../../utils/motion.js');
const app = getApp();

// M13:月桂弹入两帧后开始;名次 120ms 后滚,滚动 520ms(--cy-motion-roll),落定时轻震
const HERO_ENTER_MS = 34;
const ROLL_DELAY_MS = 120;
const ROLL_MS = 520;

/* r2-finish 榜单(战报面板与结算页共用)。
 * 本关口径:队内(服务端给了队伍) / 全体(单关榜) / 队伍(队伍之间榜,服务端有队伍才出);
 * 城市、国家、世界、俱乐部收进「更多」。名次、上榜人数、队伍进度全读服务端;读不到不写 0。
 * l-after(2026-11-10,proto st-rank):preset="settle" 时只留 全体 / 队内(业主「不用这么多榜单」),不探队伍榜、没有「更多」;
 * 月桂 M13:两枝从 .6 弹到 1,名次 120ms 后滚上来,滚完轻震一下(减少动态效果直接落位、不震)。 */
Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    topicId: { type: String, value: '' },
    activityId: { type: String, value: '' },
    teamId: { type: String, value: '' },
    cityCode: { type: String, value: '' },
    countryCode: { type: String, value: '' },
    clubId: { type: String, value: '' },
    active: { type: Boolean, value: false },
    preset: { type: String, value: '' }
  },
  data: {
    tabs: [], more: [], scope: '', moreLabel: '更多', period: 'all', moreShow: false, moreItems: [],
    state: '', error: '', rank: null, hero: null, digits: [], heroOn: false
  },
  observers: {
    'topicId, activityId, teamId, cityCode, countryCode, clubId': function () { if (!this._attached) return; this._rebuild(); this._probeTeams(); },
    active(on) { if (on && !this._loadedOnce) this.load(); }
  },
  lifetimes: {
    attached() { this._attached = true; this._rebuild(); this._probeTeams(); },
    detached() { if (this._rollTimer) clearTimeout(this._rollTimer); if (this._landTimer) clearTimeout(this._landTimer); if (this._heroTimer) clearTimeout(this._heroTimer); this._epoch = (this._epoch || 0) + 1; }
  },
  methods: {
    _ctx() {
      const d = this.data;
      return { topicId: d.topicId, cityCode: d.cityCode, countryCode: d.countryCode, clubId: d.clubId, teamId: d.teamId, teamBoard: this._teamBoard === true };
    },
    _settle() { return this.data.preset === 'settle'; },
    _rebuild() {
      const t0 = view.boardTabs(this._ctx());
      // 结算弹层只给 全体 / 队内(全体在前),没有「更多」
      const t = this._settle()
        ? { primary: ['quest', 'team'].map(k => t0.primary.find(x => x.key === k)).filter(Boolean), more: [] }
        : t0;
      const keys = t.primary.concat(t.more).map(x => x.key);
      // 有队伍先看队内,否则看全体;当前口径仍可用就不跳。
      const scope = keys.indexOf(this.data.scope) >= 0 ? this.data.scope : (t.primary[0] ? t.primary[0].key : (t.more[0] ? t.more[0].key : ''));
      const picked = t.more.find(x => x.key === scope);
      this._all = t.primary.concat(t.more);
      this.setData({ tabs: t.primary.map(x => ({ key: x.key, label: x.label })), more: t.more, scope, moreLabel: picked ? picked.label : '更多' });
      if (this.data.active) this.load();
    },
    // 队伍之间榜:服务端有队伍(主题开着队伍看板、本关有队)才出「队伍」页签。
    _probeTeams() {
      if (this._settle()) return;
      const ownerType = view.isId(this.data.activityId) ? 2 : 1;
      const ownerId = ownerType === 2 ? this.data.activityId : this.data.topicId;
      if (!view.isId(ownerId)) return;
      const epoch = this._probeEpoch = (this._probeEpoch || 0) + 1;
      api.teamRanking(app, ownerType, ownerId).then(res => {
        if (epoch !== this._probeEpoch) return;
        const rows = api.ok(res) && Array.isArray(res.data) ? res.data : [];
        this._teamRows = rows;
        if (!rows.length) return;
        this._teamBoard = true;
        this._rebuild();
      });
    },
    onScope(e) { this.setData({ scope: e.detail.key, moreLabel: '更多' }); this.load(); },
    onMore() {
      const more = this.data.more;
      if (!more.length) return;
      this.setData({ moreShow: true, moreItems: more.map(x => x.label) });
    },
    onMoreSelect(e) {
      const pick = this.data.more[e.detail.index];
      this.setData({ moreShow: false });
      if (!pick) return;
      this.setData({ scope: pick.key, moreLabel: pick.label });
      this.load();
    },
    onMoreCancel() { this.setData({ moreShow: false }); },
    onPeriod(e) { this.setData({ period: e.detail.key }); this.load(); },
    // 名次数字滚动:同位数时从上一个数滚到新数;首次出现从 0 滚上来。减少动态时直接落位。
    _show(rank, state) {
      const hero = rank && rank.hero ? rank.hero : null;
      if (this._rollTimer) { clearTimeout(this._rollTimer); this._rollTimer = null; }
      if (this._landTimer) { clearTimeout(this._landTimer); this._landTimer = null; }
      if (this._heroTimer) { clearTimeout(this._heroTimer); this._heroTimer = null; }
      const prev = this.data.digits || [];
      const reduced = this.data.reducedMotion;
      const start = hero && !reduced && prev.length !== hero.digits.length ? hero.digits.map(() => '0') : null;
      const firstHero = !!hero && !this.data.hero;
      this.setData({ rank, state, hero, digits: start || (hero ? hero.digits : []), heroOn: !!hero && (reduced || !firstHero) });
      if (firstHero && !reduced) this._heroTimer = setTimeout(() => { this._heroTimer = null; this.setData({ heroOn: true }); }, HERO_ENTER_MS);
      if (start) {
        this._rollTimer = setTimeout(() => { this._rollTimer = null; if (this.data.hero === hero || (this.data.hero && hero && this.data.hero.rank === hero.rank)) this.setData({ digits: hero.digits }); }, ROLL_DELAY_MS);
        this._landTimer = setTimeout(() => { this._landTimer = null; motion.haptic({ type: 'light' }); }, ROLL_DELAY_MS + ROLL_MS);
      }
    },
    load() {
      const scope = this.data.scope;
      const tab = (this._all || []).find(t => t.key === scope);
      if (!tab) return;
      this._loadedOnce = true;
      const epoch = this._epoch = (this._epoch || 0) + 1;
      this.setData({ state: this.data.rank ? 'refresh' : 'loading', error: '' });
      if (scope === 'teams') {
        const ownerType = view.isId(this.data.activityId) ? 2 : 1;
        const ownerId = ownerType === 2 ? this.data.activityId : this.data.topicId;
        api.teamRanking(app, ownerType, ownerId).then(res => {
          if (epoch !== this._epoch) return;
          if (!api.ok(res)) { this.setData({ state: 'error', error: (res && res.msg) || '队伍榜暂未加载', rank: null, hero: null }); return; }
          const rank = view.teamBoardView(res.data);
          this._show(rank, rank.entries.length ? 'ready' : 'empty');
        });
        return;
      }
      if (scope === 'team') {
        api.teamDetail(app, tab.scopeId).then(res => {
          if (epoch !== this._epoch) return;
          if (!api.ok(res)) { this.setData({ state: 'error', error: res && Number(res.code) === 403 ? '队内榜仅本队成员可看' : '队内榜暂未加载', rank: null, hero: null }); return; }
          const rank = view.teamRankView(res.data);
          this._show(rank, rank && rank.hasRank ? 'ready' : 'empty');
        });
        return;
      }
      const requested = { scope, scopeId: tab.scopeId, period: this.data.period };
      api.rank(app, { scope, scopeId: tab.scopeId, period: this.data.period, limit: 50 }).then(res => {
        if (epoch !== this._epoch) return;
        if (!api.ok(res)) { this.setData({ state: 'error', error: (res && res.msg) || '榜单暂未加载', rank: null, hero: null }); return; }
        const rank = view.rankView(res.data, requested);
        if (!rank || rank.mismatch) { this.setData({ state: 'error', error: '榜单暂未加载', rank: null, hero: null }); return; }
        this._show(rank, rank.entries.length ? 'ready' : 'empty');
      });
    }
  }
});
