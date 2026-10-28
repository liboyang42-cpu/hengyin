const toast = require('../../../utils/toast.js');
const motion = require('../../../utils/motion.js');
const { readReducedMotion } = require('../../../utils/motion-preference.js');
const { toTimestamp, chinaParts } = require('../../../utils/datetime');
const { activityStatusText } = require('../../../utils/activity-status');
const { bizFailureMessage } = require('../../../utils/response-shape.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { exactId, reader, sameReader } = require('../../../utils/user-character.js');
const { createRecovery } = require('../../../utils/team-event-command.js');
const app = getApp();
let subscribe = null;
try { subscribe = require('../../../utils/subscribe.js'); } catch (e) {}

function toTs(v) {
  const t = toTimestamp(v);
  return isNaN(t) ? 0 : t;
}
function isV2(e) { return Number((e && e.contractVersion) || 1) >= 2; }
function participationInvalid(e) {
  return isV2(e) && e && e.participation && e.participation.state !== 'ACTIVE';
}

const CATEGORY_TEXT = {
  city_light: '城市点亮',
  custom: '特别企划',
  festival: '节日活动',
  brand: '品牌活动',
  challenge: '城市挑战',
  hidden: '隐藏活动',
  community: '社区活动',
  merchant_hunt: '商家探索',
};

// F22:任务的 missionType 是后端枚举(见 OfficialEventMission.java),不是给玩家看的文案。
// description(shortDescription)允许为空 —— 为空时按类型给准确中文行动与完成判据,
// 未知类型明说「规则未提供」,绝不裸露枚举、也不臆造完成条件。
// supported:后端是否有真实可用的发布/核验路径。ROAM_POI_ARRIVAL / ROAM_SESSION_AREA 被
// OfficialEventV2ServiceImpl.validatePublishMission 明确禁止发布(且 missionComplete 对前者恒 false),
// 前端不得编造「去验证/自动同步」,如实标未支持,但任务条目照常展示(不删入口)。
const MISSION_TYPE_TEXT = {
  ROAM_POI_ARRIVAL: { label: '漫游节点到达', action: '该任务类型暂不支持发布与核验', supported: false },
  EVENT_POINT_ARRIVAL: { label: '活动节点到达', action: '走到活动指定节点，系统会自动确认到达', supported: true },
  THEME_VERIFIED_FINISH: { label: '完成绑定主题', action: '完成绑定的主题，完成后自动同步', supported: true },
  ROAM_SESSION_AREA: { label: '漫游区域覆盖', action: '该任务类型暂不支持发布与核验', supported: false },
};
const UNKNOWN_MISSION_LABEL = '活动任务';
const UNKNOWN_MISSION_ACTION = '任务规则未提供，请以主办方说明为准';

// 后端 rewardJson 契约(见 OfficialEventV2ServiceImpl):
//   settleXp = Java int(1..2147483647);settleCouponId / collectiveCouponId = Java long(1..9223372036854775807)。
// fastjson getInteger/getLong 会把合法十进制字符串一并转数,所以数字字符串必须照常接受。
// ⚠️ couponId 是 64 位:超过 Number.MAX_SAFE_INTEGER 的十进制字符串**不能先转 Number**
//    (9007199254740993 会变成 ...992),这里按字符串逐位判范围,不引 BigInt/新依赖。
// 原始 JSON 的大整数在 parseRewardJson 中保留词元，不能要求已有 Long 配置改成字符串。
const INT_MAX_TEXT = '2147483647';
const LONG_MAX_TEXT = '9223372036854775807';

function withinMaxText(text, maxText) {
  if (text.length > maxText.length) return false;
  if (text.length === maxText.length && text > maxText) return false;
  return true;
}

// 返回正整数(number)或规范化十进制字符串;非法一律 null。maxText = 该字段的后端上限。
function positiveInt(value, maxText) {
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || value <= 0) return null;
    return withinMaxText(String(value), maxText) ? value : null;
  }
  if (typeof value === 'string') {
    const text = value.trim();
    if (!/^\d+$/.test(text)) return null;
    const normalized = text.replace(/^0+(?=\d)/, ''); // 去前导零,至少留一位
    if (normalized === '0') return null;               // 必须为正
    return withinMaxText(normalized, maxText) ? normalized : null;
  }
  return null;
}

