// 通用分享层(2026-10-31 业主批准 P-8):活动 / 主题 / 勋章共用的全屏分享页。一张卡、四个色块、一排渠道。
// 做成组件挂在宿主页上:「微信好友」是 open-type=share,走宿主页自己的 onShareAppMessage(活动页分享这场活动,
// 漫游页不带实时地图截图),不另起一个路由。
// 渠道只承诺做得到的事:微信好友 = 小程序卡片;朋友圈 / 小红书 = 存卡片图 + 复制文案(平台不能代码发图文,P-8 ④ 已批准);
// 保存图片 = 存相册;复制文案 = 复制标题与搜索提示(小程序运行时生成不了网页链接);更多 = 系统图片分享菜单。
// 卡片图用 canvas 2d 按预览同一版式画(预览 = 导出图),码由后端按 scene 永久复用。
// r3-share(refs4/share-page-cards):每种内容一张整色卡 —— 上半封面 / 勋章,下沿一道弧,城瘾徽标压在弧上,
// 眉题胶囊 + 两行大标题 + 一行副信息;卡下一颗主按钮「发给微信好友」,其余渠道缩成一排小圆钮。
const cyToast = require('../../../utils/toast.js');
const cyModal = require('../../../utils/modal.js');
const { ALBUM_SAVE, classifyAlbumSaveError } = require('../../../utils/album-save-outcome.js');
const { tintFromPixels } = require('../../../utils/share-tint.js');
const { fitRows } = require('../../../utils/share-lines.js');

const KINDS = {
  activity: { label: '活动', colors: ['#5A3426', '#8E6430', '#1E2A24', '#121317'] }, /* ds-ok P-8 分享卡底色色块(业主批准稿) */
  theme: { label: '主题', colors: ['#7A2619', '#B8862F', '#2C2A26', '#121317'] }, /* ds-ok P-8 分享卡底色色块(业主批准稿) */
  medal: { label: '勋章', colors: ['#5A3D12', '#8E6A26', '#1D1A14', '#121317'] }, /* ds-ok P-8 分享卡底色色块(业主批准稿) */
  // 第九轮 m1-9:商家主页分享店铺,版式同活动卡(封面 / 店名 / 地址),码是店铺码
  shop: { label: '店铺', colors: ['#5A3426', '#8E6430', '#1E2A24', '#121317'] }, /* ds-ok P-8 分享卡底色色块(业主批准稿,同活动) */
};
const CHANNELS = [
  { key: 'moments', label: '朋友圈', icon: 'image' },
  { key: 'xhs', label: '小红书', icon: 'copy' },
  { key: 'save', label: '保存图片', icon: 'download' },
  { key: 'copy', label: '复制文案', icon: 'connect' },
  { key: 'more', label: '更多', icon: 'more' },
];
const W = 750;
const H = 1200;
const LOGO = '/images/cy_logo_mark.png';
const ART = '/components/cy/share-card/route-art.png'; // 没有封面时的上半插画(预览同一张)

/** 卡上的眉题与副信息(纯函数,预览与导出图共用):没有的行不出 */
function cardText(kind, meta, date, when, stops) {
  const join = (parts) => parts.filter(Boolean).join(' · ');
  if (kind === 'shop') return { eyebrow: '店铺', sub: when };
  if (kind === 'activity') return { eyebrow: '活动', sub: join([date, when]) };
  if (kind === 'medal') return { eyebrow: '勋章', sub: meta };
  return { eyebrow: meta ? '主题 · ' + meta : '主题', sub: join(stops) };
}

