// 主题模板详情(2026-10-30 业主 V-24「这个主题详情要出一个原型设计 里面可以看分别有什么游戏 剧情是什么」):
// 模板预览「查看详情」进来。版式照抄玩法模板详情(templatedetail):封面 + 名字 + 发布方、「应用」与分享同款、
// 详情 / 能力两个 tab。详情 = 剧情(开场白 → 每章一段 + 这一章依次经过的节点)、规模、节点、通关勋章;
// 能力 = 概要一行 + 按站列出这一站玩什么、玩家做什么、大概多久。数据只读 /api/template/topic-template/info。
const art = require('../shelf/art.js');
const cyToast = require('../../../utils/toast.js');
const adoption = require('../utils/topic-adoption.js');
const morph = require('../../../utils/morph-success.js');

const app = getApp();
const TABS = [{ key: 'details', label: '详情' }, { key: 'capabilities', label: '能力' }];
// p3 F3:服务端明确拒绝(不存在 / 不是你的)要讲原因并给返回;网络与系统繁忙仍按加载失败留白。
function refusalOf(res) { var c = Number(res && res.code), m = String(res && res.msg || ''); return c && c !== 200 && c !== 401 && c !== 2 && m && m !== '系统繁忙，请稍后重试' ? m : ''; }

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
    refusal: '',
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
    medalImg: '',
    deliveryRows: [],
    // U02-02:采用方式弹层(沿用原地点 / 换一个城市)
    adoptShow: false,
    adoptIntent: '',
    adoptCityName: '',
    adoptCityCode: '',
    adoptCities: adoption.adoptCities(),
    adoptCityIndex: -1,
    adoptRebind: 0,
    adoptError: '',
    adoptBusy: false,
    adoptDone: false,
    adoptUnknown: false
  },

  onLoad(options) {
    options = options || {};
    this._id = options.id;
    this._scope = options.scope === 'MERCHANT' ? 'MERCHANT' : '';
    this._readonly = options.readonly === '1';
    // 商家侧一律浅色(业主规则),版式同玩家侧深色版
    this.setData({ light: this._scope === 'MERCHANT', activeTab: options.tab === 'capabilities' ? 'capabilities' : 'details' });
    // 浅色版系统状态栏换深色字,否则白字压浅底看不见(VPS 测试服实测)
    if (this._scope === 'MERCHANT') wx.setNavigationBarColor({ frontColor: '#000000', backgroundColor: '#ffffff' }); // ds-ok: 系统导航栏只收字面色
    this.load();
  },

  onUnload() {
    this._unloaded = true;
    morph.cancel(this);
    this._seq = (this._seq || 0) + 1;
  },

  load() {
    const that = this;
    const seq = this._seq = (this._seq || 0) + 1;
    if (!this._id) { this.setData({ loading: false, loadError: true, refusal: '链接已失效' }); return; }
    this.setData({ loading: true, loadError: false, loadErrorMsg: '', refusal: '' });
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
          that.setData({ loading: false, loadError: true, refusal: refusalOf(res), loadErrorMsg: app.getRequestErrorMessage(res, '主题模板加载失败') });
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
          medalImg: info.finishMedalImg || '',
          deliveryRows: adoption.deliveryRows(info.contentDelivery)
        });
        that._delivery = info.contentDelivery || null;
        that._loadCredits(seq, nodeImgs);
      },
      fail(res) {
        if (seq !== that._seq) return;
        that.setData({ loading: false, loadError: true, loadErrorMsg: app.getRequestErrorMessage(res, '主题模板加载失败') });
      }
    });
  },

  // U02-05:节点图署名 / AI 标识 / 来源待确认,逐张只读;最多前 8 张,换页或重载后迟到回包丢弃
  _loadCredits(seq, imgs) {
    (imgs || []).slice(0, 8).forEach((g, i) => {
      app.sendRequest({
        hideLoading: true, autoErrorToast: false,
        url: '/api/media/asset/attribution', method: 'GET', data: { reference: g.img },
        success: res => {
          if (seq !== this._seq || !res || String(res.code) !== '200') return;
          const credit = adoption.creditText(res.data);
          if (credit && this.data.gallery[i] && this.data.gallery[i].img === g.img) this.setData({ ['gallery[' + i + '].credit']: credit });
        }
      });
    });
  },

  onRetry() { this.load(); },

  goBack() { wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/template/index' }) }); },

  switchTab(e) {
    const key = e && e.detail && e.detail.key;
    if (key === 'details' || key === 'capabilities') this.setData({ activeTab: key });
  },

  previewNode(e) {
    const url = e.currentTarget.dataset.url;
    if (!url) return;
    wx.previewImage({ current: url, urls: this.data.gallery.map(function (g) { return g.img; }) });
  },

  // 「应用」= 整包复制成我的草稿,进主题配置。U02-02:来源有真实地点时先选「沿用原地点 / 换一个城市」;
  // 纯线上内容(没有地点事实)没有可换的城市,直接按旧版采用。
  useTemplate() {
    if (app.ensureLoginFor && !app.ensureLoginFor('publish', () => this.useTemplate(), { owner: this, target: () => [this._id, this._scope] })) return; // 游客写前登录
    if (this._readonly) { cyToast('当前岗位仅可浏览模板'); return; }
    if (!this._id) return;
    // 第十二轮 k9(p3 N6):本页已经复制出一份草稿,从编辑器回来再点「应用」就回到那一份,不再每点一次多一份草稿
    if (this._copiedId) { this._openCopiedDraft(this._copiedId); return; }
    const loc = this._delivery && this._delivery.locationRequirement;
    if (!loc || loc.physicalPlaceRequired !== true || !adoption.sourceCityOf(this._delivery)) { this._submitUse(''); return; }
    const n = Number(loc.stationWithAddressCount);
    this.setData({ adoptShow: true, adoptError: '', adoptRebind: Number.isSafeInteger(n) && n > 0 ? n : 0 });
  },

  closeAdopt() { if (!this.data.adoptBusy) this.setData({ adoptShow: false }); },

  pickSameCity() { if (!this.data.adoptBusy && !this.data.adoptUnknown) this.setData({ adoptIntent: 'SAME_CITY', adoptError: '' }); },

  // 原生地区选择器 level=city:code[1] 是 6 位城市 adcode,与服务端 targetCityCode 同口径
  onAdoptCity(e) {
    if (this.data.adoptBusy || this.data.adoptUnknown) return;
    const idx = Number(e && e.detail && e.detail.value);
    const city = this.data.adoptCities[idx] || null;
    const code = city ? city.code : '';
    this.setData({ adoptIntent: 'NEW_CITY', adoptCityCode: code, adoptCityName: city ? city.name : '', adoptCityIndex: city ? idx : -1,
      adoptError: /^\d{6}$/.test(code) ? '' : '没有读到这个城市的编码，请重新选择' });
  },

  confirmAdopt() {
    if (this.data.adoptBusy) return;
    if (this.data.adoptUnknown) { wx.navigateTo({ url: '/subpackageA/pages/myproject/index' }); return; }
    if (!this.data.adoptIntent) { this.setData({ adoptError: '请选择沿用原地点或换一个城市' }); return; }
    this._submitUse(this.data.adoptIntent);
  },

  _submitUse(intent) {
    // 后端每次调用都整包复制出新草稿,连点两下就是两份:在途时挡住
    if (this._using) { cyToast('正在配置，请稍候'); return; }
    const built = adoption.usePayload({ id: this._id, scope: this._scope, intent, targetCityCode: this.data.adoptCityCode,
      sourceCityCode: adoption.sourceCityOf(this._delivery), revisionId: adoption.revisionIdOf(this._delivery) });
    if (built.error) { this.setData({ adoptError: built.error }); return; }
    const that = this;
    this._using = true;
    this.setData({ adoptBusy: true, adoptError: '' });
    app.sendRequest({
      url: '/api/template/topic-template/use',
      method: 'POST',
      data: built.data,
      success(res) {
        that._using = false;
        if (that._unloaded) return; // 复制回包晚于离开本页:不再把用户拉去编辑器
        const result = adoption.useResult(res);
        if (!result.ok) {
          // 服务端明确拒绝:原文写在选项下面,保留已选的采用方式
          if (that.data.adoptShow) that.setData({ adoptBusy: false, adoptError: result.message || '使用模板失败' });
          else { that.setData({ adoptBusy: false }); cyToast(result.message || '使用模板失败'); }
          return;
        }
        const copiedId = result.topicId;
        that._copiedId = copiedId;
        // qx-u3:复制成功后按钮先收成勾、停一拍,再关面板进编辑器(减动效直接走)
        morph.settle(that, 'adoptDone', () => {
          if (that._unloaded) return;
          that.setData({ adoptShow: false });
          that._openCopiedDraft(copiedId);
        }, { with: { adoptBusy: false } });
      },
      fail() {
        that._using = false;
        if (that._unloaded) return;
        // 结果未知:/use 没有请求号可查,不自动重发(会多复制一份);保留所选方式,去我的草稿核对
        if (that.data.adoptShow) that.setData({ adoptBusy: false, adoptUnknown: true, adoptError: '采用结果未确认' });
        else { that.setData({ adoptBusy: false }); cyToast('网络错误，请重试'); }
      }
    });
  },

  _openCopiedDraft(copiedId) {
    const qs = ['id=' + copiedId];
    if (this._scope) qs.push('scope=' + this._scope);
    wx.navigateTo({ url: '/pages/publish/fabu/index?' + qs.join('&') });
  },

  onShareAppMessage() {
    return {
      title: this.data.name || '主题模板',
      path: '/pages/templatedetail/topic-detail/index?id=' + this._id,
      imageUrl: this.data.cover || undefined
    };
  }
});
