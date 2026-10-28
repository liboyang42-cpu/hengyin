// 门店 AI 形象:商家给自己的店配一个出现在漫游地图和店铺主页上的形象。
//
// 两条路,互斥:
//   预设   从内置的像素形象里挑一个,avatar 存编码(px1:xxx),不产生任何文件
//   照片   自己拍一张,在端上处理成像素画再上传,avatar 存图片 URL
//
// 照片为什么在端上处理而不是传原图让后端做:后端没有图像处理能力,而且原图是人脸,
// 能不上传就不上传 —— 端上处理完只传处理后的结果,原图不出手机。
const app = getApp();
const toast = require('../../../../utils/toast.js');
const modal = require('../../../../utils/modal.js');
const voiceJournal = require('../../../../utils/voice-enrollment-journal.js');
const merchantTheme = require('../../../../utils/merchant-theme.js');
const pixelAvatar = require('../../../../utils/pixel-avatar.js');
const profileReview = require('../../../../utils/profile-review.js');
const pixelPortrait = require('../../../../utils/pixel-portrait.js');
// 单文件限额与共享上传入口同一真源(生产 application.yml 10MB),避免页面自定 20MB 与服务端脱节。
const { MAX_UPLOAD_FILE_SIZE } = require('../../../../utils/transport/upload-client.js');
const { streetOnly } = require('../../utils/address-short.js');

/**
 * 2026-10-27 业主 M-28(§6.3 已确认):性格设定不再自由输入,改成三选一 chip。
 * 后端枚举由 L01 落地(personality = gentle / cute / savage);同时把中文标签写进 persona,
 * 旧后端只认 persona 时角色照样按这个性格说话。读回时优先认 personality 枚举,其次认 persona 恰好是某个标签。
 */
const PERSONALITIES = [
  { key: 'gentle', label: '温柔', desc: '轻声细语，耐心周到' },
  { key: 'cute', label: '可爱', desc: '活泼俏皮，爱用语气词' },
  { key: 'savage', label: '毒舌', desc: '嘴上不饶人，心里很热情' },
];
function personalityOf(key, persona) {
  const byKey = PERSONALITIES.filter((p) => p.key === key)[0];
  if (byKey) return byKey;
  const text = String(persona || '').trim();
  return PERSONALITIES.filter((p) => p.label === text)[0] || null;
}
const INTRO_SEEN_KEY = 'cy_ai_npc_intro_seen';

/** CU-M-81:没有坐标时的落点文案。地址只能当辅助,不能顶替「已设置点位」。 */
const STORE_POINT_UNSET = '尚未选择地图位置';

/** 预览画布的边长(px)。32 格 × 8 = 256,整数倍,不糊。 */
const PREVIEW_SCALE = 8;
/** 上传前导出的图有多大。32 格 × 12 = 384,够 marker 和店铺主页两处用。 */
const EXPORT_SCALE = 12;
const VOICE_SENTENCES = [
  '你好，欢迎来到我的小店，很高兴今天能用我的声音和你聊聊。',
  '你可以告诉我想找什么商品，我会结合你的需要，认真介绍合适的选择。',
  '如果还有价格、服务或者到店方面的问题，也可以随时问我，我会耐心回答。',
];

