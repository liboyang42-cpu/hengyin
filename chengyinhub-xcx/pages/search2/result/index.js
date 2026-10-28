// 搜索结果独立页(2026-07-31):原来搜索输入后结果直接铺在 search2 同页下方,
// 现改成跳转到这里 —— 逻辑从 pages/search2/index.js 的 runSearch/updateVisibleResults 搬来,
// 数据源仍是同一个 utils/discover-search.js。
const toast = require('../../../utils/toast.js'); const pageMotion = require('../../../utils/page-motion.js');
const app = getApp();
const discoverSearch = require('../../../utils/discover-search.js');
const { openScene } = require('../../../utils/scene-entry.js');
const { memberId, failureStatus, retryLogin, recoverReadSession } = require('../../../utils/session/login-reload.js');
const DEFAULT_RESULT_TABS = [{ key: 'all', label: '全部' }];

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    keyword: '',
    categoryId: null,
    searchLoading: false,
    showSearchSkeleton: false,
    searchError: '',
    clubLoginRequired: false,
    clubSearchDenied: '',
    contentLoading: false, contentHasMore: false, contentError: '', contentEnabled: false, contentFilterNotice: '',
    // 出过结果没有。只用来判 auto-back:首屏搜索失败才自动退页,
    // 页内改关键词再搜的失败留在页内,别把用户刚打的词一起退掉(审查C A1)。
    everLoaded: false,
    activeResultType: 'all',
    resultTabs: DEFAULT_RESULT_TABS,
    resultGroups: [],
    visibleResults: []
  },

  onLoad(options) {
    const keyword = options.keyword ? decodeURIComponent(options.keyword) : '';
    const categoryId = options.categoryId || null;
    this._filters = {
      cityCode: options.city_code || options.cityCode || '',
      startDate: options.startDate ? decodeURIComponent(options.startDate) : '',
      endDate: options.endDate ? decodeURIComponent(options.endDate) : '',
      minPrice: options.minPrice != null && options.minPrice !== '' ? Number(options.minPrice) : null,
      maxPrice: options.maxPrice != null && options.maxPrice !== '' ? Number(options.maxPrice) : null,
      // CU-M-66:入口页的排序选择必须活到请求里。四类里只有 /api/activity/list 有 sort_type,
      // 其余三类后端没有排序参数(buildRequests 只把它给 activity)。
      sortType: options.sortType ? String(options.sortType) : '',
    };
    this.setData({ keyword, categoryId });
    this.runSearch();
  },

  onKeywordInput(e) {
    this._searchSeq = (this._searchSeq || 0) + 1;
    this._content = null;
    if (this._searchSkeletonTimer) clearTimeout(this._searchSkeletonTimer);
    this.setData({ keyword: (e.detail && e.detail.value) || '', searchLoading: false, showSearchSkeleton: false,
      resultGroups: [], visibleResults: [], resultTabs: DEFAULT_RESULT_TABS,
      contentLoading: false, contentHasMore: false, contentError: '', contentEnabled: false });
  },

  onSearchConfirm() {
    const keyword = String(this.data.keyword || '').trim();
    this.setData({ keyword: keyword }, () => this.runSearch());
  },

  onUnload() {
    this._unloaded = true;
    this._searchSeq = (this._searchSeq || 0) + 1;
    if (this._searchSkeletonTimer) clearTimeout(this._searchSkeletonTimer);
  },

  onShow() {
    this._hidden = false;
    if (this._searchWasHidden || (this._resultOwner !== undefined && this._resultOwner !== memberId(app))) {
      this._searchWasHidden = false;
      this.runSearch();
    }
  },
  onHide() {
    this._hidden = true;
    this._searchWasHidden = true;
    this._searchSeq = (this._searchSeq || 0) + 1;
    if (this._searchSkeletonTimer) clearTimeout(this._searchSkeletonTimer);
  },
  loginForClubs() {
    const seq = this._searchSeq;
    const owner = memberId(app);
    return retryLogin(this, app, function () {
      if (seq === this._searchSeq && (!owner || owner === memberId(app))) this.runSearch();
    });
  },

  runSearch() {
    if (this._hidden || this._unloaded) return;
    const { keyword, categoryId } = this.data;
    const filters = this._filters || {};
    const owner = memberId(app);
    const useUnified = !!owner && discoverSearch.unifiedCompatible(keyword, categoryId, filters);
    const requests = discoverSearch.buildRequests(keyword, categoryId, filters)
      .filter(request => (owner || request.auth === false) && (!useUnified || !['topic', 'activity'].includes(request.type)));
    const queryKey = String(keyword || '').trim() + '::' + String(categoryId || '')
      + '::' + String(filters.startDate || '') + '::' + String(filters.endDate || '')
      + '::' + String(filters.minPrice || '') + '::' + String(filters.maxPrice || '')
      + '::' + String(filters.sortType || '') + '::' + String(filters.cityCode || '');
    const preserveExisting = this._resultOwner === owner && this._resultQueryKey === queryKey
      && (this.data.resultGroups || []).some((group) => (group.items || []).length > 0);
    const previousGroups = {};
    // 合并搜索的主题/活动/帖文刷新时整体替换(已删除结果与城市不残留);原接口路径六类都保留上一次成功内容。
    const contentType = (type) => useUnified && ['topic', 'activity', 'post'].includes(type);
    if (preserveExisting) (this.data.resultGroups || []).forEach((group) => { if (!contentType(group.type)) previousGroups[group.type] = group.items || []; });
    const seq = (this._searchSeq || 0) + 1;
    this._searchSeq = seq;
    this._resultOwner = owner;
    this._contentGroups = [];
    this._content = useUnified ? { seq, owner, keyword: String(keyword).trim(), cityCode: filters.cityCode || '',
      types: typeof app.canUseCommunity === 'function' && app.canUseCommunity(false)
        ? ['topic', 'activity', 'post'] : ['topic', 'activity'], page: 0, items: [], loading: false } : null;
    this.setData({ clubLoginRequired: !owner, clubSearchDenied: '', contentEnabled: useUnified,
      contentLoading: false, contentHasMore: useUnified, contentError: '',
      contentFilterNotice: owner && String(keyword).trim() && !useUnified ? '清除类别、日期和价格筛选后，可同时搜索公开帖文' : '' });
    const isCurrent = () => !this._hidden && !this._unloaded && seq === this._searchSeq && owner === memberId(app);
    const groupsByType = {};
    let pending = requests.length;
    let failed = 0;

    if (this._searchSkeletonTimer) clearTimeout(this._searchSkeletonTimer);

    if (preserveExisting) {
      this.setData({ searchLoading: true, showSearchSkeleton: false, searchError: '',
        resultGroups: (this.data.resultGroups || []).filter(group => !contentType(group.type)) });
      this.updateVisibleResults();
    } else {
      this.setData({ searchLoading: true, showSearchSkeleton: false, searchError: '', activeResultType: 'all', resultTabs: DEFAULT_RESULT_TABS, resultGroups: [], visibleResults: [] });
      if (requests.length) {
        this._searchSkeletonTimer = setTimeout(() => {
          if (isCurrent() && this.data.searchLoading && !this.data.visibleResults.length) {
            this.setData({ showSearchSkeleton: true });
          }
        }, 400);
      }
    }

    const finish = () => {
      pending -= 1;
      if (pending > 0 || !isCurrent()) return;
      if (this._searchSkeletonTimer) clearTimeout(this._searchSkeletonTimer);
      const resultGroups = (this._contentGroups || []).concat(requests.map((request) => ({
        type: request.type,
        label: discoverSearch.TYPE_META[request.type].label,
        items: Object.prototype.hasOwnProperty.call(groupsByType, request.type) ? groupsByType[request.type] : (previousGroups[request.type] || [])
      })));
      const hasResults = resultGroups.some((group) => group.items.length > 0), allFailed = failed === requests.length; this._resultQueryKey = hasResults || failed === 0 ? queryKey : this._resultQueryKey;
      this.setData({
        searchLoading: false,
        showSearchSkeleton: false,
        everLoaded: this.data.everLoaded || hasResults,
        searchError: failed > 0 && !hasResults
          ? (allFailed ? '搜索服务暂不可用，请重试' : '部分搜索没有完成，请重试后再确认结果')
          : '',
        resultGroups: resultGroups,
        resultTabs: DEFAULT_RESULT_TABS.concat(resultGroups.map((group) => ({
          key: group.type,
          label: group.label + ' ' + group.items.length
        })))
      });
      this.updateVisibleResults();
      if (failed > 0 && hasResults) toast('部分搜索没有完成，结果可能不全');
    };

    if (!requests.length) {
      this._resultQueryKey = queryKey; this.setData({ searchLoading: false, showSearchSkeleton: false }); return; }

    if (useUnified) this.loadMoreContent();

    requests.forEach((request) => {
      let settled = false;
      const requestFailed = (res, status) => {
        if (!isCurrent() || settled) return;
        if (recoverReadSession(this, app, res, function (anonymous) {
          this._searchSeq++; this._content = null; this._contentGroups = [];
          this.setData({ resultGroups: [], visibleResults: [], resultTabs: DEFAULT_RESULT_TABS,
            searchLoading: false, showSearchSkeleton: false, searchError: '', clubLoginRequired: anonymous,
            contentLoading: false, contentHasMore: false, contentError: '', contentEnabled: false });
        }, this.runSearch, status)) return;
        if (!isCurrent()) return;
        const code = failureStatus(res, status);
        if (code === 401 || code === 403) {
          groupsByType[request.type] = [];
          this.setData({ clubLoginRequired: code === 401,
            clubSearchDenied: code === 403 ? '当前账号没有俱乐部搜索权限' : '' });
        }
        settled = true;
        failed += 1;
        finish();
      };
      app.sendRequest({
        hideLoading: true,
        autoErrorToast: false,   // 多路并发各自 toast 会连弹;全失败由 auto-back 半屏讲,部分失败在 finish 里报一次
        url: request.url,
        method: 'POST',
        auth: request.auth,
        data: request.data,
        header: request.header,
        success: (res) => {
          if (!isCurrent() || settled) return;
          if (res && (res.code == 200 || res.code == '200')) {
            groupsByType[request.type] = discoverSearch.decorateRows(request.type, res.data, Object.assign({}, filters, { keyword: keyword }));
            if (request.type === 'topicTemplate' || request.type === 'gameTemplate') {
              groupsByType[request.type].forEach((item) => { item.cover = app.getImgUrl(item.cover); });
            }
            if (request.type === 'merchant' || request.type === 'club') {
              groupsByType[request.type].forEach((item) => {
                item.cardCover = app.getImgUrl(item.cardCover);
                item.cardLogo = app.getImgUrl(item.cardLogo);
                if (item.clubCard) {
                  item.clubCard.coverUrl = app.getImgUrl(item.clubCard.coverUrl);
                  item.clubCard.logoUrl = app.getImgUrl(item.clubCard.logoUrl);
                }
              });
            }
          } else {
            requestFailed(res); return;
          }
          settled = true;
          finish();
        },
        fail: requestFailed,
        successStatusAbnormal: requestFailed,
      });
    });
  },

  onReachBottom() {
    if (!this.data.contentError) this.loadMoreContent();
  },

  loadMoreContent() {
    const state = this._content;
    if (!state || state.loading || !this.data.contentHasMore || this._hidden || this._unloaded) return;
    if (state.owner !== memberId(app)) { this.runSearch(); return; }
    const page = state.page + 1;
    const current = () => this._content === state && state.seq === this._searchSeq
      && state.owner === memberId(app) && !this._hidden && !this._unloaded;
    let settled = false;
    state.loading = true;
    this.setData({ contentLoading: true, contentError: '' });
    const failed = (res, status) => {
      if (this._content === state && state.seq === this._searchSeq && state.owner !== memberId(app) && !this._hidden && !this._unloaded) { this.runSearch(); return; }
      if (!current() || settled) return;
      settled = true; state.loading = false;
      if (recoverReadSession(this, app, res, function (anonymous) {
        this._searchSeq++; this._content = null; this._contentGroups = [];
        this.setData({ resultGroups: [], visibleResults: [], resultTabs: DEFAULT_RESULT_TABS,
          searchLoading: false, showSearchSkeleton: false, contentLoading: false, contentHasMore: false,
          contentError: '', contentEnabled: false, clubLoginRequired: anonymous });
      }, this.runSearch, status)) return;
      const code = failureStatus(res, status);
      if (code === 401 || code === 403) {
        state.items = []; state.page = 0; this._contentGroups = [];
        this.setData({ resultGroups: this.data.resultGroups.filter(g => !['topic', 'activity', 'post'].includes(g.type)) });
        this.updateVisibleResults();
      }
      this.setData({ resultTabs: DEFAULT_RESULT_TABS.concat(this.data.resultGroups.map(g => ({ key: g.type, label: g.label + ' ' + g.items.length }))),
        contentLoading: false, contentError: code === 503 ? '内容搜索暂未开放，可稍后重试'
        : code === 403 ? '当前账号无法读取这些内容' : '内容搜索未完成，重试本页' });
    };
    app.sendRequest({
      url: '/api/search/unified', method: 'POST', hideLoading: true, autoErrorToast: false,
      data: { keyword: state.keyword, types: state.types.join(','), city_code: state.cityCode, pageNum: page, pageSize: 12 },
      success: res => {
        if (this._content === state && state.seq === this._searchSeq && state.owner !== memberId(app) && !this._hidden && !this._unloaded) { this.runSearch(); return; }
        if (!current() || settled) return;
        if (!res || Number(res.code) !== 200) { failed(res); return; }
        const result = discoverSearch.unifiedPage(res.data, state.types);
        if (!result) { failed({ code: 500 }); return; }
        settled = true; state.loading = false; state.page = page;
        result.items.forEach(item => { item.cover = app.getImgUrl(item.cover); });
        state.items = discoverSearch.mergeResults(state.items, result.items);
        this._contentGroups = state.types.map(type => ({ type, label: discoverSearch.TYPE_META[type].label,
          items: state.items.filter(item => item.type === type) }));
        const groups = this._contentGroups.concat(this.data.resultGroups.filter(g => !['topic', 'activity', 'post'].includes(g.type)));
        this.setData({ resultGroups: groups, contentLoading: false, contentError: '',
          contentHasMore: result.count > 0 && page * 12 < result.total,
          everLoaded: this.data.everLoaded || state.items.length > 0,
          resultTabs: DEFAULT_RESULT_TABS.concat(groups.map(g => ({ key: g.type, label: g.label + ' ' + g.items.length }))) });
        this.updateVisibleResults();
      }, fail: failed, successStatusAbnormal: failed
    });
  },

  updateVisibleResults() {
    const type = this.data.activeResultType;
    const groups = this.data.resultGroups || [];
    const visibleResults = groups.reduce((all, group) => {
      return type === 'all' || group.type === type ? all.concat(group.items) : all;
    }, []);
    pageMotion.enter(this, 'pmList'); this.setData({ visibleResults: visibleResults });
  },

  onResultTab(e) {
    this.setData({ activeResultType: e.detail.key }, () => this.updateVisibleResults());
  },

  goResult(e) {
    const sceneId = e.currentTarget.dataset.sceneId;
    const id = e.currentTarget.dataset.id;
    if (sceneId) {
      openScene(sceneId, { id });
      return;
    }
    const path = e.currentTarget.dataset.path;
    // 商家结果没绑会员主体时拼不出主页地址 —— 点了没反应会被当成卡死,给一句话
    if (path) wx.navigateTo({ url: path });
    else toast('这条结果暂不可打开');
  }
});
