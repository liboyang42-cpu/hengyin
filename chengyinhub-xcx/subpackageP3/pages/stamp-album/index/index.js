// 集邮册:散落 collage(参考 STAMPA 的月份视图)。【乱才是魅力,不是网格】。
// 数百枚必须分页 + 懒加载,不能一次拉光(接口上限 50/页)。
const app = getApp();
const session = require('../../../../utils/session/login-reload.js'); const pageMotion = require('../../../../utils/page-motion.js');
const LAYOUT = require('./layout.js');
const { readReducedMotion } = require('../../../../utils/motion-preference.js');

const COLS = 4;

// 透明导航压在内容上,内容从导航下沿开始(状态栏 + 导航栏)
function navTopOf(a) { const g = (a && a.globalData) || {}; return (g.statusBarHeight || 44) + (g.navBarHeight || 44); }

Page({
  data: {
    readAccess: '',
    items: [], total: 0, stageH: 0,
    cellImgW: 0, cellImgH: 0,
    // loaded = 「真的读到了后端的答复」;error = 「没读到」。两者不是一回事:
    // 失败时把 loaded 置真,空态「还没有邮票」就会替失败背书,用户以为册子被清空了(截图 057)。
    pageNum: 1, loading: false, loaded: false, error: false, noMore: false,
    flyIdx: -1, fly: { show: false, on: false, src: '', style: '' },
    navTop: navTopOf(app) // 透明导航压在内容上,内容从导航下沿开始
  },

  onLoad() {
    this._destroyed = false;
    const win = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    const usable = win.windowWidth - 24;          // 减去 padding
    this._cellW = usable / COLS;
    this._cellH = this._cellW * 1.25;
    this.setData({ cellImgW: Math.round(this._cellW * 0.86), cellImgH: Math.round(this._cellH * 0.86) });
    this.load();
  },
  onShow() {
    this._hidden = false;
    if (this._readOwner !== this._readIdentity()) { this._resetRead(); this.load(); }
  },
  onHide() { this._hidden = true; },
  onUnload() { this._destroyed = true; this._unloaded = true; if (this._flyTimer) clearTimeout(this._flyTimer); },
  noop() {},
  // ── 2026-10-30 点开:那一枚从原位飞到屏幕中间放大(FLIP:先摆回原位和原角度,下一拍放开) ──
  openFly(e) {
    const idx = Number(e.currentTarget.dataset.idx);
    const it = this.data.items[idx];
    if (!it || this.data.fly.show) return;
    const that = this;
    this.createSelectorQuery().select('#al-item-' + idx).boundingClientRect().exec(function (res) {
      const r = res && res[0];
      if (!r || that._unloaded) return;
      let w = 375, h = 667;
      try { const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync(); w = info.windowWidth; h = info.windowHeight; } catch (err) {}
      const bw = Math.round(w * 0.74), bh = Math.round(bw * that.data.cellImgH / Math.max(1, that.data.cellImgW));
      const left = Math.round((w - bw) / 2), top = Math.round(h * 0.46 - bh / 2);
      const base = 'left:' + left + 'px;top:' + top + 'px;width:' + bw + 'px;height:' + bh + 'px;';
      const from = 'transform:translate(' + Math.round(r.left + r.width / 2 - (left + bw / 2)) + 'px,' + Math.round(r.top + r.height / 2 - (top + bh / 2)) + 'px) scale(' + (that.data.cellImgW / bw).toFixed(3) + ') rotate(' + (it.rot || 0) + 'deg);';
      that._flyFrom = base + from + 'transition:none;';
      that.setData({ flyIdx: idx, fly: { show: true, on: false, src: it.picUrl, style: that._flyFrom } });
      const reduced = readReducedMotion();
      clearTimeout(that._flyTimer);
      that._flyTimer = setTimeout(function () {
        if (that._unloaded) return;
        that.setData({ 'fly.on': true, 'fly.style': base + 'transform:rotate(-2deg);transition:' + (reduced ? 'none' : 'transform .6s cubic-bezier(.2,.9,.25,1.06)') + ';' });
      }, 30);
    });
  },
  closeFly() {
    if (!this.data.fly.show) return;
    const that = this;
    this.setData({ 'fly.on': false, 'fly.style': (this._flyFrom || '').replace('transition:none;', 'transition:transform .42s cubic-bezier(.4,0,.2,1);') });
    clearTimeout(this._flyTimer);
    this._flyTimer = setTimeout(function () { if (!that._unloaded) that.setData({ flyIdx: -1, fly: { show: false, on: false, src: '', style: '' } }); }, 420);
  },
  _readIdentity() { return [session.memberId(app), (app.globalData || {}).authorization || ''].join('|'); },
  _resetRead() {
    this._readOwner = this._readIdentity();
    this._loadSeq = (this._loadSeq || 0) + 1;
    this.setData({ items: [], total: 0, stageH: 0, pageNum: 1, loading: false, loaded: false, error: false, noMore: false, readAccess: '' });
  },
  _readOutcome(res, statusCode, owner, seq) {
    if (this._destroyed || seq !== this._loadSeq) return true;
    if (owner !== this._readIdentity() || res && res.reason === 'sessionChanged') {
      this._resetRead();
      if (!session.memberId(app)) this.setData({ readAccess: 'login' });
      else if (!this._hidden) this.load();
      return true;
    }
    const code = session.failureStatus(res, statusCode);
    if (code !== 401 && code !== 403) return false;
    this._resetRead();
    this.setData({ readAccess: code === 401 ? 'login' : 'denied' });
    return true;
  },
  loginToRead() {
    if (this.data.readAccess === 'denied') return;
    return session.retryLogin(this, app, function () { this._resetRead(); this.load(); });
  },

  onReachBottom() { if (!this.data.noMore) this.load(); },

  load() {
    const owner = this._readIdentity();
    if (!session.memberId(app)) { this._resetRead(); this.setData({ readAccess: 'login' }); return; }
    if (this._readOwner && this._readOwner !== owner) this._resetRead();
    this._readOwner = owner;
    if (this.data.loading || this.data.noMore) return;
    const seq = this._loadSeq = (this._loadSeq || 0) + 1;
    this.setData({ loading: true, error: false, readAccess: '' });
    const that = this;
    app.sendRequest({
      url: '/api/roam/stamp/list', method: 'POST', autoErrorToast: false,
      data: { pageNum: this.data.pageNum, pageSize: 50 },
      success(res) {
        if (that._readOutcome(res, null, owner, seq)) return;
        const d = (res && res.code == '200' && res.data) ? res.data : null;
        // 非 200 / 没有 data = 没接通,不是「一枚都没有」:落 error,loaded 保持原值。
        const validRows = d && Array.isArray(d.list) && d.list.every(function (row) {
          return row && typeof row === 'object' && !Array.isArray(row)
            && row.id !== null && row.id !== undefined
            && typeof row.picUrl === 'string' && row.picUrl.trim()
        })
        const validTotal = d && typeof d.total === 'number' && Number.isFinite(d.total)
          && Number.isInteger(d.total) && d.total >= 0
          && d.total >= that.data.items.length + (validRows ? d.list.length : 0)
        if (!validRows || !validTotal) { that.setData({ loading: false, error: true }); return; }
        const rows = d.list;
        const merged = that.data.items.concat(rows.map(function (r) { return { id: r.id, picUrl: r.picUrl }; }));
        const pos = LAYOUT.collageLayout(merged.length, {
          cols: COLS, cellW: that._cellW, cellH: that._cellH, jitter: 16, maxRot: 12
        });
        const items = merged.map(function (m, i) {
          return { id: m.id, picUrl: m.picUrl, x: pos[i].x, y: pos[i].y, rot: pos[i].rot, z: pos[i].z };
        });
        pageMotion.enter(that, 'pmList'); that.setData({
          items: items,
          total: d.total,
          stageH: Math.ceil(merged.length / COLS) * that._cellH + 60,
          pageNum: that.data.pageNum + 1,
          noMore: rows.length === 0 || merged.length >= d.total,
          loading: false, loaded: true, error: false
        });
      },
      fail(res, statusCode) { if (!that._readOutcome(res, statusCode, owner, seq)) that.setData({ loading: false, error: true }); },
      // HTTP 层非 200 走的是这一条:不挂它的话 success/fail 都不会跑,
      // loading 永远为真 ⇒ 骨架转到底、还把 load() 的 if (loading) return 锁死后续分页。
      successStatusAbnormal(res, statusCode) { if (!that._readOutcome(res, statusCode, owner, seq)) that.setData({ loading: false, error: true }); }
    });
  },

  // 失败态的下一步(微信官方指南:异常要「告知解决方案,使其有路可退」)。
  retryLoad() { this.setData({ error: false }, () => this.load()); },

  goCamera() { wx.navigateTo({ url: '/subpackageP3/pages/stamp-camera/index/index' }); },

  // 自动化自证:回报渲染了几枚 + 后端说共几枚
  __layoutProbe() { return { n: this.data.items.length, total: this.data.total }; }
});
