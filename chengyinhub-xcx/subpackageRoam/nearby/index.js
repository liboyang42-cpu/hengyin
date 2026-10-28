// 漫游地图 · 附近的队伍(地图组队 P 方案,Figma txQoyVyKb3aQdFCnc8qmhj 590:1014 · P1–P6)。
// 地图只做展示层:挂票务组队(公开队伍),不能开新局(9-15 裁决,搭子局 A 下架)。
// 数据:队伍 + 探店地点/商家 + 自由探索/活动；城市定向只在独立主题入口展示。
// 纯函数在 utils/map-team.js(卡片五态 / errorCode 分支 / P6 三态)与 utils/roam-hangout*.js(主题点位 canvas),本文件只做接线。
// ⚠️ 失败只按 errorCode 分支(map-team.resolveTeamError),走 cy-result-sheet 失败半屏,不解析 msg。
const app = getApp();
const session = require('../../utils/session/login-reload.js');
const { MAP_STYLE } = require('../../utils/map-style.js');
const { getCurrentLocation } = require('../../utils/location/location-manager.js');
const lastLocation = require('../../utils/location/last-location.js');
const { isRecordList } = require('../../utils/response-shape.js');
const H = require('../../utils/roam-hangout.js');
const T = require('../utils/map-team.js'); const L = require('../utils/map-layers.js'); // L:2026-10-22 三类点位/筛选/边缘缩小
const cyToast = require('../../utils/toast.js');
const modal = require('../../utils/modal.js');
const mediaPreview = require('../../utils/media-preview.js');
const loading = require('../../utils/loading.js');
const M = require('../../utils/roam-hangout-marker.js');
const { MAP_AVATAR_COLORS } = require('../../utils/play-visual-tokens.js'); const P = require('../utils/map-pins.js'); const kmText = (m) => T.rangeText(m).replace(/\s*km$/, ' 公里'); // v13 点位图 / 「范围 3 公里」

// BL-23:首屏占位走 last-location 的固定坐标;定位失败时 onLoad 换成「上次成功位置 → 固定坐标」
const DEFAULT_CENTER = { lat: lastLocation.FIXED.latitude, lng: lastLocation.FIXED.longitude };
/* 稿 591:1020 的角控件写死「范围 1 km」⇒ 首屏默认就是 1 km,不取 /api/team/nearby 的 3000 缺省。
 * data.scale 初值 14 本来就是按 1 km 画的(见 onRangePick 的档位表),原来 3000 与它对不上。 */
const DEFAULT_RADIUS = 1000;
const ACTIVITY_PAGE = '/pages/activity/detail/index';
const TOPIC_PAGE = '/pages/topic/index/index';
const TEAM_PAGE = '/pages/team/detail/index';
const JSON_HEADER = { 'Content-Type': 'application/json' };
const RANGE_ITEMS = T.RADII.map((r) => T.rangeText(r).replace('范围 ', ''));

function safeBottom() {
  try {
    const w = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    return Math.max(0, (w.screenHeight || 0) - ((w.safeArea && w.safeArea.bottom) || w.screenHeight || 0));
  } catch (e) { return 0; }
}

function hudTop() {
  try {
    const r = wx.getMenuButtonBoundingClientRect();
    return (r && r.bottom ? r.bottom : 80) + 10;
  } catch (e) { return 90; }
}

// 2026-10-27 owner P-38:返回键按胶囊实测位置画(与胶囊同一行),不再依赖导航条里的状态栏高度
function capsuleRow() {
  try {
    const r = wx.getMenuButtonBoundingClientRect();
    if (r && r.top && r.height) return { capTop: r.top, capH: r.height };
  } catch (e) { /* 拿不到胶囊:退回常见值 */ }
  return { capTop: 48, capH: 32 };
}

function emptyResult() {
  return { show: false, kind: 'fail', title: '', why: '', primaryText: '', secondaryText: '' };
}

