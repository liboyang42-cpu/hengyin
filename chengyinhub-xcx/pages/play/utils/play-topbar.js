// 故事流顶栏(2026-10-30 业主定稿原型 claude.ai/artifact/KaK7pWb3P2XeSkxs6oc2rh):
//   左上角两种显示 —— 罗盘 / 地图(GTA III 式雷达:人在正中、地图随朝向转、北针在边上、下一站超出范围钉在边上);
//     罗盘点一下切地图,地图点一下进大地图(openStoryMap),右下角收回罗盘;选过的显示记在本机。
//   右上角:背包(打开既有的定稿背包,不改它)、案件册(主题有内容才出现)、游玩工具。
//   「…」里新增「各站奖励」:本主题各站的奖励与领取状态。
// 新接口:GET /api/play/journey/casebook、GET /api/play/journey/backpack(后端早已有,小程序此前没有入口)。

const NAV_KEY = 'cy_play_nav_mode';     // 'compass' | 'map'
const RIM_METERS = 150;                 // 雷达半径大约覆盖的距离(地图 scale 16 时),下一站更远就钉在边上
const HEADING_STEP = 4;                 // 朝向变化小于这个角度不 setData

function bearing(from, to) {
  const r = Math.PI / 180, y = Math.sin((to.lng - from.lng) * r) * Math.cos(to.lat * r);
  const x = Math.cos(from.lat * r) * Math.sin(to.lat * r) - Math.sin(from.lat * r) * Math.cos(to.lat * r) * Math.cos((to.lng - from.lng) * r);
  return (Math.atan2(y, x) / r + 360) % 360;
}
function meters(a, b) {
  const r = Math.PI / 180, dLat = (b.lat - a.lat) * r, dLng = (b.lng - a.lng) * r;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLng / 2) ** 2;
  return 6371000 * 2 * Math.asin(Math.sqrt(h));
}
/** 下一站在雷达上的位置:范围内 → null(地图自己画);范围外 → 边上指针的角度(相对屏幕上方)。 */
function rimPointer(player, target, heading) {
  if (!player || !target || !(player.lat && target.lat)) return null;
  if (meters(player, target) <= RIM_METERS) return null;
  return Math.round(((bearing(player, target) - (heading || 0)) % 360 + 360) % 360);
}
function readMode() {
  try { return wx.getStorageSync(NAV_KEY) === 'map' ? 'map' : 'compass'; } catch (e) { return 'compass'; }
}
const REL = { ally: '信任', neutral: '中立', guarded: '戒备' };
function hhmm(at) { const t = new Date(Number(at)); if (!(Number(at) > 0) || isNaN(t)) return ''; const p = (n) => (n < 10 ? '0' : '') + n; return p(t.getHours()) + ':' + p(t.getMinutes()); }
// 日志里的数值变化用主题自己的叫法(生命值 / 保温储备 …),不写死「HP」
function delta(l, bag) {
  const names = (bag && bag.names) || {}, out = [];
  if (Number(l.hpDelta)) out.push((names.hp || '生命') + ' ' + (l.hpDelta > 0 ? '+' : '') + l.hpDelta);
  if (Number(l.luckDelta)) out.push((names.luck || '运气') + ' ' + (l.luckDelta > 0 ? '+' : '') + l.luckDelta);
  return out.join(' · ');
}
const ok = (res) => res && (res.code == 200 || res.code == '200') && res.data;
const nonEmpty = (cb) => !!cb && ['facts', 'relations', 'costs', 'log'].some((k) => Array.isArray(cb[k]) && cb[k].length);

const data = {
  navMap: false, navHeading: 0, navRim: null,
  hasCasebook: false,
  casebook: { show: false, loading: false, error: '', facts: [], relations: [], costs: [], log: [], found: 0, total: 0 },
  journeyRewards: { show: false, loading: false, error: '', rows: [] },
};

