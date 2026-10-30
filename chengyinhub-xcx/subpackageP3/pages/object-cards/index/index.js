// 藏品册(施工文档第四版 §3.3,样稿 artifact 9U7HFWDfbinjmt2tppvVGp):
// 物品从上往下掉、堆在地面线上;右上角 ✦ 聚成一团,再按一下落回去;点一件放大看。
//
// 堆是一块 2d canvas:最近 40 件,分类筛选在服务端做(计数与分页跟着筛过的走)。
// 物理在 subpackageP3/utils/object-pile.js;这里只管拉数据、加载图、逐帧画,全停住以后停掉逐帧重画(省电)。
// ponytail: 只画最近 40 件,更早的不进堆;真有人收到上百件再加「往前翻」。
const app = getApp();
const session = require('../../../../utils/session/login-reload.js');
const { toCard } = require('../../../../utils/object-card.js');
const pile = require('../../../utils/object-pile.js');
const motionPreference = require('../../../../utils/motion-preference.js');
const cyToast = require('../../../../utils/toast.js');

const PAGE_SIZE = 40;
const ALL = '全部';
const CATEGORIES = ['电子产品', '服饰', '鞋包', '食物饮料', '书籍文具', '玩具摆件', '日用杂物', '其他'];
// 分类图标(Fluent Emoji 3D,MIT License,microsoft/fluentui-emoji;缩到 96px 放在本分包)
const CAT_ICON = {
  [ALL]: '/subpackageP3/images/cat/all.png', 电子产品: '/subpackageP3/images/cat/electronics.png', 服饰: '/subpackageP3/images/cat/clothing.png',
  鞋包: '/subpackageP3/images/cat/bags.png', 食物饮料: '/subpackageP3/images/cat/food.png', 书籍文具: '/subpackageP3/images/cat/books.png',
  玩具摆件: '/subpackageP3/images/cat/toys.png', 日用杂物: '/subpackageP3/images/cat/daily.png', 其他: '/subpackageP3/images/cat/other.png',
};
/** 按住超过这么远(px)算拖,不算点 */
const DRAG_SLOP = 6;
/** 地面线离画布底边(px):下面留给安全区和一点呼吸 */
const FLOOR_GAP = 56;

// 透明导航压在内容上,内容从导航下沿开始(状态栏 + 导航栏)
function navTopOf(a) { const g = (a && a.globalData) || {}; return (g.statusBarHeight || 44) + (g.navBarHeight || 44); }

