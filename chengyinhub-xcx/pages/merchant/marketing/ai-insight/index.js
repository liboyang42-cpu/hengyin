const app = getApp();
const merchantTheme = require('../../../../utils/merchant-theme.js');
const { merchantHomeUrl } = require('../../../../utils/merchant-home-link.js');
const { toTimestamp, chinaParts } = require('../../../../utils/datetime.js');
const { failureStatus, retryLogin, recoverReadSession } = require('../../../../utils/session/login-reload.js');
const { readReducedMotion } = require('../../../../utils/motion-preference.js');

// 推荐主题 / 空态引导都去合作中心申请承接。
// 2026-09-18 UI-11:按用户稿删掉「建议活动」区块后,suggestion.type 路由表随之退场。
const COOP_CENTER_ROUTE = '/pages/merchant/coop-center/index';

// 2026-10-22 owner:主卡可切 近7天/近30天/近90天,默认近30天(与下方核销率等 facts 同窗)
const TREND_WINDOWS = [7, 30, 90];
const TREND_METRICS = [
  { key: 'visitors', label: '到店', unit: '位玩家到店', short: '位' },
  { key: 'checkins', label: '打卡', unit: '次打卡', short: '次' },
  { key: 'redeems', label: '核销', unit: '单核销', short: '单' },
];
// 数字滚动:慢—快—慢(ease-in-out),与工作台累计收入同一手感
const COUNT_MS = 900;
const COUNT_STEPS = 24;
// 进度格点亮:首格延迟 400ms(等卡片模糊入场)+ 每格 45ms + 300ms 过渡 ≈ 1105ms 亮满,再停一拍退回
const PIP_SWEEP_HOLD_MS = 1300;
// 入场期长度:最后一张主题卡 600ms 延迟 + 750ms 飞入,之后撤掉飞入过渡
const LANDING_MS = 1500;
// 卡片 → 底部弹层的形变时长,与 wxss .ts-ghost transition 对齐
const MORPH_MS = 430;

function easeInOut(t) { return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; }

