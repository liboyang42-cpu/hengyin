const { applyTheme } = require('../../../utils/theme.js');
// 创建节点玩法 · 第0页(价值主张)
//
// 位置:publish-sheet 卡2 →【本页】→ templateadd(命名)→ temp(编辑器)
// 引导、命名、编辑器各保留一层页面栈:用户在编辑器返回时先回命名,再回引导,
// 不会因为 redirectTo 连续替换页面而直接掉回发布首页。
//
// 2026-10-22 owner 定稿(原型 KGxHWjCsqbYUKt5m8SYJM2):三张扇形卡换成一串漂浮圆圈(运动规则见 orbit.js),
// 深底;去掉「跳过」;文案左对齐、在屏幕中间偏左。点圆圈 → 半屏「玩法预览」,大圆可左右滑换;
// 「用它开始」→ 圆圈飞进按钮,按钮变成「用「X」开始创建」,点了直接用这个玩法开编辑器(编辑器对
// 非本人模板自动 adopt-draft 成我的草稿,见 publish/temp);不选就照旧去命名页新建。
// 点按钮时整页先虚化、染色,再进下一页;返回时虚化收回。
//
// 圆圈数据取真实接口 /api/template/homeData 的精选/热门/最新,不写死图片;
// 拿不到就不画(见 wxml 的 cards.length 闸),不塞假模板冒充现网内容。
const app = getApp();
const { readReducedMotion } = require('../../../utils/motion-preference.js');
const { isRecordList } = require('../../../utils/response-shape.js');
const orbit = require('./orbit.js');

const POOL_MAX = 14;
const DESIGN_W = 390;
const FALLBACK_FILL = '#1C1C1E'; // ds-ok: 无封面时的深底占位圆,canvas 读不到 token

