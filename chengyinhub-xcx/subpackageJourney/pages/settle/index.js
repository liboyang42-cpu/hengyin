const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
const shareCard = require('../../utils/share-card.js');
const motion = require('../../../utils/motion.js');
const { countIn } = require('../../../utils/page-motion.js');
const { readReducedMotion } = require('../../../utils/motion-preference.js');
const app = getApp();

/* r2-finish 结算流程 → l-after(2026-11-10)按业主批准的原型改:整局走完后 总览 → 排行榜(导航栏以下的近全屏弹层,只有 全体 / 队内)
 * → 评价(五星 + 标签片 + 一句话,可跳过)→ 分享(帖文 / 故事卡,底部一行 分享 + 完成)。
 * 只读服务端事实:战报 /api/play/player-journey/report;评价走既有 /api/comment/add(owner_type 1 主题 / 2 场次)。
 * 微信分享卡片图仍用隐藏画布按同一版式画(onShareAppMessage imageUrl);「保存图片 / 分享视频」按业主「底部只有一行:分享 + 完成」撤掉。 */

const STEPS = ['overview', 'rank', 'rate', 'share'];
const MAX_REVIEW = 500;
// 评价标签片(proto st-rate):点中的随文字一起提交,提交时写在正文前面
const TAGS = ['故事好', '地点选得好', '谜题有意思', '路太远', '提示不够', '太难了'];
const ENTER_MS = 34;
const SCORE_COUNT_AT = 1100;

function landingPath(token) {
  return '/subpackageJourney/pages/landing/index?k=r&t=' + encodeURIComponent(token);
}

function navTopPx() {
  const g = (app && app.globalData) || {};
  return (g.statusBarHeight || 20) + (g.navBarHeight || 44);
}