Component({
  properties: {
    show: { type: Boolean, value: false },
    // { kind: activity | theme | medal, id, title, meta, cover, date, when, stops: [] }
    card: { type: Object, value: {} },
  },
  data: {
    statusBarHeight: 20, kind: 'theme', label: '主题', title: '', meta: '', cover: '', eyebrow: '主题', sub: '',
    colors: KINDS.theme.colors, colorIndex: 0, bg: KINDS.theme.colors[0], qr: '', channels: CHANNELS,
  },
  observers: {
    'show, card': function (show, card) {
      if (!show) return;
      const app = getApp();
      const c = card || {};
      const kind = KINDS[c.kind] ? c.kind : 'theme';
      this._id = String(c.id || '');
      this._image = '';
      this._open = (this._open || 0) + 1;
      const stops = (Array.isArray(c.stops) ? c.stops : []).filter(Boolean).slice(0, 5);
      const text = cardText(kind, c.meta || '', c.date || '', c.when || '', stops);
      this.setData({
        statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 20,
        kind, label: KINDS[kind].label, title: c.title || KINDS[kind].label,
        meta: c.meta || '',
        cover: c.cover && app.getImgUrl ? app.getImgUrl(c.cover) : (c.cover || ''),
        eyebrow: text.eyebrow, sub: text.sub,
        colors: KINDS[kind].colors, colorIndex: 0, bg: KINDS[kind].colors[0], qr: '',
      });
      this._loadCode();
      this._loadTint();
    },
  },
  methods: {
  noop() {},

  _loadCode() {
    const app = getApp();
    const kind = this.data.kind; const id = this._id;
    const done = (res) => {
      const url = res && (res.code == 200 || res.code == '200') && res.data && res.data.qrcodeUrl;
      if (url) this.setData({ qr: app.getImgUrl ? app.getImgUrl(url) : url });
    };
    if (kind === 'shop') {
      if (/^\d{1,18}$/.test(id)) app.sendRequest({ url: '/api/merchant/share-code', method: 'POST', hideLoading: true, data: { memberId: id }, success: done });
      return;
    }
    if (kind === 'activity' && /^\d{1,18}$/.test(id)) {
      app.sendRequest({ url: '/api/official/events/' + id + '/share-code', method: 'POST', hideLoading: true, success: done });
      return;
    }
    // 主题出主题码;勋章出首页码(不带 id)
    app.sendRequest({ url: '/api/topic/share-code', method: 'POST', hideLoading: true,
      data: kind === 'theme' && /^\d{1,18}$/.test(id) ? { id } : {}, success: done });
  },

  /* qx-a7 V1-P36(Spotify):第一个色块取封面主色,默认就用它;取不到就保留原四个色块。
     用户已经点过别的色块就不再改底色。 */
  _loadTint() {
    const src = this.data.cover;
    const open = this._open;
    if (!src || typeof wx.createOffscreenCanvas !== 'function') return;
    let off;
    try { off = wx.createOffscreenCanvas({ type: '2d', width: 24, height: 24 }); } catch (e) { return; }
    if (!off || typeof off.createImage !== 'function') return;
    const img = off.createImage();
    img.onload = () => {
      if (open !== this._open || !this.data.show) return;
      let tint = '';
      try {
        const g = off.getContext('2d');
        g.drawImage(img, 0, 0, 24, 24);
        tint = tintFromPixels(g.getImageData(0, 0, 24, 24).data);
      } catch (e) { tint = ''; }
      if (!tint) return;
      const base = KINDS[this.data.kind].colors;
      const colors = [tint].concat(base.filter((c) => c !== tint).slice(0, base.length - 1));
      const keep = this.data.colorIndex !== 0;
      if (!keep) this._image = '';
      this.setData({ colors, bg: keep ? this.data.bg : tint });
    };
    img.onerror = () => {};
    img.src = src;
  },

  close() { this.triggerEvent('close'); },

  pickColor(e) {
    const i = Number(e.currentTarget.dataset.i);
    if (!this.data.colors[i]) return;
    this.setData({ colorIndex: i, bg: this.data.colors[i] });
    this._image = '';
  },

  _shareText() {
    return this.data.title + (this.data.meta ? ' · ' + this.data.meta : '') + '\n微信搜索「城瘾」小程序';
  },

  onChannel(e) {
    const key = e.currentTarget.dataset.key;
    if (key === 'copy') {
      wx.setClipboardData({ data: this._shareText(), success: () => cyToast('文案已复制') });
      return;
    }
    this._exportImage().then((path) => {
      if (key === 'more') {
        wx.showShareImageMenu({ path, fail: () => {} });
        return;
      }
      this._saveToAlbum(path).then((saved) => {
        if (!saved || key === 'save') return;
        wx.setClipboardData({ data: this._shareText(), success: () => cyToast(key === 'xhs' ? '图片已保存、文案已复制,去小红书发布' : '图片已保存、文案已复制,去朋友圈发布') });
      });
    }, () => cyToast('卡片图没生成，请重试'));
  },

  _saveToAlbum(path) {
    return new Promise((resolve) => {
      wx.saveImageToPhotosAlbum({
        filePath: path,
        success: () => { cyToast('已保存到相册'); resolve(true); },
        fail: (err) => {
          const outcome = classifyAlbumSaveError(err);
          if (outcome === ALBUM_SAVE.DENIED) {
            cyModal.show({ title: '需要相册权限', content: '在设置里允许保存到相册后重试。', confirmText: '去设置', cancelText: '取消',
              success: (m) => { if (m.confirm) wx.openSetting({}); } });
          } else if (outcome === ALBUM_SAVE.FAILED) cyToast('保存失败');
          resolve(false);
        },
      });
    });
  },

  _canvas() {
    if (this._cv) return Promise.resolve(this._cv);
    return new Promise((resolve, reject) => {
      this.createSelectorQuery().select('#sccv').fields({ node: true }).exec((r) => {
        const node = r && r[0] && r[0].node;
        if (!node) { reject(new Error('no canvas')); return; }
        this._cv = node;
        resolve(node);
      });
    });
  },

  _img(cv, src) {
    if (!src) return Promise.resolve(null);
    return new Promise((resolve) => {
      const img = cv.createImage();
      img.onload = () => resolve(img);
      img.onerror = () => resolve(null);
      img.src = src;
    });
  },

  /** 按预览同一版式画(卡 560rpx = 导出 750px):整色卡,上半封面 / 勋章,椭圆弧,徽标,眉题胶囊,两行大标题,副信息,右下码 */
  _exportImage() {
    if (this._image) return Promise.resolve(this._image);
    const d = this.data;
    return this._canvas().then((cv) => Promise.all([this._img(cv, d.cover), this._img(cv, d.qr), this._img(cv, LOGO), this._img(cv, d.cover || d.kind === 'medal' ? '' : ART)]).then(([cover, qr, logo, art]) => {
      const SS = 2;
      cv.width = W * SS; cv.height = H * SS;
      const g = cv.getContext('2d');
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.scale(SS, SS);
      const round = (x, y, w, h, r) => { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); };
      const line = (text, maxW) => { let t = String(text || ''); if (g.measureText(t).width <= maxW) return t; while (t.length > 1 && g.measureText(t + '…').width > maxW) t = t.slice(0, -1); return t + '…'; };
      const fit = (img, x, y, w, h) => { const s = Math.max(w / img.width, h / img.height); g.drawImage(img, x + (w - img.width * s) / 2, y + (h - img.height * s) / 2, img.width * s, img.height * s); };
      const TOP = 620; const mx = W / 2;
      g.fillStyle = d.bg; g.fillRect(0, 0, W, H);
      // 上半:封面铺满;勋章是暖光 + 勋章本身;都没有就压暗一档
      g.save(); g.beginPath(); g.rect(0, 0, W, TOP); g.clip();
      if (d.kind === 'medal') {
        const glow = g.createRadialGradient(mx, 290, 30, mx, 290, 270);
        glow.addColorStop(0, 'rgba(255,214,120,.45)'); glow.addColorStop(1, 'rgba(255,214,120,0)'); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        g.fillStyle = glow; g.fillRect(0, 0, W, TOP);
        if (cover) g.drawImage(cover, mx - 150, 140, 300, 300);
        else { g.fillStyle = '#E9B44C'; g.beginPath(); g.arc(mx, 290, 130, 0, Math.PI * 2); g.fill(); } /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      } else if (cover) fit(cover, 0, 0, W, TOP);
      else { g.fillStyle = 'rgba(0,0,0,.22)'; g.fillRect(0, 0, W, TOP); if (art) g.drawImage(art, 0, 0, W, TOP); } /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      g.restore();
      // 弧:与预览同一椭圆(上沿 y=540,两边落到 620)
      g.fillStyle = d.bg; g.beginPath(); g.ellipse(mx, 905, 600, 365, 0, 0, Math.PI * 2); g.fill(); g.fillRect(0, 905, W, H - 905);
      // 徽标
      g.save(); g.shadowColor = 'rgba(0,0,0,.35)'; g.shadowBlur = 30; g.shadowOffsetY = 12; /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      g.fillStyle = '#FFFFFF'; round(mx - 70, 470, 140, 140, 38); g.fill(); g.restore(); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      if (logo) g.drawImage(logo, mx - 51, 489, 102, 102);
      // 眉题胶囊
      g.textAlign = 'center'; g.textBaseline = 'middle';
      g.font = '600 30px sans-serif';
      const eb = line(d.eyebrow, W - 160);
      const ebw = g.measureText(eb).width + 52;
      g.fillStyle = 'rgba(255,255,255,.16)'; round(mx - ebw / 2, 642, ebw, 58, 29); g.fill(); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      g.fillStyle = 'rgba(255,255,255,.86)'; g.fillText(eb, mx, 671); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      // 两行大标题(按宽度折行,第二行放不下加省略号)
      g.fillStyle = '#FFFFFF'; g.font = '900 72px sans-serif'; /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      const maxW = W - 108;
      const rows = fitRows(d.title, (t) => g.measureText(t).width, maxW, 2);
      let ty = 776;
      rows.forEach((r) => { g.fillText(r, mx, ty); ty += 88; });
      if (d.sub) { g.fillStyle = 'rgba(255,255,255,.78)'; g.font = '32px sans-serif'; g.fillText(line(d.sub, W - 108), mx, ty); } /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      // 底部:左边字标,右边码
      g.textAlign = 'left'; g.textBaseline = 'alphabetic';
      g.fillStyle = '#FFFFFF'; g.font = '800 40px sans-serif'; g.fillText('城瘾', 54, H - 54); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      const qs = d.kind === 'shop' ? 172 : 134;
      if (qr) { g.fillStyle = '#FFFFFF'; round(W - 43 - qs, H - 43 - qs, qs, qs, 16); g.fill(); g.drawImage(qr, W - 35 - qs, H - 35 - qs, qs - 16, qs - 16); } /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      return new Promise((resolve, reject) => wx.canvasToTempFilePath({
        canvas: cv, fileType: 'png', destWidth: cv.width, destHeight: cv.height,
        success: (r) => { this._image = r.tempFilePath; resolve(r.tempFilePath); }, fail: reject,
      }));
    }));
  },

  },
});
