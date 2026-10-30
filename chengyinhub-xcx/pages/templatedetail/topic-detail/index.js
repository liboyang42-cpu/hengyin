// 主题模板详情(2026-10-30 业主 V-24「这个主题详情要出一个原型设计 里面可以看分别有什么游戏 剧情是什么」):
// 模板预览「查看详情」进来。版式照抄玩法模板详情(templatedetail):封面 + 名字 + 发布方、「应用」与分享同款、
// 详情 / 能力两个 tab。详情 = 剧情(开场白 → 每章一段 + 这一章依次经过的节点)、规模、节点、通关勋章;
// 能力 = 概要一行 + 按站列出这一站玩什么、玩家做什么、大概多久。数据只读 /api/template/topic-template/info。
const art = require('../shelf/art.js');
const cyToast = require('../../../utils/toast.js');

const app = getApp();
const TABS = [{ key: 'details', label: '详情' }, { key: 'capabilities', label: '能力' }];

function fmtSec(sec) {
  const m = Math.round(Number(sec || 0) / 60);
  if (!m) return '';
  if (m < 60) return m + ' 分钟';
  const h = Math.floor(m / 60);
  const r = m % 60;
  return h + ' 小时' + (r ? r + ' 分' : '');
}

function buildChapters(info) {
  let no = 0;
  return (Array.isArray(info.chapters) ? info.chapters : []).map(function (c, i) {
    const nodes = Array.isArray(c.nodes) ? c.nodes : [];
    return {
      key: String(c.id || i),
      // 章名本身以「第…章」开头时不再重复编号
      no: /^第/.test(String(c.name || '')) ? '' : '第 ' + (i + 1) + ' 章',
      name: c.name || '',
      text: c.description || '',
      stops: nodes.map(function (n) { no += 1; return { no: no, name: n.name || '第 ' + no + ' 站' }; })
    };
  });
}

function buildStops(info) {
  const out = [];
  (Array.isArray(info.chapters) ? info.chapters : []).forEach(function (c) {
    (Array.isArray(c.nodes) ? c.nodes : []).forEach(function (n) {
      const minutes = Number(n.gameDuration || 0);
      out.push({
        no: out.length + 1,
        name: n.name || '',
        game: n.gameTitle || '',
        doText: n.hookTeaser || '',
        dur: minutes ? '约 ' + minutes + ' 分钟' : ''
      });
    });
  });
  return out;
}

function buildSummary(info, stops) {
  const games = Array.isArray(info.games) ? info.games : [];
  const total = fmtSec(info.totalTime);
  return [
    info.mode === 'FREE_EXPLORE' ? '自由探索' : '城市定向',
    info.mode === 'FREE_EXPLORE' ? '可以跨天' : '',
    games.length ? games.length + ' 个玩法' : '',
    stops.length ? stops.length + ' 站' : '',
    total ? '约 ' + total : ''
  ].filter(Boolean);
}

Page({
  data: {
    loading: true,
    loadError: false,
    loadErrorMsg: '',
    statusBarHeight: (getApp().globalData || {}).statusBarHeight || 20,
    navBarHeight: (getApp().globalData || {}).navBarHeight || 44,
    light: false,
    tabs: TABS,
    activeTab: 'details',
    name: '',
    cover: '',
    author: '',
    official: false,
    opening: '',
    chapters: [],
    stats: [],
    gallery: [],
    stops: [],
    summary: [],
    medalName: '',
    medalImg: ''
  },

  onLoad(options) {
    options = options || {};
    this._id = options.id;
    this._scope = options.scope === 'MERCHANT' ? 'MERCHANT' : '';
    this._readonly = options.readonly === '1';
    // 商家侧一律浅色(业主规则),版式同玩家侧深色版
    this.setData({ light: this._scope === 'MERCHANT', activeTab: options.tab === 'capabilities' ? 'capabilities' : 'details' });
    this.load();
  },

  onUnload() {
    this._unloaded = true;
    this._seq = (this._seq || 0) + 1;
  },

  load() {
    const that = this;
    const seq = this._seq = (this._seq || 0) + 1;
    this.setData({ loading: true, loadError: false, loadErrorMsg: '' });
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
          that.setData({ loading: false, loadError: true, loadErrorMsg: app.getRequestErrorMessage(res, '主题模板加载失败') });
          return;
        }
        const a = art.topicArt(info.name);
        const chapters = buildChapters(info);
        const stops = buildStops(info);
        const nodeImgs = [];
        (Array.isArray(info.chapters) ? info.chapters : []).forEach(function (c) {
          (Array.isArray(c.nodes) ? c.nodes : []).forEach(function (n) { if (n.imgUrl) nodeImgs.push({ img: n.imgUrl, name: n.name || '', teaser: n.hookTeaser || '' }); });
        });
        const total = fmtSec(info.totalTime);
        that.setData({
          loading: false,
          name: info.name || '',
          cover: a ? a.cover : (info.imgUrl || ''),
          author: info.author || '城瘾',
          official: Number(info.isOfficial) === 1,
          opening: info.description || '',
          chapters: chapters,
          stats: [
            chapters.length ? { value: String(chapters.length), label: '章节' } : null,
            stops.length ? { value: String(stops.length), label: '节点' } : null,
            total ? { value: total, label: '总时长' } : null
          ].filter(Boolean),
          gallery: nodeImgs,
          stops: stops,
          summary: buildSummary(info, stops),
          medalName: info.finishMedalName || '',
          medalImg: info.finishMedalImg || ''
        });
      },
      fail(res) {
        if (seq !== that._seq) return;
        that.setData({ loading: false, loadError: true, loadErrorMsg: app.getRequestErrorMessage(res, '主题模板加载失败') });
      }
    });
  },

  onRetry() { this.load(); },

  switchTab(e) {
    const key = e && e.detail && e.detail.key;
    if (key === 'details' || key === 'capabilities') this.setData({ activeTab: key });
  },

  previewNode(e) {
    const url = e.currentTarget.dataset.url;
    if (!url) return;
    wx.previewImage({ current: url, urls: this.data.gallery.map(function (g) { return g.img; }) });
  },

  // 「应用」= 整包复制成我的草稿,进主题配置(与模板预览原来的「用这个模板」同一条链路),不再二次确认
  useTemplate() {
    if (app.ensureLoginFor && !app.ensureLoginFor('publish', () => this.useTemplate(), { owner: this, target: () => [this._id, this._scope] })) return; // 游客写前登录
    if (this._readonly) { cyToast('当前岗位仅可浏览模板'); return; }
    // 后端每次调用都整包复制出新草稿,连点两下就是两份:在途时挡住
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
        if (that._unloaded) return; // 复制回包晚于离开本页:不再把用户拉去编辑器
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
  },

  onShareAppMessage() {
    return {
      title: this.data.name || '主题模板',
      path: '/pages/templatedetail/topic-detail/index?id=' + this._id,
      imageUrl: this.data.cover || undefined
    };
  }
});
