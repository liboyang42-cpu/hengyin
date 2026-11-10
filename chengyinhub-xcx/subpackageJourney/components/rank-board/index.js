'use strict';

const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');
const pageMotion = require('../../../utils/page-motion.js');
const app = getApp();

/* r2-finish 榜单(战报面板与结算页共用)。
 * 本关口径:队内(服务端给了队伍) / 全体(单关榜) / 队伍(队伍之间榜,服务端有队伍才出);
 * 城市、国家、世界、俱乐部收进「更多」。名次、上榜人数、队伍进度全读服务端;读不到不写 0。 */
Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    topicId: { type: String, value: '' },
    activityId: { type: String, value: '' },
    teamId: { type: String, value: '' },
    cityCode: { type: String, value: '' },
    countryCode: { type: String, value: '' },
    clubId: { type: String, value: '' },
    active: { type: Boolean, value: false }
  },
  data: {
    tabs: [], more: [], scope: '', moreLabel: '更多', period: 'all',
    state: '', error: '', rank: null, hero: null, digits: [], pmRows: 0
  },
  observers: {
    'topicId, activityId, teamId, cityCode, countryCode, clubId': function () { if (!this._attached) return; this._rebuild(); this._probeTeams(); },
    active(on) { if (on && !this._loadedOnce) this.load(); }
  },
  lifetimes: {
    attached() { this._attached = true; this._rebuild(); this._probeTeams(); },
    detached() { if (this._rollTimer) clearTimeout(this._rollTimer); this._epoch = (this._epoch || 0) + 1; }
  },
  methods: {
    _ctx() {
      const d = this.data;
      return { topicId: d.topicId, cityCode: d.cityCode, countryCode: d.countryCode, clubId: d.clubId, teamId: d.teamId, teamBoard: this._teamBoard === true };
    },
    _rebuild() {
      const t = view.boardTabs(this._ctx());
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
      wx.showActionSheet({
        itemList: more.map(x => x.label),
        success: r => {
          const pick = more[r.tapIndex];
          if (!pick) return;
          this.setData({ scope: pick.key, moreLabel: pick.label });
          this.load();
        }
      });
    },
    onPeriod(e) { this.setData({ period: e.detail.key }); this.load(); },
    // 名次数字滚动:同位数时从上一个数滚到新数;首次出现从 0 滚上来。减少动态时直接落位。
    _show(rank, state) {
      const hero = rank && rank.hero ? rank.hero : null;
      if (this._rollTimer) { clearTimeout(this._rollTimer); this._rollTimer = null; }
      const prev = this.data.digits || [];
      const start = hero && !this.data.reducedMotion && prev.length !== hero.digits.length ? hero.digits.map(() => '0') : null;
      // r4-motion(MOTION.md 4.6 第 2 步 / M2):榜单行第一次到齐时按 --i 错峰上浮,自己那一行落定时多一下 1.02 的 settle;刷新 / 换页签不重播
      if (rank && Array.isArray(rank.entries) && rank.entries.length && !this._rowsEntered) {
        this._rowsEntered = true;
        pageMotion.enter(this, 'pmRows', { once: true });
      }
      this.setData({ rank, state, hero, digits: start || (hero ? hero.digits : []) });
      if (start) this._rollTimer = setTimeout(() => { this._rollTimer = null; if (this.data.hero === hero || (this.data.hero && hero && this.data.hero.rank === hero.rank)) this.setData({ digits: hero.digits }); }, 60);
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