function windowInfo() {
  try { return wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync(); } catch (e) { return { windowWidth: 375, windowHeight: 812, pixelRatio: 2 }; }
}

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    reducedMotion: false,
    cards: [],
    cardsLoading: true,
    cardsError: '',
    navigating: false,
    navigationError: '',
    operationScope: '',
    sheetOpen: false,
    cur: 0,
    bigStyle: '',
    bigHidden: false,
    pick: null,
    ctaThumbShown: false,
    fly: null,
    morph: false,
  },

  onLoad(options) {
    const operationScope = options && options.scope === 'MERCHANT' ? 'MERCHANT' : '';
    applyTheme(operationScope ? 'light' : 'dark');   // 玩家深底;商家恒浅
    this.setData({ operationScope });
    this.loadCards();
  },

  onShow() {
    applyTheme(this.data.operationScope ? 'light' : 'dark');
    const reducedMotion = readReducedMotion();
    if (this.data.reducedMotion !== reducedMotion) this.setData({ reducedMotion });
    if (this.data.morph) this.setData({ morph: false });   // 从下一页返回:虚化收回
    this._resume();
  },

  onHide() { this._pause(); },
  onUnload() { this._pause(); this._disposed = true; },

  loadCards() {
    if (this._cardsRequestInFlight) return;
    const that = this;
    const token = (this._cardsRequestToken || 0) + 1;
    this._cardsRequestToken = token;
    this._cardsRequestInFlight = true;
    this.setData({ cardsLoading: !this.data.cards.length, cardsError: '' });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/template/homeData',
      method: 'POST',
      data: {},
      success(res) {
        if (token !== that._cardsRequestToken) return;
        if (!res || res.code != '200' || !res.data || typeof res.data !== 'object' || Array.isArray(res.data)) {
          that._showCardsError(app.getRequestErrorMessage
            ? app.getRequestErrorMessage(res, '精选示例加载失败，请重试')
            : '精选示例加载失败，请重试');
          return;
        }
        if (!that.applyCards(res.data)) {
          that._showCardsError('精选示例加载失败，请重试');
        }
      },
      fail() {
        if (token !== that._cardsRequestToken) return;
        that._showCardsError('精选示例加载失败，请重试');
      },
      complete() {
        if (token !== that._cardsRequestToken) return;
        that._cardsRequestInFlight = false;
        that.setData({ cardsLoading: false });
      },
    });
  },

  // 精选 → 热门 → 最新依次补满,按 id 去重,最多 14 张
  applyCards(d) {
    const lists = ['recommendList', 'hotList', 'latestList'];
    if (lists.some((key) => d[key] != null && !isRecordList(d[key]))) return false;
    const pool = [];
    const seen = {};
    lists.forEach((key) => (isRecordList(d[key]) ? d[key] : []).forEach((it) => {
      if (pool.length >= POOL_MAX) return;
      const k = it.id == null ? '' : String(it.id);
      if (k && seen[k]) return;
      if (k) seen[k] = true;
      pool.push(it);
    }));
    if (pool.some((it) => it.sysCategoryList != null && !isRecordList(it.sysCategoryList))) return false;
    const cards = pool.map((it, i) => {
      const cat = (it.sysCategoryList || [])[0] || {};
      return {
        id: it.id,
        key: it.id == null ? `example-${i}` : `example-${it.id}`,
        title: it.title || it.name || '',
        imgUrl: it.imgUrl || '',
        catName: cat.categoryName || '节点玩法',
      };
    });
    this.setData({ cards, cardsError: '' }, () => this._initField());
    return true;
  },

  _showCardsError(message) {
    // 已有旧内容:刷新失败静默降级,旧示例留在屏上。
    if (this.data.cards.length) return;
    this.setData({ cardsError: message });
  },

  // ---------------------------------------------------------------- 画布
  _initField() {
    if (this._disposed || this._canvas || !this.data.cards.length || typeof wx === 'undefined' || !wx.createSelectorQuery) return;
    const info = windowInfo();
    this._W = info.windowWidth; this._H = info.windowHeight; this._k = this._W / DESIGN_W;
    const dpr = info.pixelRatio || 2;
    wx.createSelectorQuery().in(this)
      .select('#tiField').fields({ node: true, size: true })
      .select('#tiCopy').boundingClientRect()
      .exec((res) => {
        const node = res && res[0] && res[0].node;
        if (!node || this._disposed) return;
        const copy = res[1];
        node.width = this._W * dpr; node.height = this._H * dpr;
        const ctx = node.getContext('2d');
        ctx.scale(dpr * this._k, dpr * this._k);   // 之后全用 390 宽设计稿坐标
        this._canvas = node; this._ctx = ctx;
        this._center = orbit.center(DESIGN_W, copy ? (copy.top + copy.height / 2) / this._k : 421);
        this._orbit = orbit.createOrbit();
        this._imgs = this.data.cards.map((c) => {
          if (!c.imgUrl) return null;
          const im = node.createImage();
          im.onload = () => { if (this.data.reducedMotion) this._draw(); };
          im.src = c.imgUrl;
          return im;
        });
        const n = this.data.cards.length;
        this._bubImg = [];
        for (let i = 0; i < orbit.N; i += 1) this._bubImg.push(i % n);
        this._nextImg = orbit.N % n;
        this._lastU = [];
        this._draw();
        this._resume();
      });
  },

  _resume() {
    if (!this._canvas || this._raf || this.data.reducedMotion || this._disposed) return;
    let last = Date.now();
    const loop = () => {
      const now = Date.now();
      const dt = Math.min(0.05, (now - last) / 1000); last = now;
      if (!this.data.sheetOpen) orbit.step(this._orbit, dt, !!(this._drag && this._drag.moved));
      this._draw();
      this._raf = this._canvas.requestAnimationFrame(loop);
    };
    this._raf = this._canvas.requestAnimationFrame(loop);
  },

  _pause() {
    if (this._canvas && this._raf) this._canvas.cancelAnimationFrame(this._raf);
    this._raf = 0;
  },

  _bubbles() {
    const o = this._orbit; const c = this._center; const out = [];
    for (let i = 0; i < orbit.N; i += 1) {
      const p = orbit.place(o, i, c.cx, c.cy);
      // 走完一圈从左下重新进屏:换下一张封面
      if (this._lastU[i] != null && p.u < this._lastU[i] - orbit.N / 2) {
        this._bubImg[i] = this._nextImg; this._nextImg = (this._nextImg + 1) % this.data.cards.length;
      }
      this._lastU[i] = p.u;
      p.i = i; p.card = this._bubImg[i];
      out.push(p);
    }
    return out;
  },

  _drawCircle(ctx, b) {
    const im = this._imgs[b.card];
    ctx.save();
    ctx.beginPath(); ctx.arc(b.x, b.y, b.r, 0, Math.PI * 2); ctx.clip();
    if (im && im.width) {
      const s = Math.max((b.r * 2) / im.width, (b.r * 2) / im.height);
      ctx.drawImage(im, b.x - (im.width * s) / 2, b.y - (im.height * s) / 2, im.width * s, im.height * s);
    } else { ctx.fillStyle = FALLBACK_FILL; ctx.fillRect(b.x - b.r, b.y - b.r, b.r * 2, b.r * 2); }
    ctx.restore();
  },

  _draw() {
    const ctx = this._ctx; if (!ctx) return;
    const W = DESIGN_W; const H = this._H / this._k; const B = orbit.RIM;
    ctx.clearRect(0, 0, W, H);
    // 画布可能按原生层渲染、压过半屏与遮罩:半屏打开期间整块不画(下面本来就被遮罩压暗)
    if (this.data.sheetOpen) return;
    const bubs = this._bubbles().filter((b) => !(this.data.bigHidden && b.i === this._openIdx));
    this._last = bubs;
    // 玻璃带以内正常画
    ctx.save(); ctx.beginPath(); ctx.rect(B, 0, W - 2 * B, H); ctx.clip();
    bubs.forEach((b) => this._drawCircle(ctx, b));
    ctx.restore();
    // 左右玻璃边:滑进来的部分以内沿为轴往外拉伸,再以内沿为镜面映出旁边的圆,最后染一层色散
    [0, 1].forEach((side) => {
      const edge = side ? W - B : B;
      const near = bubs.filter((b) => (side ? b.x + b.r > W - B - b.r : b.x - b.r < B + b.r));
      if (!near.length) return;
      ctx.save(); ctx.beginPath(); ctx.rect(side ? W - B : 0, 0, B, H); ctx.clip();
      ctx.save(); ctx.translate(edge, 0); ctx.scale(1.6, 1); ctx.translate(-edge, 0);
      near.forEach((b) => this._drawCircle(ctx, b)); ctx.restore();
      ctx.save(); ctx.globalAlpha = 0.45; ctx.translate(2 * edge, 0); ctx.scale(-1, 1);
      near.forEach((b) => this._drawCircle(ctx, b)); ctx.restore();
      const g = ctx.createLinearGradient(edge, 0, side ? W : 0, 0);
      g.addColorStop(0, 'rgba(255,255,255,0)');
      g.addColorStop(0.4, 'rgba(255,70,50,0.28)');    // ds-ok: 玻璃边色散,canvas 读不到 token
      g.addColorStop(0.7, 'rgba(70,230,200,0.28)');   // ds-ok: 玻璃边色散
      g.addColorStop(1, 'rgba(90,110,255,0.4)');      // ds-ok: 玻璃边色散
      ctx.globalCompositeOperation = 'source-atop'; ctx.fillStyle = g; ctx.fillRect(side ? W - B : 0, 0, B, H);
      ctx.restore();
    });
  },

  // ---------------------------------------------------------------- 手势:上下拖动推转,轻点开预览
  onFieldStart(e) {
    const t = (e.touches || [])[0]; if (!t || !this._orbit) return;
    this._drag = { x0: t.x, y0: t.y, y: t.y, t: Date.now(), moved: false };
  },
  onFieldMove(e) {
    const d = this._drag; const t = (e.touches || [])[0]; if (!d || !t) return;
    if (Math.abs(t.x - d.x0) + Math.abs(t.y - d.y0) > 8) d.moved = true;
    if (!d.moved) return;
    const now = Date.now(); const dy = (t.y - d.y) / this._k; const dts = Math.max(0.008, (now - d.t) / 1000);
    this._orbit.vel = this._orbit.vel * 0.4 + 0.6 * (-dy / orbit.SLOT) / dts;   // 往上拖 = 往前转
    d.y = t.y; d.t = now;
    if (this.data.reducedMotion) { orbit.step(this._orbit, dts, true); this._draw(); }
  },
  onFieldEnd() {
    const d = this._drag; this._drag = null; if (!d) return;
    if (d.moved) {
      const extra = this._orbit.vel - orbit.DRIFT;
      if (extra > 1.5) orbit.startSpin(this._orbit, extra);
      this._orbit.sinceKick = 0;
      return;
    }
    const x = d.x0 / this._k; const y = d.y0 / this._k;
    const hit = (this._last || []).slice().reverse().find((b) => Math.hypot(b.x - x, b.y - y) < b.r && b.x > 0);
    if (hit) this.openSheet(hit);
  },

  // ---------------------------------------------------------------- 预览半屏(圆圈飞进来)
  openSheet(b) {
    if (this._flying) return;
    this._flying = true; this._openIdx = b.i;
    const k = this._k;
    const from = { x: (b.x - b.r) * k, y: (b.y - b.r) * k, w: b.r * 2 * k };
    this.setData({ cur: b.card, bigHidden: true, sheetOpen: true, bigStyle: '' });
    this._measureBig((to) => this._flyTo(this.data.cards[b.card].imgUrl, from, to, () => {
      this._flying = false; this.setData({ bigHidden: false });
    }));
  },

  // 大圆最终位置:半屏还在入场位移中,先量当前位置再扣掉剩余位移
  _measureBig(cb) {
    wx.createSelectorQuery().in(this)
      .select('#tiBig').boundingClientRect()
      .select('#tiSheet').boundingClientRect()
      .exec((r) => {
        const big = r && r[0]; const sheet = r && r[1];
        if (!big || !sheet) { cb(null); return; }
        const shift = sheet.top - (this._H - sheet.height);
        cb({ x: big.left, y: big.top - shift, w: big.width });
      });
  },

  _flyTo(src, from, to, done) {
    if (!to || this.data.reducedMotion) { this.setData({ fly: null }); done(); return; }
    // 只动 transform(合成层),落点尺寸定死,起点用 translate + scale 反推回去
    const base = `left:${to.x}px;top:${to.y}px;width:${to.w}px;height:${to.w}px;`;
    const s = from.w / to.w;
    this.setData({ fly: { src, style: base + `transform:translate(${from.x - to.x}px,${from.y - to.y}px) scale(${s});transition:none;` } }, () => {
      setTimeout(() => {
        this.setData({ 'fly.style': base + 'transform:none;' });
        setTimeout(() => { this.setData({ fly: null }); done(); }, 580);
      }, 30);
    });
  },

  closeSheet() {
    if (this._flying) return;
    this._openIdx = -1;
    this.setData({ sheetOpen: false, bigStyle: '' });
  },

  noop() {},

  pickMini(e) { this._swap(Number(e.currentTarget.dataset.i), 0); },

  // 大圆左右滑换一个(带倾斜)
  onBigStart(e) { const t = (e.touches || [])[0]; if (t) this._big = { x0: t.clientX, dx: 0 }; },
  onBigMove(e) {
    const s = this._big; const t = (e.touches || [])[0]; if (!s || !t) return;
    s.dx = t.clientX - s.x0;
    this.setData({ bigStyle: `transform:translateX(${s.dx}px) rotate(${s.dx / 14}deg);transition:none;` });
  },
  onBigEnd() {
    const s = this._big; this._big = null; if (!s) return;
    const n = this.data.cards.length;
    if (Math.abs(s.dx) > 70) this._swap((this.data.cur + (s.dx < 0 ? 1 : -1) + n) % n, s.dx < 0 ? -1 : 1);
    else this.setData({ bigStyle: '' });
  },
  _swap(i, dir) {
    if (i === this.data.cur && !dir) return;
    if (this.data.reducedMotion) { this.setData({ cur: i, bigStyle: '' }); return; }
    const out = dir || (i > this.data.cur ? -1 : 1);
    this.setData({ bigStyle: `transform:translateX(${out * 240}px) rotate(${out * 12}deg) scale(.8);opacity:0;transition:all .24s ease-in;` });
    setTimeout(() => {
      this.setData({ cur: i, bigStyle: `transform:translateX(${-out * 200}px) rotate(${-out * 10}deg) scale(.82);opacity:0;transition:none;` }, () => {
        setTimeout(() => this.setData({ bigStyle: '' }), 30);
      });
    }, 240);
  },

  // 用它开始:大圆飞进按钮左侧的小圆
  useCurrent() {
    if (this._flying) return;
    this._flying = true;
    const card = this.data.cards[this.data.cur];
    this.setData({ pick: { idx: this.data.cur, id: card.id, title: card.title, imgUrl: card.imgUrl }, ctaThumbShown: false }, () => {
      wx.createSelectorQuery().in(this)
        .select('#tiBig').boundingClientRect()
        .select('#tiThumb').boundingClientRect()
        .exec((r) => {
          const big = r && r[0]; const thumb = r && r[1];
          this._openIdx = -1;
          this.setData({ sheetOpen: false, bigHidden: true });
          const from = big ? { x: big.left, y: big.top, w: big.width } : null;
          const to = thumb ? { x: thumb.left, y: thumb.top, w: thumb.width } : null;
          const finish = () => { this._flying = false; this.setData({ ctaThumbShown: true, bigHidden: false }); };
          if (!from || !to) { finish(); return; }
          this._flyTo(card.imgUrl, from, to, finish);
        });
    });
  },

  // ---------------------------------------------------------------- 开始创建(整页虚化后进下一页)
  goCreate() {
    if (this.data.navigating) return;
    this.setData({ navigating: true, navigationError: '' });
    const pick = this.data.pick;
    const merchant = this.data.operationScope === 'MERCHANT';
    // 选了玩法:直接用它开编辑器(编辑器对非本人模板自动 adopt-draft 成我的草稿);没选:照旧去命名页新建。
    // navigateTo 而非 redirectTo:引导页必须留在栈里(publish-editor-return-flow 合同)。
    // CU-M-31:成功也要放闸 —— 本页留在栈里,从创建页返回后 navigating 还是 true 会把按钮挡死。
    const success = () => this.setData({ navigating: false });
    const fail = () => this.setData({ navigating: false, morph: false, navigationError: '创建页面没有打开，请重试' });
    const go = pick && pick.id != null
      ? () => wx.navigateTo({ url: '/pages/publish/temp/index?id=' + pick.id + (merchant ? '&scope=MERCHANT' : ''), success, fail })
      : () => wx.navigateTo({ url: '/pages/publish/templateadd/templateadd' + (merchant ? '?scope=MERCHANT' : ''), success, fail });
    if (this.data.reducedMotion || typeof setTimeout !== 'function') { go(); return; }
    this.setData({ morph: true });
    setTimeout(go, 420);
  },
});