function numberOrNull(value) {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

/** CU-M-85:场次行只写到月日,与合作广场路线卡同一写法(coop-center/index.js 的 monthDay)。 */
function monthDay(value) {
  const timestamp = toTimestamp(value);
  const parts = timestamp ? chinaParts(timestamp) : null;
  return parts ? parts.month + '月' + parts.day + '日' : '';
}

// 主题卡底栏用短日期「10/20」,给主办方名留位置
function slashDay(value) {
  const timestamp = toTimestamp(value);
  const parts = timestamp ? chinaParts(timestamp) : null;
  return parts ? parts.month + '/' + parts.day : '';
}

// 逐日格起点标签,与后端 trends.daily[].date 同格式(MM-dd,中国时间)
function dayLabelDaysAgo(days) {
  const parts = chinaParts(Date.now() - days * 86400000);
  return parts ? String(parts.month).padStart(2, '0') + '-' + String(parts.day).padStart(2, '0') : '';
}

function isAccessDenied(res) {
  const code = res && (res.code !== undefined ? res.code : res.statusCode);
  return String(code) === '401' || String(code) === '403';
}

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    loading: false,
    error: '',
    errorIsDenied: false,
    errorNeedsLogin: false,
    facts: null,          // 后端聚合事实包(必出层)
    ai: null,             // AI 解读(可为 null 的降级层)
    // aiError 已撤(2026-10-23,见 load 成功分支注释)
    generatedAt: '',
    storeName: '',        // 页头问候「Hi，店名」
    storeLogo: '',        // 页头店铺头像(商家 Logo)
    factState: 'unknown', // unknown=字段缺失 | empty=服务端明确全零 | ready=有真实事实
    valueCard: null,      // 首屏归因价值卡:城瘾为你带来 N 位玩家/M 次打卡/K 单核销 + 核销进度 + 环比
    metricCards: [],      // 核销率 / 复购率两张小卡:比率 + 说明行 + 分段胶囊进度条
    split: null,          // 新客 vs 回头客双段横条
    recentVisitors: [],   // Blackbird 式单客行(后端已格式化)
    recTopics: [],        // 推荐主题(真实招商中主题,可点去申请;AI 挂时无理由兜底)
    recPartners: [],      // 推荐联动商家(真实附近商家,可点看店铺)
    win: 30,              // 主卡时间窗
    winIdx: 1,
    hero: null,           // 主卡:大数字 + 环比 + 逐日格 + 三指标;无 trends(旧缓存)时退回 30 天归因三数
    slots: null,          // 到店时段四柱(facts.checkin.slotBuckets)
    sources: [],          // 客源主题排行(facts.sources)
    grown: false,         // 柱子/进度条从 0 长到位的开关(数据落地后下一拍打开)
    shown: false,         // 区块模糊入场的开关(类切换 + transition,不新增 @keyframes)
    pipSweep: false,      // 核销进度 10 格:每次打开先整排点亮一遍,再退回真实格数(零也要有这一下)
    landing: false,       // 入场期:主题卡/商家卡只在这段时间带飞入过渡,结束后按压反馈不受延迟拖累
    replay: false,        // 重播入场的复位帧:先无过渡回到隐藏态,下一拍再飞入
    reducedMotion: false, // 「减少动态效果」:不滚数字、不形变,只保留淡入
    scrimOn: false,
    sheetTopic: null,     // 推荐主题详情弹层
    sheetPhase: '',       // '' | measure | open | closing
    ghostStyle: '',       // 卡片形变成弹层的过渡块
  },

  onLoad() {
    this._skipNextShowReload = true;
    const gd = app.globalData || {};
    const sys = wx.getSystemInfoSync();
    this.setData({
      statusBarHeight: gd.statusBarHeight || sys.statusBarHeight || 20,
      navBarHeight: gd.navBarHeight || 44,
    });
    this.load();
  },

  onShow() {
    this._hidden = false;
    const reducedMotion = readReducedMotion();
    if (this.data.reducedMotion !== reducedMotion) this.setData({ reducedMotion });
    merchantTheme.merchantPageShow();
    // 首次 onShow 紧跟 onLoad，不重复请求；以后每次返回都让服务端重新确认商家资格。
    const memberId = String(typeof app.getUserID === 'function' ? (app.getUserID() || '') : '');
    const scopeChanged = this._dataMemberId !== undefined && this._dataMemberId !== memberId;
    if (scopeChanged) {
      this._skipNextShowReload = false;
      this.load();
      return;
    }
    if (this._skipNextShowReload) {
      this._skipNextShowReload = false;
      return;
    }
    // owner 2026-10-23:每次打开都要有入场(卡片飞入),不只首次;旧数据先播,新数据回来再静默更新
    this._replayIn();
    this.load();
  },
  onHide() {
    this._hidden = true;
    this._requestEpoch = (this._requestEpoch || 0) + 1;
    this._stopMotion();
    // 扫到一半离开:下次回来从真实格数重新扫,不停在整排点亮(卸载时不再写页)
    if (this.data.pipSweep) this.setData({ pipSweep: false });
    merchantTheme.merchantPageRestore();
  },
  onUnload() {
    this._unloaded = true;
    this._stopMotion();
    this._requestEpoch = (this._requestEpoch || 0) + 1;
    merchantTheme.merchantPageRestore();
  },

  onNavBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/merchant/marketing/index' }); },

  load() {
    if (this._hidden || this._unloaded) return;
    const that = this;
    const requestEpoch = (this._requestEpoch || 0) + 1;
    this._requestEpoch = requestEpoch;
    const memberId = String(typeof app.getUserID === 'function' ? (app.getUserID() || '') : '');
    const scopeChanged = this._dataMemberId !== undefined && this._dataMemberId !== memberId;
    this._dataMemberId = memberId;
    const isCurrent = function () {
      const currentMemberId = String(typeof app.getUserID === 'function' ? (app.getUserID() || '') : '');
      return !that._hidden && !that._unloaded && requestEpoch === that._requestEpoch && memberId === currentMemberId;
    };
    if (scopeChanged) that._clearSensitiveState('');
    if (!memberId) {
      that._clearSensitiveState('请先登录后查看店铺数据');
      that.setData({ loading: false, errorIsDenied: true, errorNeedsLogin: true });
      return;
    }
    that.setData({ loading: true, error: '', errorIsDenied: false, errorNeedsLogin: false });
    const handleFailure = (res, status) => {
      if (that._hidden || that._unloaded || requestEpoch !== that._requestEpoch) return;
      if (recoverReadSession(that, app, res, function (anonymous) {
        this._requestEpoch++;
        this._clearSensitiveState(anonymous ? '请重新登录后查看店铺数据' : '');
        this.setData({ loading: false, errorIsDenied: anonymous, errorNeedsLogin: anonymous });
      }, that.load, status)) return;
      if (!isCurrent()) return;
      const code = failureStatus(res, status);
      if (code === 401 || code === 403) {
        that._clearSensitiveState(app.getRequestErrorMessage(res,
          code === 401 ? '请重新登录后查看店铺数据' : '当前账号没有店铺数据查看权限'));
        that.setData({ loading: false, errorIsDenied: true, errorNeedsLogin: code === 401 });
      } else that.setData({ error: app.getRequestErrorMessage(res, '店铺数据加载失败') });
    };
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/ai/merchant/insight',
      method: 'POST',
      success(res) {
        if (!isCurrent()) return;
        if (isAccessDenied(res)) { handleFailure(res); return; }
        const listsValid = res && res.data && ['recentVisitors', 'recommendedTopics', 'recommendedPartners']
          .every(function (key) {
            return res.data[key] == null || (Array.isArray(res.data[key])
              && res.data[key].every(function (item) {
                return item && typeof item === 'object' && !Array.isArray(item);
              }));
          });
        if ((res.code === '200' || res.code === 200) && res.data && res.data.facts && listsValid) {
          const d = res.data;
          const attribution = d.facts.attribution || {};
          const attributionCounts = [attribution.visitors, attribution.checkins, attribution.redeems]
            .map(numberOrNull);
          const factsKnown = attributionCounts.every(function (value) { return value !== null; });
          const factState = !factsKnown ? 'unknown'
            : (attributionCounts.some(function (value) { return value > 0; }) ? 'ready' : 'empty');
          const profile = d.profile || {};
          const firstPaint = !that.data.facts;
          // 返回页面重播入场时,新数据若在数字滚完前就到,接着从 0 滚到新值,不直接跳数
          const replaying = !firstPaint && that._replayAt > 0 && Date.now() - that._replayAt < 1500;
          that._trends = that._indexTrends(d.trends);
          const hero = that._buildHero(d.facts, that.data.win, that._metric || 'visitors', firstPaint || replaying);
          that.setData({
            facts: d.facts,
            ai: d.ai || null,
            // aiError 不再下发:2026-10-23 业主「没有就不显示」,唯一消费方 AI 降级卡已删(占位保留行号锚点)
            generatedAt: d.generatedAt || '',
            storeName: String(profile.storeName || '').trim(),
            storeLogo: String(profile.logo || '').trim(),
            factState,
            hero,
            slots: that._buildSlots(d.facts),
            sources: that._buildSources(d.facts),
            valueCard: that._buildValueCard(d.facts, that.data.win),
            metricCards: that._buildMetricCards(d.facts),
            split: that._buildSplit(d.facts),
            recentVisitors: Array.isArray(d.recentVisitors) ? d.recentVisitors : [],
            recTopics: that._decorateTopics(Array.isArray(d.recommendedTopics) ? d.recommendedTopics : []),
            recPartners: (Array.isArray(d.recommendedPartners) ? d.recommendedPartners : []).map(function (p) {
              const m = Number(p.distanceM);
              const distanceText = !Number.isFinite(m) || m <= 0 ? ''
                : (m < 1000 ? Math.round(m) + 'm' : (Math.round(m / 100) / 10) + 'km');
              return Object.assign({}, p, {
                // 稿 3:9「书店 · 450m」
                metaText: [p.category, distanceText].filter(Boolean).join(' · '),
                // 没有 logo 时圆形头像里放店名首字(稿 3:4)
                initial: String(p.name || '').trim().charAt(0),
              });
            }),
          });
          // 回到页面的静默刷新不重播:数字直接落位,只有首帧入场才滚动
          if (firstPaint) that._playIn();
          else if (replaying) that._countTo(hero.target);
          // owner 2026-10-23:核销进度条每次打开都扫一遍,不因暂无核销就一动不动
          that._sweepPips();
          // M-21(2026-10-29 复查):参谋包里缺店名/Logo 时(旧后端或商家行没按会员号查到),
          // 补读首页与「我的」同一来源 /api/merchant/info,不再留空「Hi」
          if (!that.data.storeName || !that.data.storeLogo) that._loadStoreIdentity(isCurrent);
        } else if (res && res.code !== '200' && res.code !== 200) {
          that._clearSensitiveState(app.getRequestErrorMessage(res, '商家资格已失效'));
        } else {
          that.setData({ error: app.getRequestErrorMessage(res, '店铺数据加载失败') });
        }
      },
      fail(res, status) { handleFailure(res, status); },
      successStatusAbnormal: handleFailure,
      complete() { if (isCurrent()) that.setData({ loading: false }); },
    });
  },

  _clearSensitiveState(error) {
    this.setData({
      error: error || '',
      facts: null,
      ai: null,
      // aiError 已撤(2026-10-23)
      generatedAt: '',
      storeName: '',
      storeLogo: '',
      factState: 'unknown',
      valueCard: null,
      metricCards: [],
      split: null,
      recentVisitors: [],
      recTopics: [],
      recPartners: [],
      hero: null,
      slots: null,
      sources: [],
      grown: false,
      shown: false,
      pipSweep: false,
      landing: false,
      replay: false,
      sheetTopic: null,
      sheetPhase: '',
      ghostStyle: '',
      scrimOn: false,
    });
    this._trends = null;
    this._metric = null;
    this._stopMotion();
  },

  _loadStoreIdentity(isCurrent) {
    const that = this;
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/info',
      method: 'POST',
      success(res) {
        if (!isCurrent() || !res || (res.code !== '200' && res.code !== 200) || !res.data) return;
        that.setData({
          storeName: that.data.storeName || String(res.data.name || '').trim(),
          storeLogo: that.data.storeLogo || String(res.data.logo || '').trim(),
        });
      },
      // 补读失败只轻提示一次,问候区保持现状(经营数据照常可看)
      fail(res) { if (isCurrent()) app.showToast({ title: app.getRequestErrorMessage(res, '店铺名称和 Logo 没能取回') }); },
      successStatusAbnormal(res) { if (isCurrent()) app.showToast({ title: app.getRequestErrorMessage(res, '店铺名称和 Logo 没能取回') }); },
    });
  },

  retry() {
    if (!this.data.errorNeedsLogin) { this.load(); return; }
    const epoch = this._requestEpoch;
    const owner = String(app.getUserID() || '');
    return retryLogin(this, app, function () {
      if (epoch === this._requestEpoch && (!owner || owner === String(app.getUserID() || ''))) this.load();
    });
  },

  // 2026-10-27 业主 M-21(0.3 冲突裁决,取代 10-23 D4 的「比率位写—」):数字不要用「-」代替,没有就是 0。
  // 比率分母为 0 / 缺失时写 0%;「样本太少」由 facts.lowSample 提示与卡上的 scope 行说实话。
  _pct(v) {
    const value = numberOrNull(v);
    return value === null ? '0%' : Math.round(value * 100) + '%';
  },

  _countText(v) {
    const value = numberOrNull(v);
    return value === null ? '0' : String(value);
  },

  // 小卡细进度条宽度(0–100);比率缺失按 0 宽,数字位另有「0%」与口径行说明
  _fill(rate) {
    const value = numberOrNull(rate);
    return value === null ? 0 : Math.max(0, Math.min(100, Math.round(value * 100)));
  },

  // 分段胶囊进度:10 枚,rate null(样本不足)→ 全空虚线胶囊,不冒充 0
  _segs(rate) {
    const value = numberOrNull(rate);
    const filled = value === null ? 0 : Math.max(0, Math.min(10, Math.round(value * 10)));
    const segs = [];
    for (let i = 0; i < 10; i++) segs.push({ on: i < filled });
    return segs;
  },

  // 稿 1:39 两张小卡:核销率(打卡数 · 平均等待) / 复购率(核销顾客数)。
  // CU-M-46 / CU-M-84(2026-09-23 用户裁决 A):两个比率各有自己的总体和时间窗,
  // 并排显示而不写清楚会被读成互相矛盾。「N 位回头」原本按**跨窗口**口径算
  // (窗口前也到过店),与百分比的分母(窗口内核销会员)根本不是一批人 ⇒ 从这张卡撤掉,
  // 跨窗口的回头客只留在下面「新客 vs 回头客」横条里。副行改用与百分比同一批人。
  _buildMetricCards(facts) {
    const c = (facts && facts.checkin) || {};
    const window = (facts && facts.window) || '近30天';
    const wait = numberOrNull(c.avgWaitMinutes);
    return [
      { key: 'redeem', name: '核销率', num: this._pct(c.redeemRate), pct: this._fill(c.redeemRate),
        sub: this._countText(c.total) + ' 次打卡' + (wait === null ? '' : ' · 平均等待 ' + wait + ' 分钟'),
        scope: '计算方式：已核销报名数 ÷ ' + window + '内报名数' },
      { key: 'repeat', name: '复购率', num: this._pct(c.repeatRate), pct: this._fill(c.repeatRate),
        sub: '基于 ' + this._countText(facts && facts.sampleMembers) + ' 位核销顾客',
        scope: '计算方式：核销≥2次的会员 ÷ ' + window + '内核销会员' },
    ];
  },

  // 首屏归因价值卡(调研共性规律#1:面板第一职责是「平台为你带来了什么」)
  _buildValueCard(facts, win) {
    const a = (facts && facts.attribution) || {};
    const base = {
      visitors: this._countText(a.visitors),
      checkins: this._countText(a.checkins),
      redeems: this._countText(a.redeems),
    };
    // 有 trends:进度条跟主卡所选 7/30/90 天走,= 该窗口核销次数 ÷ 打卡次数(只核销没打卡的封顶满格),
    // 下行直接写这两个数,不另起一个和下方「核销率」卡口径不同的百分比。
    const w = this._trends && this._trends[win];
    if (w) {
      const checkins = numberOrNull(w.checkins) || 0;
      const redeems = numberOrNull(w.redeems) || 0;
      const label = '近' + win + '天';
      return Object.assign(base, {
        segs: this._segs(checkins > 0 ? Math.min(1, redeems / checkins) : (redeems > 0 ? 1 : null)),
        caption: checkins + redeems === 0 ? label + '暂无打卡核销' : label + '打卡 ' + checkins + ' 次 · 核销 ' + redeems + ' 次',
      });
    }
    // 旧缓存没有 trends:沿用 facts 窗口的核销率
    const rate = numberOrNull(((facts && facts.checkin) || {}).redeemRate);
    const caption = (facts && facts.window) || '近30天';
    return Object.assign(base, {
      segs: this._segs(rate),
      caption: rate === null ? '' : caption + '核销率 ' + this._pct(rate), // 业主 10-23:没有就不显示(不出「暂无核销」提示,也不拿 0% 冒充)
    });
  },

  // 新客 vs 回头客双段横条(Square loyalty vs non-loyalty 的内部对比版)
  // CU-M-46:回头客是**跨窗口**口径(窗口内到店 ∩ 窗口前也到过店),这句话必须写在卡上 ——
  // 数字位本身看不出时间窗,同一张屏上的复购率又是别的总体。
  _buildSplit(facts) {
    const s = (facts && facts.split) || {};
    const window = (facts && facts.window) || '近30天';
    const n = numberOrNull(s.newVisitors);
    const r = numberOrNull(s.returningVisitors);
    if (n === null || r === null) return null;
    if (n + r === 0) return null;
    return {
      newVisitors: n,
      returningVisitors: r,
      newPct: Math.round(n / (n + r) * 100),
      scope: '计算方式：' + window + '内有到店的玩家，窗口前（全历史）也到过店的算回头客',
    };
  },

  // ---- 主卡:时间窗 × 指标 ----

  /** trends.windows → { 7: w, 30: w, 90: w };结构不对就当没有(退回 30 天归因三数,不画格子) */
  _indexTrends(trends) {
    const windows = trends && Array.isArray(trends.windows) ? trends.windows : [];
    const out = {};
    windows.forEach(function (w) {
      if (!w || typeof w !== 'object' || !Array.isArray(w.daily)) return;
      const days = numberOrNull(w.days);
      if (TREND_WINDOWS.indexOf(days) !== -1 && w.daily.length === days) out[days] = w;
    });
    return Object.keys(out).length === TREND_WINDOWS.length ? out : null;
  },

  _buildHero(facts, win, metric, fromZero) {
    const trends = this._trends;
    const w = trends ? trends[win] : null;
    // 旧缓存/旧后端没有 trends:只有 30 天归因三数,窗口切换与逐日格都不出
    const src = w || (facts && facts.attribution) || {};
    const def = TREND_METRICS.filter(function (m) { return m.key === metric; })[0] || TREND_METRICS[0];
    const prevKey = 'prev' + def.key.charAt(0).toUpperCase() + def.key.slice(1);
    const cur = numberOrNull(src[def.key]);
    const prev = numberOrNull(src[prevKey]);
    // 上期为 0 或缺失不算百分比(没有基数 ≠ 涨了无穷大)
    let deltaText = '';
    let deltaDown = false;
    if (cur !== null && prev !== null && prev > 0) {
      const pct = Math.round((cur - prev) / prev * 100);
      deltaDown = pct < 0;
      deltaText = pct === 0 ? '持平' : (pct < 0 ? '' : '+') + pct + '%';
    }
    const windowText = w ? '近' + win + '天' : ((facts && facts.window) || '近30天');
    const nonce = (this._heatNonce = (this._heatNonce || 0) + 1);
    // 2026-10-27 业主 M-21:方块图每次打开都要画。没有逐日数据(旧后端/旧缓存没下发 trends)时
    // 也铺满 30 格,全部最浅一档 —— 不再整块不出。窗口切换仍只在有 trends 时出现。
    let cells = [];
    let cols = 15;
    if (!w) {
      for (let i = 0; i < 30; i++) {
        cells.push({ k: nonce + '-' + i, lv: 0, delay: (i % cols) * 22 + Math.floor(i / cols) * 40, label: '' });
      }
    }
    if (w) {
      const values = w.daily.map(function (d) { return numberOrNull(d && d[def.key]) || 0; });
      const max = Math.max.apply(null, values.concat([0]));
      cols = win === 7 ? 7 : 15;
      cells = values.map(function (v, i) {
        const lv = v <= 0 || max <= 0 ? 0 : Math.min(3, Math.ceil(v / max * 3));
        return {
          k: nonce + '-' + i,
          lv,
          // 逐列波纹:同列自上而下、列与列之间错开
          delay: (i % cols) * 22 + Math.floor(i / cols) * 40,
          label: (w.daily[i].date || '') + ' ' + v + def.short,
        };
      });
    }
    const target = cur === null ? 0 : cur;
    return {
      hasTrend: !!w,
      title: windowText + '，城瘾为你带来',
      unit: def.unit,
      target,
      num: fromZero ? '0' : String(target),
      deltaText,
      deltaDown,
      prevText: prev === null ? '' : '上期 ' + prev + def.short,
      cells,
      cols,
      axisFrom: w && w.daily[0] ? w.daily[0].date : (w ? '' : dayLabelDaysAgo(29)),
      trio: TREND_METRICS.map(function (m) {
        return { key: m.key, label: m.label, num: String(numberOrNull(src[m.key]) || 0), on: m.key === def.key };
      }),
    };
  },

  onWinTap(e) {
    const win = Number((e.currentTarget.dataset || {}).win);
    if (TREND_WINDOWS.indexOf(win) === -1 || win === this.data.win || !this._trends) return;
    this._switchHero(win, this._metric || 'visitors');
  },

  onMetricTap(e) {
    const metric = String((e.currentTarget.dataset || {}).metric || '');
    if (metric === (this._metric || 'visitors') || !TREND_METRICS.some(function (m) { return m.key === metric; })) return;
    this._switchHero(this.data.win, metric);
  },

  _switchHero(win, metric) {
    // 指标只是内部状态:WXML 读的是 hero.trio[].on,不走 setData
    this._metric = metric;
    const hero = this._buildHero(this.data.facts, win, metric, true);
    const winChanged = win !== this.data.win;
    const winIdx = TREND_WINDOWS.indexOf(win);
    if (winChanged) this.setData({ win, winIdx, hero, valueCard: this._buildValueCard(this.data.facts, win) });
    else this.setData({ win, winIdx, hero });
    this._countTo(hero.target);
    // 切时间窗:进度条跟着换窗口数,并重扫一遍(和打开页面同一套)
    if (winChanged) this._sweepPips();
  },

  /** 大数字从 0 滚到目标值;切页/隐藏/清场都会停 */
  _countTo(target) {
    this._stopCount();
    const to = numberOrNull(target) || 0;
    if (typeof setTimeout !== 'function' || to === 0 || this.data.reducedMotion) { this.setData({ 'hero.num': String(to) }); return; }
    let step = 0;
    const tick = () => {
      step++;
      const v = step >= COUNT_STEPS ? to : Math.round(to * easeInOut(step / COUNT_STEPS));
      this.setData({ 'hero.num': String(v) });
      if (step < COUNT_STEPS) this._countTimer = setTimeout(tick, COUNT_MS / COUNT_STEPS);
      else this._countTimer = null;
    };
    this._countTimer = setTimeout(tick, COUNT_MS / COUNT_STEPS);
  },

  _stopCount() {
    if (this._countTimer) { clearTimeout(this._countTimer); this._countTimer = null; }
  },

  _stopMotion() {
    this._stopCount();
    if (this._growTimer) { clearTimeout(this._growTimer); this._growTimer = null; }
    if (this._showTimer) { clearTimeout(this._showTimer); this._showTimer = null; }
    if (this._sheetTimer) { clearTimeout(this._sheetTimer); this._sheetTimer = null; }
    if (this._pipTimer) { clearTimeout(this._pipTimer); this._pipTimer = null; }
    if (this._landTimer) { clearTimeout(this._landTimer); this._landTimer = null; }
    if (this._replayTimer) { clearTimeout(this._replayTimer); this._replayTimer = null; }
  },

  /** 返回页面:先无过渡复位到隐藏态(区块、柱子、卡片、数字归 0),下一拍整套入场再播一遍 */
  _replayIn() {
    if (!this.data.facts || typeof setTimeout !== 'function') return;
    this._stopMotion();
    this._replayAt = Date.now();
    if (this.data.hero) this.setData({ replay: true, shown: false, grown: false, landing: false, 'hero.num': '0' });
    else this.setData({ replay: true, shown: false, grown: false, landing: false });
    this._replayTimer = setTimeout(() => {
      this._replayTimer = null;
      if (this._hidden || this._unloaded) return;
      this.setData({ replay: false });
      this._playIn();
    }, 50);
  },

  /** 核销进度:10 格自左向右整排点亮,停一拍,再自左向右退回真实格数(可能全空)。减少动态效果时不扫。 */
  _sweepPips() {
    if (this._pipTimer) { clearTimeout(this._pipTimer); this._pipTimer = null; }
    if (typeof setTimeout !== 'function' || this.data.reducedMotion || !this.data.valueCard) return;
    this.setData({ pipSweep: true });
    this._pipTimer = setTimeout(() => {
      this._pipTimer = null;
      if (this._hidden || this._unloaded) return;
      this.setData({ pipSweep: false });
    }, PIP_SWEEP_HOLD_MS);
  },

  /** 首帧:区块模糊入场(wxss),下一拍柱子与进度条长出、大数字滚动 */
  _playIn() {
    if (typeof setTimeout !== 'function') { this.setData({ shown: true, grown: true }); return; }
    if (this._growTimer) clearTimeout(this._growTimer);
    if (this._showTimer) clearTimeout(this._showTimer);
    if (this._landTimer) clearTimeout(this._landTimer);
    this.setData({ landing: true });
    this._landTimer = setTimeout(() => {
      this._landTimer = null;
      if (!this._hidden && !this._unloaded) this.setData({ landing: false });
    }, LANDING_MS);
    this._showTimer = setTimeout(() => {
      this._showTimer = null;
      if (!this._hidden && !this._unloaded && this.data.facts) this.setData({ shown: true });
    }, 30);
    this._growTimer = setTimeout(() => {
      this._growTimer = null;
      if (this._hidden || this._unloaded || !this.data.facts) return;
      this.setData({ grown: true });
    }, 380);
    this._countTo(this.data.hero && this.data.hero.target);
  },

  // ---- 到店时段 / 客源主题(都是已有 facts,原来页面没画) ----

  _buildSlots(facts) {
    const buckets = ((facts && facts.checkin) || {}).slotBuckets;
    if (!Array.isArray(buckets) || !buckets.length) return null;
    const counts = buckets.map(function (b) { return numberOrNull(b && b.count) || 0; });
    const total = counts.reduce(function (a, b) { return a + b; }, 0);
    if (total <= 0) return null;
    const max = Math.max.apply(null, counts);
    let peak = '';
    const bars = buckets.map(function (b, i) {
      const isMax = counts[i] === max;
      if (isMax && !peak) peak = String(b.slot || '');
      return {
        slot: String(b.slot || ''),
        count: counts[i],
        pct: Math.round(counts[i] / total * 100) + '%',
        // 最矮也留一截,零值柱不消失(和「没有这个时段」区分开)
        h: Math.max(6, Math.round(counts[i] / max * 100)),
        max: isMax,
      };
    });
    return { bars, total, peakText: peak ? peak + '上到店最多' : '' };
  },

  _buildSources(facts) {
    const list = facts && Array.isArray(facts.sources) ? facts.sources : [];
    const rows = list.filter(function (r) { return r && r.name && numberOrNull(r.count) > 0; });
    if (!rows.length) return [];
    const total = rows.reduce(function (a, r) { return a + Number(r.count); }, 0);
    const max = Number(rows[0].count);
    return rows.slice(0, 5).map(function (r) {
      const n = Number(r.count);
      return {
        name: String(r.name),
        count: n,
        initial: String(r.name).trim().charAt(0),
        w: Math.round(n / max * 100),
        pct: Math.round(n / total * 100) + '%',
      };
    });
  },

  // ---- 推荐主题:卡片原位形变成底部详情弹层,再去合作中心申请 ----

  openTopic(e) {
    const idx = Number((e.currentTarget.dataset || {}).idx);
    const topic = this.data.recTopics[idx];
    if (!topic || this.data.sheetPhase) return;
    this.setData({ sheetTopic: topic, sheetPhase: 'measure', ghostStyle: '', scrimOn: false }, () => {
      if (typeof wx.createSelectorQuery !== 'function' || this.data.reducedMotion) {
        this.setData({ sheetPhase: 'open', scrimOn: true });
        return;
      }
      const q = wx.createSelectorQuery().in(this);
      q.select('#tc-' + idx).boundingClientRect();
      q.select('#topic-sheet').boundingClientRect();
      q.exec((rects) => {
        const from = rects && rects[0];
        const to = rects && rects[1];
        if (!from || !to || this.data.sheetPhase !== 'measure') { this.setData({ sheetPhase: 'open', scrimOn: true }); return; }
        // FLIP:ghost 落在弹层最终位置,先用 transform 缩回卡片处,再过渡到 none(只动合成层,不动布局)
        const rect = 'left:' + to.left + 'px;top:' + to.top + 'px;width:' + to.width + 'px;height:' + to.height + 'px;';
        const invert = 'transform:translate(' + (from.left - to.left) + 'px,' + (from.top - to.top) + 'px) scale('
          + (from.width / to.width) + ',' + (from.height / to.height) + ');';
        this.setData({ ghostStyle: rect + invert + 'border-radius:44rpx;transition:none;' }, () => {
          this._sheetTimer = setTimeout(() => {
            this.setData({ ghostStyle: rect + 'transform:none;border-radius:56rpx 56rpx 0 0;', scrimOn: true });
            this._sheetTimer = setTimeout(() => {
              this._sheetTimer = null;
              if (this.data.sheetPhase === 'measure') this.setData({ sheetPhase: 'open', ghostStyle: '' });
            }, MORPH_MS);
          }, 20);
        });
      });
    });
  },

  closeTopic() {
    if (!this.data.sheetPhase || this.data.sheetPhase === 'closing') return;
    if (this._sheetTimer) clearTimeout(this._sheetTimer);
    this.setData({ sheetPhase: 'closing', ghostStyle: '', scrimOn: false });
    this._sheetTimer = setTimeout(() => {
      this._sheetTimer = null;
      this.setData({ sheetPhase: '', sheetTopic: null });
    }, 220);
  },

  /** 弹层主按钮:关弹层再带 topicId 去合作中心 */
  applyTopic(e) {
    this.closeTopic();
    this.goRecTopic(e);
  },

  noop() {},

  // CU-M-85:同名主题(两期「探店日一期」)只靠名字分不出是哪一场。推荐池里的 cms_topic
  // 自带起止日期与主办方,合作广场的路线卡也是这么写的 —— 这里按同一写法补一行场次。
  _decorateTopics(list) {
    return (list || []).map(function (item) {
      const start = monthDay(item.startDate);
      const end = monthDay(item.endDate);
      const dateText = start && end && start !== end ? start + ' – ' + end : start;
      const hostText = item.hostName ? '主办 ' + item.hostName : '';
      return Object.assign({}, item, {
        metaText: [dateText, hostText].filter(Boolean).join(' · '),
        // 卡面底栏:日期与主办分两枚图标位
        dateText: (function () {
          const a = slashDay(item.startDate);
          const b = slashDay(item.endDate);
          return a && b && a !== b ? a + '–' + b : a;
        })(),
      });
    });
  },

  // 推荐主题 → 合作中心申请承接;带 topicId 过去把对应条目定位出来(同名主题不必再认一遍)
  goRecTopic(e) {
    const topicId = String((e.currentTarget.dataset || {}).topicId || '').trim();
    if (!/^\d+$/.test(topicId)) return this.goCoopCenter();
    wx.navigateTo({ url: COOP_CENTER_ROUTE + '?topicId=' + topicId });
  },

  // 推荐商家 → canonical 商家主页(主体是 memberId,不是档案主键)
  goRecPartner(e) {
    const url = merchantHomeUrl((e.currentTarget.dataset || {}).memberId);
    if (url) wx.navigateTo({ url });
  },

  goCoopCenter() { wx.navigateTo({ url: COOP_CENTER_ROUTE }); },


});