Page({
  data: {
    safeBottom: 0,
    hudTop: 90,
    fmSubkey: MAP_STYLE.subkey,
    fmLayerStyle: MAP_STYLE.layerStyle,
    center: DEFAULT_CENTER,
    scale: 15,
    markers: [],
    circles: [],
    headerText: '附近',
    nearbyReadAccess: '',
    rangeText: kmText(DEFAULT_RADIUS), pcTop: 200, mddLeft: 16,
    myTeamsN: 0,
    viewMode: 'map',        // map | list(2026-10-14 地图优化)
    listRows: [], layer: 'all', layerSub: '', layerSheet: '', layerItems: [], poiCard: null, poiCardIn: false, poiFlip: false, poiExpand: false, pcClip: '', poiPhoto: 0, camOpen: false, privacyGateShow: false, // 2026-10-22 筛选行与地标/商家点位卡
    myReadAccess: '', myReadError: false, myLoaded: false,
    rangeItems: RANGE_ITEMS,
    rangeIndex: T.RADII.indexOf(DEFAULT_RADIUS),
    rangeSheetShow: false,
    loadError: false,
    /* sheet = '' | team(P2/P3/P4/被拒/已加入)| leader(P5)| mine(P6)| topic。非空时底部角控件收起。 */
    sheet: '',
    sheetIn: false,
    current: null,
    busy: false,
    applicants: [],
    leaderSub: '',
    myRows: [],
    myLoading: false,
    topic: null,
    resultSheet: emptyResult(),
  },

  onLoad(options) {
    this._openMineOnShow = options && options.sheet === 'mine';
    const requestedRadius = Number(options && options.radius); this._radius = T.RADII.indexOf(requestedRadius) >= 0 ? requestedRadius : DEFAULT_RADIUS; this._requestedTeamId = options && options.teamId;
    this._teams = [];        // /api/team/nearby 原始行(只在内存,视图走 T.decorateTeam 白名单)
    this._items = []; this._pois = [];        // 主题 / 活动点位;_pois = /api/roam/pois 地标与商家
    this._icons = {}; this._pins = {};
    this._far = {};
    this._iconFailed = {};
    this._selfIcon = '';
    this._me = null;
    const cap = capsuleRow();
    this.setData({ safeBottom: safeBottom(), hudTop: hudTop(), rangeText: kmText(this._radius), rangeIndex: T.RADII.indexOf(this._radius), capTop: cap.capTop, capH: cap.capH });
    const that = this; const linkedLat = H.numOrNull(options && options.lat); const linkedLng = H.numOrNull(options && options.lng);
    if (linkedLat != null && linkedLng != null) { const center = { lat: linkedLat, lng: linkedLng }; this.setData({ center, circles: [] }); this.refresh(center); this.loadMyTeams(false); return; }
    getCurrentLocation({
      onSuccess(res) {
        lastLocation.save(res);
        const c = { lat: res.latitude, lng: res.longitude };
        that._me = c;
        that.setData({ center: c, circles: [] });
        that.refresh(c);
      },
      onFail() {
        const fb = lastLocation.resolve();
        const c = { lat: fb.latitude, lng: fb.longitude };
        that.setData({ center: c, circles: [] });
        cyToast(fb.hint);
        that.refresh(c);
      },
    }); this.loadMyTeams(false);
  },

  /* 买完票回到地图,卡片要从「去买这场的票」变成「申请加入」(P2 底注):回前台重拉一次。 */
  onShow() { this._hidden = false;
    if (this._nearbyReadOwner && this._nearbyReadOwner !== this._myIdentity()) this._resetNearbyRead(this._myIdentity());
    if (this._shownOnce) this.refresh(this.data.center); this._shownOnce = true; this._navigating = false;
    if (this._myReadOwner && this._myReadOwner !== this._myIdentity()) this.loadMyTeams(false);
    if (this._openMineOnShow) { this._openMineOnShow = false; this.openMyTeams(); }
    const c = this.data.poiCard; if (c && c.official) this._loadSpot(c.id); // 挑战回来:完成状态、评价跟着刷新
  },
  onHide() { this._hidden = true; },

  onUnload() { this._unloaded = true; if (this._regionTimer) clearTimeout(this._regionTimer); },

  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/roam/index' });
  },

  refresh(center) {
    if (!this._prepareNearbyRead()) return;
    this.fetchTeams(center, this._radius);
    this.fetchTopics(center, this._radius); this.fetchPois(center, this._radius);
  },

  retryNearby() {
    if (this.data.nearbyReadAccess === 'login') {
      return session.retryLogin(this, app, function () { this.refresh(this.data.center); });
    }
    this.refresh(this.data.center);
  },

  /* ---------- 数据 ---------- */

  _resetNearbyRead(owner) {
    this._nearbyReadOwner = owner;
    this._teamSeq = (this._teamSeq || 0) + 1;
    this._topicSeq = (this._topicSeq || 0) + 1;
    this._nearbyTeamsLoaded = false;
    this._nearbyFailures = { teams: false, topics: false };
    this._teams = []; this._items = []; this._icons = {}; this._far = {}; this._iconFailed = {}; this._selfIcon = '';
    this.setData({ headerText: '附近', nearbyReadAccess: '', loadError: false,
      current: null, topic: null, applicants: [], leaderSub: '', sheet: '', sheetIn: false });
    this._syncMarkers();
  },

  _prepareNearbyRead() {
    if (this._unloaded) return null;
    const owner = this._myIdentity();
    if (this._nearbyReadOwner !== owner) this._resetNearbyRead(owner);
    if (!session.memberId(app)) {
      this._resetNearbyRead(owner);
      this.setData({ nearbyReadAccess: 'login' });
      return null;
    }
    return owner;
  },

  _nearbyCurrent(layer, owner, seq) {
    return !this._unloaded && owner === this._nearbyReadOwner && owner === this._myIdentity()
      && seq === (layer === 'teams' ? this._teamSeq : this._topicSeq);
  },

  _nearbyOutcome(layer, owner, seq, res, statusCode) {
    if (this._unloaded || owner !== this._nearbyReadOwner
        || seq !== (layer === 'teams' ? this._teamSeq : this._topicSeq)) return true;
    const nextOwner = this._myIdentity();
    // 真实 request-client 重登失败会先清旧会话；仍必须清掉页面私有快照。
    if (owner !== nextOwner) {
      this._resetNearbyRead(nextOwner);
      if (!session.memberId(app)) this.setData({ nearbyReadAccess: 'login' });
      else if (!this._hidden && owner.split('|')[0] === String(session.memberId(app))) this.refresh(this.data.center);
      return true;
    }
    const code = session.failureStatus(res, statusCode);
    if (code !== 401 && code !== 403) return false;
    this._resetNearbyRead(nextOwner);
    this.setData({ nearbyReadAccess: code === 401 ? 'login' : 'denied' });
    return true;
  },

  _nearbyLayerResult(layer, failed) {
    this._nearbyFailures = this._nearbyFailures || { teams: false, topics: false };
    this._nearbyFailures[layer] = !!failed;
    this.setData({ loadError: this._nearbyFailures.teams || this._nearbyFailures.topics });
  },

  fetchTeams(center, radius) {
    const that = this; this._lodCenter = center; this._lodRadius = radius; // 边缘缩小以这次查询的圆心/半径为准
    const owner = this._prepareNearbyRead();
    if (!owner) return;
    const seq = (this._teamSeq = (this._teamSeq || 0) + 1);
    this._nearbyTeamsLoaded = false;
    this.setData({ headerText: '附近', nearbyReadAccess: '' });
    this._nearbyLayerResult('teams', false);
    const current = () => that._nearbyCurrent('teams', owner, seq);
    const failed = (res, status) => {
      if (that._nearbyOutcome('teams', owner, seq, res, status)) return;
      that._teams = []; that._nearbyTeamsLoaded = false;
      that.setData({ headerText: '附近' }); that._syncMarkers(); that._nearbyLayerResult('teams', true);
    };
    app.sendRequest({
      url: '/api/team/nearby', method: 'GET', hideLoading: true, silentError: true,
      data: { lat: center.lat, lng: center.lng, radius },
      success(res) {
        if (that._nearbyOutcome('teams', owner, seq, res)) return;
        if (!(res && res.code == 200 && isRecordList(res.data))) { failed(res); return; }
        that._teams = res.data; that._nearbyTeamsLoaded = true; that._nearbyLayerResult('teams', false);
        that._syncTeams(); if (that._requestedTeamId) { const teamId = that._requestedTeamId; that._requestedTeamId = null; that.openTeam(teamId); }
        that._queueIcons(() => current() ? that._genTeamIcons(current) : false).then((made) => { if (made && current()) that._syncMarkers(); });
      },
      fail: failed,
      successStatusAbnormal: failed,
    });
  },

  /* 主题 / 活动点位沿用漫游附近接口;接口里存量的局(kind=hangout)不再画 —— 地图上不存在「局」了。 */
  fetchTopics(center, radius) {
    const that = this; this._lodCenter = center; this._lodRadius = radius;
    const owner = this._prepareNearbyRead();
    if (!owner) return;
    const seq = (this._topicSeq = (this._topicSeq || 0) + 1);
    this._nearbyLayerResult('topics', false);
    const current = () => that._nearbyCurrent('topics', owner, seq);
    const failed = (res, status) => {
      if (that._nearbyOutcome('topics', owner, seq, res, status)) return;
      that._items = []; that._syncMarkers(); that._nearbyLayerResult('topics', true);
    };
    app.sendRequest({
      url: '/api/roam/hangout/nearby', method: 'GET', hideLoading: true, silentError: true,
      data: { lat: center.lat, lng: center.lng, radius },
      success(res) {
        if (that._nearbyOutcome('topics', owner, seq, res)) return;
        const d = res && res.code == 200 && res.data ? res.data : null;
        if (!d || !isRecordList(d.items)) { failed(res); return; }
        that._items = d.items.filter((it) => L.keepItem(it)); that._syncLayerOptions(); that._nearbyLayerResult('topics', false);
        that._syncMarkers();
        const items = that._items;
        that._queueIcons(() => current() ? that._genIcons(items, current) : false).then((made) => { if (made && current()) that._syncMarkers(); });
      },
      fail: failed,
      successStatusAbnormal: failed,
    });
  },

  _syncTeams() {
    const teams = this._teams || [];
    const cur = this.data.current;
    const row = cur && teams.find((t) => String(t.teamId) === String(cur.teamId));
    const next = row ? T.decorateTeam(row) : cur;
    const BADGE = { buy: ['需购票', 'plain'], apply: ['可申请', 'plain'], pending: ['审核中', 'wait'], joined: ['已通过', 'ok'], rejected: ['未通过', 'bad'], leader: ['我带队', 'lead'] };
    const listRows = teams.map((t) => { const d = T.decorateTeam(t); const b = BADGE[d.card && d.card.mode] || ['可申请', 'plain'];
      return { teamId: d.teamId, name: d.heading || d.name, sub: d.sub, badge: b[0], tone: b[1], count: d.joinedCount + '/' + d.maxMembers }; });
    this.setData({ current: next,
      leaderSub: next ? T.leaderSub(next) : '' });
    // 卡片开着的那支队伍刷新后不在了(满员 / 开场 / 审核中,服务端已过滤):卡上的「申请加入」已经不成立,收掉
    if (cur && !row && this.data.sheet === 'team') this.closeSheet();
    this._syncMarkers();
  },

  _syncMarkers() {
    const fallback = H.HANGOUT_FALLBACK_ICON; const f = this._layerFilter(); // 2026-10-22:按筛选取三类点位,再做边缘缩小 / 远处隐藏
    const items = (this._items || []).filter((it) => L.keepItem(it, f));
    const teams = L.keepTeam(f) ? (this._teams || []) : [];
    const pois = (this._pois || []).filter((p) => L.keepPoi(p, f));
    const hide = this._openPin; // 点位卡开着时,那颗点位由卡片代替
    const raw = H.buildHangoutMarkers(items, this._icons, this._far)
      .concat(T.buildTeamMarkers(teams, this._icons, (this._far || {})[MAP_AVATAR_COLORS.cream] || fallback))
      .concat(this._poiMarkers(f)).concat(H.buildSelfMarker(this._me, this._selfIcon));
    const places = (this._items || []).length + (this._pois || []).filter((p) => L.poiKind(p) !== 'shop').length;
    const shops = (this._pois || []).filter((p) => L.poiKind(p) === 'shop').length;
    this.setData({
      markers: L.edgeLod(P.applyPins(raw, this._pins), this._lodCenter || this.data.center, this._lodRadius || this._radius).filter((m) => !hide || m.id !== hide),
      headerText: '附近' + (this._nearbyTeamsLoaded ? ' · ' + (this._teams || []).length + ' 支队伍' : '') + (places ? ' · ' + places + ' 处地点' : '') + (shops ? ' · ' + shops + ' 家商家' : ''), // v13 计数条;队伍没读到不写 0
      listRows: this._listRows(items, teams, pois),
    });
    this._wantPins(items, teams, pois);
  },

  /* 列表:队伍 / 地点 / 商家同一张表,缩略图 + 类型小牌(队伍写人数) */
  _listRows(items, teams, pois) {
    const img = (u) => (u ? app.getImgUrl(u) : '');
    const rows = [];
    teams.forEach((t) => { const d = T.decorateTeam(t); rows.push({ key: 't' + d.teamId, kind: 'team', id: d.teamId, img: img(String(t.memberAvatarsJoined || t.leaderAvatar || '').split(',')[0]), round: false, name: d.heading || d.name, sub: d.sub, badge: d.joinedCount + '/' + d.maxMembers, tone: 'team' }); });
    items.forEach((it) => rows.push({ key: 'i' + it.kind + it.id, kind: it.kind === 'topic' ? 'topic' : 'activity', id: it.id, img: img(it.picUrl), round: true, name: it.name || '', sub: [it.addressName, H.fmtKm(it.distance)].filter(Boolean).join(' · '), badge: it.kind === 'topic' ? (Number(it.productType) === 2 ? '自由探索' : '城市定向') : '热门活动', tone: 'place' }));
    pois.forEach((p) => { const c = L.poiCard(p); rows.push({ key: 'p' + p.id, kind: 'poi', id: p.id, img: img(c.img), round: c.kind !== 'shop', name: c.title, sub: c.address, badge: c.tags[0] || c.tag, tone: c.kind === 'shop' ? 'shop' : 'place' }); });
    return rows;
  },

  /* 点位图按需生成:缺哪张画哪张(一次最多 24 张),画好再刷一次 marker */
  _wantPins(items, teams, pois) {
    const specs = P.pinSpecs({ items, teams, pois, teamTag: T.TEAM_MARKER_TAG });
    this._pins = this._pins || {};
    const need = specs.filter((sp) => !(this._pins[sp.id] && this._pins[sp.id].key === P.pinKey(sp))).slice(0, 24);
    if (!need.length || this._pinBusy) return;
    this._pinBusy = true;
    this._queueIcons(() => this._genPins(need)).then((made) => { this._pinBusy = false; if (made && !this._unloaded) this._syncMarkers(); }, () => { this._pinBusy = false; });
  },
  async _genPins(need) {
    if (this._unloaded) return false;
    let made = 0;
    try {
      const cv = await this._canvas();
      for (const sp of need) {
        if (this._unloaded) return false;
        const b = P.BOX[sp.kind];
        cv.width = b.w * P.SCALE; cv.height = b.h * P.SCALE;
        const urls = sp.kind === 'team' ? (sp.faces || []) : [sp.img];
        const imgs = [];
        for (const u of urls) {
          const url = u ? app.getImgUrl(u) : '';
          let im = null;
          if (url && !this._iconFailed[url]) { try { im = await this._loadImage(cv, url); } catch (e) { this._iconFailed[url] = true; } }
          imgs.push(im);
        }
        if (sp.kind !== 'team' && !imgs[0]) continue;
        try {
          P.drawPin(cv.getContext('2d'), sp, imgs);
          this._pins[sp.id] = { icon: await this._save(cv), kind: sp.kind, key: P.pinKey(sp) }; made++;
        } catch (e) { /* 这一枚留小圆点 */ }
      }
    } catch (e) { /* canvas 拿不到:全部留小圆点 */ }
    return made > 0;
  },

  /* ---------- marker 图标(离屏 canvas) ---------- */

  _canvas() {
    return new Promise((res, rej) => {
      wx.createSelectorQuery().select('#teamcv').fields({ node: true }).exec((r) =>
        (r && r[0] && r[0].node ? res(r[0].node) : rej(new Error('no teamcv'))));
    });
  },

  _loadImage(cv, src) {
    return new Promise((res, rej) => {
      const img = cv.createImage();
      const timer = setTimeout(() => rej(new Error('timeout')), 6000);
      img.onload = () => { clearTimeout(timer); res(img); };
      img.onerror = () => { clearTimeout(timer); rej(new Error('load fail')); };
      img.src = src;
    });
  },

  _save(cv) {
    return new Promise((res, rej) => wx.canvasToTempFilePath({ canvas: cv, fileType: 'png', success: (r) => res(r.tempFilePath), fail: rej }));
  },

  /* 两个生成器共用 #teamcv 一张画布,并发会互相清掉 cv.width —— 串行排队,晚到的批次排在后面照样生成。 */
  _queueIcons(job) {
    const run = (this._iconQueue || Promise.resolve()).then(job, job);
    this._iconQueue = run.catch(() => false);
    return run;
  },

  /* 队伍点位:圆头像(队长)+ 人数角标。头像下不来就画首字,一个点都不少。 */
  async _genTeamIcons(current = () => !this._unloaded) {
    if (!current()) return false;
    const need = (this._teams || []).filter((t) => !this._icons[T.teamIconKey(t)]).slice(0, M.ICON_MAX);
    const wantFar = !this._far[MAP_AVATAR_COLORS.cream];
    if (!need.length && !wantFar) return false;
    let made = 0;
    try {
      const cv = await this._canvas();
      if (!current()) return false;
      if (wantFar) {
        cv.width = 28; cv.height = 28;
        try {
          M.drawFarDot(cv.getContext('2d'), 28, MAP_AVATAR_COLORS.cream);
          const icon = await this._save(cv); if (!current()) return false;
          this._far[MAP_AVATAR_COLORS.cream] = icon; made++;
        } catch (e) { if (!current()) return false; /* 兜底图 */ }
      }
      const S = 108; cv.width = S; cv.height = S;
      const g = cv.getContext('2d');
      for (const t of need) {
        if (!current()) return false;
        let img = null;
        const url = t.leaderAvatar ? app.getImgUrl(t.leaderAvatar) : '';
        if (url && !this._iconFailed[url]) { try { img = await this._loadImage(cv, url); } catch (e) { if (!current()) return false; this._iconFailed[url] = true; } }
        if (!current()) return false;
        try {
          M.drawMarker(g, S, T.teamMarkerSpec(t), img);
          const icon = await this._save(cv); if (!current()) return false;
          this._icons[T.teamIconKey(t)] = icon; made++;
        } catch (e) { if (!current()) return false; /* 留给远距点 */ }
      }
    } catch (e) { /* canvas 拿不到:全部走远距点 */ }
    return made > 0;
  },

  /* 主题 / 活动点位(照片瓦片 + 玩法描边),画法与漫游同源。 */
  async _genIcons(raw, current = () => !this._unloaded) {
    if (!current()) return false;
    const need = new Map();
    const farNeed = new Set();
    (raw || []).forEach((it) => {
      const spec = M.markerSpec(it);
      if (!this._far[spec.color]) farNeed.add(spec.color);
      const near = it.distance != null && Number(it.distance) <= M.NEAR_M;
      const key = M.iconKey(spec, spec.initial);
      if (near && !this._icons[key] && need.size < M.ICON_MAX) need.set(key, { spec, url: it.picUrl ? app.getImgUrl(it.picUrl) : '' });
    });
    const wantSelf = !this._selfIcon && !!this._me;
    if (!need.size && !farNeed.size && !wantSelf) return false;
    let made = 0;
    try {
      const cv = await this._canvas();
      if (!current()) return false;
      cv.width = 28; cv.height = 28;
      let g = cv.getContext('2d');
      for (const color of farNeed) {
        if (!current()) return false;
        try {
          M.drawFarDot(g, 28, color);
          const icon = await this._save(cv); if (!current()) return false;
          this._far[color] = icon; made++;
        } catch (e) { if (!current()) return false; /* 兜底图 */ }
      }
      const S = 108; cv.width = S; cv.height = S;
      g = cv.getContext('2d');
      if (wantSelf) {
        let me = null;
        const av = app.getImgUrl(app.globalData.avatar || '');
        if (av && !this._iconFailed[av]) { try { me = await this._loadImage(cv, av); } catch (e) { if (!current()) return false; this._iconFailed[av] = true; } }
        if (!current()) return false;
        try {
          M.drawSelfDot(g, S, MAP_AVATAR_COLORS.player, me); // P-38:「我」画成居中的完整圆点,不再被画布裁边
          const icon = await this._save(cv); if (!current()) return false;
          this._selfIcon = icon; made++;
        } catch (e) { if (!current()) return false; /* 没有自己的点也不影响队伍 */ }
      }
      for (const [key, n] of need) {
        if (!current()) return false;
        let img = null;
        if (n.url && !this._iconFailed[n.url]) { try { img = await this._loadImage(cv, n.url); } catch (e) { if (!current()) return false; this._iconFailed[n.url] = true; } }
        if (!current()) return false;
        try {
          M.drawMarker(g, S, n.spec, img);
          const icon = await this._save(cv); if (!current()) return false;
          this._icons[key] = icon; made++;
        } catch (e) { if (!current()) return false; /* 留给远距点 */ }
      }
    } catch (e) { /* canvas 拿不到:全部走远距点 */ }
    return made > 0;
  },

  /* ---------- 地图 ---------- */

  onMarkerTap(e) {
    const markerId = Number(e.detail && e.detail.markerId); this._lastMarkerId = markerId; const poiHit = L.parsePoiMarkerId(markerId); if (poiHit) { this.openPoiCard(poiHit); return; }
    if (markerId % 10 === T.TEAM_MARKER_TAG) { this.openPinCard('team', Math.floor(markerId / 10)); return; }
    const hit = H.parseMarkerId(markerId);
    if (!hit) return;
    if (hit.kind === 'activity' || hit.kind === 'topic') this.openPinCard(hit.kind, hit.id);
  },

  onRegionChange(e) {
    const d = e.detail || {};
    if (d.phase !== 'end' || !d.centerLocation) return;
    if (d.causedBy !== 'drag' && d.causedBy !== 'scale') return;
    const c = { lat: d.centerLocation.latitude, lng: d.centerLocation.longitude };
    if (this._regionTimer) clearTimeout(this._regionTimer);
    this._regionTimer = setTimeout(() => {
      this._regionTimer = null;
      this.setData({ center: c });
      this.refresh(c);
    }, 600);
  },

  /* ---------- 角控件:范围 / 我的队伍 ---------- */

  openRange() { this.setData({ rangeSheetShow: true }); },
  setViewMode(e) { const mode = e.currentTarget.dataset.mode === 'list' ? 'list' : 'map'; if (mode !== this.data.viewMode) this.setData({ viewMode: mode }); },
  onListRowTap(e) {
    const { kind, id } = e.currentTarget.dataset;
    if (kind === 'team') { this.openTeam(id); return; }
    this.setData({ viewMode: 'map' }, () => this.openPinCard(kind === 'poi' ? 'poi' : kind, id));
  },
  // 我的队伍改为独立页面(带队 / 已通过 / 审核中 / 未通过 + 附近招募);P6 半屏保留给 sheet=mine 旧链接
  openMyTeamsPage() { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageRoam/my-teams/index', complete: () => { this._navigating = false; } }); },
  closeRange() { this.setData({ rangeSheetShow: false }); },
  onRangePick(e) {
    const idx = Number(e.detail && e.detail.index);
    const r = T.RADII[idx];
    if (!r) { this.closeRange(); return; }
    this._radius = r;
    this.setData({ rangeSheetShow: false, rangeIndex: idx, rangeText: kmText(r), scale: r >= 10000 ? 11 : r >= 5000 ? 12 : r >= 3000 ? 13 : 15 }); // v13:1 公里时再近一级,照片点位铺得开
    this.refresh(this.data.center);
  },

  /* ---------- 半屏壳 ---------- */

  /* 2026-10-23 弹层动效:关闭原来是一帧卸载;现在先落下(half 档 250ms = --cy-motion-sheet-out)再卸载。
     上一层还在落下时又要开新的一层:等它落完再开(同一时刻只有一个弹层在动)。 */
  _openSheet(name) {
    if (this._sheetInTimer) { clearTimeout(this._sheetInTimer); this._sheetInTimer = null; }
    if (this._sheetOutTimer) { this._pendingSheet = name; return; }
    this.setData({ sheet: name, sheetIn: false });
    this._sheetInTimer = setTimeout(() => this.setData({ sheetIn: true }), this.data.reducedMotion ? 0 : 20);
  },
  closeSheet() {
    if (this._sheetInTimer) { clearTimeout(this._sheetInTimer); this._sheetInTimer = null; }
    this._pendingSheet = '';
    if (!this.data.sheet || this.data.reducedMotion) { this.setData({ sheet: '', sheetIn: false }); return; }
    if (this._sheetOutTimer) return;
    this.setData({ sheetIn: false });
    this._sheetOutTimer = setTimeout(() => {
      this._sheetOutTimer = null;
      if (this._unloaded) return;
      this.setData({ sheet: '' });
      const next = this._pendingSheet;
      this._pendingSheet = '';
      if (next) this._openSheet(next);
    }, 250);
  },
  /* 半屏本体吃掉冒泡,免得点正文把自己关了。 */
  noop() {},

  /* ---------- 队伍卡(P2/P3/P4/被拒/已加入)与 P5 ---------- */

  openTeam(teamId) {
    const row = (this._teams || []).find((t) => String(t.teamId) === String(teamId));
    if (!row) return;
    const cur = T.decorateTeam(row);
    if (cur.viewerStatus === 'LEADER') {
      this.setData({ current: cur, applicants: [], leaderSub: T.leaderSub(cur) }, () => this._openSheet('leader'));
      this.loadApplicants();
    } else {
      this.setData({ current: cur }, () => this._openSheet('team'));
    }
  },

  _patchCurrent(patch) {
    const cur = this.data.current;
    if (!cur) return;
    const row = (this._teams || []).find((t) => String(t.teamId) === String(cur.teamId));
    if (row) Object.assign(row, patch);
    this.setData({ current: T.decorateTeam(row || Object.assign({}, cur, patch)) });
    this._syncMarkers();
  },

  _dropCurrent() {
    const cur = this.data.current;
    if (cur) this._teams = (this._teams || []).filter((t) => String(t.teamId) !== String(cur.teamId));
    this.closeSheet();
    this._syncMarkers();
  },

  onCardPrimary() {
    if (this._navigating) return; const cur = this.data.current;
    const btn = cur && cur.card && cur.card.primary;
    if (!btn) return;
    if (btn.action === 'buy') this._goBuy(cur);
    else if (btn.action === 'apply') this.apply();
    else if (btn.action === 'enter') { this._navigating = true; wx.navigateTo({ url: TEAM_PAGE + '?teamId=' + encodeURIComponent(cur.teamId), complete: () => { this._navigating = false; } }); }
  },

  onCardSecondary() {
    const cur = this.data.current;
    const btn = cur && cur.card && cur.card.secondary;
    if (btn && btn.action === 'withdraw') this.withdraw(cur.teamId);
  },

  /* 买票 = 这支队伍挂的那一场的详情页(购票在那儿),买完回来 onShow 重拉。 */
  _goBuy(cur) {
    if (!cur || this._navigating || !(cur.activityId || cur.topicId)) return; this._navigating = true;
    if (cur.activityId) wx.navigateTo({ url: ACTIVITY_PAGE + '?id=' + encodeURIComponent(cur.activityId), complete: () => { this._navigating = false; } });
    else wx.navigateTo({ url: TOPIC_PAGE + '?id=' + encodeURIComponent(cur.topicId), complete: () => { this._navigating = false; } });
  },

  _post(url, body, op, onOk, onFail) {
    if (this.data.busy) return;
    const that = this;
    this.setData({ busy: true });
    app.sendRequest({
      url, method: 'POST', hideLoading: true, silentError: true,
      data: JSON.stringify(body), header: JSON_HEADER,
      success(res) {
        that.setData({ busy: false });
        if (res && res.code == 200) { onOk(res); return; }
        const r = T.resolveTeamError(op, res);
        if (onFail) onFail(r);
        that._showFail(r);
      },
      fail(res) { that.setData({ busy: false }); that._showFail(T.resolveTeamError(op, res)); },
    });
  },

  apply() {
    const cur = this.data.current;
    if (!cur || this.data.busy) return;
    const that = this;
    // 申请留言(可选,≤200 字;后端 trimToNull + 内容安全机审):
    // 陌生队伍先自报来意,队长在 P5 审批行按留言决定同不同意。空留言照旧只发 teamId。
    modal.show({
      title: '申请加入这支队伍',
      editable: true,
      placeholderText: '想跟队长说一句(可选):同场打算、几个人…',
      maxlength: 200,
      confirmText: '提交申请',
      success(r) {
        if (!r.confirm) return;
        const message = String(r.content || '').trim();
        const body = { teamId: Number(cur.teamId) };
        if (message) body.message = message;
        that._post('/api/team/apply', body, 'apply',
          // 服务端回执带 applyExpireTime(真实失效时刻 = min(申请+24h, 场次开始)),卡片据此说剩余时间
          (res) => that._patchCurrent({ viewerStatus: 'PENDING', applyExpireTime: res && res.data && res.data.applyExpireTime }),
          (err) => that._applyErrorToCard(err));
      },
      fail: () => cyToast('操作确认没有打开，请重试'),
    });
  },

  _applyErrorToCard(r) {
    if (r.dropTeam) { this._dropCurrent(); return; }
    if (r.patch) this._patchCurrent(r.patch);
  },

  withdraw(teamId, done) {
    this._post('/api/team/withdraw', { teamId: Number(teamId) }, 'withdraw',
      () => {
        const cur = this.data.current;
        if (cur && String(cur.teamId) === String(teamId)) this._patchCurrent({ viewerStatus: 'NONE' });
        if (done) done();
      },
      (r) => { if (r.refresh) this.refresh(this.data.center); if (done) done(); });
  },

  loadApplicants() {
    const cur = this.data.current;
    if (!cur) return;
    const that = this;
    app.sendRequest({
      url: '/api/team/applications', method: 'POST', hideLoading: true, silentError: true,
      data: JSON.stringify({ teamId: Number(cur.teamId) }), header: JSON_HEADER,
      success(res) {
        const now = that.data.current;
        if (!now || String(now.teamId) !== String(cur.teamId)) return;   // 已经切到别的队伍,旧列表丢弃
        if (!(res && res.code == 200 && Array.isArray(res.data))) { that._showFail(T.resolveTeamError('applications', res)); return; }
        that.setData({ applicants: T.applicantRows(res.data) });
      },
      fail(res) { that._showFail(T.resolveTeamError('applications', res)); },
    });
  },

  onHandle(e) {
    const cur = this.data.current;
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    if (!cur || !ds.id) return;
    const memberId = Number(ds.id);
    const approved = ds.ok === true || ds.ok === 'true';
    const drop = () => this.setData({ applicants: this.data.applicants.filter((a) => Number(a.memberId) !== memberId) });
    const submit = () => this._post('/api/team/handle', { teamId: Number(cur.teamId), memberId, approved }, 'handle',
      () => {
        drop();
        // 同意会让人数变、满员会让队伍从地图消失 —— 以服务端为准重拉
        if (approved) this.refresh(this.data.center);
      },
      (r) => { if (r.dropApplicant) drop(); if (r.refresh) this.refresh(this.data.center); });
    if (approved) { submit(); return; }
    // 拒绝 = 对方再也不能申请这支队伍,走危险确认(team.reject);同意照旧一点即成
    const who = (this.data.applicants || []).find((a) => Number(a.memberId) === memberId);
    modal.show({
      dangerKey: 'team.reject',
      dangerParams: { name: (who && who.name) || '这位玩家' },
      // 危险确认组件不认 key 时会退回普通确认框,这里给足兜底文案,别让队长面对一个空白框
      title: '拒绝这条申请?',
      content: '拒绝后对方不能再申请加入这支队伍。此操作不可撤销。',
      confirmText: '拒绝申请',
      danger: true,
      success: (r) => { if (r.confirm) submit(); },
    });
  },

  /* ---------- P6 我的队伍 ---------- */

  openMyTeams() {
    this._openSheet('mine');
    this.loadMyTeams(true);
  },

  /* 角控件计数也靠它:onLoad 静默拉一次(不弹失败),打开 P6 / 撤回后再拉。 */
  _myIdentity() { return [session.memberId(app), (app.globalData || {}).authorization || ''].join('|'); },
  loginForMyTeams() {
    if (this.data.myReadAccess === 'denied') return;
    return session.retryLogin(this, app, function () { this.loadMyTeams(true); });
  },
  loadMyTeams(showFail) {
    const owner = this._myIdentity();
    const seq = this._myReadSeq = (this._myReadSeq || 0) + 1;
    if (this._myReadOwner !== owner) this.setData({ myRows: [], myLoaded: false, myTeamsN: 0 });
    this._myReadOwner = owner;
    if (!session.memberId(app)) {
      this.setData({ myLoading: false, myReadAccess: 'login', myReadError: false }); return;
    }
    this.setData({ myLoading: true, myReadAccess: '', myReadError: false });
    const that = this;
    let joined = null; let apps = null; let pending = 2;
    const current = () => !that._unloaded && seq === that._myReadSeq && owner === that._myIdentity();
    const auth = (res, status) => {
      if (that._unloaded || seq !== that._myReadSeq) return true;
      if (owner !== that._myIdentity() || res && res.reason === 'sessionChanged') {
        that._myReadSeq += 1;
        that.setData({ myRows: [], myLoaded: false, myTeamsN: 0, myLoading: false,
          myReadAccess: session.memberId(app) ? '' : 'login', myReadError: false });
        if (!that._hidden && session.memberId(app)) that.loadMyTeams(showFail);
        return true;
      }
      const code = session.failureStatus(res, status);
      if (code !== 401 && code !== 403) return false;
      that._myReadSeq += 1;
      that.setData({ myRows: [], myLoaded: false, myTeamsN: 0, myLoading: false,
        myReadAccess: code === 401 ? 'login' : 'denied', myReadError: false });
      return true;
    };
    const settle = () => {
      if (!current()) return;
      pending -= 1;
      if (pending) return;
      if (joined == null && apps == null) {
        that.setData({ myLoading: false, myReadError: true });
        if (showFail) that._showFail(T.resolveTeamError('my', null));
        return;
      }
      const rows = T.myTeamRows(joined || [], apps || []);
      const complete = joined != null && apps != null;
      that.setData({ myRows: rows, myLoading: false, myLoaded: complete, myReadError: !complete,
        myTeamsN: complete ? rows.filter((r) => r.tone !== 'bad').length : 0 });
    };
    app.sendRequest({
      url: '/api/team/my', method: 'POST', hideLoading: true, silentError: true, data: JSON.stringify({}), header: JSON_HEADER,
      success(res) { if (auth(res)) return; if (res && res.code == 200 && Array.isArray(res.data)) joined = res.data; settle(); },
      fail(res, status) { if (!auth(res, status)) settle(); },
      successStatusAbnormal(res, status) { if (!auth(res, status)) settle(); },
    });
    app.sendRequest({
      url: '/api/team/my-applications', method: 'POST', hideLoading: true, silentError: true, data: JSON.stringify({}), header: JSON_HEADER,
      success(res) { if (auth(res)) return; if (res && res.code == 200 && Array.isArray(res.data)) apps = res.data; settle(); },
      fail(res, status) { if (!auth(res, status)) settle(); },
      successStatusAbnormal(res, status) { if (!auth(res, status)) settle(); },
    });
  },

  onMyRowAction(e) {
    if (this._navigating) return; const idx = Number(e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.index);
    const row = this.data.myRows[idx];
    if (!row) return;
    if (row.action.act === 'enter') { this._navigating = true; wx.navigateTo({ url: TEAM_PAGE + '?teamId=' + encodeURIComponent(row.teamId), complete: () => { this._navigating = false; } }); return; }
    if (row.action.act === 'nearby') { this.closeSheet(); return; }
    if (row.action.act === 'withdraw') this.withdraw(row.teamId, () => this.loadMyTeams(false));
  },

  /* ---------- 失败半屏(cy-result-sheet kind=fail) ---------- */

  _showFail(r) {
    this._resultAction = r.primaryAction || '';
    this.setData({ resultSheet: { show: true, kind: 'fail', title: r.title, why: r.why, primaryText: r.primary, secondaryText: r.secondary } });
  },
  onResultClose() { this._resultAction = ''; this.setData({ resultSheet: emptyResult() }); },
  onResultPrimary() {
    const act = this._resultAction;
    this.onResultClose();
    if (act === 'buy') this._goBuy(this.data.current);
  },

  /* ---------- 主题半屏 ---------- */

  openTopicCard(id) {
    const row = (this._items || []).find((it) => String(it.id) === String(id) && it.kind === 'topic');
    if (!row) { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: TOPIC_PAGE + '?id=' + encodeURIComponent(id), complete: () => { this._navigating = false; } }); return; }
    const free = Number(row.productType) === 2;
    this.setData({
      topic: {
        id: row.id,
        tag: free ? '自由探索' : '城市定向',
        tone: free ? 'free' : 'city',
        eyebrow: [row.addressName, H.fmtKm(row.distance)].filter(Boolean).join(' · '),
        title: row.name || '',
        desc: row.description || (free ? '不规定顺序、不要求一天跑完，只用一家也成立。' : '按顺序一站站走完。'),
        pics: [app.getImgUrl(row.picUrl || '')].filter(Boolean),
      },
    }, () => { this._openSheet('topic'); this._placeStrip(); });
  },
  openTopicPage() {
    const t = this.data.topic;
    if (this._navigating || !(t && t.id)) return; this._navigating = true; wx.navigateTo({ url: TOPIC_PAGE + '?id=' + encodeURIComponent(t.id), complete: () => { this._navigating = false; } });
  },

  /* ===== 2026-10-22 业主:三类点位 · 筛选行 · 点位卡(docs/design/动效界面落地_20261022 D 段) ===== */
  /* 地标 / 商家点位:与队伍、主题活动并列的第三类。读不到只是少一层点,不影响队伍与主题。 */
  fetchPois(center, radius) {
    const that = this;
    const seq = (this._poiSeq = (this._poiSeq || 0) + 1);
    app.sendRequest({
      url: '/api/roam/pois', method: 'GET', hideLoading: true, autoErrorToast: false,
      data: { lat: center.lat, lng: center.lng, radius },
      success(res) {
        if (that._unloaded || seq !== that._poiSeq) return;
        that._pois = res && res.code == 200 && isRecordList(res.data) ? res.data : [];
        that._syncLayerOptions(); that._syncMarkers();
        that._queueIcons(() => (!that._unloaded && seq === that._poiSeq) ? that._genPoiDots() : false).then((made) => { if (made && !that._unloaded) that._syncMarkers(); });
      },
      fail() { if (that._unloaded || seq !== that._poiSeq) return; that._pois = []; that._syncLayerOptions(); that._syncMarkers(); },
    });
  },

  _poiColors() { return { place: MAP_AVATAR_COLORS.freeExplore, shop: MAP_AVATAR_COLORS.merchant, plateText: MAP_AVATAR_COLORS.panel }; },

  _poiMarkers(f) {
    return L.buildPoiMarkers((this._pois || []).filter((p) => L.keepPoi(p, f)), this._lodCenter || this.data.center, this._far, this._poiColors(), H.HANGOUT_FALLBACK_ICON);
  },

  async _genPoiDots() {
    const c = this._poiColors();
    const need = [c.place, c.shop].filter((color) => !this._far[color]);
    if (!need.length) return false;
    let made = 0;
    try {
      const cv = await this._canvas();
      cv.width = 28; cv.height = 28;
      const g = cv.getContext('2d');
      for (const color of need) {
        if (this._unloaded) return false;
        try { M.drawFarDot(g, 28, color); this._far[color] = await this._save(cv); made++; } catch (e) { /* 兜底图 */ }
      }
    } catch (e) { /* canvas 拿不到:用兜底图 */ }
    return made > 0;
  },

  _layerFilter() { return { layer: this.data.layer || 'all', sub: this.data.layerSub || '' }; },

  _syncLayerOptions() { this._layerOptions = L.filterOptions(this._pois, this._items); },

  /* 筛选行:全部 / 主题队伍 直接切;地点 / 商家 先弹下拉选子类 */
  onLayerTap(e) {
    const layer = e.currentTarget.dataset.layer;
    if (layer === 'place' || layer === 'shop') {
      if (this.data.layerSheet === layer) { this.closeLayerSheet(); return; }
      this._syncLayerOptions();
      this._pendingLayer = layer;
      const opts = this._layerOptions[layer] || [];
      const cur = this.data.layer === layer ? this.data.layerSub : null;
      const count = (sub) => {
        const f = { layer, sub: sub || '' };
        return (this._items || []).filter((it) => L.keepItem(it, f)).length + (this._pois || []).filter((p) => L.keepPoi(p, f)).length;
      };
      const layerItems = opts.map((label, i) => ({ label, count: count(i ? label : ''), on: cur !== null && (i ? cur === label : !cur), hot: label === '活动' ? '本周' : '' }));
      this.setData({ layerSheet: layer, layerItems, mddIn: !!this.data.reducedMotion, mddLeft: 16 + (Number(e.currentTarget.offsetLeft) || 0) });
      setTimeout(() => { if (!this._unloaded && this.data.layerSheet === layer) this.setData({ mddIn: true }); }, 20);
      return;
    }
    // 2026-10-27 owner P-38:「全部」= 所有距离 —— 范围直接放到最大档(服务端上限 20 公里),再按全部类型显示
    if (layer !== 'team') {
      const maxIdx = T.RADII.length - 1;
      if (this._radius !== T.RADII[maxIdx]) {
        this.setData({ layer: 'all', layerSub: '', layerSheet: '' });
        this.onRangePick({ detail: { index: maxIdx } });
        return;
      }
    }
    this.setData({ layer: layer === 'team' ? 'team' : 'all', layerSub: '' }, () => this._syncMarkers());
  },
  onLayerPickTap(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const items = this.data.layerItems || [];
    const layer = this._pendingLayer || 'place';
    this.setData({ layer, layerSub: idx > 0 && items[idx] ? items[idx].label : '', layerSheet: '' }, () => this._syncMarkers());
  },
  closeLayerSheet() { this.setData({ layerSheet: '' }); },

  openPoiCard(hit) { this.openPinCard('poi', hit.id); },

  /* v13 点位卡内容:只放后端给的字段;没有的那一行不出 */
  _cardFor(kind, id) {
    const img = (u) => (u ? app.getImgUrl(u) : '');
    if (kind === 'team') {
      const row = (this._teams || []).find((t) => String(t.teamId) === String(id));
      if (!row) return null;
      const d = T.decorateTeam(row);
      const topic = (this._items || []).find((it) => it.kind === 'topic' && String(it.id) === String(row.topicId));
      const missing = (Number(row.maxMembers) || 0) - (Number(row.joinedCount) || 0);
      return { kind: 'team', id: row.teamId, chipLetter: '队', chipColor: '#0A84FF', chipText: '主题队伍', img: img(topic && topic.picUrl), // ds-ok v13 队伍蓝
        faces: String(row.memberAvatarsJoined || row.leaderAvatar || '').split(',').filter(Boolean).slice(0, 3).map(img), countText: d.joinedCount + '/' + d.maxMembers + ' 人',
        title: d.heading || d.name, subName: d.sub, metaLine: row.topicName ? '主题《' + row.topicName + '》' : '', metaStrong: missing > 0 ? '还差 ' + missing + ' 人' : '',
        address: row.addressName || '', desc: '', thumbs: [], rows: [], primary: '查看队伍', secondary: '' };
    }
    if (kind === 'poi') {
      const p = (this._pois || []).find((x) => String(x.id) === String(id));
      if (!p) return null;
      const c = L.poiCard(p), shop = c.kind === 'shop', tag = c.tags[0] || c.tag;
      return { kind: shop ? 'shop' : 'spot', id: c.id, chipLetter: tag.slice(0, 1), chipColor: shop ? '#FF9F0A' : '#34C759', chipText: tag, img: img(c.img), faces: [], countText: '', // ds-ok v13 商家橙 / 地点绿
        ribbon: '', title: c.title, subName: shop ? (p.merchantName || '') : c.tags.slice(1, 3).join(' · '), metaLine: c.address, metaStrong: c.found ? '到过' : '',
        address: c.address, desc: c.desc, thumbs: [], rows: [{ k: '类型', v: c.tags.join(' · ') || c.tag }].concat(c.address ? [{ k: '地址', v: c.address }] : []),
        // 官方地点(带玩法):背面展开就是完整详情 —— 相册左右滑、玩法、小红书、评价都在这张卡里,不再跳别的半屏
        official: !!p.templateId, game: null, reviews: [], reviewsLoaded: false, ratingText: '',
        primary: p.templateId ? '开始挑战' : '去这里', secondary: p.templateId ? '导航' : '', lat: c.lat, lng: c.lng };
    }
    const it = (this._items || []).find((x) => String(x.id) === String(id) && x.kind === kind);
    if (!it) return null;
    const topic = kind === 'topic', free = Number(it.productType) === 2;
    const where = [it.addressName, H.fmtKm(it.distance)].filter(Boolean).join(' · ');
    return { kind: 'spot', item: kind, id: it.id, chipLetter: topic ? (free ? '探' : '定') : '活', chipColor: topic ? (free ? '#34C759' : '#0A84FF') : '#FF375F', chipText: topic ? (free ? '自由探索' : '城市定向') : '热门活动', // ds-ok v13 类型色
      img: img(it.picUrl), faces: [], countText: '', ribbon: '', title: it.name || '', subName: '', metaLine: where, metaStrong: '',
      address: it.addressName || '', desc: it.description || '', thumbs: [], rows: where ? [{ k: '位置', v: where }] : [], primary: '查看详情', secondary: '', lat: H.numOrNull(it.latitude), lng: H.numOrNull(it.longitude) };
  },

  /* 点开:地图把点位放到卡片尾巴下面(卡片停在屏幕正中),卡片同时长出来 */
  openPinCard(kind, id) {
    const card = this._cardFor(kind, id);
    if (!card) { // 本地没有这一行(主题 / 活动):直接进详情,连点只跳一次
      if ((kind === 'topic' || kind === 'activity') && !this._navigating) { this._navigating = true; wx.navigateTo({ url: (kind === 'topic' ? TOPIC_PAGE : ACTIVITY_PAGE) + '?id=' + encodeURIComponent(id), complete: () => { this._navigating = false; } }); }
      return;
    }
    if (this._poiInTimer) clearTimeout(this._poiInTimer);
    let win = { windowHeight: 667 };
    try { win = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync(); } catch (e) { /* 用默认高度 */ }
    const h = win.windowHeight || 667, cardH = Math.round(608 * (win.windowWidth || 375) / 750); // 卡高 608rpx 换成 px
    const pcTop = Math.round(h / 2 - cardH / 2);
    const m = (this.data.markers || []).find((x) => x.id === this._lastMarkerId);
    if (m) {
      const below = pcTop + cardH + 14 + 30 - h / 2; // 点位要落在卡片尾巴下面,离屏幕中心这么多像素
      const mpp = 156543.03 * Math.cos(m.latitude * Math.PI / 180) / Math.pow(2, Number(this.data.scale) || 14);
      this.setData({ center: { lat: m.latitude + below * mpp / 111320, lng: m.longitude } });
    }
    this._openPin = this._lastMarkerId; this._pcBox = { top: pcTop, h: Math.round(608 * (win.windowWidth || 375) / 750), w: Math.round(558 * (win.windowWidth || 375) / 750), W: win.windowWidth || 375, H: h };
    this.setData({ poiCard: card, poiCardIn: false, poiFlip: false, poiExpand: false, pcClip: '', sheet: 'poi', pcTop }); this._syncMarkers(); // 点开的那颗点位藏起来:卡片就是它长出来的
    this._poiInTimer = setTimeout(() => { if (!this._unloaded) this.setData({ poiCardIn: true }); }, 30);
    this.setData({ poiPhoto: 0 });
    if (card.official) this._loadSpot(card.id);
    const request = {}; this._poiGalleryRequest = request;
    const poi = kind === 'poi' && (this._pois || []).find((row) => String(row.id) === String(id));
    const url = poi && L.poiGalleryPath(poi.coverImg);
    if (url) {
      const identity = this._myIdentity();
      app.sendRequest({ url, method: 'GET', auth: false, hideLoading: true, silentError: true, autoErrorToast: false,
        success: (res) => {
          if (this._unloaded || this._poiGalleryRequest !== request || this._myIdentity() !== identity
              || this.data.sheet !== 'poi' || !this.data.poiCard || String(this.data.poiCard.id) !== String(id)) return;
          const gallery = res && res.code === 200 && L.poiGallery(res.data, id, poi.coverImg);
          if (gallery && this.data.poiCard.official) { if (gallery.credit) this.setData({ 'poiCard.photoCredit': gallery.credit }); return; } // 官方地点的相册以节点 galleryJson 为准
          if (gallery && gallery.keys.length) this.setData({
            'poiCard.thumbs': gallery.keys.map((key) => app.getImgUrl(key)).filter(Boolean), 'poiCard.photoCredit': gallery.credit,
          });
        },
      });
    }
  },
  /* 点正面:队伍进原来的队伍半屏;其余翻到背面 */
  onPoiFront() {
    const c = this.data.poiCard;
    if (c && c.kind === 'team') { const id = c.id; if (this._poiInTimer) { clearTimeout(this._poiInTimer); this._poiInTimer = null; } this._openPin = null; this.setData({ poiCard: null, poiCardIn: false, poiExpand: false, pcClip: '', sheet: '' }); this._syncMarkers(); this.openTeam(id); return; }
    this.onPoiFlip();
  },
  onPcStart(e) { const t = e.touches && e.touches[0]; this._pcY = t ? t.clientY : null; },
  onPcEnd(e) {
    const t = e.changedTouches && e.changedTouches[0];
    if (this._pcY == null || !t) return;
    const dy = t.clientY - this._pcY; this._pcY = null;
    if (dy < -40 && this.data.poiFlip && !this.data.poiExpand) this._expand(true);
    else if (dy > 60 && this.data.poiExpand) this._expand(false);
  },
  onPoiPrimary() {
    const c = this.data.poiCard;
    if (!c) return;
    if (c.kind === 'team') { this.onPoiFront(); return; }
    if (c.item === 'topic' || c.item === 'activity') {
      if (this._navigating) return; this._navigating = true;
      wx.navigateTo({ url: (c.item === 'topic' ? TOPIC_PAGE : ACTIVITY_PAGE) + '?id=' + encodeURIComponent(c.id), complete: () => { this._navigating = false; } });
      return;
    }
    if (c.official) { this._startChallenge(); return; }
    this.onPoiNavigate();
  },
  onPoiSecondary() { this.onPoiNavigate(); },

  /* 官方地点:节点详情(相册 / 玩法 / 完成状态 / 评分)+ 评价。只写回仍开着的同一张卡 */
  _loadSpot(id) {
    const live = () => !this._unloaded && this.data.poiCard && this.data.poiCard.official && String(this.data.poiCard.id) === String(id);
    app.sendRequest({ url: '/api/city/nodes/' + id, method: 'GET', hideLoading: true, silentError: true, autoErrorToast: false,
      success: (res) => {
        const n = res && res.code == 200 && res.data;
        if (!live() || !n) return;
        let keys = [];
        try { keys = JSON.parse(n.galleryJson || '[]'); } catch (e) { keys = []; }
        const thumbs = (Array.isArray(keys) ? keys : []).filter((k) => typeof k === 'string' && k).map((k) => app.getImgUrl(k)).filter(Boolean);
        const steps = [n.photoRequired ? '到场拍照' : '', n.validationMethod === 3 ? '观察答题' : '', n.badgeCode ? '收藏勋章' : ''].filter(Boolean);
        this.setData({
          'poiCard.thumbs': thumbs.length ? thumbs : this.data.poiCard.thumbs,
          'poiCard.img': this.data.poiCard.img || thumbs[0] || '',
          'poiCard.game': { title: n.templateTitle || '', desc: n.templateDesc || '', steps, completed: n.completed === true, radiusM: Number(n.radiusM) || 80 },
          'poiCard.quotes': L.poiQuotes(n.quotesJson),
          'poiCard.photoCredit': typeof n.photoCredit === 'string' ? n.photoCredit.slice(0, 1000) : this.data.poiCard.photoCredit,
          'poiCard.ratingText': n.ratingCount ? '★ ' + Number(n.averageRating || 0).toFixed(1) + ' · ' + n.ratingCount + ' 条评价' : '',
          'poiCard.metaStrong': n.completed === true ? '已完成挑战' : this.data.poiCard.metaStrong,
          'poiCard.primary': n.completed === true ? '投一张换一张' : '开始挑战',
        });
      },
    });
    const owner = String(app.getUserID ? app.getUserID() || '' : '');
    app.sendRequest({ url: '/api/comment/list', method: 'POST', hideLoading: true, silentError: true, autoErrorToast: false,
      data: { owner_type: 4, owner_id: id, pageNum: 1, pageSize: 10 },
      success: (res) => {
        const rows = res && res.code == 200 && res.data && res.data.rows;
        if (!live()) return;
        const list = Array.isArray(rows) ? rows : [];
        this.setData({ 'poiCard.reviewsLoaded': Array.isArray(rows), 'poiCard.reviewed': list.some((r) => String(r.memberId) === owner),
          'poiCard.reviews': list.map((r) => ({ id: r.id, name: r.memberNickname || '玩家', avatar: r.memberIcon ? app.getImgUrl(r.memberIcon) : '',
            stars: Number(r.rating) || 0, text: r.contents || '', time: String(r.createTime || '').slice(0, 10) })) });
      },
    });
  },
  /* 开始挑战:先在卡里确认到没到,不到就在卡上写一句,不弹窗;到了进挑战页(拍照 / 答题 / 勋章各一屏) */
  _startChallenge() {
    const c = this.data.poiCard;
    if (!c || this._navigating) return;
    if (c.game && c.game.completed) { // 完成过:接原来的「投一张换一张」城市签
      this._navigating = true;
      wx.navigateTo({ url: '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent(c.title), complete: () => { this._navigating = false; } });
      return;
    }
    this.setData({ 'poiCard.hint': '正在确认你的位置…' });
    wx.getLocation({ type: 'gcj02',
      success: (loc) => {
        const cur = this.data.poiCard;
        if (this._unloaded || !cur || String(cur.id) !== String(c.id)) return;
        const d = L.distanceM({ lat: loc.latitude, lng: loc.longitude }, { lat: c.lat, lng: c.lng });
        const near = ((cur.game && cur.game.radiusM) || 80) + 30;
        if (!(d <= near)) { this.setData({ 'poiCard.hint': '还没到：离这里约 ' + (d >= 1000 ? (d / 1000).toFixed(1) + ' 公里' : Math.round(d) + ' 米') + '，走到附近再开始' }); return; }
        this.setData({ 'poiCard.hint': '', camOpen: true }); // 到了:取景卡直接在地图上弹出
      },
      fail: () => { if (!this._unloaded) this.setData({ 'poiCard.hint': '需要定位才能确认你到了这里' }); },
    });
  },
  /* 定位 / 相机触发隐私授权时在本页弹 gate,不被隐私页整页盖住 */
  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },
  closeSpotCam() { this.setData({ camOpen: false }); },
  /* 拍到一张:先传上去拿地址,再进整屏玩法(答题 → 勋章);上传失败回到卡片,卡上写原因 */
  onSpotShot(e) {
    const path = (e && e.detail && e.detail.path) || '';
    const c = this.data.poiCard;
    this.setData({ camOpen: false });
    if (!path || !c || this._uploading) return;
    this._uploading = true;
    loading.show('正在上传');
    app.getUploadClient().uploadAll([path], {
      onDone: (r) => {
        this._uploading = false;
        loading.hide();
        if (this._unloaded) return;
        const url = r && r.ok && r.results && r.results[0];
        if (!url) { this.setData({ 'poiCard.hint': '照片没有传上去，再拍一次试试' }); return; }
        this._navigating = true;
        wx.navigateTo({ url: '/pages/play/poi-challenge/index?poiId=' + encodeURIComponent(c.id) + '&photo=' + encodeURIComponent(url),
          complete: () => { this._navigating = false; } });
      },
    });
  },
  /* 小红书:小程序打不开外站,复制地点名去小红书搜 */
  onPoiXhs() {
    const c = this.data.poiCard;
    if (!c) return;
    wx.setClipboardData({ data: c.title, success: () => cyToast('已复制「' + c.title + '」，去小红书搜索看看笔记') });
  },
  /* 公开引述:小程序打不开外站,点一下复制原文链接,去浏览器看出处 */
  onPoiQuote(e) {
    const q = ((this.data.poiCard || {}).quotes || [])[Number(e.currentTarget.dataset.index)];
    if (q && q.url) wx.setClipboardData({ data: q.url, success: () => cyToast('原文链接已复制，可在浏览器打开') });
  },
  onPoiSwipe(e) { this.setData({ poiPhoto: (e && e.detail && e.detail.current) || 0 }); },
  onReviewStar(e) { this.setData({ 'poiCard.myStars': Number(e.currentTarget.dataset.n) || 0 }); },
  onReviewInput(e) { this.setData({ 'poiCard.myText': e.detail.value }); },
  onReviewSubmit() {
    const c = this.data.poiCard;
    if (!c || c.reviewBusy) return;
    const text = String(c.myText || '').trim();
    if (!(c.myStars >= 1)) { cyToast('先点星星打个分'); return; }
    if (!text) { cyToast('写一句游玩感受'); return; }
    this.setData({ 'poiCard.reviewBusy': true });
    const done = (ok, msg) => {
      if (this._unloaded || !this.data.poiCard || String(this.data.poiCard.id) !== String(c.id)) return;
      this.setData({ 'poiCard.reviewBusy': false });
      if (!ok) { cyToast(msg || '评价没有发出去，请重试'); return; }
      this.setData({ 'poiCard.myText': '', 'poiCard.myStars': 0 }); cyToast('评价已发布'); this._loadSpot(c.id);
    };
    app.sendRequest({ url: '/api/comment/add', method: 'POST', hideLoading: true, silentError: true, autoErrorToast: false,
      data: { owner_type: 4, owner_id: c.id, rating: c.myStars, contents: text, reply_id: 0 },
      success: (res) => done(res && res.code == 200, res && res.msg),
      fail: () => done(false), successStatusAbnormal: (res) => done(false, res && res.msg) });
  },
  onPoiImagePreview(e) { mediaPreview.open((this.data.poiCard || {}).thumbs || [], Number(e.currentTarget.dataset.index)); },
  onPoiPhotoCredit() { if (this.data.poiCard) this.setData({ 'poiCard.creditOpen': !this.data.poiCard.creditOpen }); },
  onPoiFlip() { this.setData({ poiFlip: !this.data.poiFlip, poiExpand: false }); },
  onPoiExpand() { this._expand(!this.data.poiExpand); },
  /* 展开 / 收起:卡片先按大小切到目标版式,再用裁切从原卡位置放开(或收回到原卡位置),看起来是卡在长大,
     不去逐帧动 top / width / height(那些属性每帧重排) */
  _expand(on) {
    const b = this._pcBox, hud = this.data.hudTop, bottom = this.data.safeBottom + 20;
    if (!b || this.data.reducedMotion) { this.setData({ poiExpand: on, pcClip: '' }); return; }
    const inset = 'clip-path: inset(' + Math.max(0, b.top - hud) + 'px ' + Math.round((b.W - b.w) / 2 - 12) + 'px ' + Math.max(0, (b.H - bottom) - (b.top + b.h)) + 'px ' + Math.round((b.W - b.w) / 2 - 12) + 'px round 21px);'
    clearTimeout(this._pcTimer);
    if (on) {
      this.setData({ poiExpand: true, pcClip: inset + 'transition: none;' });
      this._pcTimer = setTimeout(() => this.setData({ pcClip: 'clip-path: inset(0 round 21px);' }), 30);
    } else {
      this.setData({ pcClip: inset });
      this._pcTimer = setTimeout(() => this.setData({ poiExpand: false, pcClip: '' }), 420);
    }
  },
  closePoiCard() {
    this._poiGalleryRequest = null;
    if (this._poiInTimer) { clearTimeout(this._poiInTimer); this._poiInTimer = null; }
    this.setData({ poiCardIn: false, poiExpand: false, pcClip: '' });
    this._poiInTimer = setTimeout(() => { if (this._unloaded) return; this._openPin = null; this.setData({ poiCard: null, sheet: this.data.sheet === 'poi' ? '' : this.data.sheet }); this._syncMarkers(); }, 460); // 卡缩回点位后点位再出现
  },
  onPoiNavigate() {
    const c = this.data.poiCard;
    if (!c || c.lat == null || c.lng == null) return;
    wx.openLocation({ latitude: c.lat, longitude: c.lng, name: c.title, address: c.address, scale: 17 });
  },

  /* 主题照片条贴在半屏上沿:本页半屏高随内容走,共用样式里按 70vh 算的位置会飘到筛选行上 */
  _placeStrip() {
    if (typeof wx.createSelectorQuery !== 'function') return;
    setTimeout(() => {
      if (this._unloaded) return;
      try {
        wx.createSelectorQuery().in(this).select('.psheet--half').boundingClientRect((r) => {
          if (!r || this._unloaded) return;
          this.setData({ tstripStyle: 'bottom:' + Math.round(r.height + 10) + 'px;' }); // 半屏贴底,用高度不受滑入动画影响
        }).exec();
      } catch (e) { /* 量不到就用共用样式的位置 */ }
    }, 60);
  },
});