function parseRewardJson(e) {
  try {
    const raw = e && e.rewardJson;
    if (!raw) return null;
    const parsed = JSON.parse(raw); // 先验证原始 JSON，不能把非法前导零等配置“修成”有效。
    if (typeof raw !== 'string') return parsed;
    // 跳过完整 JSON 字符串；数字从原始词元移位成整数文本，不经过浮点转换。
    // 同时保留整数的小数/指数写法；非整数和超出 Long 位数的值仍由字段校验拒绝。
    const precise = raw.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
      (token) => {
        if (token[0] === '"') return token;
        const parts = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token);
        const allDigits = parts[2] + (parts[3] || '');
        const digits = allDigits.replace(/^0+/, '');
        if (!digits) return '"0"';
        const exponent = Number(parts[4] || 0);
        const point = parts[2].length + exponent - (allDigits.length - digits.length);
        if (!Number.isSafeInteger(exponent) || point <= 0 || point > LONG_MAX_TEXT.length
          || /[1-9]/.test(digits.slice(point))) return JSON.stringify(token);
        const integer = digits.slice(0, point) + '0'.repeat(Math.max(0, point - digits.length));
        return JSON.stringify(parts[1] + integer);
      });
    return precise === raw ? parsed : JSON.parse(precise);
  } catch (x) { return null; }
}
function isRewardObject(rw) {
  return !!rw && typeof rw === 'object' && !Array.isArray(rw);
}
// 是否配置了可用的集体券:决定集体进度区能否承诺「达标后发放」。
function hasCollectiveReward(e) {
  const rw = parseRewardJson(e);
  return isRewardObject(rw) && positiveInt(rw.collectiveCouponId, LONG_MAX_TEXT) != null;
}

function displayDate(value) {
  const p = chinaParts(value);
  return p ? (p.month + '.' + p.day) : '';
}

function parseContentCards(contentJson) {
  let content = {};
  try { content = contentJson ? JSON.parse(contentJson) : {}; } catch (e) {}
  const tasks = Array.isArray(content.tasks) ? content.tasks : [];
  return tasks.map((task, index) => {
    if (typeof task === 'string') {
      return { _key: 'legacy-' + index, missionCode: '', title: task, description: '活动任务 ' + (index + 1), complete: false };
    }
    task = task && typeof task === 'object' ? task : {};
    return {
      _key: 'legacy-' + index,
      missionCode: '',
      title: task.title || task.name || ('活动任务 ' + (index + 1)),
      description: task.description || task.shortDescription || task.type || '按活动说明完成',
      complete: false,
    };
  });
}

