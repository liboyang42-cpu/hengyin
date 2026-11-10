const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
const shareCard = require('../../utils/share-card.js');
const app = getApp();

/* r2-finish 结算流程:整局走完后 总览 → 排行榜(队内/全体/队伍) → 评价(星级+文字,可跳过) → 分享卡(帖文/故事,保存图片,视频)。
 * 只读服务端事实:战报 /api/play/player-journey/report;评价走既有 /api/comment/add(owner_type 1 主题 / 2 场次)。
 * 视频:基础库有 wx.createMediaRecorder + wx.shareVideoMessage 时,把分享卡动画录 3 秒再走微信视频分享;没有就不出按钮。 */

const STEPS = ['overview', 'rank', 'rate', 'share'];
const TITLES = { overview: '本局结算', rank: '排行榜', rate: '评价', share: '分享' };
const MAX_REVIEW = 500;

function landingPath(token) {
  return '/subpackageJourney/pages/landing/index?k=r&t=' + encodeURIComponent(token);
}

function canRecordVideo() {
  return typeof wx !== 'undefined' && typeof wx.createMediaRecorder === 'function' && typeof wx.shareVideoMessage === 'function';
}

Page({
  data: {
    state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '',
    step: 'overview', stepIndex: 0, title: TITLES.overview, steps: STEPS,
    report: null, topicName: '', celebrate: '',
    stars: 0, starList: [1, 2, 3, 4, 5], review: '', reviewMax: MAX_REVIEW, rateBusy: false, rateError: '',
    shareKind: 'post', shareTabs: [{ key: 'post', label: '帖文' }, { key: 'story', label: '故事' }],
    cardReady: false, videoOk: false, videoBusy: false, privacyGateShow: false
  },

  onLoad(options) {
    const o = options || {};
    this._query = {
      activityId: view.isId(o.activityId) ? o.activityId : '',
      topicId: view.isId(o.topicId) ? o.topicId : '',
      runId: view.isId(o.runId) ? o.runId : ''
    };
    this.setData({ topicName: o.topicName ? decodeURIComponent(o.topicName) : '', videoOk: canRecordVideo() });
    if (!this._query.activityId && !this._query.topicId) {
      this.setData({ state: 'missing', stateKind: 'missing-param', stateTitle: '链接已失效' });
      return;
    }
    this.reload();
  },

  onUnload() { this._gone = true; if (this._videoTimer) clearTimeout(this._videoTimer); },

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
      this.setData({ state: 'ready', report, celebrate: report.win ? 'settle:' + Date.now() : '' });
    });
  },

  onStateCta() {
    if (this.data.state === 'auth') { app.requireLogin({ reason: '登录后查看结算' }).then(ok => { if (ok) this.reload(); }); return; }
    this.reload();
  },

  _go(step) {
    const i = STEPS.indexOf(step);
    if (i < 0) return;
    this.setData({ step, stepIndex: i, title: TITLES[step] });
    if (step === 'share') this._paintCard();
  },
  next() { this._go(STEPS[Math.min(STEPS.length - 1, this.data.stepIndex + 1)]); },
  // 导航返回:流程内退一步;第一步退出页面。
  onBack() {
    if (this.data.stepIndex > 0) { this._go(STEPS[this.data.stepIndex - 1]); return; }
    wx.navigateBack({ delta: 1 });
  },
  done() { wx.navigateBack({ delta: 1 }); },
  goRank() { this._go('rank'); },

  // ——— 评价 ———
  pickStar(e) { this.setData({ stars: Number(e.currentTarget.dataset.n) || 0, rateError: '' }); },
  onReview(e) { this.setData({ review: String((e.detail && e.detail.value) || '').slice(0, MAX_REVIEW), rateError: '' }); },
  skipRate() { this._go('share'); },
  submitRate() {
    const report = this.data.report || {};
    const text = this.data.review.trim();
    if (!this.data.stars) { this.setData({ rateError: '先选星级' }); return; }
    if (!text) { this.setData({ rateError: '写一句再提交' }); return; }
    if (this.data.rateBusy) return;
    const ownerType = report.activityId ? 2 : 1;
    const ownerId = report.activityId || report.topicId;
    if (!view.isId(ownerId)) { this.setData({ rateError: '这一局暂时不能评价' }); return; }
    this.setData({ rateBusy: true, rateError: '' });
    api.rate(app, ownerType, ownerId, this.data.stars, text).then(res => {
      if (this._gone) return;
      if (api.ok(res)) { this.setData({ rateBusy: false }); this._go('share'); return; }
      // 服务端原文(内容安全、频控等)照原样显示在字段下面。
      this.setData({ rateBusy: false, rateError: (res && res.msg) || (res && res.netFail ? '网络异常，星级和文字已保留，请重试' : '评价暂时无法提交') });
    });
  },

  // ——— 分享卡 ———
  onShareKind(e) { this.setData({ shareKind: e.detail.key, cardReady: false }, () => this._paintCard()); },
  _card() { return shareCard.cardLines(this.data.report, this.data.topicName); },
  _paintCard() {
    const kind = this.data.shareKind;
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
          this.setData({ cardReady: true });
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
  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },
  saveCard() {
    if (!this._cv || !this.data.cardReady) return;
    wx.canvasToTempFilePath({
      canvas: this._cv,
      success: r => wx.saveImageToPhotosAlbum({
        filePath: r.tempFilePath,
        success: () => wx.showToast({ title: '已存进相册', icon: 'none' }),
        fail: err => {
          const msg = String((err && err.errMsg) || '');
          if (msg.indexOf('auth') >= 0) { wx.openSetting({}); return; }
          if (msg.indexOf('cancel') < 0) wx.showToast({ title: '存进相册失败，请重试', icon: 'none' });
        }
      }),
      fail: () => wx.showToast({ title: '图片生成失败，请重试', icon: 'none' })
    }, this);
  },
  // 视频分享:把分享卡的入场动画录成 3 秒视频(wx.createMediaRecorder),再走微信视频分享。
  shareVideo() {
    if (!this.data.videoOk || this.data.videoBusy || !this._cv) return;
    const cv = this._cv;
    const size = shareCard.SIZES[this.data.shareKind];
    const ctx = cv.getContext('2d');
    const card = this._card();
    let recorder;
    try { recorder = wx.createMediaRecorder(cv, { fps: 30, videoBitsPerSecond: 1500, gop: 12 }); } catch (e) { recorder = null; }
    if (!recorder) { this.setData({ videoOk: false }); return; }
    this.setData({ videoBusy: true });
    const total = 90;
    let frame = 0;
    const fail = () => { if (!this._gone) { this.setData({ videoBusy: false }); wx.showToast({ title: '视频生成失败，请重试', icon: 'none' }); } };
    const tick = () => {
      if (this._gone) return;
      if (frame > total) {
        recorder.stop().then(r => {
          if (this._gone) return;
          this.setData({ videoBusy: false });
          shareCard.draw(ctx, size.w, size.h, card, 1, this._logo);
          wx.shareVideoMessage({ videoPath: r.tempFilePath, fail: err => { if (String((err && err.errMsg) || '').indexOf('cancel') < 0) fail(); } });
        }, fail);
        return;
      }
      shareCard.draw(ctx, size.w, size.h, card, Math.min(1, frame / 45), this._logo);
      frame++;
      recorder.requestFrame(tick);
    };
    recorder.start().then(tick, fail);
  },

  onShareAppMessage() {
    const report = this.data.report || {};
    const title = (this.data.topicName ? this.data.topicName + ' · ' : '') + (report.shareTitle || '一起来玩');
    const targetType = report.activityId ? 2 : 1;
    const targetId = report.activityId || report.topicId;
    const fallback = '/pages/topic/index/index?id=' + report.topicId;
    return {
      title, path: fallback,
      promise: api.issueReferral(app, targetType, targetId).then(res => ({
        title, path: api.ok(res) && res.data && res.data.token ? landingPath(res.data.token) : fallback
      }))
    };
  }
});