Page({
  data: {
    state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '',
    step: 'overview', stepIndex: 0, steps: STEPS, navTop: 64, reduced: false,
    report: null, topicName: '', ovOn: false, soK: null, rankOn: false,
    stars: 0, starList: [1, 2, 3, 4, 5], tags: TAGS.map(label => ({ label, on: false })), review: '', reviewMax: MAX_REVIEW, rateBusy: false, rateError: '', thanks: false,
    shareKind: 'post', shareTabs: [{ key: 'post', label: '帖文' }, { key: 'story', label: '故事' }], shareCard: { kicker: '', title: '', meta: '' },
    privacyGateShow: false
  },

  onLoad(options) {
    const o = options || {};
    this._query = {
      activityId: view.isId(o.activityId) ? o.activityId : '',
      topicId: view.isId(o.topicId) ? o.topicId : '',
      runId: view.isId(o.runId) ? o.runId : ''
    };
    this.setData({ topicName: o.topicName ? decodeURIComponent(o.topicName) : '', navTop: navTopPx(), reduced: !!readReducedMotion() });
    if (!this._query.activityId && !this._query.topicId) {
      this.setData({ state: 'missing', stateKind: 'missing-param', stateTitle: '链接已失效' });
      return;
    }
    this.reload();
  },

  onUnload() { this._gone = true; this._unloaded = true; this._clearTimers(); },
  _clearTimers() { (this._timers || []).forEach(clearTimeout); this._timers = []; },
  _later(fn, ms) {
    if (typeof setTimeout !== 'function') { fn(); return; }
    this._timers = this._timers || [];
    this._timers.push(setTimeout(() => { if (!this._gone) fn(); }, ms));
  },

  reload() {
    const epoch = this._epoch = (this._epoch || 0) + 1;
    this.setData({ state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '' });
    api.report(app, this._query).then(res => {
      if (epoch !== this._epoch || this._gone) return;
      const code = res && (res.httpStatus === 401 || res.code == 401) ? 401 : Number(res && res.code);
      if (code === 401) { this.setData({ state: 'auth', stateKind: 'no-permission', stateTitle: '登录后查看结算', stateCta: '登录' }); return; }
      if (!api.ok(res) || !res.data) {
        const gone = res && (res.reasonCode === 'RUN_NOT_FOUND');
        this.setData({ state: 'error', stateKind: gone ? 'not-found' : 'data', stateTitle: gone ? '这一局不存在或无权查看' : '结算暂未加载', stateCta: gone ? '' : '重新加载' });
        return;
      }
      const report = view.reportView(res.data);
      if (this._query.runId && report.runId && report.runId !== String(this._query.runId)) {
        this.setData({ state: 'error', stateKind: 'not-found', stateTitle: '这一局不存在或无权查看', stateCta: '' }); return;
      }
      // 结算只给结束的局;进行中不出结算(去战报看进度)。
      if (!report.finished) { this.setData({ state: 'error', stateKind: 'not-started', stateTitle: '这一局还没结束', stateCta: '' }); return; }
      this.setData({ state: 'ready', report, shareCard: shareCard.cardLines(report, this.data.topicName) });
      this._enterOverview();
      this._fillTopicName(report.topicId || this._query.topicId);
    });
  },

  // x-fix4 N-9:从战报/直链进来没带 topicName 时,按主题 id 补主题名,标题不再落「本局」
  _fillTopicName(topicId) {
    if (this.data.topicName || !view.isId(topicId)) return;
    app.sendRequest({ url: '/api/topic/info-to-user', method: 'POST', data: { id: topicId }, hideLoading: true, autoErrorToast: false,
      success: res => {
        const d = res && String(res.code) === '200' && res.data;
        if (!d || !d.name || this._gone || this.data.topicName) return;
        this.setData({ topicName: d.name, shareCard: shareCard.cardLines(this.data.report, d.name) });
      } });
  },

  // 总览进场:插图先落,字与两格依次升起;分数在卡落定后数上来(只在第一次拿到战报时)
  _enterOverview() {
    if (this.data.reduced) { this.setData({ ovOn: true }); return; }
    this.setData({ ovOn: false });
    this._later(() => this.setData({ ovOn: true }), ENTER_MS);
    if (this.data.report && this.data.report.scored && !this._counted) {
      this._counted = true;
      this._later(() => countIn(this, 'soK'), SCORE_COUNT_AT);
    }
  },

  onStateCta() {
    if (this.data.state === 'auth') { app.requireLogin({ reason: '登录后查看结算' }).then(ok => { if (ok) this.reload(); }); return; }
    this.reload();
  },

  _go(step) {
    const i = STEPS.indexOf(step);
    if (i < 0) return;
    this.setData({ step, stepIndex: i, thanks: false });
    if (step === 'overview') this._enterOverview();
    if (step === 'rank') {
      // 近全屏弹层从下方升起
      this.setData({ rankOn: this.data.reduced });
      if (!this.data.reduced) this._later(() => this.setData({ rankOn: true }), ENTER_MS);
    }
    if (step === 'share') this._paintCard();
  },
  next() { this._go(STEPS[Math.min(STEPS.length - 1, this.data.stepIndex + 1)]); },
  // 导航返回:流程内退一步;第一步退出页面。
  onBack() {
    if (this.data.stepIndex > 0) { this._go(STEPS[this.data.stepIndex - 1]); return; }
    this._leave();
  },
  done() { this._leave(); },
  // 结算可由分享 / 卡片直接打开(页栈只有本页):没有上一页时回票夹,不留死胡同(同 play 的 goBackDetail)
  _leave() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.reLaunch({ url: '/subpackageMember/signup/index' });
  },
  goRank() { this._go('rank'); },

  // ——— 评价 ———
  pickStar(e) {
    const n = Number(e.currentTarget.dataset.n) || 0;
    this.setData({ stars: n, rateError: '' });
    motion.haptic({ type: 'light' });
  },
  toggleTag(e) {
    const i = Number(e.currentTarget.dataset.i);
    if (!this.data.tags[i]) return;
    this.setData({ ['tags[' + i + '].on']: !this.data.tags[i].on, rateError: '' });
  },
  onReview(e) { this.setData({ review: String((e.detail && e.detail.value) || '').slice(0, MAX_REVIEW), rateError: '' }); },
  skipRate() { this._go('share'); },
  _reviewText() {
    const picked = this.data.tags.filter(t => t.on).map(t => t.label);
    const text = this.data.review.trim();
    return [picked.join('，'), text].filter(Boolean).join('。').slice(0, MAX_REVIEW);
  },
  submitRate() {
    const report = this.data.report || {};
    const text = this._reviewText();
    if (!this.data.stars) { this.setData({ rateError: '先选星级' }); return; }
    if (!text) { this.setData({ rateError: '写一句再提交' }); return; }
    if (this.data.rateBusy) return;
    const ownerType = report.activityId ? 2 : 1;
    const ownerId = report.activityId || report.topicId;
    if (!view.isId(ownerId)) { this.setData({ rateError: '这一局暂时不能评价' }); return; }
    this.setData({ rateBusy: true, rateError: '' });
    api.rate(app, ownerType, ownerId, this.data.stars, text).then(res => {
      if (this._gone) return;
      if (api.ok(res)) { this.setData({ rateBusy: false, thanks: true }); motion.haptic({ type: 'medium' }); return; }
      // 服务端原文(内容安全、频控等)照原样显示在字段下面。
      this.setData({ rateBusy: false, rateError: (res && res.msg) || (res && res.netFail ? '网络异常，星级和文字已保留，请重试' : '评价暂时无法提交') });
    });
  },
  goShare() { this._go('share'); },

  // ——— 分享卡 ———
  onShareKind(e) { this.setData({ shareKind: e.detail.key }, () => this._paintCard()); },
  _card() { return shareCard.cardLines(this.data.report, this.data.topicName); },
  // 微信分享卡片图:隐藏画布按同一版式画一张(帖文 / 故事尺寸),onShareAppMessage 用它做 imageUrl
  _paintCard() {
    const kind = this.data.shareKind;
    this._cardImage = '';
    wx.nextTick(() => {
      this.createSelectorQuery().select('#share-cv').fields({ node: true, size: true }).exec(res => {
        const item = res && res[0];
        if (!item || !item.node || this._gone) return;
        const size = shareCard.SIZES[kind];
        const dpr = 2;
        const cv = item.node;
        cv.width = size.w * dpr; cv.height = size.h * dpr;
        const ctx = cv.getContext('2d');
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        this._cv = cv;
        // r3-share:徽标图读到再画;读不到也照样出卡(只少徽标里的标)
        this._loadLogo(cv).then(logo => {
          if (this._gone || this._cv !== cv) return;
          shareCard.draw(ctx, size.w, size.h, this._card(), 1, logo);
          this._cardPainted = true;
        });
      });
    });
  },
  _loadLogo(cv) {
    if (this._logo && this._logoCv === cv) return Promise.resolve(this._logo);
    return new Promise(resolve => {
      let img;
      try { img = cv.createImage(); } catch (e) { resolve(null); return; }
      img.onload = () => { this._logo = img; this._logoCv = cv; resolve(img); };
      img.onerror = () => resolve(null);
      img.src = shareCard.LOGO;
    });
  },
  _cardImageUrl() {
    if (this._cardImage) return Promise.resolve(this._cardImage);
    if (!this._cv || !this._cardPainted) return Promise.resolve('');
    return new Promise(resolve => {
      wx.canvasToTempFilePath({ canvas: this._cv, success: r => { this._cardImage = r.tempFilePath; resolve(r.tempFilePath); }, fail: () => resolve('') }, this);
    });
  },
  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },

  onShareAppMessage() {
    const report = this.data.report || {};
    const title = (this.data.topicName ? this.data.topicName + ' · ' : '') + (report.shareTitle || '一起来玩');
    const targetType = report.activityId ? 2 : 1;
    const targetId = report.activityId || report.topicId;
    const fallback = '/pages/topic/index/index?id=' + report.topicId;
    return {
      title, path: fallback,
      promise: Promise.all([api.issueReferral(app, targetType, targetId), this._cardImageUrl()]).then(([res, imageUrl]) => {
        const out = { title, path: api.ok(res) && res.data && res.data.token ? landingPath(res.data.token) : fallback };
        if (imageUrl) out.imageUrl = imageUrl;
        return out;
      })
    };
  }
});
