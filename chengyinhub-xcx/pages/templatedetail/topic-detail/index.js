// 主题模板详情(2026-10-30 业主 V-24「这个主题详情要出一个原型设计 里面可以看分别有什么游戏 剧情是什么」):
// 模板预览「查看详情」进来。版式照抄玩法模板详情(templatedetail):封面 + 名字 + 发布方、「应用」与分享同款、
// 详情 / 能力两个 tab。详情 = 剧情(开场白 → 每章一张紧凑卡)、规模、节点、通关勋章;
// 能力 = 概要数字卡 + 按章分组、每站一张卡(玩什么、玩家做什么、大概多久)。数据只读 /api/template/topic-template/info。
// 2026-11-14 业主「主题模板太复杂、能力那块没有排版,要卡片和样式」:开场白默认收 4 行可展开;
// 章节卡不再列站点路径,站点只在「能力」里按章分组出卡;采用条件改成分组键值卡。
const art = require('../shelf/art.js');
const cyToast = require('../../../utils/toast.js');
const adoption = require('../utils/topic-adoption.js');
const useFlow = require('../utils/topic-use-flow.js');

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

// 章节编号:章名本身以「第…章」开头时不再重复编号
function chapterNo(c, i) { return /^第/.test(String(c.name || '')) ? '' : String(i + 1); }

function buildChapters(info) {
  return (Array.isArray(info.chapters) ? info.chapters : []).map(function (c, i) {
    return { key: String(c.id || i), no: chapterNo(c, i), name: c.name || '', text: c.description || '' };
  });
}

// 能力 tab:按章分组,每站一张卡。玩法图标沿用货架 art.gameArt 的道具图(与玩法模板详情同一套),表外玩法不配图。
function buildStopGroups(info) {
  let no = 0;
  const groups = (Array.isArray(info.chapters) ? info.chapters : []).map(function (c, i) {
    const nodes = Array.isArray(c.nodes) ? c.nodes : [];
    return {
      key: String(c.id || i),
      no: chapterNo(c, i),
      name: c.name || '',
      stops: nodes.map(function (n) {
        no += 1;
        const minutes = Number(n.gameDuration || 0);
        const ga = art.gameArt(n.gameTitle);
        return {
          no: no,
          name: n.name || '第 ' + no + ' 站',
          game: n.gameTitle || '',
          gameIcon: ga ? ga.prop : '',
          doText: n.hookTeaser || '',
          dur: minutes ? '约 ' + minutes + ' 分钟' : ''
        };
      })
    };
  }).filter(function (g) { return g.stops.length; });
  // 只有一组且没有章名:整组一个头、不出标题
  if (groups.length === 1 && !groups[0].name) groups[0].no = '';
  return groups;
}

function buildStops(groups) {
  return groups.reduce(function (all, g) { return all.concat(g.stops); }, []);
}

// 能力 tab 顶部概要:与详情的 xb-stat-card 同一视觉,一格一个事实
function buildSummary(info, chapters, stops) {
  const games = Array.isArray(info.games) ? info.games : [];
  const total = fmtSec(info.totalTime);
  const free = info.mode === 'FREE_EXPLORE';
  return [
    { value: free ? '自由探索' : '城市定向', label: '模式' },
    free ? { value: '可以跨天', label: '时间' } : null,
    chapters.length ? { value: String(chapters.length), label: '章节' } : null,
    stops.length ? { value: String(stops.length), label: '站点' } : null,
    games.length ? { value: String(games.length), label: '玩法' } : null,
    total ? { value: total, label: '总时长' } : null
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
    openingExpanded: false,
    openingNeedMore: false,
    chapters: [],
    stats: [],
    gallery: [],
    stops: [],
    stopGroups: [],
    summary: [],
    medalName: '',
    medalImg: '',
    deliveryRows: [],
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
        const stopGroups = buildStopGroups(info);
        const stops = buildStops(stopGroups);
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
          openingExpanded: false,
          openingNeedMore: false,
          chapters: chapters,
          stats: [
            chapters.length ? { value: String(chapters.length), label: '章节' } : null,
            stops.length ? { value: String(stops.length), label: '节点' } : null,
            total ? { value: total, label: '总时长' } : null
          ].filter(Boolean),
          gallery: nodeImgs,
          stops: stops,
          stopGroups: stopGroups,
          summary: buildSummary(info, chapters, stops),
          medalName: info.finishMedalName || '',
          medalImg: info.finishMedalImg || '',
          deliveryRows: adoption.deliveryRows(info.contentDelivery)
        });
        that._delivery = info.contentDelivery || null;
        that._loadCredits(seq, nodeImgs);
        that._measureOpening(seq);
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

  // 开场白默认收 4 行(4 × 44rpx = 88px);量到实际更高才给「展开」,短文不出假按钮。与玩法模板详情 checkTextOverflow 同一做法。
  _measureOpening(seq) {
    const that = this;
    if (!that.data.opening || typeof wx.createSelectorQuery !== 'function') return;
    wx.nextTick(function () {
      if (seq !== that._seq) return;
      wx.createSelectorQuery().in(that).select('#td-opening').boundingClientRect().exec(function (res) {
        if (seq !== that._seq || !res || !res[0]) return;
        if (res[0].height > 88) that.setData({ openingNeedMore: true });
      });
    });
  },

  toggleOpening() { this.setData({ openingExpanded: !this.data.openingExpanded }); },

  onRetry() { this.load(); },

  goBack() { wx.navigateBack({ fail: () => wx.switchTab({ url: '/pages/template/index' }) }); },

  switchTab(e) {
    const key = e && e.detail && e.detail.key;
    if (key !== 'details' && key !== 'capabilities') return;
    this.setData({ activeTab: key });
    // 从「能力」进来的页面首屏没渲染开场白,量不到高度;切回「详情」再量一次
    if (key === 'details' && !this.data.openingNeedMore) this._measureOpening(this._seq);
  },

  previewNode(e) {
    const url = e.currentTarget.dataset.url;
    if (!url) return;
    wx.previewImage({ current: url, urls: this.data.gallery.map(function (g) { return g.img; }) });
  },

  // 「应用」= 整包复制成我的草稿,进主题配置。
  // r2-create(b4-u02-02/04 业主:「不需要选择城市 就是配置的时候需要选择地址就好了」「不需要换城市」):
  // 不再弹「沿用原地点 / 换一个城市」,一律沿用原地点采用;作者在编辑器的「地点待办」里逐个替换地址。
  useTemplate() {
    if (app.ensureLoginFor && !app.ensureLoginFor('publish', () => this.useTemplate(), { owner: this, target: () => [this._id, this._scope] })) return; // 游客写前登录
    if (this._readonly) { cyToast('当前岗位仅可浏览模板'); return; }
    if (!this._id) return;
    // 第十二轮 k9(p3 N6):本页已经复制出一份草稿,从编辑器回来再点「应用」就回到那一份,不再每点一次多一份草稿
    if (this._copiedId) { this._openCopiedDraft(this._copiedId); return; }
    const loc = this._delivery && this._delivery.locationRequirement;
    const placed = !!(loc && loc.physicalPlaceRequired === true && adoption.sourceCityOf(this._delivery));
    this._submitUse(placed ? 'SAME_CITY' : '');
  },

  _submitUse(intent) {
    useFlow.submit(this, { id: this._id, scope: this._scope, intent,
      sourceCityCode: adoption.sourceCityOf(this._delivery), revisionId: adoption.revisionIdOf(this._delivery) }, {
      toast: cyToast,
      onCopied: (id) => { this._copiedId = id; },
      open: (id) => this._openCopiedDraft(id)
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