function methods(req) {
  // 写成 const 对象再返回:台账 / 处理器门禁(xcx-scan)只扫模块里 `const X = {` 的键
  const api = {
    _initTopbar() {
      if (readMode() === 'map') this._setNav(true);
    },
    _stopTopbar() { this._stopHeading(); },
    /** 罗盘点一下 → 地图;地图点一下 → 大地图。 */
    onNavTap() {
      if (!this.data.navMap) { this._setNav(true); return; }
      this.openStoryMap();
    },
    foldNavMap() { this._setNav(false); },
    _setNav(on) {
      try { wx.setStorageSync(NAV_KEY, on ? 'map' : 'compass'); } catch (e) {}
      this.setData({ navMap: on, navRim: on ? rimPointer(this.data.fmPlayer, this.data.nextNode, this.data.navHeading) : null });
      if (on) this._startHeading(); else this._stopHeading();
    },
    // 减少动态效果时地图保持北朝上,不跟朝向转
    _startHeading() {
      if (this._headingCb || this.data.reducedMotion || typeof wx.onCompassChange !== 'function') return;
      this._headingCb = (res) => {
        const dir = Math.round(res && res.direction || 0), now = Date.now();
        const delta = Math.abs(((dir - this.data.navHeading + 540) % 360) - 180);
        if (delta < HEADING_STEP || now - (this._headingAt || 0) < 200) return;
        this._headingAt = now;
        this.setData({ navHeading: dir, navRim: rimPointer(this.data.fmPlayer, this.data.nextNode, dir) });
      };
      wx.onCompassChange(this._headingCb);
      try { wx.startCompass({ fail: () => {} }); } catch (e) {}
    },
    _stopHeading() {
      if (!this._headingCb) return;
      try { wx.offCompassChange && wx.offCompassChange(this._headingCb); wx.stopCompass({ fail: () => {} }); } catch (e) {}
      this._headingCb = null;
    },
    _refreshNavRim() {
      if (this.data.navMap) this.setData({ navRim: rimPointer(this.data.fmPlayer, this.data.nextNode, this.data.navHeading) });
    },

    _scope() { return { topicId: this.data.topicId, activityId: this.data.activityId || undefined }; },
    /** 进页后静默问一次:主题有案件册内容才在顶栏放这颗。 */
    _probeCasebook() {
      if (!this.data.topicId || this.data.isPreview || this.data.isMock) return;
      req('/api/play/journey/casebook', 'GET', this._scope(), undefined, true)
        .then((res) => { if (ok(res)) this.setData({ hasCasebook: nonEmpty(res.data) }); })
        .catch(() => {});
    },
    openCasebook() {
      this.setData({ 'casebook.show': true, 'casebook.loading': true, 'casebook.error': '' });
      req('/api/play/journey/casebook', 'GET', this._scope(), undefined, true).then((res) => {
        if (!ok(res)) { this.setData({ 'casebook.loading': false, 'casebook.error': (res && res.msg) || '案件册暂时没有读到' }); return; }
        const d = res.data;
        this.setData({ casebook: { show: true, loading: false, error: '',
          facts: d.facts || [], costs: d.costs || [],
          relations: (d.relations || []).map((r) => Object.assign({}, r, { stateLabel: REL[r.state] || '中立' })),
          log: (d.log || []).map((l) => Object.assign({}, l, { time: hhmm(l.at), delta: delta(l, this.data.themeBag) })),
          found: Number(d.found) || 0, total: Number(d.total) || 0 } });
      }).catch(() => this.setData({ 'casebook.loading': false, 'casebook.error': '案件册暂时没有读到' }));
    },
    closeCasebook() { this.setData({ 'casebook.show': false }); },
    openJourneyRewards() {
      this.setData({ sessionToolsOpen: false, 'journeyRewards.show': true, 'journeyRewards.loading': true, 'journeyRewards.error': '' });
      req('/api/play/journey/backpack', 'GET', this._scope(), undefined, true).then((res) => {
        if (!ok(res)) { this.setData({ 'journeyRewards.loading': false, 'journeyRewards.error': (res && res.msg) || '奖励暂时没有读到' }); return; }
        const names = {};
        (this.data.nodes || []).forEach((n) => { names[String(n.nodeId)] = n.name; });
        const rows = (res.data.items || res.data.nodes || []).filter((it) => it.reward && it.status !== 'none').map((it) => ({
          nodeId: it.nodeId, name: names[String(it.nodeId)] || '', reward: it.reward,
          state: it.status === 'claimed' ? '已领' : (it.claimableAt ? '待领取' : '完成后可领'),
        }));
        this.setData({ journeyRewards: { show: true, loading: false, error: '', rows } });
      }).catch(() => this.setData({ 'journeyRewards.loading': false, 'journeyRewards.error': '奖励暂时没有读到' }));
    },
    closeJourneyRewards() { this.setData({ 'journeyRewards.show': false }); },
  };
  return api;
}

module.exports = { data, methods, rimPointer, bearing, meters, NAV_KEY, RIM_METERS };
