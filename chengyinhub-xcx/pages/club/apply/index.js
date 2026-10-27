const app = getApp();
const toast = require('../../../utils/toast.js');
const wizardMorph = require('../../../utils/wizard-morph.js');
const roleGuard = require('../../../utils/roleGuard.js');
const { readReducedMotion } = require('../../../utils/motion-preference.js');

// D18 表单A:注册成为俱乐部主理人(身份资格 + 能力档案 → L1-L5 定级依据)。
// 填完即有:提交即 role→club;能力档案挂 club_leader,后台异步定级。建团走 club/create(表单B)。
// STEP_NAMES 只用于返回标签(短名);读屏用已渲染出来的 STEP_TITLES,
// 免得多一个「只进 aria、眼睛看不见」的死数据字段。
// 文案与 Figma「H1–H4 主理人申请 v2」四块稿逐字对齐;副标题 2026-09-06 用户定删除。
const STEP_NAMES = ['主理人资料', '组织经验', '能力自评', '资质证明'];
const STEP_TITLES = ['先认识一下你', '你带过多少场？', '你能自己做哪些？', '有资质就传一份'];

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    mode: 'intro',           // intro | form | done —— intro 是四步之前的开场屏(Figma H0)
    doneState: 'idle',       // idle | loading | ready | error，完成态必须有身份快照背书
    bootstrapState: 'checking-role', // checking-role | ready | error | blocked
    bootstrapError: '',
    step: 1,
    stepTitle: STEP_TITLES[0],
    backLabel: '设置',
    capCount: 0,
    dots: [0, 1, 2, 3],
    reducedMotion: false,
    morphing: false,
    morph: null,
    morphRun: false,
    // P-23(owner 2026-10-27 p111):已有商家记录的账号进来就说明,并给去商家端的出口,不等第 4 步才报错。
    merchantActive: false,
    // 身份
    leaderName: '',
    phone: '',
    identity: '',
    coFounders: '',
    // 经验
    experience: '',
    maxEventSize: '',
    avgEventSize: '',
    // 能力自评(0/1)
    canDesignRoute: 0,
    canDesignTask: 0,
    canNpc: 0,
    canMerchantCoop: 0,
    // 资质
    hasGuideCert: 0,
    certImages: [],
    certUploading: [],
    certErrorIndexes: [],
    expOptions: ['没有经验', '1-5场', '5-20场', '20场以上'],
    canNext: false,
    submitting: false,
    submitError: '',
    submitErrorKind: 'data',
    submissionReceipt: null
  },

  onLoad() {
    this.validate();
    this.startBootstrap();
  },

  // 减动效开关在设置页可随时改,每次回到本页重读(与 club/enroll 同写法)
  onShow() {
    const reducedMotion = readReducedMotion();
    if (this.data.reducedMotion !== reducedMotion) this.setData({ reducedMotion });
  },

  onUnload() {
    wizardMorph.cancelIntroMorph(this);
    this._bootstrapEpoch = (this._bootstrapEpoch || 0) + 1;
    this._submitEpoch = (this._submitEpoch || 0) + 1;
    this._leaderStatusEpoch = (this._leaderStatusEpoch || 0) + 1;
    this._bootstrapChecking = false;
    this._submitting = false;
    this._leaderStatusChecking = false;
  },

  startBootstrap() {
    if (this._bootstrapChecking) return;
    this._bootstrapChecking = true;
    var bootstrapEpoch = (this._bootstrapEpoch || 0) + 1;
    this._bootstrapEpoch = bootstrapEpoch;
    var that = this;
    this.setData({ bootstrapState: 'checking-role', bootstrapError: '' });
    // 账户互斥:已注册商户不能再申请俱乐部主理人(一个账户只能是商户或主理人之一)。
    // P-23:不再只摆一句「不能申请」后自动退出,而是说明原因并给去商家端的出口。
    if (this.isMerchantAccount()) {
      this._bootstrapChecking = false;
      this.setData({ bootstrapState: 'merchant', merchantActive: true });
      return;
    }
    // P-23:本地身份是玩家、但后端有商家行(申请过/停用过)的账号,后端会在第 4 步提交时拒绝
    // (ClubServiceImpl.requireNoActiveMerchantIdentity)。进页就问一次商家身份,与资格快照一起放行。
    var merchantRow;          // undefined = 未回;null = 没有商家行;{active} = 有
    var roleReady = false;
    var settle = function () {
      if (bootstrapEpoch !== that._bootstrapEpoch) return;
      if (!roleReady || merchantRow === undefined) return;
      that._bootstrapChecking = false;
      if (merchantRow) {
        that.setData({ bootstrapState: 'merchant', merchantActive: !!merchantRow.active });
        return;
      }
      that.setData({ bootstrapState: 'ready', bootstrapError: '' });
    };
    app.sendRequest({
      url: '/api/merchant/access/me',
      method: 'POST',
      data: {},
      hideLoading: true,
      silentError: true,
      success: function (res) {
        if (bootstrapEpoch !== that._bootstrapEpoch) return;
        merchantRow = merchantRowOf(res);
        settle();
      },
      // 问不到不拦人:后端提交时仍会兜底拒绝,这里只是提前说明
      fail: function (res) {
        if (bootstrapEpoch !== that._bootstrapEpoch) return;
        merchantRow = merchantRowOf(res);
        settle();
      },
    });
    // 已是主理人则不重复让其填整套表单,直接进"已成为主理人"态(去创建/管理俱乐部)
    roleGuard.load(function (snapshot) {
      if (bootstrapEpoch !== that._bootstrapEpoch) return;
      if (!snapshot || !roleGuard.hasSnapshot()) {
        that._bootstrapChecking = false;
        that.setData({
          bootstrapState: 'error',
          bootstrapError: '暂时无法确认当前账号的主理人资格，请重试',
        });
        return;
      }
      if (roleGuard.isClubLeader()) {
        that._bootstrapChecking = false;
        that.setData({ mode: 'done', doneState: 'ready', bootstrapState: 'ready' });
        return;
      }
      roleReady = true;
      settle();
    });
  },

  // P-23:去商家端。生效中的商家直接回商家首页;申请中/被拒/停用的去商家入驻页看申请状态。
  goMerchantSide() {
    if (this._navigating) return;
    this._navigating = true;
    var done = () => { this._navigating = false; };
    if (this.data.merchantActive) wx.reLaunch({ url: '/pages/merchant/index/index', complete: done });
    else wx.redirectTo({ url: '/pages/merchant/apply/index', complete: done });
  },

  retryBootstrap() { this.startBootstrap(); },

  // 账户互斥判断:商户身份(user_type==2 或 role=merchant)
  isMerchantAccount() {
    return app.getUserType() == 2 || app.getUserRole() === 'merchant';
  },

  // ===== 步骤导航 =====
  refreshStep(step) {
    // stepIn 先落再抬:大标题不随 wx:if 重挂,只能靠去掉/加回 .in 重播进场动画。
    // 步骤内容(.step)本身随 wx:if 重挂自动重播,不需要这里管。
    this.setData({
      step: step,
      stepTitle: STEP_TITLES[step - 1],
      backLabel: step === 1 ? '设置' : STEP_NAMES[step - 2],
      stepIn: false
    }, this.validate);
    var that = this;
    wx.nextTick(function () { that.setData({ stepIn: true }); });
  },

  // 开场屏 → 第一步。四步之前先给一屏说清「这是什么、要多久、填完得到什么」。
  // 那颗 CTA 会飞上去变成进度条,几何与约束见 utils/wizard-morph.js。
  // P-24(owner 2026-10-27 p98):形变条一开始就快速缩到第一段的长度,不再先画满整条、到最后才突然缩短。
  startForm() {
    if (this.data.bootstrapState !== 'ready') return;
    if (this.data.reducedMotion) {          // 减动效:直接切,不放形变
      this.setData({ mode: 'form' });
      this.refreshStep(1);
      return;
    }
    var that = this;
    wizardMorph.runIntroMorph(this, {
      segSel: '.prog__seg',
      enterData: { mode: 'form' },
      afterEnter: function () { that.refreshStep(1); },
    });
  },

  endMorph() { wizardMorph.endIntroMorph(this); },

  onBack() {
    if (this._submitting) return;
    // 第一步往回退到开场屏,不是直接摔出页面 —— 开场屏才是这条流程的起点
    if (this.data.mode === 'form' && this.data.step === 1) { this.setData({ mode: 'intro' }); return; }
    if (this.data.step === 1) { this.exitPage(); return; }
    this.refreshStep(this.data.step - 1);
  },

  exitPage() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/talent/list/index' });
  },

  onNext() {
    if (this._submitting || !this.data.canNext || this.data.bootstrapState !== 'ready') return;
    if (this.data.step < 4) { this.refreshStep(this.data.step + 1); }
    else { this.submit(); }
  },

  validate() {
    var d = this.data, ok = false;
    if (d.step === 1) ok = !!(d.leaderName && d.phone);
    else if (d.step === 2) ok = !!d.experience;
    // 创建主理人不收费；资质选填，但已有上传必须完成后才能提交。
    else if (d.step === 4) ok = !d.certUploading.length && !d.certErrorIndexes.length;
    else ok = true; // 能力自评选填
    // 「已选 N 项」要跟着四个布尔走,不能在 wxml 里算(表达式里没有求和)
    var caps = [d.canDesignRoute, d.canDesignTask, d.canNpc, d.canMerchantCoop];
    var capCount = caps.filter(Boolean).length;
    this.setData({ canNext: ok, capCount: capCount });
  },

  onInput(e) {
    var f = e.currentTarget.dataset.field;
    this.setData({ [f]: e.detail.value }, this.validate);
  },

  pickExp(e) {
    this.setData({ experience: e.currentTarget.dataset.val }, this.validate);
  },

  // 能力自评 是/否 切换(0/1)
  toggleBool(e) {
    var f = e.currentTarget.dataset.field;
    // 回调走 validate:能力自评那屏的「已选 N 项」计数就挂在它上面,不回调就不会动
    this.setData({ [f]: this.data[f] ? 0 : 1 }, this.validate);
  },

  // ===== 资质图片 =====
  addCerts() {
    var that = this;
    if (this.data.certUploading.length) return;
    var remain = 9 - this.data.certImages.length;
    if (remain <= 0) { toast('最多9张'); return; }
    var offset = this.data.certImages.length;
    app.chooseImage(function () {}, remain, {
      onUploadStart(filePaths) {
        offset = that.data.certImages.length;
        const indexes = filePaths.map((_, index) => offset + index);
        that.setData({
          certImages: that.data.certImages.concat(filePaths).slice(0, 9),
          certUploading: indexes,
          certErrorIndexes: that.data.certErrorIndexes.filter((index) => index < offset),
        }, that.validate);
      },
      onUploadDone(r, filePaths) {
        const images = that.data.certImages.slice();
        filePaths.forEach((path, index) => { images[offset + index] = r.results[index] || path; });
        const errors = that.data.certErrorIndexes.filter((index) => index < offset)
          .concat((r.failures || []).map((failure) => offset + failure.index));
        that.setData({ certImages: images.slice(0, 9), certUploading: [], certErrorIndexes: errors }, that.validate);
        return true;
      },
    });
  },

  retryCert(e) {
    const index = Number(e.detail.index);
    const path = this.data.certImages[index];
    if (!path || this.data.certUploading.length) return;
    this.setData({
      certUploading: [index],
      certErrorIndexes: this.data.certErrorIndexes.filter((value) => value !== index),
    }, this.validate);
    app.getUploadClient().uploadAll([path], {
      bizType: 'image_free',
      onDone: (r) => {
        if (r.ok && r.results[0]) {
          const images = this.data.certImages.slice();
          images[index] = r.results[0];
          this.setData({
            certImages: images,
            certUploading: [],
            certErrorIndexes: this.data.certErrorIndexes.filter((value) => value !== index),
          }, this.validate);
        } else {
          this.setData({
            certUploading: [],
            certErrorIndexes: Array.from(new Set(this.data.certErrorIndexes.concat(index))),
          }, this.validate);
        }
      },
    });
  },

  removeCert(e) {
    var i = e && e.detail ? e.detail.index : -1, arr = this.data.certImages.slice();
    if (!Number.isInteger(i) || i < 0 || i >= arr.length) return; // 异常索引直接忽略：splice(-1/undefined) 会删错图
    arr.splice(i, 1);
    this.setData({
      certImages: arr,
      certErrorIndexes: this.data.certErrorIndexes
        .filter((index) => index !== i)
        .map((index) => index > i ? index - 1 : index),
    }, this.validate);
  },

  // ===== 提交(填完即有 → /api/club/become-leader) =====
  submit() {
    if (this._submitting || !this.data.canNext || this.data.bootstrapState !== 'ready') return;
    // 账户互斥兜底:提交前再拦一次商户账户
    if (this.isMerchantAccount()) {
      toast('商家账号不能申请主理人');
      return;
    }
    this._submitting = true;
    var submitEpoch = (this._submitEpoch || 0) + 1;
    this._submitEpoch = submitEpoch;
    this.setData({ submitting: true, submitError: '', submitErrorKind: 'data' });

    // 这里只创建主理人能力档案；收费发布入口另行检查发布者实名。
    this.sendLeaderApply(submitEpoch);
  },

  sendLeaderApply(submitEpoch) {
    var d = this.data, that = this;
    app.sendRequest({
      url: '/api/club/become-leader',
      method: 'POST',
      data: JSON.stringify({
        leaderName: d.leaderName,
        phone: d.phone,
        identity: d.identity,
        coFounders: d.coFounders,
        experience: d.experience,
        hasExperience: (d.experience && d.experience !== '没有经验') ? 1 : 0,
        maxEventSize: d.maxEventSize ? parseInt(d.maxEventSize) : null,
        avgEventSize: d.avgEventSize ? parseInt(d.avgEventSize) : null,
        canDesignRoute: d.canDesignRoute,
        canDesignTask: d.canDesignTask,
        canNpc: d.canNpc,
        canMerchantCoop: d.canMerchantCoop,
        hasGuideCert: d.hasGuideCert,
        certImages: d.certImages.join(',')
      }),
      header: { 'Content-Type': 'application/json' },
      success: function (res) {
        if (submitEpoch !== that._submitEpoch) return;
        that._submitting = false;
        if (res.code == '200') {
          // 填完即有:即时回填本地 role 并清缓存,避免后续入口仍按旧 player 路由(role 抖动)
          app.setUserRole('club');
          roleGuard.clear();
          that.setData({
            mode: 'done', doneState: 'ready',
            submitting: false,
            submitError: '',
            submissionReceipt: { leaderName: d.leaderName, phone: d.phone },
          });
        } else {
          that.setData({
            submitting: false,
            submitErrorKind: 'data',
            submitError: (res && res.msg) || '提交没有完成，请稍后重试',
          });
        }
      },
      fail: function (error) {
        if (submitEpoch !== that._submitEpoch) return;
        that._submitting = false;
        var network = isTransportFailure(error);
        that.setData({
          submitting: false,
          submitErrorKind: network ? 'network' : 'data',
          submitError: network
            ? '网络异常，请检查连接后重试'
            : ((error && (error.msg || error.message)) || '提交没有完成，请稍后重试'),
        });
      }
    });
  },

  retrySubmit() { this.submit(); },

  onHeaderBack() {
    if (this._submitting) return;
    if (this.data.mode === 'done') { this.onExit(); }
    else { this.onBack(); }
  },

  retryLeaderStatus() {
    if (this._leaderStatusChecking) return;
    this._leaderStatusChecking = true;
    var statusEpoch = (this._leaderStatusEpoch || 0) + 1;
    this._leaderStatusEpoch = statusEpoch;
    var that = this;
    this.setData({ doneState: 'loading' });
    roleGuard.load(function (snapshot) {
      if (statusEpoch !== that._leaderStatusEpoch) return;
      that._leaderStatusChecking = false;
      that.setData({
        doneState: snapshot && roleGuard.hasSnapshot() && roleGuard.isClubLeader() ? 'ready' : 'error',
      });
    });
  },

  // 成为主理人后 → 去创建第一个俱乐部(表单B)
  goCreateClub() { wx.redirectTo({ url: '/pages/club/create/index' }); },

  onExit() { this.exitPage(); }
});

// /api/merchant/access/me → 这个账号有没有商家行。生效中(含商家员工)= active;
// 申请中/被拒/停用 = 有行但未生效;后端对异常商家行回 403,同样说明有行。其余(没有行/问不到)不拦。
function merchantRowOf(res) {
  if (!res || typeof res !== 'object') return null;
  if (res.code == 403) return { active: false };
  if (res.code != 200 || !res.data || typeof res.data !== 'object') return null;
  if (res.data.active === true) return { active: true };
  return ['PENDING', 'REJECTED', 'DISABLED'].indexOf(res.data.applicationState) >= 0 ? { active: false } : null;
}

function isTransportFailure(error) {
  return /request:fail|timeout/i.test(String(error && error.errMsg || ''));
}
