// 主题模板预览(2026-10-28 业主定稿原型 v21):方形照片卡扇形叠放，左右滑切换，
// 点最前面那张翻面(放大 1.25,两侧邻卡仍露出);下方只有标题、✓ 清单和「用这个模板」。
// 主题模板没有场地：只讲玩法与故事，不展示里程、站点或位置信息。
const art = require('../shelf/art.js');
const { buildIntro } = require('./intro.js');
const { readReducedMotion } = require('../../../utils/motion-preference.js');

const app = getApp();
// p3 F3:服务端明确拒绝(模板不存在)时「重试」永远不会成功,改给返回。
function refusalOf(res) { var c = Number(res && res.code), m = String(res && res.msg || ''); return c && c !== 200 && c !== 401 && c !== 2 && m && m !== '系统繁忙，请稍后重试' ? m : ''; }
// 同一张封面在每张卡上取不同局部:[水平%, 垂直%, 放大倍数]
const CROP = [[50, 50, 1], [18, 40, 1.7], [82, 35, 1.8], [30, 75, 1.9], [70, 70, 1.7], [45, 20, 2], [60, 55, 2.2], [25, 25, 1.9]];
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
  const total = fmtSec(info.totalTime);
  // 第十二轮 k3(p3 N3):卡背原来按产品类型写死「读档案 · 做任务，拿证据」「三选一结局 · 领取勋章」,
  // 不是这份模板的数据。卡背只留玩法名与时长,不再替作者许诺流程。
  const cards = games.map(function (g) {
    const minutes = Number(g.duration || 0);
    return { title: g.title || '', steps: [], foot: minutes ? minutes + ' 分钟' : total };
  });
  const list = cards.map(function (c) { return c.title; });
  const cover = { cover: true, title: info.name || '', steps: list.length > 5 ? list.slice(0, 5).concat('…') : list, foot: total };
  return [cover].concat(cards);
}

function buildChecks(info, gameCount) {
  const dur = fmtSec(info.totalTime);
  // 第十二轮 k3(p3 N3):清单只写模板里真有的东西(章节数、节点数、时长、具名勋章),
  // 不再写死「3 关剧情，每关一个玩法」「最后一关三选一结局」「随时开始，可以跨天」。
  const chapterList = Array.isArray(info.chapters) ? info.chapters : [];
  if (Number(info.productType) === 1) {
    const nodes = chapterList.reduce(function (n, c) { return n + (Number(c && c.nodeCount) || 0); }, 0);
    const shape = [chapterList.length ? chapterList.length + ' 章节' : '', nodes ? nodes + ' 节点' : ''].filter(Boolean).join(' · ');
    return [shape, dur ? '约 ' + dur : '',
      info.finishMedalName ? '勋章「' + info.finishMedalName + '」' : ''].filter(Boolean);
  }
  if (gameCount <= 1) return [gameCount ? '1 个玩法' : '', dur ? '约 ' + dur : ''].filter(Boolean);
  const chapters = chapterList
    .map(function (c) { return String(c.name || '').split(' ')[0]; }).filter(Boolean);
  return [chapters.length ? chapters.join(' / ') : '', gameCount + ' 个小玩法', dur ? '首局约 ' + dur : ''].filter(Boolean);
}

Page({
  data: {
    loading: true,
    errorMsg: '',
    errorRefused: false,
    name: '',
    cover: '',
    glow: 'hsla(270,45%,34%,.85)',
    cards: [],
    checks: [],
    intro: { desc: '', chapters: [] },
    sel: 0,
    flip: false,
    entered: false,
    settled: true,
    reducedMotion: false,
    light: false,
    navTop: (getApp().globalData.statusBarHeight || 20) + (getApp().globalData.navBarHeight || 44)
  },

  onLoad(options) {
    options = options || {};
    this._id = options.id;
    this._scope = options.scope === 'MERCHANT' ? 'MERCHANT' : '';
    this._readonly = options.readonly === '1';
    // 商家侧一律浅色(业主规则):只换颜色，版式同玩家侧深色版
    this.setData({ reducedMotion: readReducedMotion(), light: this._scope === 'MERCHANT' });
    // 浅色版系统状态栏换深色字(VPS 测试服实测白字压浅底看不见)
    if (this._scope === 'MERCHANT') wx.setNavigationBarColor({ frontColor: '#000000', backgroundColor: '#ffffff' }); // ds-ok: 系统导航栏只收字面色
    this.load();
  },

  onHide() {
    this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
  },

  onUnload() {
    this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
    this._unloaded = true;
    this._seq = (this._seq || 0) + 1;
    if (this._enterTimer) clearTimeout(this._enterTimer);
    if (this._settleTimer) clearTimeout(this._settleTimer);
  },

  goBack() { wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/template/index' }) }); },

  load() {
    const that = this;
    const seq = this._seq = (this._seq || 0) + 1;
    if (!this._id) { this.setData({ loading: false, errorRefused: true, errorMsg: '缺少模板ID' }); return; } this.setData({ loading: true, errorMsg: '', errorRefused: false });
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
          that.setData({ loading: false, errorRefused: Boolean(refusalOf(res)), errorMsg: app.getRequestErrorMessage(res, '主题模板加载失败') });
          return;
        }
        that._delivery = info.contentDelivery || null;
        const a = art.topicArt(info.name);
        const cards = buildDeck(info);
        that.setData({
          loading: false,
          name: info.name || '',
          cover: a ? a.cover : (info.imgUrl || ''),
          glow: a ? a.glow : that.data.glow,
          checks: buildChecks(info, cards.length - 1),
          intro: buildIntro(info),
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

  // 「用这个模板」：与详情页同一条采用流程（requestId 幂等、登录与只读闸一致）
  useTemplate() {
    if (!this._id) return;
    const adoption = require('../utils/topic-adoption.js'); // 点击时才加载，不增加预览首屏依赖
    const useFlow = require('../utils/topic-use-flow.js');
    const cyToast = require('../../../utils/toast.js');
    if (app.ensureLoginFor && !app.ensureLoginFor('publish', () => this.useTemplate(), { owner: this, target: () => [this._id, this._scope] })) return;
    if (this._readonly) { cyToast('当前岗位仅可浏览模板'); return; }
    if (this._copiedId) { this._openCopied(this._copiedId); return; }
    const loc = this._delivery && this._delivery.locationRequirement;
    const placed = !!(loc && loc.physicalPlaceRequired === true && adoption.sourceCityOf(this._delivery));
    useFlow.submit(this, { id: this._id, scope: this._scope, intent: placed ? 'SAME_CITY' : '',
      sourceCityCode: adoption.sourceCityOf(this._delivery), revisionId: adoption.revisionIdOf(this._delivery) }, {
      toast: cyToast,
      onCopied: (id) => { this._copiedId = id; },
      open: (id) => this._openCopied(id)
    });
  },

  _openCopied(id) {
    const qs = ['id=' + id];
    if (this._scope) qs.push('scope=' + this._scope);
    wx.navigateTo({ url: '/pages/publish/fabu/index?' + qs.join('&') });
  },

  // 2026-10-30 V-24:预览页只看,「查看详情」进主题模板详情;复制草稿(应用)搬到详情页
  goDetail() {
    if (!this._id) return;
    const qs = ['id=' + this._id];
    if (this._scope) qs.push('scope=' + this._scope);
    if (this._readonly) qs.push('readonly=1');
    wx.navigateTo({ url: '/pages/templatedetail/topic-detail/index?' + qs.join('&') });
  }
});
