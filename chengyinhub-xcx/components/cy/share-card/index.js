// 通用分享层(2026-10-31 业主批准 P-8):活动 / 主题 / 勋章共用的全屏分享页。一张卡、四个色块、一排渠道。
// 做成组件挂在宿主页上:「微信好友」是 open-type=share,走宿主页自己的 onShareAppMessage(活动页分享这场活动,
// 漫游页不带实时地图截图),不另起一个路由。
// 渠道只承诺做得到的事:微信好友 = 小程序卡片;朋友圈 / 小红书 = 存卡片图 + 复制文案(平台不能代码发图文,P-8 ④ 已批准);
// 保存图片 = 存相册;复制文案 = 复制标题与搜索提示(小程序运行时生成不了网页链接);更多 = 系统图片分享菜单。
// 卡片图用 canvas 2d 按预览同一版式画(预览 = 导出图),码由后端按 scene 永久复用。
const cyToast = require('../../../utils/toast.js');
const cyModal = require('../../../utils/modal.js');
const { ALBUM_SAVE, classifyAlbumSaveError } = require('../../../utils/album-save-outcome.js');

const KINDS = {
  activity: { label: '活动', colors: ['#5A3426', '#8E6430', '#1E2A24', '#121317'] }, /* ds-ok P-8 分享卡底色色块(业主批准稿) */
  theme: { label: '主题', colors: ['#7A2619', '#B8862F', '#2C2A26', '#121317'] }, /* ds-ok P-8 分享卡底色色块(业主批准稿) */
  medal: { label: '勋章', colors: ['#5A3D12', '#8E6A26', '#1D1A14', '#121317'] }, /* ds-ok P-8 分享卡底色色块(业主批准稿) */
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

Component({
  properties: {
    show: { type: Boolean, value: false },
    // { kind: activity | theme | medal, id, title, meta, cover, date, when, stops: [] }
    card: { type: Object, value: {} },
  },
  data: {
    statusBarHeight: 20, kind: 'theme', label: '主题', title: '', meta: '', cover: '', date: '', when: '', stops: [],
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
      this.setData({
        statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 20,
        kind, label: KINDS[kind].label, title: c.title || KINDS[kind].label,
        meta: c.meta || '', date: c.date || '', when: c.when || '',
        cover: c.cover && app.getImgUrl ? app.getImgUrl(c.cover) : (c.cover || ''),
        stops: (Array.isArray(c.stops) ? c.stops : []).filter(Boolean).slice(0, 5),
        colors: KINDS[kind].colors, colorIndex: 0, bg: KINDS[kind].colors[0], qr: '',
      });
      this._loadCode();
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
    if (kind === 'activity' && /^\d{1,18}$/.test(id)) {
      app.sendRequest({ url: '/api/official/events/' + id + '/share-code', method: 'POST', hideLoading: true, success: done });
      return;
    }
    // 主题出主题码;勋章出首页码(不带 id)
    app.sendRequest({ url: '/api/topic/share-code', method: 'POST', hideLoading: true,
      data: kind === 'theme' && /^\d{1,18}$/.test(id) ? { id } : {}, success: done });
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

  /** 按预览同一版式画:底色满屏,卡片居中偏下,右下小程序码 */
  _exportImage() {
    if (this._image) return Promise.resolve(this._image);
    const d = this.data;
    return this._canvas().then((cv) => Promise.all([this._img(cv, d.cover), this._img(cv, d.qr)]).then(([cover, qr]) => {
      const SS = 2;
      cv.width = W * SS; cv.height = H * SS;
      const g = cv.getContext('2d');
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.scale(SS, SS);
      g.fillStyle = d.bg; g.fillRect(0, 0, W, H);
      const cx = 95; const cy = 250; const cw = 560; const ch = 820;
      const round = (x, y, w, h, r) => { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); };
      g.save(); round(cx, cy, cw, ch, 40); g.clip();
      g.fillStyle = '#1A1B20'; g.fillRect(cx, cy, cw, ch); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      const fit = (img, x, y, w, h) => { const s = Math.max(w / img.width, h / img.height); g.drawImage(img, x + (w - img.width * s) / 2, y + (h - img.height * s) / 2, img.width * s, img.height * s); };
      g.textAlign = 'left';
      let ty = cy + 70;
      if (d.kind === 'theme') {
        if (cover) fit(cover, cx, cy, cw, ch);
        g.fillStyle = 'rgba(0,0,0,.55)'; g.fillRect(cx, cy, cw, ch); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        g.fillStyle = 'rgba(255,255,255,.72)'; g.font = '600 24px sans-serif'; g.fillText(d.meta ? '主题 · ' + d.meta : '主题', cx + 40, ty); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        g.fillStyle = '#FFFFFF'; g.font = 'italic 900 64px sans-serif'; g.fillText(d.title.slice(0, 8), cx + 40, ty + 84); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        ty += 160;
        d.stops.forEach((s, i) => {
          const y = ty + i * 76;
          if (i < d.stops.length - 1) { g.fillStyle = 'rgba(255,255,255,.4)'; g.fillRect(cx + 59, y + 18, 3, 58); } /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
          g.fillStyle = '#FFFFFF'; g.beginPath(); g.arc(cx + 60, y, 20, 0, Math.PI * 2); g.fill(); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
          g.fillStyle = '#121317'; g.font = '800 22px sans-serif'; g.textAlign = 'center'; g.fillText(String(i + 1), cx + 60, y + 8); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
          g.textAlign = 'left'; g.fillStyle = '#FFFFFF'; g.font = '600 28px sans-serif'; g.fillText(s.slice(0, 14), cx + 98, y + 10); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        });
      } else if (d.kind === 'activity') {
        if (cover) fit(cover, cx, cy, cw, 420);
        ty = cy + 480;
        g.fillStyle = 'rgba(255,255,255,.6)'; g.font = '600 24px sans-serif'; g.fillText('活动', cx + 40, ty); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        g.fillStyle = '#FFFFFF'; g.font = '800 44px sans-serif'; g.fillText(d.title.slice(0, 11), cx + 40, ty + 64); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        if (d.date) { g.font = 'italic 900 72px sans-serif'; g.fillText(d.date, cx + 40, ty + 160); }
        g.fillStyle = 'rgba(255,255,255,.7)'; g.font = '26px sans-serif'; g.fillText(d.when.slice(0, 18), cx + 40, ty + 206); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      } else {
        g.fillStyle = 'rgba(255,255,255,.6)'; g.font = '600 24px sans-serif'; g.fillText('勋章', cx + 40, ty); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        const mx = cx + cw / 2; const my = cy + 320;
        const glow = g.createRadialGradient(mx, my, 40, mx, my, 220);
        glow.addColorStop(0, 'rgba(255,214,120,.45)'); glow.addColorStop(1, 'rgba(255,214,120,0)'); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        g.fillStyle = glow; g.fillRect(cx, cy, cw, ch);
        if (cover) g.drawImage(cover, mx - 130, my - 130, 260, 260);
        else { g.fillStyle = '#E9B44C'; g.beginPath(); g.arc(mx, my, 120, 0, Math.PI * 2); g.fill(); } /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        g.textAlign = 'center'; g.fillStyle = '#FFFFFF'; g.font = '800 48px sans-serif'; g.fillText(d.title.slice(0, 10), mx, my + 210); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        if (d.meta) { g.fillStyle = 'rgba(255,255,255,.7)'; g.font = '26px sans-serif'; g.fillText(d.meta.slice(0, 18), mx, my + 258); } /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
        g.textAlign = 'left';
      }
      g.fillStyle = '#FFFFFF'; g.font = '800 30px sans-serif'; g.fillText('城瘾', cx + 40, cy + ch - 48); /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      if (qr) { g.fillStyle = '#FFFFFF'; round(cx + cw - 160, cy + ch - 160, 120, 120, 16); g.fill(); g.drawImage(qr, cx + cw - 152, cy + ch - 152, 104, 104); } /* ds-ok canvas 导出图(画布读不到 CSS token,与预览同色) */
      g.restore();
      return new Promise((resolve, reject) => wx.canvasToTempFilePath({
        canvas: cv, fileType: 'png', destWidth: cv.width, destHeight: cv.height,
        success: (r) => { this._image = r.tempFilePath; resolve(r.tempFilePath); }, fail: reject,
      }));
    }));
  },

  },
});
