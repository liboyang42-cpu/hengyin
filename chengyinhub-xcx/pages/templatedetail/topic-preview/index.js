// 主题模板预览(2026-10-28 业主定稿原型 v21):方形照片卡扇形叠放，左右滑切换，
// 点最前面那张翻面(放大 1.25,两侧邻卡仍露出);下方只有标题、✓ 清单和「用这个模板」。
// 主题模板没有场地：只讲玩法与故事，不展示里程、站点或位置信息。
const art = require('../shelf/art.js');
const cyToast = require('../../../utils/toast.js');
const { readReducedMotion } = require('../../../utils/motion-preference.js');

const app = getApp();
// 同一张封面在每张卡上取不同局部:[水平%, 垂直%, 放大倍数]
const CROP = [[50, 50, 1], [18, 40, 1.7], [82, 35, 1.8], [30, 75, 1.9], [70, 70, 1.7], [45, 20, 2], [60, 55, 2.2], [25, 25, 1.9]];
const STEPS = {
  mid: ['读档案', '做任务，拿证据', '解锁下一关'],
  last: ['汇总证据', '三选一结局', '领取勋章'],
  free: ['随时开始', '任选一个轻动作', '玩满 3 次通关'],
  life: ['答几道小题', '拿到备用答案']
};
const FAN_X = [0, 62, 108, 140];
const FAN_R = [0, 6, 11, 15];

function fmtSec(sec) {
  const m = Math.round(Number(sec || 0) / 60);
  if (!m) return '';
  if (m < 60) return m + ' 分钟';
  const h = Math.floor(m / 60);
  const r = m % 60;
  return h + ' 小时' + (r ? r + ' 分' : '');
}

function buildDeck(info) {
  const games = Array.isArray(info.games) ? info.games : [];
  const orienteering = Number(info.productType) === 1;
  const total = fmtSec(info.totalTime);
  const cards = games.map(function (g, i) {
    const steps = orienteering ? (i === games.length - 1 ? STEPS.last : STEPS.mid)
      : (games.length <= 1 ? STEPS.free : STEPS.life);
    const minutes = Number(g.duration || 0);
    return { title: g.title || '', steps: steps, foot: minutes ? minutes + ' 分钟' : total };
  });
  const list = cards.map(function (c) { return c.title; });
  const cover = { cover: true, title: info.name || '', steps: list.length > 5 ? list.slice(0, 5).concat('…') : list, foot: total };
  return [cover].concat(cards);
}

function buildChecks(info, gameCount) {
  const dur = fmtSec(info.totalTime);
  if (Number(info.productType) === 1) {
    return [gameCount + ' 关剧情，每关一个玩法', '最后一关三选一结局', dur ? '约 ' + dur : '',
      info.finishMedalName ? '勋章「' + info.finishMedalName + '」' : ''].filter(Boolean);
  }
  if (gameCount <= 1) return ['1 个玩法，一个人就能玩', '随时开始，可以跨天', dur ? '约 ' + dur : ''].filter(Boolean);
  const chapters = (Array.isArray(info.chapters) ? info.chapters : [])
    .map(function (c) { return String(c.name || '').split(' ')[0]; }).filter(Boolean);
  return [chapters.length ? chapters.join(' / ') : '', gameCount + ' 个小玩法', dur ? '首局约 ' + dur : ''].filter(Boolean);
}