Page({
  data: {
    id: 0,
    e: null,
    boundTopicError: '',
    rewards: [],
    cta: { text: '', type: '' },
    countdown: '',
    navScrolled: false,
    loading: true,
    loadErrKind: 'network',
    loadErrTitle: '',
    loadErrSub: '',
    loadErrAction: '重试',
    loadErrActionType: 'retry',
    organizerEntry: false,
    commandRecovery: null,
    privacyGateShow: false,
    policyChecked: false,
    signupBlocked: false,
  },

  // 赛事签到组件的扫码(wx.scanCode)需要隐私授权：页内弹窗承接，不让 app.js 回退整页跳隐私页。
  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },

  onLoad(q) {
    this._hidden = false;
    this._disposed = false;
    this._reader = reader(app);
    this.setData({ id: exactId(q && q.id) });
    merchantTheme.merchantPageShow();
    this._openRecovery();
    this.fetch();
  },

  onShow() {
    this._navigating = false;
    const refresh = this._hidden || this._reader && !sameReader(app, this._reader);
    this._hidden = false;
    merchantTheme.merchantPageShow();
    if (refresh) { this._openRecovery(); this.fetch(); }
  },

  onHide() {
    if (this._recovery) this._recovery.dispose();
    this._hidden = true;
    this._fetchSeq = (this._fetchSeq || 0) + 1;
    merchantTheme.merchantPageRestore();
  },

  onScroll(event) {
    const next = Number(event && event.detail && event.detail.scrollTop) > 180;
    if (next !== this.data.navScrolled) this.setData({ navScrolled: next });
  },

  onUnload() {
    if (this._recovery) this._recovery.dispose();
    this._disposed = true;
    merchantTheme.merchantPageRestore();
    this._fetchSeq = (this._fetchSeq || 0) + 1;
  },

  onRetry() {
    this.setData({
      loading: true,
      loadErrTitle: '',
      loadErrSub: ''
    });
    this.fetch();
  },

  // 失败半屏收起后的落点:有上一页就回去,冷启动直达回活动列表(原「返回活动列表」出口)
  onLoadErrorBack() {
    if (getCurrentPages().length > 1) { wx.navigateBack(); return; }
    wx.reLaunch({ url: '/pages/activity/list/index' });
  },

  onLoadErrorAction() {
    if (this.data.loadErrActionType === 'retry') {
      this.onRetry();
      return;
    }
    wx.redirectTo({
      url: '/pages/activity/list/index',
      fail: () => wx.reLaunch({ url: '/pages/activity/list/index' })
    });
  },

  _context() {
    return { id: exactId(this.data.id), seq: this._fetchSeq, reader: reader(app) };
  },

  _current(context) {
    if (this._disposed || this._hidden || context.id !== exactId(this.data.id)
        || context.seq !== this._fetchSeq) return false;
    if (!sameReader(app, context.reader)) {
      this.setData({ e: null, rewards: [], cta: { text: '', type: '' },
        organizerEntry: false, boundTopicError: '', countdown: '' });
      return false;
    }
    return true;
  },

  fetch() {
    if (this._disposed || this._hidden) return;
    const id = exactId(this.data.id);
    if (!id) {
      this.data.loadErrActionType = 'back';
      this.setData({ loading: false, e: null, organizerEntry: false,
        loadErrKind: 'data', loadErrTitle: '活动参数无效', loadErrSub: '',
        loadErrAction: '返回活动列表' });
      return;
    }
    if (this._reader && !sameReader(app, this._reader)) {
      this.setData({ e: null, rewards: [], cta: { text: '', type: '' }, countdown: '', boundTopicError: '' });
      this._ctaBusy = false;
      this._ctaContext = null;
    }
    this._reader = reader(app);
    const seq = (this._fetchSeq || 0) + 1;
    this._fetchSeq = seq;
    const context = this._context();
    this.setData({ organizerEntry: false });
    this.setData({ loading: true, loadErrTitle: '', loadErrSub: '' });
    app.sendRequest({
      url: '/api/official/events/' + id, method: 'GET',
      autoErrorToast: false,   // 落 wx:else 整页留白;保留旧详情的刷新失败同样不提示(F-R2U-01)
      success: (res) => {
        if (!this._current(context)) return;
        if (!res || (res.code != 200 && res.code != '200') || !res.data) {
          const code = res && Number(res.code);
          // 只有服务端明确表达「内容没了」才走不可恢复态。畸形 200(200 但没有 data)
          // 是服务端异常,不能替用户断定活动已下线 —— 它必须落到可重试分支。
          const contentUnavailable = code === 404 || code === 410
            || String((res && res.msg) || '').trim() === '活动不存在';
          if (contentUnavailable) {
            this.data.loadErrActionType = 'back';
            this.setData({
              e: null,
              loadErrKind: 'data',
              loadErrTitle: '活动内容不可用',
              loadErrSub: '',
              loadErrAction: '返回活动列表'
            });
          } else {
            this.data.loadErrActionType = 'retry';
            this.setData({
              loadErrKind: 'data',
              loadErrTitle: '活动内容没加载成功',
              loadErrSub: bizFailureMessage(res, '服务暂时不可用，请稍后重试。'),
              loadErrAction: '重试'
            });
          }
          return;
        }
        const e = this._decorate(res.data);
        this.setData({ e, rewards: this._rewards(e), cta: this._cta(e), countdown: this._countdown(e), boundTopicError: '' });
        this._loadBoundTopicNames(e);
        this._probeOrganizer(e);
      },
      fail: () => {
        if (!this._current(context)) return;
        this.data.loadErrActionType = 'retry';
        this.setData({
          loadErrKind: 'network',
          loadErrTitle: '网络没连上',
          loadErrSub: this.data.e ? '已保留上次加载的活动详情，连接恢复后可以重试。' : '活动详情加载失败，连接恢复后可以重试。',
          loadErrAction: '重试'
        });
      },
      complete: () => {
        if (this._current(context)) this.setData({ loading: false });
      }
    });
  },

  // 主办入口探针:publish-check 只对本场主办/协办(且在官方白名单内)返回 200,其余一律当作普通玩家,
  // 不提示、不报错 —— 对绝大多数玩家这本来就是「不是主办」,不是故障。
  _probeOrganizer(e) {
    if (!e || !e._isV2) { this.setData({ organizerEntry: false }); return; }
    const context = this._context();
    app.sendRequest({
      url: `/api/official/v2/events/${this.data.id}/publish-check`, method: 'GET',
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!this._current(context)) return;
        this.setData({ organizerEntry: !!res && (res.code == 200 || res.code == '200') });
      },
      fail: () => {
        if (!this._current(context)) return;
        this.setData({ organizerEntry: false });
      },
    });
  },

  openOrganizer() {
    if (!this.data.organizerEntry || this._navigating) return; // 跳转中不响应连点,免得叠两层控制台
    this._navigating = true;
    wx.navigateTo({ url: '/pages/activity/official-organizer/index?id=' + this.data.id, complete: () => { this._navigating = false; } });
  },

  _rewards(e) {
    const rw = parseRewardJson(e);
    // F22:解析失败 / 无配置 / 非对象 ⇒ 一律按「没有配置奖励」。
    // 坏数据不当有奖,也不再用无依据的默认惊喜承诺(见 WXML 无奖时的如实说明)。
    if (!isRewardObject(rw)) return [];
    // B30:rewardJson 的字段可能回对象/数组,直接参与拼接会渲染成 `[object Object]`;
    // positiveInt 只放行正整数/合法十进制字符串(按各自后端上限),其余按「没有这项」。
    const out = [];
    if (rw.settleBadge === true) out.push('🏅 结算限定徽章');
    // 逐字段按后端类型与范围校验(不接受 truthy):couponId=long、xp=int。
    if (positiveInt(rw.settleCouponId, LONG_MAX_TEXT) != null) out.push('🎟 结算专属券');
    const settleXp = positiveInt(rw.settleXp, INT_MAX_TEXT);
    if (settleXp != null) out.push('✨ ' + settleXp + ' 成长值');
    if (positiveInt(rw.collectiveCouponId, LONG_MAX_TEXT) != null) out.push(this._collectiveRewardText(e));
    return out;
  },

  // 集体券不是「全员无条件」:后端 collectiveReached 要求 collectiveEnabled 且 threshold>0 且当前达标,
  // 且发放循环要求本人 eligible(allMissionsComplete)。文案必须把这两个条件都说清楚。
  _collectiveRewardText(e) {
    const c = (e && e.collective) || null;
    const enabled = !!(c && c.enabled) && Number(c.threshold) > 0;
    return enabled
      ? '🎉 集体达标券（全城达标且本人完成全部任务后发放）'
      : '🎉 集体达标券（主办方未开启全城达标，开启后达标且本人完成全部任务才发放）';
  },

  _cta(e) {
    const s = e.status;
    if (s == 9) return { text: '活动已下线', type: 'ended' };
    if (s >= 5) return { text: '活动已结束', type: 'ended' };
    if (s == 4) return { text: '活动待结算', type: 'wait' };
    if (e.paused) return { text: '活动已暂停', type: 'wait' };
    if (participationInvalid(e)) return { text: '参与资格已失效', type: 'wait' };
    if (e.signed) {
      if (s == 3 && isV2(e)) {
        const missions = e.missions || [];
        if (missions.some(m => m.canVerifyArrival)) return { text: '去漫游 · 验证到达', type: 'roam' };
        if (missions.some(m => m.missionType === 'THEME_VERIFIED_FINISH')) return { text: '完成绑定主题后自动同步', type: 'theme' };
        // 只有后端禁发布的类型时,不能再许诺「等待可验证任务」——如实说暂不支持。
        if (missions.length && missions.every(m => m._supported === false)) return { text: '任务暂不支持核验', type: 'wait' };
        return { text: '等待可验证任务', type: 'wait' };
      }
      if (s == 3) return e.roamEnabled
        ? { text: '去点亮 · 点亮城市', type: 'roam' }
        : { text: '去探索 · 完成任务', type: 'explore' };
      if (s == 1) return { text: '已报名 · 待开始', type: 'wait' };
      return { text: '已报名', type: 'wait' };
    }
    if (s == 2 || s == 3) return { text: '立即报名', type: 'signup' };
    if (s == 1) return { text: '报名即将开放', type: 'wait' };
    return { text: '暂不可报名', type: 'wait' };
  },

  _countdown(e) {
    const now = Date.now();
    if (e.status == 1 && toTs(e.activityStart)) {
      const d = toTs(e.activityStart) - now;
      return d > 0 ? '距开始 ' + this._dur(d) : '';
    }
    if ((e.status == 2 || e.status == 3) && toTs(e.activityEnd)) {
      const d = toTs(e.activityEnd) - now;
      return d > 0 ? '距结束 ' + this._dur(d) : '';
    }
    return '';
  },
  _dur(ms) {
    const h = Math.floor(ms / 3600000);
    if (h >= 24) return Math.floor(h / 24) + ' 天';
    if (h >= 1) return h + ' 小时';
    return Math.max(1, Math.floor(ms / 60000)) + ' 分钟';
  },

  _decorate(e) {
    const v2 = isV2(e);
    const missions = v2 && Array.isArray(e.missions)
      ? e.missions.map((item, index) => {
        const meta = MISSION_TYPE_TEXT[item && item.missionType];
        const title = String((item && item.title) || '').trim();
        const desc = String((item && item.description) || '').trim();
        return Object.assign({
          _key: (item && item.missionCode) || ('mission-' + index),
          _titleText: title || (meta ? meta.label : UNKNOWN_MISSION_LABEL),
          _descText: desc || (meta ? meta.action : UNKNOWN_MISSION_ACTION),
          _supported: !!(meta && meta.supported),
        }, item);
      })
      : [];
    const cards = v2 ? [] : parseContentCards(e.contentJson);
    const done = missions.filter(item => item.complete).length;
    const start = displayDate(e.activityStart);
    const end = displayDate(e.activityEnd);
    const duration = toTs(e.activityEnd) - toTs(e.activityStart);

    e._isV2 = v2;
    e._hasCollectiveReward = hasCollectiveReward(e);
    e._statusText = activityStatusText(e.status);
    e._categoryText = CATEGORY_TEXT[e.category] || '官方活动';
    e._dateRange = start && end ? (start + '–' + end) : (start || end || '待公布');
    e._durationText = duration > 0
      ? (duration >= 86400000
        ? Math.max(1, Math.ceil(duration / 86400000)) + '天'
        : Math.max(1, Math.ceil(duration / 3600000)) + '小时')
      : '待公布';
    e._scopeText = e.city || '全国';
    e._activityCards = cards;
    e._taskDone = done;
    e._taskTotal = v2 ? missions.length : cards.length;
    e._progressText = v2 ? (done + '/' + missions.length) : String(e.myProgress || 0);
    e._eligibleText = v2 ? (participationInvalid(e) ? '参与资格已失效' : e.eligible ? '已满足资格' : '完成全部任务后获得资格') : '';
    e._showStoryDetail = !!(e.story && e.subtitle);
    // 赛事榜单(原型 B T-30 / B8,需后端 e.leaderboard):主办没开时整段不出;公示期内写「暂列第 N 名」
    const lb = e.leaderboard;
    if (lb && lb.enabled === true) {
      const clock = (sec) => { const n = Math.max(0, Math.floor(Number(sec) || 0)); const p2 = (v) => (v < 10 ? '0' : '') + v; return Math.floor(n / 3600) + ':' + p2(Math.floor(n / 60) % 60) + ':' + p2(n % 60); };
      const rows = (Array.isArray(lb.rows) ? lb.rows : []).filter((r) => r && Number.isSafeInteger(Number(r.rank)) && Number(r.rank) > 0).map((r, i) => ({
        _key: i, rank: Number(r.rank), name: r.name || '城瘾玩家',
        value: (Number(r.doneCount) || 0) + ' / ' + (Number(r.totalCount) || 0) + (r.elapsedSeconds != null ? ' · ' + clock(r.elapsedSeconds) : '') }));
      const my = Number(lb.myRank);
      e._board = { rows, chip: Number.isSafeInteger(my) && my > 0 ? (lb.final === true ? '第 ' + my + ' 名' : '暂列第 ' + my + ' 名') : '' };
    } else e._board = null;
    // 安全须知(原型 B T-35,需后端 e.safetyNotice{title,version}):报名前单独勾选,不默认勾
    e._policyTitle = e.safetyNotice && e.safetyNotice.title ? String(e.safetyNotice.title) : '';
    if (v2) e.missions = missions;
    return e;
  },

  onCta() {
    const type = this.data.cta.type;
    if (type === 'signup') return this.doSignup();
    if (type === 'explore') return this.doComplete();
    if (type === 'roam') return this.goRoam();
    if (type === 'theme') {
      const e = this.data.e || {};
      const mission = (e.missions || []).find(m => m.missionType === 'THEME_VERIFIED_FINISH' && !m.complete);
      if (!this.goBoundTopic(mission)) toast('请完成绑定主题，系统会自动核验');
    }
  },

  // CU-C-16:任务卡原来只有「完成探店日主题」这类标题,看不出是哪条主题。按 THEME 绑定的
  // sourceRefId(= cms_topic.id)读主题公开详情补上名字;读不到就说明一句,点任务仍能前往。
  _loadBoundTopicNames(e) {
    // info-to-user 要登录;游客看活动详情时别白打一枪触发重登,标题与「点任务直达」照常可用
    if (!app.getUserID || !app.getUserID()) return;
    const ids = [];
    (e.missions || []).forEach((m) => {
      const b = m && m.binding;
      const id = b && b.bindingType === 'THEME' ? exactId(b.sourceRefId) : '';
      if (id && ids.indexOf(id) < 0) ids.push(id);
    });
    const eventId = exactId(e.id);
    const context = this._context();
    ids.forEach((topicId) => {
      app.sendRequest({
        url: '/api/topic/info-to-user', method: 'POST', data: { id: Number.isSafeInteger(Number(topicId)) ? Number(topicId) : topicId }, hideLoading: true, silentError: true,
        success: (res) => {
          const cur = this.data.e;
          if (!this._current(context) || !cur || exactId(cur.id) !== eventId) return;   // 已切到别的活动,旧回包作废
          const name = res && (res.code == 200 || res.code == '200') && res.data && res.data.name;
          if (!name) { this.setData({ boundTopicError: '点任务仍可前往绑定主题' }); return; }
          const missions = (cur.missions || []).map((m) => {
            const b = m && m.binding;
            return b && b.bindingType === 'THEME' && exactId(b.sourceRefId) === topicId
              ? Object.assign({}, m, { _topicName: String(name) }) : m;
          });
          this.setData({ 'e.missions': missions });
        },
        fail: () => {
          if (this._current(context) && this.data.e && exactId(this.data.e.id) === eventId) this.setData({ boundTopicError: '点任务仍可前往绑定主题' });
        },
      });
    });
  },

  // CU-C-16:「完成绑定主题」任务要能直达那条主题。THEME 绑定的 sourceRefId 就是 cms_topic.id
  // (OfficialEventV2ServiceImpl 发布校验按它读主题);完成事实仍由服务端核验同步,这里只是入口。
  goBoundTopic(mission) {
    const binding = mission && mission.binding;
    const topicId = binding && binding.bindingType === 'THEME' ? exactId(binding.sourceRefId) : '';
    if (!topicId) return false;
    if (this._navigating) return true; this._navigating = true; wx.navigateTo({ url: '/pages/topic/index/index?id=' + topicId, complete: () => { this._navigating = false; } });
    return true;
  },

  onMissionTap(ev) {
    const e = this.data.e || {};
    const code = ev && ev.currentTarget && ev.currentTarget.dataset && ev.currentTarget.dataset.code;
    const mission = (e.missions || []).find(item => item.missionCode === code);
    if (!mission || mission.complete) return;
    if (participationInvalid(e)) { toast('参与资格已失效'); return; }
    if (e.paused || e.status != null && Number(e.status) >= 4) { toast(this._cta(e).text); return; }
    // 后端禁发布/无核验路径的类型:给明确提示,不静默,也不误触发报名。
    if (mission._supported === false) { toast('该任务类型暂不支持核验'); return; }
    if (!e.signed) { this.doSignup(); return; }
    if (mission.canVerifyArrival) { this.goRoam(mission.missionCode); return; }
    if (mission.missionType === 'THEME_VERIFIED_FINISH' && !this.goBoundTopic(mission)) {
      toast('完成绑定主题后会自动同步本活动');
    }
  },

  // 去点亮:跳漫游 tab 并带 eventId,探索点亮计入本活动集体进度
  // switchTab 不带 query,eventId 经 globalData 暂存,roam 侧 onShow 消费后即清
  goRoam(missionCode) {
    getApp().globalData.roamEventId = this.data.id;
    getApp().globalData.roamEventMissionCode = missionCode || '';
    wx.switchTab({ url: '/pages/roam/index' });
  },

  _openRecovery() {
    this._recoveryReader = reader(app);
    if (this._recovery) this._recovery.dispose();
    this._recovery = createRecovery({ app, platform: wx, domain: 'SIGNUP', targetId: this.data.id,
      changed: value => this.setData({ commandRecovery: value }),
      terminal: result => {
        // 服务端按平台规则拒绝报名时,只在安全须知段写一句中性说明
        if (result.state === 'REJECTED' && (result.errorCode || (result.data && result.data.errorCode)) === 'ORIENT_SAFETY_BLOCKED') this.setData({ signupBlocked: true });
        if (result.state === 'APPLIED') {
          motion.haptic({ type: 'light', reducedMotion: readReducedMotion() });
          toast.success('报名成功');
        }
        this.fetch();
      } });
  },
  queryCommand() { this._recovery.query(); },
  retryCommand() { this._recovery.retry(); },
  cancelCommand() { this._recovery.cancel(); },
  acknowledgeCommand() { this._recovery.acknowledge(); },

  onPolicyChange(ev) { this.setData({ policyChecked: !!(ev && ev.detail && ev.detail.checked) }); },

  doSignup() {
    if (app.ensureLoginFor && !app.ensureLoginFor('signup', () => this.doSignup())) return;
    if (participationInvalid(this.data.e)) { toast('参与资格已失效'); return; }
    if (this.data.e && this.data.e._policyTitle && !this.data.policyChecked) { toast('请先勾选安全须知'); return; }
    if (!exactId(this.data.id) || this._disposed || this._hidden) return;
    if (this._ctaBusy) return;
    if (!this._recovery || !sameReader(app, this._recoveryReader)) this._openRecovery();
    if (this._recovery.pending()) return;
    // Notification permission is independent of the command receipt and never proves signup.
    if (subscribe && subscribe.request) {
      const context = this._context();
      subscribe.request(['activityStart']).then(result => {
        if (this._current(context)) this._activityStartSubscribeStatus = result.status;
      }).catch(() => {});
    }
    return this._recovery.start('signup', {});
  },

  doComplete() {
    if (!exactId(this.data.id) || this._disposed || this._hidden) return;
    if (isV2(this.data.e)) {
      toast('任务完成需现场验证');
      return;
    }
    if (this._ctaBusy) return;
    this._ctaBusy = true;
    const context = this._context();
    this._ctaContext = context;
    app.sendRequest({
      url: '/api/official/events/' + this.data.id + '/complete', method: 'POST',
      success: (res) => {
        if (!this._current(context)) return;
        if (res && (res.code == 200 || res.code == '200')) {
          toast.success('完成 +1');
          this.fetch();
        } else {
          toast((res && res.msg) || '操作失败');
        }
      },
      complete: () => { if (this._ctaContext === context) this._ctaBusy = false; }
    });
  },

  onShareAppMessage() {
    const e = this.data.e || {};
    return {
      title: e.title || '城瘾官方活动',
      path: '/pages/activity/official-detail/index?id=' + encodeURIComponent(this.data.id),
      imageUrl: e.coverImg || '',
    };
  },
});