Page({
  data: {
    readAccess: '',
    cats: [ALL].concat(CATEGORIES).map((c) => ({ key: c, label: c })),
    filter: ALL,
    cards: [],
    // loaded = 「真的读到过后端答复」;error = 「整页没读到」。两者不能合并:
    // 失败时把 loaded 置真,空态就会替失败背书,玩家以为册子被清空了。
    loading: false,
    loaded: false,
    error: false,
    ball: false,
    // 放大态:null = 堆;非空 = 盖在堆上的那一件
    active: null,
    // BL-79 改名:盖在放大态上的半屏;关掉回到放大态
    renaming: false,
    renameValue: '',
    renameError: '',
    renameSubmitting: false,
    catIcon: CAT_ICON,
    catsOpen: false,
    fly: { show: false, on: false, src: '', style: '', name: '', meta: '', cut: false },
    navTop: navTopOf(app), // 透明导航压在内容上
  },

  onLoad() {
    this._reduced = motionPreference.readReducedMotion();
    this._images = {};
    this._bodies = [];
    this._destroyed = false;
    this._loadSeq = 0;
    this.load(ALL);
  },

  onReady() {
    this.createSelectorQuery().select('#ocPile').fields({ node: true, size: true, rect: true }).exec((res) => {
      if (this._destroyed) return;
      const info = res && res[0];
      if (!info || !info.node) return;
      let dpr = 2;
      try { dpr = wx.getWindowInfo().pixelRatio || 2; } catch (e) { /* 默认值 */ }
      const canvas = info.node;
      canvas.width = Math.round(info.width * dpr);
      canvas.height = Math.round(info.height * dpr);
      const ctx = canvas.getContext('2d');
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      this._canvas = canvas;
      this._ctx = ctx;
      this._rect = { left: info.left, top: info.top, width: info.width, height: info.height };
      this._world = { W: info.width, floor: info.height - FLOOR_GAP, cx: info.width / 2, cy: info.height * 0.42 };
      if (this.data.cards.length) this._build(false);
    });
  },

  // 页面藏起来就停画(聚成一团时逐帧重画永远不会自己停),回来接着画
  onHide() { this._hidden = true; this._stop(); },
  onShow() {
    this._hidden = false;
    if (this._readOwner && this._readOwner !== this._readIdentity()) { this._resetRead(); this.load(ALL); }
    if (this._bodies && this._bodies.length) this._run();
  },
  onUnload() {
    this._destroyed = true;
    this._unloaded = true;
    if (this._flyTimer) clearTimeout(this._flyTimer);
    this._loadSeq = (this._loadSeq || 0) + 1;
    this._stop();
  },


  _readIdentity() { return [session.memberId(app), (app.globalData || {}).authorization || ''].join('|'); },
  _resetRead() {
    this._readOwner = this._readIdentity();
    this._loadSeq = (this._loadSeq || 0) + 1;
    this._stop(); this._bodies = []; this._images = {}; this._total = undefined;
    this.setData({ cards: [], active: null, ball: false, filter: ALL, loading: false, loaded: false, error: false, readAccess: '' });
    if (this._ctx && this._world) this._draw();
  },
  _readOutcome(res, statusCode, owner, seq) {
    if (this._destroyed || seq !== this._loadSeq) return true;
    if (owner !== this._readIdentity() || res && res.reason === 'sessionChanged') {
      this._resetRead();
      if (!session.memberId(app)) this.setData({ readAccess: 'login' });
      else if (!this._hidden) this.load(ALL);
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
    return session.retryLogin(this, app, function () { this._resetRead(); this.load(ALL); });
  },

  load(category) {
    const owner = this._readIdentity();
    if (!session.memberId(app)) { this._resetRead(); this.setData({ readAccess: 'login' }); return; }
    if (this._readOwner && this._readOwner !== owner) this._resetRead();
    this._readOwner = owner;
    const that = this;
    const previous = this.data.filter;
    const loadSeq = (this._loadSeq || 0) + 1;
    this._loadSeq = loadSeq;
    this.setData({ loading: true, filter: category, readAccess: '' });
    app.sendRequest({
      url: '/api/object-card/list',
      method: 'POST',
      autoErrorToast: false,
      data: { pageNum: 1, pageSize: PAGE_SIZE, category: category === ALL ? '' : category },
      success(res) {
        if (that._readOutcome(res, null, owner, loadSeq)) return;
        const d = res && res.code == '200' && res.data ? res.data : null;
        // 非 200 / 没有 data / 行形状不对 = 没接通,不是「一张都没有」
        const okRows = d && Array.isArray(d.list) && d.list.every(function (row) {
          return row && typeof row === 'object' && !Array.isArray(row)
            && row.id !== null && row.id !== undefined && typeof row.title === 'string';
        });
        const okTotal = d && typeof d.total === 'number' && Number.isInteger(d.total) && d.total >= 0;
        if (!okRows || !okTotal) { that._failed(previous, loadSeq); return; }
        const cards = d.list.map(toCard);
        const firstTime = !that.data.loaded;
        that._total = d.total;   // 只给自证探针读,不进 setData(界面不渲染它)
        that.setData({ cards, loading: false, loaded: true, error: false, ball: false });
        that._build(!firstTime);
      },
      fail(res, statusCode) { if (!that._readOutcome(res, statusCode, owner, loadSeq)) that._failed(previous, loadSeq); },
      // HTTP 层非 200 走这一条:不挂它 success/fail 都不跑,骨架转到底
      successStatusAbnormal(res, statusCode) { if (!that._readOutcome(res, statusCode, owner, loadSeq)) that._failed(previous, loadSeq); },
    });
  },

  /** 首屏没读到 = 整页失败(自动返回);切分类没读到 = 提示一句,退回原来那一类,堆不动。 */
  _failed(previous, loadSeq) {
    if (this._destroyed || loadSeq !== this._loadSeq) return;
    if (!this.data.loaded) { this.setData({ loading: false, error: true }); return; }
    this.setData({ loading: false, filter: previous });
    cyToast('这一类没读到，再点一次试试');
  },

  onFilter(e) {
    const key = e && e.detail && e.detail.key;
    if (!key || key === this.data.filter || this.data.loading) return;
    this.load(key);
  },

  // 2026-10-30 业主:分类不再占第二排,收进「筛选」—— 半屏三列图标格,选中黑底白字;选完物件从上面重新落下
  openCats() { this.setData({ catsOpen: true }); },
  // 画布是原生层,会压在半屏上面 —— 半屏开着时画布藏起来,关掉后补画一帧
  closeCats() { this.setData({ catsOpen: false }, () => { if (this._ctx) this._draw(); }); },
  pickCat(e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ catsOpen: false });
    this.onFilter({ detail: { key: key } });
  },

  // ---------------- 拿起来拖(2026-10-30 业主:以前的藏品能拖) ----------------
  // 按住一件:它跟着手指走、把别的挤开;松手按最后一段的速度甩出去;没动过就是点 → 飞到中间
  onPileStart(e) {
    const t = e && e.touches && e.touches[0];
    if (!t || !this._world) return;
    const b = pile.hit(this._bodies, t.x, t.y);
    this._drag = { body: b, x0: t.x, y0: t.y, lx: t.x, ly: t.y, lt: Date.now(), vx: 0, vy: 0, moved: false };
    if (b) { b.held = true; b.vx = 0; b.vy = 0; this._bodies.splice(this._bodies.indexOf(b), 1); this._bodies.push(b); this._run(); }
  },
  onPileMove(e) {
    const d = this._drag;
    const t = e && e.touches && e.touches[0];
    if (!d || !t) return;
    if (!d.moved && Math.hypot(t.x - d.x0, t.y - d.y0) > DRAG_SLOP) d.moved = true;
    if (!d.body || !d.moved) return;
    const now = Date.now();
    const dt = Math.max(1, now - d.lt);
    // 速度按 16ms 一帧折算,和物理一步同一个单位
    d.vx = (t.x - d.lx) / dt * 16; d.vy = (t.y - d.ly) / dt * 16;
    d.lx = t.x; d.ly = t.y; d.lt = now;
    const b = d.body, w = this._world;
    b.x = Math.max(b.r, Math.min(w.W - b.r, t.x));
    b.y = Math.max(b.r, Math.min(w.floor - b.r, t.y));
    b.rot += d.vx * 0.004;
    this._run();
  },
  onPileEnd() {
    const d = this._drag;
    this._drag = null;
    if (!d) return;
    if (d.body) {
      d.body.held = false;
      if (d.moved) {
        d.body.vx = Math.max(-28, Math.min(28, d.vx));
        d.body.vy = Math.max(-28, Math.min(28, d.vy));
        d.body.vr = d.vx * 0.01;
        this._run();
        return;
      }
      this._run();
    }
    if (!d.moved) this.onTapPile({ detail: { x: d.x0 + this._rect.left, y: d.y0 + this._rect.top } });
  },

  // ---------------- 物品堆 ----------------
  /** fromTop = 从屏幕上方掉进来(切分类);首屏直接落定再画,不让玩家干等。 */
  _build(fromTop) {
    if (!this._world) return;   // 画布还没量好,onReady 里会再建一次
    const world = this._world;
    this._bodies = this.data.cards.map((card, i) => pile.body(card, world, {
      size: card.cutoutUrl ? 60 : 50, seed: i + 7, fromTop,
    }));
    if (!fromTop || this._reduced) for (let k = 0; k < 600; k++) pile.step(this._bodies, world, 1);
    this.data.cards.forEach((card) => this._loadImage(card.cutoutUrl || card.sourceUrl || card.frames[0]));
    this._run();
  },

  _loadImage(src) {
    if (!src || this._images[src] !== undefined || !this._canvas) return;
    this._images[src] = null;   // 在路上
    const img = this._canvas.createImage();
    img.onload = () => { this._images[src] = img; this._run(); };
    // 图床域名没进 downloadFile 白名单也会走这里:画一块占位,堆照常能点
    img.onerror = () => { this._images[src] = false; this._run(); };
    img.src = src;
  },

  onGather() {
    const ball = !this.data.ball;
    this.setData({ ball });
    this._world.ball = ball;
    this._bodies.forEach((b) => { b.vx += (Math.random() - 0.5) * 2; if (ball) b.vy -= 2 + Math.random() * 3; });
    if (this._reduced) for (let k = 0; k < 500; k++) pile.step(this._bodies, this._world, 1);
    this._run();
  },

  /** 逐帧:走一步物理、画一遍;全停住(且没在聚团)就停,省电。减弱动态效果时只画一帧。 */
  _run() {
    if (!this._canvas || this._hidden || this._destroyed) return;
    if (this._raf) return;
    const frame = () => {
      this._raf = null;
      if (this._hidden || this._destroyed) return;
      if (!this._reduced) pile.step(this._bodies, this._world, 1);
      this._draw();
      const still = this._reduced || (!this._world.ball && pile.settled(this._bodies));
      if (!still) this._raf = this._canvas.requestAnimationFrame(frame);
    };
    this._raf = this._canvas.requestAnimationFrame(frame);
  },

  _stop() {
    if (this._raf && this._canvas) this._canvas.cancelAnimationFrame(this._raf);
    this._raf = null;
  },

  _draw() {
    const ctx = this._ctx;
    const w = this._world;
    ctx.clearRect(0, 0, w.W, w.floor + FLOOR_GAP);
    // 地面线 + 一点往下的雾
    const g = ctx.createLinearGradient(0, w.floor, 0, w.floor + 18);
    // 2026-10-30 收集册浅色底:地面线与雾改成深色淡影
    g.addColorStop(0, 'rgba(40,52,90,0.08)'); /* ds-ok: canvas 读不到 CSS 变量,浅底上的地面雾 */
    g.addColorStop(1, 'rgba(40,52,90,0)'); /* ds-ok: 同上 */
    ctx.fillStyle = g;
    ctx.fillRect(18, w.floor, w.W - 36, 18);
    ctx.fillStyle = 'rgba(40,52,90,0.2)'; /* ds-ok: 同上,地面线 */
    ctx.fillRect(18, w.floor, w.W - 36, 1);
    // 右下角的木牌(样稿里就有)
    ctx.fillStyle = '#6b5a47'; /* ds-ok: 样稿里的木牌,插画色不走主题 token */
    ctx.fillRect(w.W - 93, w.floor - 60, 5, 60);
    ctx.fillStyle = '#8a7660'; /* ds-ok: 同上,木牌牌面 */
    ctx.fillRect(w.W - 118, w.floor - 76, 56, 24);
    for (const b of this._bodies) this._drawBody(ctx, b);
  },

  _drawBody(ctx, b) {
    const card = b.item;
    const src = card.cutoutUrl || card.sourceUrl || card.frames[0];
    const img = this._images[src];
    ctx.save();
    ctx.translate(b.x, b.y);
    ctx.rotate(b.rot);
    const s = b.size;
    if (img && card.cutoutUrl) {
      // 抠图外面一圈白色裁切边,像贴纸(业主定稿 P-3);白边图按图缓存一次
      const st = this._sticker(src, img);
      const k = s / Math.max(img.width, img.height);
      ctx.shadowColor = 'rgba(40,30,20,0.28)'; /* ds-ok: canvas 落影 */
      ctx.shadowBlur = 6; ctx.shadowOffsetY = 3;
      if (st) ctx.drawImage(st.c, -st.w * k / 2, -st.h * k / 2, st.w * k, st.h * k);
      else ctx.drawImage(img, -img.width * k / 2, -img.height * k / 2, img.width * k, img.height * k);
    } else if (img) {
      // 没抠出来的:一小块照片,圆角
      const k = Math.max(s / img.width, s / img.height);
      ctx.beginPath();
      ctx.moveTo(-s / 2 + 6, -s / 2);
      ctx.arcTo(s / 2, -s / 2, s / 2, s / 2, 6);
      ctx.arcTo(s / 2, s / 2, -s / 2, s / 2, 6);
      ctx.arcTo(-s / 2, s / 2, -s / 2, -s / 2, 6);
      ctx.arcTo(-s / 2, -s / 2, s / 2, -s / 2, 6);
      ctx.closePath();
      ctx.clip();
      ctx.drawImage(img, -img.width * k / 2, -img.height * k / 2, img.width * k, img.height * k);
    } else {
      ctx.fillStyle = '#E4E6EC'; /* ds-ok: 图没加载出来的占位块,浅底上的浅灰(canvas 读不到 CSS 变量) */
      ctx.fillRect(-s * 0.4, -s * 0.4, s * 0.8, s * 0.8);
    }
    ctx.restore();
  },

  /** 贴纸白边:把抠图向四周错开画 16 次再整块染白,最后把原图压在上面;离屏 2d 画布,一张图只做一次 */
  _sticker(src, img) {
    const cache = this._stickers || (this._stickers = {});
    if (cache[src] !== undefined) return cache[src];
    cache[src] = null;
    if (typeof wx.createOffscreenCanvas !== 'function') return null;
    try {
      const pad = Math.round(Math.max(img.width, img.height) * 0.06);
      const W = img.width + pad * 2, H = img.height + pad * 2;
      const c = wx.createOffscreenCanvas({ type: '2d', width: W, height: H });
      const x = c.getContext('2d');
      for (let a = 0; a < 16; a++) {
        const t = a / 16 * Math.PI * 2;
        x.drawImage(img, pad + Math.cos(t) * pad * 0.8, pad + Math.sin(t) * pad * 0.8, img.width, img.height);
      }
      x.globalCompositeOperation = 'source-in';
      x.fillStyle = '#FFFFFF'; /* ds-ok: 贴纸白边 */
      x.fillRect(0, 0, W, H);
      x.globalCompositeOperation = 'source-over';
      x.drawImage(img, pad, pad, img.width, img.height);
      cache[src] = { c: c, w: W, h: H };
    } catch (err) { cache[src] = null; }
    return cache[src];
  },

  onTapPile(e) {
    if (!this._rect || !e || !e.detail) return;
    const hitBody = pile.hit(this._bodies, e.detail.x - this._rect.left, e.detail.y - this._rect.top);
    if (hitBody) this.openFly(hitBody);
  },

  // ---------------- 飞到中间(FLIP:先摆在堆里的位置和角度,下一拍放开) ----------------
  openFly(b) {
    const card = b.item;
    const src = card.cutoutUrl || card.sourceUrl || (card.frames && card.frames[0]) || '';
    if (!src) { this.setData({ active: card }); return; }
    let w = 375, h = 667;
    try { const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync(); w = info.windowWidth; h = info.windowHeight; } catch (err) { /* 默认值 */ }
    const size = Math.round(w * 0.7), left = Math.round((w - size) / 2), top = Math.round(h * 0.42 - size / 2);
    const cx = this._rect.left + b.x, cy = this._rect.top + b.y;
    const base = 'left:' + left + 'px;top:' + top + 'px;width:' + size + 'px;height:' + size + 'px;';
    const from = 'transform:translate(' + Math.round(cx - (left + size / 2)) + 'px,' + Math.round(cy - (top + size / 2)) + 'px) scale(' + (b.size / size).toFixed(3) + ') rotate(' + (b.rot * 180 / Math.PI).toFixed(1) + 'deg);';
    this._flyFrom = base + from + 'transition:none;';
    this._flyCard = card;
    this.setData({ fly: { show: true, on: false, src: src, style: this._flyFrom, name: card.title || '藏品', meta: card.place || card.category || '', cut: !!card.cutoutUrl } });
    clearTimeout(this._flyTimer);
    this._flyTimer = setTimeout(() => {
      if (this._destroyed) return;
      this.setData({ 'fly.on': true, 'fly.style': base + 'transform:none;transition:' + (this._reduced ? 'none' : 'transform .6s cubic-bezier(.2,.9,.25,1.06)') + ';' });
    }, 30);
  },
  closeFly() {
    if (!this.data.fly.show) return;
    this.setData({ 'fly.on': false, 'fly.style': (this._flyFrom || '').replace('transition:none;', 'transition:transform .42s cubic-bezier(.4,0,.2,1);') });
    clearTimeout(this._flyTimer);
    this._flyTimer = setTimeout(() => {
      if (this._destroyed) return;
      this.setData({ fly: { show: false, on: false, src: '', style: '', name: '', meta: '', cut: false } }, () => { if (this._ctx) this._draw(); });
    }, 420);
  },
  // 中间那件再点一下:打开原来的详情(转台 / 贴纸 / 名称可改)
  onFlyItem() {
    const card = this._flyCard;
    if (!card) return;
    clearTimeout(this._flyTimer);
    this.setData({ fly: { show: false, on: false, src: '', style: '', name: '', meta: '', cut: false }, active: card });
  },
  noop() {},

  // 画布在放大态时是 display:none,回来补画一帧(停住的堆不会自己再画)
  onCloseActive() { this.setData({ active: null }, () => { if (this._ctx) this._draw(); }); },

  // ---------------- BL-79 改名 ----------------
  onRenameStart() {
    const active = this.data.active;
    if (!active) return;
    this.setData({ renaming: true, renameValue: active.title || '', renameError: '', renameSubmitting: false });
  },

  onRenameInput(e) {
    this.setData({ renameValue: String((e && e.detail && e.detail.value) || ''), renameError: '' });
  },

  onRenameCancel() {
    if (this.data.renameSubmitting) return;
    this.setData({ renaming: false, renameError: '' });
  },

  /** 空名/超长不在前端拦:后端是真源,报错原文回显;成功后以返回的卡名为准,再回读当前分类列表。 */
  onRenameSubmit() {
    const active = this.data.active;
    if (!active || this.data.renameSubmitting) return;
    this.setData({ renameSubmitting: true, renameError: '' });
    app.sendRequest({
      url: '/api/object-card/rename',
      method: 'POST',
      hideLoading: true,
      silentError: true,
      autoErrorToast: false,
      data: { id: active.id, title: this.data.renameValue },
      success: (res) => {
        if (this._destroyed) return;
        const saved = res && (res.code === 200 || res.code === '200') && res.data
          && typeof res.data === 'object' && typeof res.data.title === 'string' ? res.data : null;
        if (!saved) {
          this.setData({ renameSubmitting: false, renameError: renameErrorText(res) });
          return;
        }
        this.setData({
          renaming: false,
          renameSubmitting: false,
          active: Object.assign({}, active, { title: saved.title }),
        });
        cyToast('已改名');
        this.load(this.data.filter);
      },
      fail: () => {
        if (this._destroyed) return;
        this.setData({ renameSubmitting: false, renameError: '网络异常，请重试' });
      },
      successStatusAbnormal: () => {
        if (this._destroyed) return;
        this.setData({ renameSubmitting: false, renameError: '改名没成功，请稍后重试' });
      },
    });
  },

  // 自动化自证:堆里画了几件 + 后端说共几件
  __layoutProbe() { return { n: this._bodies.length, total: this._total, filter: this.data.filter }; },
});

function renameErrorText(res) {
  const fallback = '改名没成功，请稍后重试';
  return (app.getRequestErrorMessage && app.getRequestErrorMessage(res, fallback)) || fallback;
}