Page({
  data: {
    loading: true,
    errorMsg: '',
    name: '',
    cover: '',
    glow: 'hsla(270,45%,34%,.85)',
    cards: [],
    checks: [],
    sel: 0,
    flip: false,
    entered: false,
    settled: true,
    reducedMotion: false,
    navTop: (getApp().globalData.statusBarHeight || 20) + (getApp().globalData.navBarHeight || 44)
  },

  onLoad(options) {
    options = options || {};
    this._id = options.id;
    this._scope = options.scope === 'MERCHANT' ? 'MERCHANT' : '';
    this._readonly = options.readonly === '1';
    this.setData({ reducedMotion: readReducedMotion() });
    this.load();
  },

  onUnload() {
    this._unloaded = true;
    this._seq = (this._seq || 0) + 1;
    if (this._enterTimer) clearTimeout(this._enterTimer);
    if (this._settleTimer) clearTimeout(this._settleTimer);
  },

  load() {
    const that = this;
    const seq = this._seq = (this._seq || 0) + 1;
    this.setData({ loading: true, errorMsg: '' });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/template/topic-template/info',
      method: 'POST',
      data: { id: this._id },
      success(res) {
        if (seq !== that._seq) return;
        const info = res && res.code == '200' ? res.data : null;
        if (!info || typeof info !== 'object') {
          that.setData({ loading: false, errorMsg: app.getRequestErrorMessage(res, '主题模板加载失败') });
          return;
        }
        const a = art.topicArt(info.name);
        const cards = buildDeck(info);
        that.setData({
          loading: false,
          name: info.name || '',
          cover: a ? a.cover : (info.imgUrl || ''),
          glow: a ? a.glow : that.data.glow,
          checks: buildChecks(info, cards.length - 1),
          sel: 0,
          flip: false,
          entered: false
        });
        that.layout(cards, true);
      },
      fail(res) {
        if (seq !== that._seq) return;
        that.setData({ loading: false, errorMsg: app.getRequestErrorMessage(res, '主题模板加载失败') });
      }
    });
  },

  // 每次打开都从中间扇开:先全部收在中心(透明、缩小),下一帧按与中心的距离依次展开
  layout(cards, enter) {
    const n = cards.length;
    const sel = this.data.sel;
    const flip = this.data.flip;
    const placed = cards.map(function (c, i) {
      let o = i - sel;
      if (n > 2) { if (o > n / 2) o -= n; if (o < -n / 2) o += n; }
      const a = Math.min(Math.abs(o), 3);
      const s = Math.sign(o);
      const crop = CROP[i % CROP.length];
      let style;
      if (flip && !a) {
        style = 'transform: scale(1.25); z-index: 40; opacity: 1;';
      } else if (flip) {
        style = 'transform: translateX(' + (s * 124) + 'px) translateY(8px) rotate(' + (s * 8) + 'deg) scale(.9); z-index: ' + (10 - a) +
          '; opacity: ' + (a === 1 ? 1 : 0) + '; filter: brightness(.7);';
      } else {
        style = 'transform: translateX(' + (s * FAN_X[a]) + 'px) translateY(' + (a * 6) + 'px) rotate(' + (s * FAN_R[a]) + 'deg) scale(' + (1 - a * 0.07) + '); z-index: ' + (10 - a) +
          '; opacity: ' + (a > 2 ? 0 : 1) + ';' + (a ? ' filter: brightness(' + (1 - a * 0.22) + ');' : '');
      }
      return Object.assign({}, c, {
        front: !a,
        flipped: flip && !a,
        style: style,
        delay: (Math.abs(o) * 0.09 + 0.05).toFixed(2),
        imgStyle: 'transform: scale(' + crop[2] + '); transform-origin: ' + crop[0] + '% ' + crop[1] + '%;'
      });
    });
    if (!enter || this.data.reducedMotion) {
      this.setData({ cards: placed, entered: true, settled: true });
      return;
    }
    this.setData({ cards: placed, entered: false, settled: false });
    this._enterTimer = setTimeout(() => this.setData({ entered: true }), 60);
    this._settleTimer = setTimeout(() => this.setData({ settled: true }), 1400);
  },

  // 远端封面拉不动(不在包内的主题):换成既有失败态，不拿别的图冒充
  onCoverError() {
    if (this.data.cover) this.setData({ cover: '' });
  },

  move(step) {
    const n = this.data.cards.length;
    if (n < 2) return;
    this.setData({ sel: (this.data.sel + step + n) % n, flip: false });
    this.layout(this.data.cards, false);
  },

  onTouchStart(e) {
    const t = e.touches && e.touches[0];
    this._x = t ? t.clientX : null;
    this._swiped = false;
  },

  onTouchEnd(e) {
    const t = e.changedTouches && e.changedTouches[0];
    if (this._x == null || !t) return;
    const dx = t.clientX - this._x;
    this._x = null;
    if (Math.abs(dx) < 30) return;
    this._swiped = true;
    this.move(dx < 0 ? 1 : -1);
  },

  tapCard(e) {
    if (this._swiped) { this._swiped = false; return; }
    const i = Number(e.currentTarget.dataset.index);
    if (i === this.data.sel) this.setData({ flip: !this.data.flip });
    else this.setData({ sel: i, flip: false });
    this.layout(this.data.cards, false);
  },

  // 主题模板 = 整包复制成我的草稿，进专业编辑器(与原模板页「配置」同一条链路)
  useTemplate() {
    if (app.ensureLoginFor && !app.ensureLoginFor('publish', () => this.useTemplate())) return; // 游客写前登录
    if (this._readonly) { cyToast('当前岗位仅可浏览模板'); return; }
    // 后端每次调用都整包复制出新草稿，连点两下就是两份：在途时挡住
    if (this._using) { cyToast('正在配置，请稍候'); return; }
    if (!this._id) return;
    const that = this;
    this._using = true;
    app.sendRequest({
      url: '/api/template/topic-template/use',
      method: 'POST',
      data: { id: this._id, scope: this._scope },
      success(res) {
        that._using = false;
        if (that._unloaded) return; // 复制回包晚于离开本页：不再把用户拉去编辑器
        const copiedId = res && res.code == '200' && res.data && res.data.topicId;
        if (!copiedId) { cyToast((res && res.msg) || '使用模板失败'); return; }
        const qs = ['id=' + copiedId];
        if (that._scope) qs.push('scope=' + that._scope);
        wx.navigateTo({ url: '/pages/publish/fabu/index?' + qs.join('&') });
      },
      fail() {
        that._using = false;
        if (that._unloaded) return;
        cyToast('网络错误，请重试');
      }
    });
  }
});