// 原生 canvas 不遵循外层 overflow，预览圆角须在画布内裁切；不改变上传原图。
function clipPreview(ctx, side) {
  const radius = side * 0.16;
  ctx.beginPath();
  ctx.moveTo(radius, 0);
  ctx.arcTo(side, 0, side, side, radius);
  ctx.arcTo(side, side, 0, side, radius);
  ctx.arcTo(0, side, 0, 0, radius);
  ctx.arcTo(0, 0, side, 0, radius);
  ctx.closePath();
  ctx.clip();
}

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    loading: true,
    loadError: '',
    saving: false,
    saveError: '',
    view: 'home',
    source: 'preset',
    presetIds: pixelAvatar.AVATAR_IDS,
    presetNames: Object.fromEntries(pixelAvatar.AVATAR_IDS.map(id => [id, require('../../../../utils/pixel-avatar-data.js').AVATARS[id].name])),
    presetId: pixelAvatar.AVATAR_IDS[0],
    pickId: pixelAvatar.AVATAR_IDS[0],
    photoReady: false,
    uploadedUrl: '',
    name: '',
    greeting: '',
    persona: '',
    knowledge: '',
    // M-28:性格三选一(gentle/cute/savage);personaSheet = 选性格的弹层,personaPick = 弹层里的当前勾选
    personalities: PERSONALITIES,
    personality: '',
    personaSheet: false,
    personaPick: '',
    // M-28:门店信息版式 —— 行默认只读,点底部「编辑」才可改;首次进入给一段「这是什么」的说明
    editing: false,
    introVisible: false,
    // M-28:麦克风权限被拒后,引导去设置页打开 scope.record
    voiceNeedSetting: false,
    auditStatus: null,
    auditReason: '',
    // W10b #25:服务端审核回执翻成的一句话(人设审核 / 玩家能否看到 / 形象图复核);读不到为空,不编造。
    reviewText: '',
    savedAvatar: '',
    hasProfile: false,
    presetVisible: false,
    field: null,
    discardVisible: false,
    dirty: false,
    processing: false,
    photoError: '',
    previewError: '',
    storePointText: STORE_POINT_UNSET,
    voiceVisible: false,
    voiceStatus: 0,
    voiceFile: null,
    voiceBusy: false,
    voiceError: '',
    voicePlaying: false,
    voiceSample: '',
    voiceUnknown: false,
    voiceHistory: [],
    voiceHistoryResult: '',
    voiceConsent: false,
    voiceSentences: VOICE_SENTENCES,
    voiceSentenceIndex: 0,
    voiceSentenceConfirmed: 0,
    voiceSentenceText: VOICE_SENTENCES[0],
    voiceSentenceReady: false,
    voiceSessionActive: false,
    voiceHolding: false,
    voiceFinalizing: false,
    privacyGateShow: false,
  },
  showPrivacyGate() {
    this.setData({ privacyGateShow: true });
  },
  onPrivacyGateSettled() {
    this.setData({ privacyGateShow: false });
  },
  onLoad() {
    const sys = wx.getSystemInfoSync();
    this.setData({
      statusBarHeight: sys.statusBarHeight || 20,
      navBarHeight: app.globalData.navBarHeight || 44,
    });
    let introSeen = false;
    try { introSeen = typeof wx.getStorageSync === 'function' && !!wx.getStorageSync(INTRO_SEEN_KEY); } catch (e) { introSeen = false; }
    if (!introSeen) this.setData({ introVisible: true });
    this._loadProfile();
  },
  dismissIntro() {
    this.setData({ introVisible: false });
    try { if (typeof wx.setStorageSync === 'function') wx.setStorageSync(INTRO_SEEN_KEY, 1); } catch (e) { /* 存不下只是下次再说明一遍 */ }
  },
  toggleEditing() {
    if (this.data.saving || this.data.processing) return;
    this.setData({ editing: !this.data.editing });
  },
  // 只读态下行不响应;编辑态按 data-action 分派到原来的处理函数(同门店信息 onBasicAction)
  onHomeAction(e) {
    if (!this.data.editing) return;
    const action = e.currentTarget.dataset.action;
    if (['openAvatar', 'openVoice', 'editField', 'chooseStorePoint'].indexOf(action) !== -1) this[action](e);
  },
  onShow() {
    merchantTheme.merchantPageShow();
    this._hidden = false;
    if (this._voiceClearing) {
      this._voiceClearing = false;
      this.setData({ voiceBusy: false });
      // Reentry only queries state; a missing reset response never triggers another reset.
      this.refreshVoice(this.voiceClearCurrent());
    } else if (this.data.voiceStatus === 1) this.refreshVoice();
  },
  onHide() {
    this._voiceClearEpoch = (this._voiceClearEpoch || 0) + 1;
    this._voicePageEpoch = (this._voicePageEpoch || 0) + 1;
    // Retire only this page generation's in-memory confirmation; durable UNKNOWN identities stay intact.
    this._voiceConfirming = false;
    this._voiceRiskModalOpen = false;
    this._voiceRiskModalOwner = null;
    this._hidden = true;
    this._voiceStatusVersion = (this._voiceStatusVersion || 0) + 1;
    this._voiceHoldReleased = true;
    clearTimeout(this._voiceTimer);
    if (this.data.voiceSessionActive && this._voiceRecorder) {
      this._discardVoiceStop = true;
      this._voiceRecorder.stop();
    }
    if (this._audio) this._audio.stop();
    merchantTheme.merchantPageRestore();
  },
  onUnload() {
    this._voiceClearEpoch = (this._voiceClearEpoch || 0) + 1;
    this._voicePageEpoch = (this._voicePageEpoch || 0) + 1;
    // Retire only this page generation's in-memory confirmation; durable UNKNOWN identities stay intact.
    this._voiceConfirming = false;
    this._voiceRiskModalOpen = false;
    this._voiceRiskModalOwner = null;
    this._destroyed = true;
    this._voiceHoldReleased = true;
    clearTimeout(this._voiceTimer);
    clearTimeout(this._durationTimer);
    if (this.data.voiceSessionActive && this._voiceRecorder) {
      this._discardVoiceStop = true;
      this._voiceRecorder.stop();
    }
    if (this._voiceRecorder) {
      if (typeof this._voiceRecorder.offStop === 'function') this._voiceRecorder.offStop(this._voiceRecorderOnStop);
      if (typeof this._voiceRecorder.offError === 'function') this._voiceRecorder.offError(this._voiceRecorderOnError);
    }
    if (this._audio) this._audio.destroy();
    merchantTheme.merchantPageRestore();
  },
  _request(url, data) {
    return new Promise((resolve, reject) =>
      app.sendRequest({
        url,
        method: 'POST',
        hideLoading: true,
        data: JSON.stringify(data || {}),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          if (res && String(res.code) === '200') resolve(res);
          else reject(Error((res && res.msg) || '请求失败，请重试'));
        },
        // transport=true:没拿到业务回执,写请求可能已在服务端生效(H 原音色:只有这类才进入 UNKNOWN)
        successStatusAbnormal: (res) => reject(Object.assign(Error((res && res.msg) || '请求失败，请重试'), { transport: true })),
        fail: () => reject(Object.assign(Error('网络异常，请稍后重试'), { transport: true })),
      })
    );
  },
  _loadProfile() {
    const memberId = String(app.getUserID() || '');
    const current = () => !this._destroyed && String(app.getUserID() || '') === memberId;
    this.setData({ loading: true, loadError: '' });
    this._request('/api/merchant/npc/profile')
      .then((res) => {
        if (!current()) return;
        this._photoMember = this._voiceMember = memberId;
        const d = res.data || {},
          avatar = String(d.avatar || '');
        const picked = personalityOf(d.personality, d.persona);
        this._saved = { avatar, name: d.name || '', greeting: d.greeting || '', persona: picked ? picked.label : (d.persona || ''), knowledge: d.knowledge || '' };
        (Object.assign(this.data, {hasProfile: !!avatar, savedAvatar: avatar}), this.setData({loading: false, name: this._saved.name, greeting: this._saved.greeting, persona: this._saved.persona, personality: picked ? picked.key : '', knowledge: this._saved.knowledge, auditStatus: d.auditStatus == null ? null : Number(d.auditStatus), auditReason: d.auditReason || '', voiceStatus: Number(d.voiceStatus) || 0}, () => this.restoreAvatar()));
        this.refreshVoice();
        this._loadReview();
      })
      .catch((e) => {
        if (current()) this.setData({ loading: false, loadError: e.message });
      });
    this._loadStorePoint();
  },
  _loadReview() {
    const memberId = String(app.getUserID() || '');
    const seq = (this._reviewSeq || 0) + 1;
    this._reviewSeq = seq;
    const current = () => !this._destroyed && seq === this._reviewSeq && String(app.getUserID() || '') === memberId;
    app.sendRequest({
      url: '/api/merchant/npc/review',
      method: 'GET',
      hideLoading: true,
      autoErrorToast: false,
      success: (res) => {
        if (!current()) return;
        this.setData({ reviewText: res && String(res.code) === '200' ? profileReview.merchantNpcText(res.data) : '' });
      },
      fail: () => { if (current()) this.setData({ reviewText: '' }); },
    });
  },

  /**
   * CU-M-81:漫游落点有没有设置,**判据是坐标不是地址**。
   *
   * 生产存量商家「有地址、零坐标」是常态(见 apply/index.js 的注释),只拿 address 当判据
   * 会让商家以为自己已经标好了落点,而玩家侧的漫游地图根本落不下去(坐标在
   * ApiMerchantNodeController 才被真消费)。接口本来就下发 locationLat/locationLng,
   * 所以这里按坐标判:没坐标就不冒充已设置。
   */
  _loadStorePoint() {
    this._request('/api/merchant/coop-profile')
      .then((res) => {
        if (!this._destroyed) this._applyStorePoint(res.data || {});
      })
      .catch(() => {
        // 读不到档案 ≠ 已设置:落点文案退回「尚未选择」这一侧(宁可让商家再标一次,
        // 也不能让他以为标好了)。
        if (!this._destroyed) this.setData({ storePointText: STORE_POINT_UNSET });
      });
  },

  _applyStorePoint(profile) {
    const lat = profile.locationLat;
    const lng = profile.locationLng;
    const hasPoint = lat !== null && lat !== undefined && lat !== ''
      && lng !== null && lng !== undefined && lng !== '';
    this.setData({
      // G-7:行里只显示到路名,完整地址不进设置行
      storePointText: hasPoint ? (streetOnly(profile.address) || '已选择地图位置') : STORE_POINT_UNSET,
    });
  },
  restoreAvatar() {
    const avatar = (this._saved && this._saved.avatar) || '',
      preset = pixelAvatar.isPixelAvatar(avatar);
    this._portrait = null;
    // CU-M-82:记下「进入本视图时」的形象编码当基线。空资料后端回的是空串,而预览会落到
    // 首个预设上 —— 拿预览编码直接比 savedAvatar,商家一打开就被判成有未保存修改。
    this._avatarBaseline = avatar || pixelAvatar.stringifyCode(pixelAvatar.AVATAR_IDS[0]);
    (Object.assign(this.data, {presetId: preset ? pixelAvatar.parseCode(avatar) : pixelAvatar.AVATAR_IDS[0], dirty: false}), this.setData({source: avatar && !preset ? 'photo' : 'preset', uploadedUrl: avatar && !preset ? avatar : '', photoReady: !!avatar && !preset}, () => this._drawPreview()));
  },
  openAvatar() {
    if (this.data.saving) return;
    this.setData({ view: 'avatar', saveError: '', photoError: '' }, () => this.restoreAvatar());
  },
  // CU-M-82:比的是基线而不是 savedAvatar。空资料基线 = 首个预设的编码,所以
  //「打开就返回」不弹确认,而空资料下真换一个预设照样会弹(code 真的变了)。
  // code 为空串(切到照片但没有成品)不算修改。
  updateDirty() {
    const code =
      this.data.source === 'preset' ? pixelAvatar.stringifyCode(this.data.presetId) : this.data.uploadedUrl;
    const baseline = typeof this._avatarBaseline === 'string' ? this._avatarBaseline : this.data.savedAvatar;
    (Object.assign(this.data, {dirty: !!this._portrait || (!!code && code !== baseline)}), this.setData({}));
  },
  openPresets() {
    if (this.data.saving || this.data.processing) return;
    this.setData({ presetVisible: true, pickId: this.data.presetId }, () => this._drawPresetTiles());
  },
  pickPreset(e) {
    const id = e.currentTarget.dataset.id;
    if (this.data.presetIds.indexOf(id) >= 0) this.setData({ pickId: id });
  },
  closePresets() {
    this.setData({ presetVisible: false });
  },
  confirmPreset() {
    (Object.assign(this.data, {presetId: this.data.pickId}), this.setData({source: 'preset', presetVisible: false, photoError: ''}, () => {
        this._drawPreview();
        this.updateDirty();
      }));
  },
  editField(e) {
    const key = e.currentTarget.dataset.key;
    // M-28:性格不再是文本框,走三选一弹层
    if (key === 'persona') return this.openPersona();
    if (key !== 'name' && key !== 'greeting' && key !== 'knowledge') return;
    this.setData({
      field: {
        key,
        label: key === 'name' ? '角色名字' : key === 'knowledge' ? '店铺知识' : '招呼语',
        value: this.data[key],
        initial: this.data[key],
        max: key === 'name' ? 32 : key === 'greeting' ? 60 : 2000,
        multiline: key === 'knowledge',
      },
      saveError: '',
    });
  },
  openPersona() {
    this.setData({ personaSheet: true, personaPick: this.data.personality, saveError: '' });
  },
  closePersona() {
    if (!this.data.saving) this.setData({ personaSheet: false });
  },
  pickPersona(e) {
    const key = e.currentTarget.dataset.key;
    if (this.data.saving || !personalityOf(key)) return;
    if (key === this.data.personality && this.data.hasProfile) { this.setData({ personaPick: key, personaSheet: false }); return; }
    this.setData({ personaPick: key });
    this.savePersona();
  },
  // 选定即保存:与其它字段弹层同一条持久化路径(首次建档走 save,已有档案走 persistProfile)
  savePersona() {
    if (this.data.saving) return;
    const picked = personalityOf(this.data.personaPick);
    if (!picked) return toast('选一个性格');
    if (!this.data.hasProfile) {
      this.setData({ persona: picked.label, personality: picked.key });
      if (!this.data.name.trim()) {
        toast('先给角色起个名字');
        this.setData({ personaSheet: false });
        this.editField({ currentTarget: { dataset: { key: 'name' } } });
        return;
      }
      this.save(() => this.setData({ personaSheet: false }));
      return;
    }
    this.persistProfile(
      { name: this.data.name, greeting: this.data.greeting, persona: picked.label, knowledge: this.data.knowledge, avatar: this.data.savedAvatar },
      () => this.setData({ personaSheet: false })
    );
  },
  onFieldInput(e) {
    if (!this.data.saving) this.setData({ 'field.value': e.detail.value });
  },
  closeField() {
    if (this.data.saving) return;
    if (this.data.field.value !== this.data.field.initial) {
      this._discardKind = 'field';
      this.setData({ discardVisible: true });
    } else this.setData({ field: null });
  },
  saveField() {
    if (this.data.saving) return;
    const f = this.data.field,
      value = f.value.trim();
    if (f.key === 'name' && !value) return toast('请填写角色名字');
    if (!this.data.hasProfile) {
      // CU-M-167:按钮写「保存」就得真落库。后端建档要求同时带形象
      // (ApiMerchantController `/npc/save`:avatar 为空直接拒),所以这里连同屏幕上
      // 预览着的那个形象,走和「角色形象 → 保存形象」完全相同的那条持久化路径。
      // 以前这一支只 setData 就 return:页面立刻显示新名字、弹层安静关闭,离开时却被
      // 「还没有保存」拦下 ⇒ 用户拿到的是「已保存」的回执,丢的是刚写的内容。
      if (f.key === 'name') this.setData({ name: value });
      else if (f.key === 'persona') this.setData({ persona: value });
      else if (f.key === 'knowledge') this.setData({ knowledge: value });
      else this.setData({ greeting: value });
      if (f.key !== 'name' && !this.data.name.trim()) {
        // 名字是建档必填项,缺就先回到名字这一栏继续填(与「保存形象」同一处置)
        toast('先给角色起个名字');
        this.editField({ currentTarget: { dataset: { key: 'name' } } });
        return;
      }
      this.save(() => this.setData({ field: null }));
      return;
    }
    this.persistProfile(
      {
        name: f.key === 'name' ? value : this.data.name,
        greeting: f.key === 'greeting' ? value : this.data.greeting,
        persona: f.key === 'persona' ? value : this.data.persona,
        knowledge: f.key === 'knowledge' ? value : this.data.knowledge,
        avatar: this.data.savedAvatar,
      },
      () => this.setData({ field: null })
    );
  },
  onNavBack() {
    if (this.data.saving || this.data.processing) return;
    if (this.data.view === 'avatar') {
      this.updateDirty();
      if (this.data.dirty) {
        this._discardKind = 'avatar';
        this.setData({ discardVisible: true });
        return;
      }
      this.setData({ view: 'home' }, () => this.restoreAvatar());
      return;
    }
    const saved = this._saved || { name: '', greeting: '', persona: '', knowledge: '' };
    if (this.data.name !== saved.name || this.data.greeting !== saved.greeting
      || this.data.persona !== saved.persona || this.data.knowledge !== saved.knowledge) {
      this._discardKind = 'page';
      this.setData({ discardVisible: true });
      return;
    }
    this.leavePage();
  },
  leavePage() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.redirectTo({ url: '/pages/merchant/decor/index' });
  },
  keepEditing() {
    this.setData({ discardVisible: false });
  },
  discardChanges() {
    this.setData({ discardVisible: false });
    if (this._discardKind === 'page') this.leavePage();
    else if (this._discardKind === 'field') this.setData({ field: null });
    else this.setData({ view: 'home' }, () => this.restoreAvatar());
  },
  chooseStorePoint() {
    if (this.data.saving) return;
    wx.chooseLocation({
      success: (point) =>
        this._request('/api/merchant/decor/save', {
          locationLat: point.latitude,
          locationLng: point.longitude,
          address: point.address || point.name || '',
        })
          // 选点成功后从后端回读落点状态:落库的是经纬度,页面显示的必须是服务端确实存下来的那份。
          .then(() => this._loadStorePoint())
          .catch((e) => toast(e.message)),
    });
  },
  // ===== canvas =====

  /** 拿一个 2d canvas 节点。小程序里必须走 SelectorQuery,拿不到就直接失败,不静默。 */
  _canvas(id) {
    return new Promise((resolve, reject) => {
      wx.createSelectorQuery()
        .in(this)
        .select(id)
        .fields({ node: true, size: true })
        .exec((r) => {
          const node = r && r[0] && r[0].node;
          node ? resolve(node) : reject(new Error('canvas not found: ' + id));
        });
    });
  },

  /** 画预览。预设直接画编码;照片画已经算好的网格。 */
  _drawPreview() {
    this.setData({ previewError: '' });
    if (this.data.source === 'photo' && this.data.uploadedUrl) return;
    this._canvas('#npc-preview')
      .then((cv) => {
        const side = 32 * PREVIEW_SCALE;
        cv.width = side;
        cv.height = side;
        const g = cv.getContext('2d');
        g.clearRect(0, 0, side, side);
        clipPreview(g, side);
        if (this.data.source === 'photo' && this._portrait) {
          g.fillStyle = '#EDE6D6'; /* ds-ok: 像素肖像画布底色，属于导出图像数据 */
          g.fillRect(0, 0, side, side);
          pixelPortrait.drawPortrait(g, this._portrait, 0, 0, PREVIEW_SCALE);
        } else if (this.data.source === 'photo' && this.data.uploadedUrl) {
          // 已保存过的照片形象:这一版直接用 image 标签展示,canvas 留空
          g.clearRect(0, 0, side, side);
        } else {
          pixelAvatar.drawAvatarInBox(g, pixelAvatar.stringifyCode(this.data.presetId), 0, 0, side, side);
        }
      })
      .catch(() => {
        if (!this._destroyed) this.setData({ previewError: '形象预览没能载入，请重试' });
      });
  },

  /**
   * 画预设网格里的 12 个小图。
   *
   * 每格一个 canvas 而不是一张雪碧图:预设数量以后会变,雪碧图得跟着重新导出并对齐坐标,
   * 而这些形象本来就是数据、画一遍很便宜。格子尺寸取 32 的整数倍,不让 CSS 去拉伸。
   */
  _drawPresetTiles() {
    const ids = this.data.presetIds || [];
    ids.forEach((id) => {
      this._canvas('#tile-' + id)
        .then((cv) => {
          const scale = 3;
          const side = 32 * scale;
          cv.width = side;
          cv.height = side;
          const ctx = cv.getContext('2d');
          clipPreview(ctx, side);
          pixelAvatar.drawAvatarInBox(ctx, pixelAvatar.stringifyCode(id), 0, 0, side, side);
        })
        .catch(() => {});
    });
  },

  // ===== 照片 =====

  choosePhoto(e) {
    const memberId = String(app.getUserID() || '');
    const current = () => !this._destroyed && String(app.getUserID() || '') === memberId;
    if (this.data.saving || this.data.processing) return;
    const sourceType = ['album'];
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: sourceType,
      sizeType: ['compressed'],
      success: (res) => {
        if (!current()) return;
        const file = res.tempFiles && res.tempFiles[0];
        if (!file || !file.tempFilePath) return;
        this._cropThenProcess(file.tempFilePath);
      },
    });
  },

  /** 先让商家自己裁一刀。没有人脸检测的情况下,人自己框比任何启发式都准。 */
  _cropThenProcess(src) {
    const that = this;
    const memberId = String(app.getUserID() || '');
    const current = () => !this._destroyed && String(app.getUserID() || '') === memberId;
    if (typeof wx.cropImage === 'function') {
      wx.cropImage({
        src: src,
        cropScale: '1:1',
        success: (r) => { if (current()) that._processPhoto(r.tempFilePath || src); },
        fail: (e) => {
          if (!current()) return;
          if (!/cancel/i.test(e.errMsg || '')) that.setData({ photoError: '裁剪失败，请重新选择照片' });
        },
      });
    } else {
      this._processPhoto(src);
    }
  },

  /**
   * 把照片处理成像素画。
   *
   * 画到离屏 canvas 再 getImageData —— 小程序拿不到图片的原始像素,只能过一遍 canvas。
   * 商家没裁的话按 squareCrop 猜一个框,那个默认值是拿真实半身照试出来的。
   */
  _processPhoto(tempPath) {
    const memberId = String(app.getUserID() || '');
    const current = () => !this._destroyed && String(app.getUserID() || '') === memberId;
    this.setData({ processing: true, photoError: '' });
    const that = this;
    this._canvas('#npc-work')
      .then((cv) => {
        if (!current()) return;
        const g = cv.getContext('2d');
        return new Promise((resolve, reject) => {
          const img = cv.createImage();
          img.onload = () => {
            if (!current()) { resolve(null); return; }
            const box = pixelPortrait.squareCrop(img.width, img.height);
            const SIDE = 256;
            cv.width = SIDE;
            cv.height = SIDE;
            g.clearRect(0, 0, SIDE, SIDE);
            g.drawImage(img, box.x, box.y, box.size, box.size, 0, 0, SIDE, SIDE);
            const data = g.getImageData(0, 0, SIDE, SIDE);
            resolve(pixelPortrait.buildPortrait(data.data, SIDE, SIDE, { grid: 32 }));
          };
          img.onerror = () => reject(new Error('image load fail'));
          img.src = tempPath;
        });
      })
      .then((portrait) => {
        if (!current()) return;
        that._photoMember = memberId;
        that._portrait = portrait;
        that.setData({ source: 'photo', photoReady: true, uploadedUrl: '' }, () => {
          that.setData({ processing: false });
          that._drawPreview();
          that.updateDirty();
        });
      })
      .catch(() => {
        if (!current()) return;
        that.setData({ processing: false, photoError: '照片处理失败，换一张清晰正面照片试试' });
      });
  },

  /** 把处理好的像素画导出成图片文件,准备上传。导出用更大的倍数,marker 和主页都够用。 */
  _exportPortrait() {
    const memberId = String(app.getUserID() || '');
    const current = () => !this._destroyed && String(app.getUserID() || '') === memberId;
    const that = this;
    return this._canvas('#npc-work').then((cv) => {
      if (!current()) return;
      const side = 32 * EXPORT_SCALE;
      cv.width = side;
      cv.height = side;
      const g = cv.getContext('2d');
      g.fillStyle = '#EDE6D6'; /* ds-ok: 导出像素肖像的画布底色 */
      g.fillRect(0, 0, side, side);
      pixelPortrait.drawPortrait(g, that._portrait, 0, 0, EXPORT_SCALE);
      return new Promise((resolve, reject) => {
        wx.canvasToTempFilePath({
          canvas: cv,
          fileType: 'png',
          success: (r) => resolve(r.tempFilePath),
          fail: reject,
        });
      });
    });
  },

  _upload(filePath) {
    return new Promise((resolve, reject) => {
      app.getUploadClient().uploadAll([filePath], {
        bizType: 'image_1_1',
        mapResult: (d) => d.url,
        onDone: (r) => {
          if (r.ok && r.results && r.results[0]) resolve(r.results[0]);
          else reject(new Error((r.failures && r.failures[0] && r.failures[0].msg) || '上传失败'));
        },
      });
    });
  },

  persistProfile(payload, onSuccess) {
    const memberId = String(app.getUserID() || '');
    const current = () => !this._destroyed && String(app.getUserID() || '') === memberId;
    if (this.data.saving) return;
    this.setData({ saving: true, saveError: '' });
    // M-28:性格枚举随 persona 标签一起提交(L01 后端读 personality;旧后端忽略未知字段、只用 persona)
    const picked = personalityOf('', payload.persona);
    const body = picked ? Object.assign({}, payload, { personality: picked.key }) : payload;
    return this._request('/api/merchant/npc/save', body)
      .then((res) => {
        if (!current()) return;
        const receipt = res && res.receipt;
        this._saved = Object.assign({}, payload);
        this._portrait = null;
        (Object.assign(this.data, {hasProfile: true, savedAvatar: payload.avatar, dirty: false}), this.setData({saving: false, name: payload.name, greeting: payload.greeting, persona: payload.persona || '', personality: picked ? picked.key : '', knowledge: payload.knowledge || '', auditStatus: 0, auditReason: ''}));
        this._reviewSeq = (this._reviewSeq || 0) + 1;
        this.setData({ reviewText: profileReview.merchantNpcText(receipt) });
        if (!receipt) this._loadReview();
        if (onSuccess) onSuccess();
        // 保存 ≠ 上线:改动会回到待审,审核通过前玩家看不到。
        toast.success(receipt && receipt.review === 'APPROVED' ? '角色已更新' : '已提交审核');
      })
      .catch((e) => {
        if (current()) this.setData({ saving: false, saveError: e.message });
      });
  },
  /**
   * 保存角色形象。`onSaved` 是给「名字弹层首次建档」(CU-M-167)用的收尾回调 ——
   * 它要关掉弹层留在原地,而不是像形象页那样退回 home。wxml 的 bindtap 会把事件对象
   * 当第一个参数传进来,所以只认函数。
   */
  save(onSaved) {
    const memberId = String(app.getUserID() || '');
    const current = () => !this._destroyed && String(app.getUserID() || '') === memberId;
    if (this.data.saving || this.data.processing) return;
    if (!this.data.name.trim()) return this.editField({ currentTarget: { dataset: { key: 'name' } } });
    if (this.data.source === 'photo' && this._photoMember !== memberId)
      return toast('登录状态已变更，请重新选择照片');
    if (this.data.source === 'photo' && !this.data.photoReady)
      return toast('先选择一张照片');
    this.setData({ saving: true, saveError: '' });
    const ready =
      this.data.source === 'preset'
        ? Promise.resolve(pixelAvatar.stringifyCode(this.data.presetId))
        : this._portrait
        ? this._exportPortrait().then((path) => current() ? this._upload(path) : Promise.reject(Error('登录状态已变更')))
        : Promise.resolve(this.data.uploadedUrl);
    ready
      .then((avatar) => {
        if (!current()) return;
        if (!avatar) throw Error('形象未就绪');
        // 上传成功后复用 URL，保存失败重试不重复上传。
        if (this.data.source === 'photo') {
          this._portrait = null;
          this.setData({ uploadedUrl: avatar });
        }
        this.setData({ saving: false });
        return this.persistProfile(
          { name: this.data.name.trim(), greeting: this.data.greeting.trim(), persona: this.data.persona.trim(), knowledge: this.data.knowledge.trim(), avatar },
          () => {
            if (typeof onSaved === 'function') onSaved();
            else this.setData({ view: 'home' }, () => this.restoreAvatar());
          }
        );
      })
      .catch((e) => {
        if (current()) this.setData({ saving: false, saveError: e.message });
      });
  },
  openVoice() {
    if (!this.data.hasProfile) {
      toast('请先保存角色形象');
      return;
    }
    this.setData({ voiceVisible: true, voiceError: '' });
    this.refreshVoice();
  },
  closeVoice() {
    if (this.data.voiceBusy || this.data.voiceSessionActive || this.data.voiceFinalizing) return;
    this._voiceHoldReleased = true;
    this.setData({ voiceVisible: false });
    if (this._audio) this._audio.stop();
  },
  _voiceContext() {
    const epoch = this._voicePageEpoch || 0;
    return voiceJournal.context(app, wx, () => !this._destroyed && !this._hidden && (this._voicePageEpoch || 0) === epoch);
  },
  _voiceObserve(context, requestId, value) {
    if (!context.current()) return false;
    if (value.voiceSelected !== false && /^media-asset:[1-9][0-9]*$/.test(this.data.voiceSample)
        && value.voiceSample !== this.data.voiceSample) throw Error('返回结果与这段录音不一致，请查询原请求');
    const observed = voiceJournal.observe(wx, context.member, 'merchant', requestId, value);
    this.setData({ voiceHistory: voiceJournal.recover(wx, context.member, 'merchant', value.voiceHistory).records });
    if (!observed.selected) return false;
    this._voiceDisplayContext = context;
    const uncertain = ['IN_FLIGHT', 'UNKNOWN'].includes(value.voiceIntentState);
    this.setData({ voiceStatus: Number(value.voiceStatus), voiceSample: value.voiceSample || this.data.voiceSample,
      voiceUnknown: uncertain, voiceFile: uncertain || value.voiceIntentState === 'NOT_SENT' ? this.data.voiceFile : null });
    return true;
  },
  refreshVoice(clearCurrent) {
    if (typeof clearCurrent !== 'function') clearCurrent = undefined;
    let context, pending;
    try { context = this._voiceContext(); pending = voiceJournal.load(wx, context.member, 'merchant'); }
    catch (e) { this.setData({ voiceError: e.message }); return Promise.resolve(); }
    const version = this._voiceStatusVersion = (this._voiceStatusVersion || 0) + 1;
    const current = () => (!clearCurrent || clearCurrent()) && context.current() && this._voiceStatusVersion === version;
    clearTimeout(this._voiceTimer);
    if (!current()) return Promise.resolve();
    this.setData({ voiceHistory: pending.records });
    return this._request('/api/merchant/npc/voice/status', { voiceRequestId: pending.active })
      .then((res) => {
        if (!current()) return;
        const value = res.data || res;
        if (!value.voiceRequestId && !pending.active) {
          this._voiceDisplayContext = context;
          this.setData({ voiceStatus: Number(value.voiceStatus) || 0, voiceSample: value.voiceSample || '' });
          if (Number(value.voiceStatus) === 1) this._voiceTimer = setTimeout(() => { if (current()) this.refreshVoice(clearCurrent); }, 5000);
          return;
        }
        if (this._voiceObserve(context, pending.active, value) && value.voiceIntentState === 'PENDING')
          this._voiceTimer = setTimeout(() => { if (current()) this.refreshVoice(clearCurrent); }, 5000);
      }).catch((e) => {
        if (!current()) return;
        this.setData({ voiceError: e.message });
        if (this.data.voiceStatus === 1)
          this._voiceTimer = setTimeout(() => { if (current()) this.refreshVoice(clearCurrent); }, 5000);
      });
  },
  readVoiceHistory(e) {
    let context;
    try { context = this._voiceContext(); } catch (error) { this.setData({ voiceError: error.message }); return; }
    const requestId = e.currentTarget.dataset.request;
    const pending = voiceJournal.load(wx, context.member, 'merchant');
    if (!pending.records.some(row => row.requestId === requestId)) return;
    this._request('/api/merchant/npc/voice/status', { voiceRequestId: requestId }).then(res => {
      if (!context.current()) return;
      const value = res.data || res;
      voiceJournal.observe(wx, context.member, 'merchant', requestId, value);
      this.setData({ voiceHistory: voiceJournal.load(wx, context.member, 'merchant').records,
        voiceHistoryResult: '原请求状态：' + value.voiceIntentState });

    }).catch(error => { if (context.current()) this.setData({ voiceError: error.message }); });
  },
  confirmNewVoiceRequest() {
    if (this._voiceConfirming || this._voiceRiskModalOpen || this.data.voiceBusy || !this.data.voiceUnknown) return;
    let context, original;
    try { context = this._voiceContext(); original = voiceJournal.load(wx, context.member, 'merchant').active; }
    catch (e) { this.setData({ voiceError: e.message }); return; }
    this._voiceConfirming = true;
    // Prefer querying the original known voice id/result; status never creates a new enrollment.
    return this.refreshVoice().then(() => {
      if (!context.current() || voiceJournal.load(wx, context.member, 'merchant').active !== original) return;
      if (this.data.voiceUnknown) this._confirmNewVoiceAfterRead();
    }).finally(() => { if (context.current()) this._voiceConfirming = false; });
  },
  _confirmNewVoiceAfterRead() {
    if (this.data.voiceBusy || !this.data.voiceUnknown || !this.data.voiceConsent) return;
    let context, original;
    try {
      context = this._voiceContext();
      const pending = voiceJournal.load(wx, context.member, 'merchant');
      original = pending.records.find(row => row.requestId === pending.active);
      if (!original || !original.intentId) throw Error('请先查询原请求，保留未知记录');
      if (!/^media-asset:[1-9][0-9]*$/.test(this.data.voiceSample)) throw Error('请先查询原录音身份');
    } catch (e) { this.setData({ voiceError: e.message }); return; }
    const sample = this.data.voiceSample;
    const confirmation = {};
    this._voiceRiskModalOwner = confirmation;
    this._voiceRiskModalOpen = true;
    modal.show({ title: '另起独立声音请求？',
      content: '原结果仍可能完成。新建可能产生重复音色或占用额度，费用以实际模型为准；原未知记录会保留，系统不会重发原请求。', confirmText: '确认新建',
      success: result => {
        if (!context.current() || this._voiceRiskModalOwner !== confirmation) return;
        this._voiceRiskModalOwner = null;
        this._voiceRiskModalOpen = false;
        if (!result || !result.confirm) return;
        this.setData({ voiceBusy: true, voiceError: '' });
        app.requestSeparateConsent('voice', context.current).then(() => {
          if (!context.current()) return;
          const row = voiceJournal.begin(wx, context.member, 'merchant', original.requestId, true);
          return this._sendVoiceIntent(context, row, sample);
        }).catch(e => { if (context.current()) this.setData({ voiceBusy: false, voiceError: e.message }); });
      }, complete: () => { if (context.current() && this._voiceRiskModalOwner === confirmation) {
          this._voiceRiskModalOwner = null; this._voiceRiskModalOpen = false;
        } } });
  },
  _sendVoiceIntent(context, row, sample) {
    if (!context.current()) return Promise.resolve();
    voiceJournal.dispatched(wx, context.member, 'merchant', row);
    this._voiceStatusVersion = (this._voiceStatusVersion || 0) + 1;
    return this._request('/api/merchant/npc/voice/enroll', { voiceSample: sample, voiceConsent: this.data.voiceConsent,
      voiceRequestId: row.requestId, voicePreviousIntentId: row.previousIntentId,
      voiceDuplicateChargeConsent: row.previousIntentId !== null }).then(res => {
        if (!context.current()) return;
        if (!this._voiceObserve(context, row.requestId, res.data || res)) return;
        this.setData({ voiceBusy: false });
        return this.refreshVoice();
      }).catch(e => {
        if (context.current() && voiceJournal.load(wx, context.member, 'merchant').active === row.requestId) {
          this.setData({ voiceBusy: false, voiceUnknown: e.message !== '语音服务未开通', voiceError: e.message || '原请求结果未知，请查询状态' });
          if (e.message !== '语音服务未开通') this.refreshVoice();
        }
      });
  },
  onVoiceConsentChange(e) {
    this.setData({ voiceConsent: !!(e.detail && e.detail.checked), voiceError: '' });
  },
  voiceRecorder() {
    if (this._voiceRecorder) return this._voiceRecorder;
    const memberId = String(app.getUserID() || '');
    const context = this._voiceContext();
    this._voiceRecorderContext = context;
    this._voiceRecorderMember = memberId;
    const recorder = wx.getRecorderManager();
    this._voiceRecorderOnStop = (res) => {
      if (!context.current()) return;
      const durationMs = Number(res && res.duration) || 0;
      const size = Number(res && res.fileSize) || 0;
      if (this._discardVoiceStop) {
        this._discardVoiceStop = false;
        this.setData({
          voiceFile: null,
          voiceHolding: false,
          voiceSessionActive: false,
          voiceFinalizing: false,
          voiceSentenceIndex: 0,
          voiceSentenceConfirmed: 0,
          voiceSentenceText: VOICE_SENTENCES[0],
          voiceSentenceReady: false,
        });
        return;
      }
      if (this.data.voiceSentenceConfirmed < VOICE_SENTENCES.length) {
        this.setData({
          voiceFile: null,
          voiceHolding: false,
          voiceSessionActive: false,
          voiceFinalizing: false,
          voiceSentenceIndex: 0,
          voiceSentenceConfirmed: 0,
          voiceSentenceText: VOICE_SENTENCES[0],
          voiceSentenceReady: false,
          voiceError: '录音提前结束，请重新录完三句话',
        });
        return;
      }
      this.setData({ voiceHolding: false, voiceSessionActive: false, voiceFinalizing: false });
      if (!res || !res.tempFilePath) return this.setData({ voiceError: '录音没有保存下来，请重试' });
      if (durationMs < 10000) return this.setData({ voiceFile: null, voiceSentenceIndex: 0, voiceSentenceConfirmed: 0, voiceSentenceText: VOICE_SENTENCES[0], voiceError: '请至少录满 10 秒' });
      if (!Number.isFinite(size) || size <= 0) return this.setData({ voiceFile: null, voiceSentenceIndex: 0, voiceSentenceConfirmed: 0, voiceSentenceText: VOICE_SENTENCES[0], voiceError: '录音文件为空或无效，请重新录制' });
      if (size > MAX_UPLOAD_FILE_SIZE) return this.setData({ voiceFile: null, voiceSentenceIndex: 0, voiceSentenceConfirmed: 0, voiceSentenceText: VOICE_SENTENCES[0], voiceError: '录音过大，请缩短后重录（最大10MB）' });
      this._voiceFileContext = context;
      this.setData({
        voiceFile: {
          path: res.tempFilePath,
          name: '现场录音.mp3',
          size,
          duration: Math.round(durationMs / 1000),
          sizeText: (size / 1048576).toFixed(1),
        },
        voiceError: '',
      });
    };
    this._voiceRecorderOnError = () => {
      if (context.current()) this.setData({
        voiceFile: null,
        voiceHolding: false,
        voiceSessionActive: false,
        voiceFinalizing: false,
        voiceSentenceIndex: 0,
        voiceSentenceConfirmed: 0,
        voiceSentenceText: VOICE_SENTENCES[0],
        voiceSentenceReady: false,
        voiceError: '录音出错了，请重试',
      });
    };
    recorder.onStop(this._voiceRecorderOnStop);
    recorder.onError(this._voiceRecorderOnError);
    this._voiceRecorder = recorder;
    return recorder;
  },
  onVoiceHoldStart() {
    const memberId = String(app.getUserID() || '');
    let context;
    try { context = this._voiceContext(); } catch (e) { this.setData({ voiceError: e.message }); return; }
    const current = context.current;
    // shortcut: 跨账号的原生录音句柄需重进页面释放；要支持同页换号录制时再重建会话。
    if (this._voiceRecorder && (!this._voiceRecorderContext || !this._voiceRecorderContext.current())) {
      this.setData({ voiceError: '登录状态已变更，请退出页面后重新录音' });
      return;
    }
    if (this.data.voiceBusy || this.data.voiceStatus === 1 || this.data.voiceUnknown || this.data.voiceSentenceReady || this.data.voiceFinalizing || this.data.voiceHolding) return;
    if (!this.data.voiceConsent) {
      this.setData({ voiceError: '请确认这是本人声音或已获得声音使用授权' });
      return;
    }
    this._voiceHoldReleased = false;
    const recorder = this.voiceRecorder();
    const begin = () => {
      if (!current() || this._hidden || this._voiceHoldReleased) return;
      this._voiceMember = memberId;
      this.audio().stop();
      if (this.data.voiceSessionActive) recorder.resume();
      else recorder.start({ duration: 60000, format: 'mp3', sampleRate: 16000, numberOfChannels: 1 });
      this.setData({
        voiceHolding: true,
        voiceSessionActive: true,
        voiceFile: null,
        voiceError: '',
        voiceNeedSetting: false,
      });
    };
    if (this.data.voiceSessionActive) {
      begin();
      return;
    }
    if (this._voiceStarting) return;
    this._voiceStarting = true;
    wx.authorize({ scope: 'scope.record' })
      .then(() => {
        this._voiceStarting = false;
        begin();
      })
      .catch(() => {
        this._voiceStarting = false;
        // M-28:被拒过一次后 wx.authorize 不会再弹窗,只能去设置页打开 —— 给一颗直达设置的按钮
        if (current()) this.setData({ voiceHolding: false, voiceNeedSetting: true, voiceError: '没有录音权限，去设置打开麦克风后再录' });
      });
  },
  // 必须由用户点击直接触发(wx.openSetting 的调用约束),所以挂在按钮的 bindtap 上
  openRecordSetting() {
    if (typeof wx.openSetting !== 'function') return;
    wx.openSetting({
      success: (res) => {
        const granted = !!(res && res.authSetting && res.authSetting['scope.record']);
        if (granted && !this._destroyed) this.setData({ voiceNeedSetting: false, voiceError: '' });
      },
    });
  },
  onVoiceHoldEnd() {
    this._voiceHoldReleased = true;
    if (!this.data.voiceHolding || !this._voiceRecorder) return;
    this._voiceRecorder.pause();
    this.setData({
      voiceHolding: false,
      voiceSentenceReady: true,
    });
  },
  confirmVoiceSentence() {
    if (!this.data.voiceSentenceReady || !this.data.voiceSessionActive) return;
    const confirmed = this.data.voiceSentenceIndex + 1;
    if (confirmed < VOICE_SENTENCES.length) {
      this.setData({
        voiceSentenceIndex: confirmed,
        voiceSentenceConfirmed: confirmed,
        voiceSentenceText: VOICE_SENTENCES[confirmed],
        voiceSentenceReady: false,
      });
      return;
    }
    this.setData({
      voiceSentenceConfirmed: confirmed,
      voiceSentenceReady: false,
      voiceSessionActive: false,
      voiceFinalizing: true,
    });
    this._voiceRecorder.stop();
  },
  // BL-43:清除已生成的角色声音(POST /api/merchant/npc/voice/reset),二次确认后回读 voice/status。
  // 「重新录音」只清本地录音,服务端旧音色在新音色生成前仍会被用来合成;这里才是真正停用旧音色。
  voiceClearCurrent() {
    const owner = String(app.getUserID() || '');
    const token = app.getAuthorization ? app.getAuthorization() || '' : '';
    const epoch = this._voiceClearEpoch = (this._voiceClearEpoch || 0) + 1;
    return () => !this._destroyed && !this._hidden && epoch === this._voiceClearEpoch
      && owner === String(app.getUserID() || '')
      && token === (app.getAuthorization ? app.getAuthorization() || '' : '');
  },
  clearVoice() {
    if (this.data.voiceBusy || this.data.voiceUnknown || this._voiceClearing) return;
    let context;
    try { context = this._voiceContext(); } catch (e) { this.setData({ voiceError: e.message }); return; }
    if (this._voiceDisplayContext && !this._voiceDisplayContext.current()) return;
    const current = this.voiceClearCurrent();
    modal.show({
      title: '清除角色声音？',
      content: '清除后店铺角色不再用这段声音说话，需要重新录音生成才能恢复。',
      confirmText: '清除',
      danger: true,
      success: (res) => { if (res && res.confirm && context.current() && current()) this._doClearVoice(context); },
    });
  },
  _doClearVoice(context) {
    if (this._voiceClearing || this._hidden || this._destroyed || !context || !context.current()) return;
    const current = this.voiceClearCurrent();
    this._voiceClearing = true;
    if (this._audio) this._audio.stop();
    this.setData({ voiceBusy: true, voiceError: '', voicePlaying: false });
    this._request('/api/merchant/npc/voice/reset')
      .then(() => context.current() && current() ? this.refreshVoice(current) : null)
      .then(() => {
        if (!context.current() || !current()) return;
        this._voiceClearing = false;
        if (this.data.voiceStatus === 0 && !this.data.voiceError) {
          this.setData({ voiceBusy: false, voiceSample: '' });
          toast('角色声音已清除');
        } else {
          this.setData({ voiceBusy: false, voiceError: this.data.voiceError || '声音状态还没更新，请稍后刷新再看' });
        }
      })
      .catch((e) => {
        if (!context.current() || !current()) return;
        this._voiceClearing = false;
        this.setData({ voiceBusy: false, voiceError: e.message });
      });
  },
  resetVoiceRecording() {
    if (this.data.voiceBusy || this.data.voiceUnknown) return;
    if (this._audio) this._audio.stop();
    if (this.data.voiceSessionActive && this._voiceRecorder) {
      this._discardVoiceStop = true;
      this._voiceRecorder.stop();
    }
    this._voiceHoldReleased = true;
    this.setData({
      voiceFile: null,
      voiceStatus: 0,
      voiceError: '',
      voicePlaying: false,
      voiceSentenceIndex: 0,
      voiceSentenceConfirmed: 0,
      voiceSentenceText: VOICE_SENTENCES[0],
      voiceSentenceReady: false,
      voiceSessionActive: false,
      voiceHolding: false,
      voiceFinalizing: false,
    });
  },
  audio() {
    if (this._audio) return this._audio;
    const audio = wx.createInnerAudioContext();
    audio.obeyMuteSwitch = false;
    audio.onEnded(() => {
      if (!this._destroyed) this.setData({ voicePlaying: false });
    });
    audio.onStop(() => {
      if (!this._destroyed) this.setData({ voicePlaying: false });
    });
    audio.onError(() => {
      if (!this._destroyed) this.setData({ voicePlaying: false, voiceError: '录音无法播放，请重新选择' });
    });
    this._audio = audio;
    return audio;
  },
  playVoice() {
    const original = this.data.voiceFile ? this._voiceFileContext : this._voiceDisplayContext;
    if (!original || !original.current()) {
      if (this._audio) this._audio.stop();
      this.setData({ voicePlaying: false, voiceError: '登录或页面已变化，请重新读取声音' }); return;
    }
    const audio = this.audio();
    if (this.data.voicePlaying) {
      audio.stop();
      return;
    }
    const src = this.data.voiceFile ? this.data.voiceFile.path : this.data.voiceSample;
    if (!src) return;
    audio.src = src;
    audio.play();
    this.setData({ voicePlaying: true });
  },
  uploadVoice() {
    if (this.data.voiceBusy || this.data.voiceStatus === 1 || this.data.voiceUnknown || !this.data.voiceFile) return;
    let context, pending;
    try { context = this._voiceContext(); pending = voiceJournal.load(wx, context.member, 'merchant'); }
    catch (e) { this.setData({ voiceError: e.message }); return; }
    if ((this._voiceFileContext && !this._voiceFileContext.current()) || this._voiceMember !== context.member || !this.data.voiceConsent) {
      this.setData({ voiceError: '请确认当前账号的声音使用授权并重新录音' }); return;
    }
    const file = this.data.voiceFile;
    this.audio().stop(); this.setData({ voiceBusy: true, voiceError: '' });
    const upload = () => file.url && /^media-asset:[1-9][0-9]*$/.test(file.url) ? Promise.resolve(file.url)
      : new Promise((resolve, reject) => app.getUploadClient().uploadAll([file.path], {
        fileSizes: [file.size], mapResult: d => d.assetId ? 'media-asset:' + d.assetId : '',
        onDone: r => { if (r.ok && r.results[0]) resolve(r.results[0]); else {
          const failure = r.failures && r.failures[0];
          reject(Error(failure && failure.reason === 'oversize' ? failure.msg : '录音上传失败或缺少资产身份，文件已保留'));
        } } }));
    app.requestSeparateConsent('voice', context.current).then(() => context.current() ? upload() : null)
      .then(sample => {
        if (!context.current()) return;
        this.setData({ 'voiceFile.url': sample, voiceSample: sample });
        const row = voiceJournal.begin(wx, context.member, 'merchant', pending.active, false);
        return this._sendVoiceIntent(context, row, sample);
      }).catch(e => { if (context.current()) this.setData({ voiceBusy: false, voiceError: e.message }); });
  },
});
