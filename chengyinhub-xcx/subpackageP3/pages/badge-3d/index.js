// 徽章双样式详情页。enamel=3D珐琅转台(xr-badge WebGL);glow=勋章墙同一台波点引擎渲染单枚。
const ENGINE = require('../badge-wall/index/engine.js');
const { readReducedMotion } = require('../../../utils/motion-preference.js');
const DEFAULT_BADGE_ASSET = '/subpackageP3/assets/badge-demo.png';

function decodeQueryValue(value, fallback) {
  if (typeof value !== 'string' || !value) return fallback;
  try { return decodeURIComponent(value); } catch (e) { return fallback; }
}

function resolveBadgeAsset(value) {
  const decoded = decodeQueryValue(value, '');
  if (/^https?:\/\//.test(decoded) || /^\/(images|subpackageP3)\//.test(decoded)) return decoded;
  return DEFAULT_BADGE_ASSET;
}

Page({
  data: {
    actionTop: 54,
    name: '徽章',
    sub: '',
    src: DEFAULT_BADGE_ASSET,
    fallbackSrc: DEFAULT_BADGE_ASSET,
    unsupported: false,
    renderState: 'loading',
    reducedMotion: readReducedMotion(),
    motionReduced: false,
    rendererMounted: true,
    rarity: 0,
    style: 'enamel',   // glow=二维发光大卡 / enamel=3D珐琅转台(发布者选择,双样式统一路由) / pin=勋章墙的珐琅勋章(2026-10-28)
    glyph: '',
  },
  onUnload() {
    this._renderDisposed = true;
    this._renderEpoch = (this._renderEpoch || 0) + 1;
    this._clearRendering();
  },
  _renderCurrent(epoch) {
    return !this._renderHidden && !this._renderDisposed && epoch === this._renderEpoch;
  },
  _clearRendering() {
    const xr = typeof this.selectComponent === 'function' ? this.selectComponent('#xr') : null;
    if (xr && typeof xr.disposeRenderer === 'function') xr.disposeRenderer();
    for (const key of ['_xrTimer', '_glowTimer', '_glowDetailTimer']) {
      if (this[key]) clearTimeout(this[key]);
      this[key] = null;
    }
    this._glowPending = false;
    if (this._glowEng) {
      if (this._glowEng.stop) this._glowEng.stop();
      if (this._glowEng.destroy) this._glowEng.destroy();
      this._glowEng = null;
    }
  },
  onLoad(q) {
    this._renderHidden = false;
    this._renderDisposed = false;
    this._renderEpoch = (this._renderEpoch || 0) + 1;
    let actionTop = 54;
    try {
      const r = wx.getMenuButtonBoundingClientRect && wx.getMenuButtonBoundingClientRect();
      if (r && r.top) actionTop = r.top;
    } catch (e) {}
    const src = resolveBadgeAsset(q && q.img);
    this._xrOk = false;
    this._xrLoaded = false;
    this.setData({
      actionTop,
      style: (q && q.style) === 'glow' ? 'glow' : ((q && q.style) === 'pin' ? 'pin' : 'enamel'),
      glyph: /^[A-Za-z]{1,24}$/.test((q && q.glyph) || '') ? q.glyph : 'DEFAULT',
      rarity: Math.min(4, Math.max(0, Number(q && q.rarity) || 0)),   // 波点五色轨:0普通…4神话
      name: decodeQueryValue(q && q.name, '徽章'),
      sub: decodeQueryValue(q && q.sub, ''),
      src,
      fallbackSrc: (q && q.style) === 'pin' ? '' : src,   // pin 现场画,加载那一下不先闪一张无关的示例图
      unsupported: false,
      renderState: 'loading',
      rendererMounted: true,
      motionReduced: false,
    });
    if (this.data.style === 'glow') this._scheduleGlow();
  },
  _scheduleGlow() {
    if (this._glowTimer || this._glowPending || this._glowEng) return;
    const epoch = this._renderEpoch;
    this._glowTimer = setTimeout(() => {
      if (!this._renderCurrent(epoch)) return;
      this._glowTimer = null;
      this._initGlow();
    }, 200);
  },
  // 组件 ready 回执。⚠️ 不能叫 onReady:会撞 Page 生命周期钩子(页面渲染完必触发),
  // _xrOk 恒真 ⇒ 3 秒不支持降级永不生效 —— 本次 review 抓出的真 bug
  onXrReady() {
    if (this._renderHidden || this._renderDisposed || this.data.unsupported) return;
    this._xrOk = true;
  },
  onLoaded() {
    if (this._renderHidden || this._renderDisposed || this.data.unsupported) return;
    this._xrLoaded = true;
    if (this._xrTimer) clearTimeout(this._xrTimer);
    this.setData({ unsupported: false, renderState: 'ready', motionReduced: this.data.reducedMotion });
  },
  onUnsupported(event) {
    if (this._renderHidden || this._renderDisposed || this.data.unsupported) return;
    this._clearRendering();
    const reason = event && event.detail && event.detail.reason;
    this.setData({
      unsupported: true,
      renderState: 'fallback',
      motionReduced: false,
      fallbackSrc: reason === 'asset' || !this.data.fallbackSrc ? DEFAULT_BADGE_ASSET : this.data.fallbackSrc,
    });
  },
  onFallbackError() {
    if (this._renderHidden || this._renderDisposed || this.data.renderState === 'ready') return;
    if (this.data.fallbackSrc !== DEFAULT_BADGE_ASSET) this.setData({ fallbackSrc: DEFAULT_BADGE_ASSET });
  },
  /** glow:复用勋章墙引擎,单枚居中 + 直接进 detail 特写(波点粒子全效果) */
  _initGlow() {
    const that = this;
    const epoch = this._renderEpoch;
    if (!this._renderCurrent(epoch) || this.data.unsupported || this.data.renderState !== 'loading') return;
    this._glowPending = true;
    wx.createSelectorQuery().in(this).select('#glowcv').fields({ node: true, size: true }).exec((res) => {
      if (!that._renderCurrent(epoch) || that.data.unsupported || that.data.renderState !== 'loading') return;
      that._glowPending = false;
      const item = res && res[0];
      if (!item || !item.node) { that.onUnsupported({ detail: { reason: 'renderer' } }); return; }
      let eng = null;
      try {
        const win = (wx.getWindowInfo && wx.getWindowInfo()) || {};
        eng = ENGINE.createEngine({
          canvas: item.node,
          width: item.width, height: item.height,
          pixelRatio: win.pixelRatio || 2,
          createOffscreen: (w, h) => wx.createOffscreenCanvas({ type: '2d', width: w, height: h }),
          onError: () => { if (that._renderCurrent(epoch)) that.onUnsupported({ detail: { reason: 'renderer' } }); },
        });
      } catch (e) { eng = null; }
      if (!eng) { that.onUnsupported({ detail: { reason: 'renderer' } }); return; }
      if (!that._renderCurrent(epoch) || that.data.unsupported) {
        if (eng.stop) eng.stop();
        if (eng.destroy) eng.destroy();
        return;
      }
      that._glowEng = eng;
      try {
        // 单枚:布局带收窄到屏中,setBadges 后直接 openDetail 进特写
        if (eng.setInsets) eng.setInsets(item.height * 0.30, item.height * 0.34);
        eng.setBadges([{
          name: that.data.name, en: '', iconUrl: /^https?:/.test(that.data.src) ? that.data.src : '',
          glyph: undefined, rarity: that.data.rarity, tierKey: 'T' + that.data.rarity,
          tierZh: ['普通','稀有','史诗','传说','神话'][that.data.rarity] || '徽章',
          locked: false, time: '', source: '', desc: '', cond: '',
        }]);
        if (!that._renderCurrent(epoch) || that.data.unsupported) return;
        if (that.data.reducedMotion && eng.renderStatic) {
          eng.openDetail(0);
          eng.renderStatic();
        } else {
          if (that.data.reducedMotion) { that.onUnsupported({ detail: { reason: 'renderer' } }); return; }
          eng.start();
          if (!that._renderCurrent(epoch) || that.data.unsupported) return;
          that._glowDetailTimer = setTimeout(() => {
            if (!that._renderCurrent(epoch) || that._glowEng !== eng || that.data.reducedMotion) return;
            try { eng.openDetail(0); } catch (e) { that.onUnsupported({ detail: { reason: 'renderer' } }); }
          }, 400);
        }
      } catch (e) { that.onUnsupported({ detail: { reason: 'renderer' } }); return; }
      if (!that._renderCurrent(epoch) || that.data.unsupported) return;
      that.setData({ unsupported: false, renderState: 'ready', motionReduced: !!(that.data.reducedMotion && eng.renderStatic) });
    });
  },
  // 两种样式 loading 3 秒仍未就绪即退静态图；已 ready 的迟到超时不回退。
  // ⚠️ 不能拿「没报错」当「支持」:DevTools 不支持 xr-frame 时是静默的(实测 0 console error)。
  onShow() {
    if (this._renderDisposed) return;
    this._renderHidden = false;
    if (this.data.style === 'enamel' && !this.data.unsupported && !this.data.rendererMounted) {
      this._xrOk = false;
      this._xrLoaded = false;
      const mountEpoch = this._renderEpoch;
      this.setData({ rendererMounted: true, renderState: 'loading', motionReduced: false }, () => {
        if (!this._renderCurrent(mountEpoch) || this.data.unsupported) return;
        const xr = typeof this.selectComponent === 'function' ? this.selectComponent('#xr') : null;
        // hide/show 的视图更新若被合批，原实例仍须按新代次重建，不能停在已 dispose 的 canvas。
        if (xr && typeof xr.resumeRenderer === 'function') xr.resumeRenderer();
      });
    }
    const reducedMotion = readReducedMotion();
    if (this.data.reducedMotion !== reducedMotion) this.setData({ reducedMotion });
    if (this.data.style === 'enamel') this.setData({ motionReduced: reducedMotion && this.data.renderState === 'ready' });
    if (this._glowEng) {
      try {
        if (reducedMotion) {
          if (this._glowEng.stop) this._glowEng.stop();
          if (this._glowEng.renderStatic) this._glowEng.renderStatic();
        } else if (this._glowEng.start) {
          this._glowEng.start();
        }
        this.setData({ motionReduced: !!(reducedMotion && this._glowEng.renderStatic) });
      } catch (e) { this.onUnsupported({ detail: { reason: 'renderer' } }); return; }
    }
    if (this.data.style === 'glow' && !this.data.unsupported && !this._glowEng) {
      if (this.data.renderState === 'ready') this.setData({ renderState: 'loading', motionReduced: false });
      this._scheduleGlow();
    }
    if (this.data.renderState !== 'loading') return;
    if (this._xrTimer) clearTimeout(this._xrTimer);
    const epoch = this._renderEpoch;
    this._xrTimer = setTimeout(() => {
      if (!this._renderCurrent(epoch) || this.data.renderState !== 'loading' || this.data.unsupported) return;
      if (this.data.style === 'glow') { this.onUnsupported({ detail: { reason: 'renderer' } }); return; }
      if (!this._xrLoaded && !this.data.unsupported) this.onUnsupported({ detail: { reason: this._xrOk ? 'asset' : 'renderer' } });
    }, this.data.style === 'pin' ? 6000 : 3000);   // pin 要先在手机上逐张画四面,给足时间
  },
  onHide() {
    this._renderHidden = true;
    this._renderEpoch = (this._renderEpoch || 0) + 1;
    this._clearRendering();
    if (this.data.style === 'enamel') this.setData({ rendererMounted: false });
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/roam/index' });
  },
});
