const modal = require('../../utils/modal.js');
const toast = require('../../utils/toast.js');
const { toTimestamp } = require('../../utils/datetime');
const app = getApp();
const policy = require('../../utils/identity/identity-policy.js');
const merchantTheme = require('../../utils/merchant-theme.js');
const { isRecordList } = require('../../utils/response-shape.js');
const { marketView } = require('../../utils/template-market.js');
const { gameTemplateCover } = require('../../utils/template-display.js');
const { decodeDisplayFields } = require('../../utils/display-text.js');

Page({
  data: {
    isMerchant: false,
    operationScope: '',
    list: [],
    searchKeyword: '',
    filterCategoryId: 0,
    page: 1,
    pageSize: 10,
    total: 0,
    hasMore: true,
    loading: false,
    loadingMore: false,
    errorMsg: '',
    loadMoreError: ''
  },

  onBack() {
    if (getCurrentPages().length > 1) {
      wx.navigateBack({ delta: 1 });
      return;
    }
    wx.switchTab({ url: '/pages/template/index' });
  },

  // 分类筛选
  onFilterCategory(e) {
    const categoryId = e.currentTarget.dataset.id;
    this.setData({
      filterCategoryId: categoryId
    });
    this.refreshList();
  },

  // 已授权补齐七款旧玩法的图标封面；其它缺图仍用标题首字，不借用内容图。
  decorateItem(item) {
    // 列表卡片按纯文本展示作者文字与审核反馈:服务端过滤会把 x<y 存成 x&lt;y,在这里解码一次。
    item = decodeDisplayFields(item, ['title', 'name', 'description', 'reason', 'marketReason']);
    const title = String(item && (item.title || item.name) || '玩法').trim();
    return Object.assign({}, item, marketView(item), {
      drop: false,
      imgUrl: gameTemplateCover(item),
      coverFailed: false,
      coverInitial: title.charAt(0) || '玩'
    });
  },

  onCoverError(e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.index);
    const boundKey = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.key;
    if (!Number.isInteger(index) || index < 0 || index >= this.data.list.length) return;
    const item = this.data.list[index];
    const currentKey = item && item.id != null ? String(item.id) : '';
    if (!item || !currentKey || String(boundKey || '') !== currentKey || item.coverFailed) return;
    this.setData({ [`list[${index}].coverFailed`]: true });
  },

  // 下拉展开关闭
  toggleDropdown(e) {
    const index = e.currentTarget.dataset.index;
    const list = this.data.list.map((item, i) => {
      if (i === index) {
        return { ...item, drop: !item.drop };
      }
      return { ...item, drop: false };
    });
    this.setData({ list });
  },
  goInfo(e){
    const index = e.currentTarget.dataset.index;
    const item = this.data.list[index]; if (!item || this._navigating) return;
    this._navigating = true; wx.navigateTo({ complete: () => { this._navigating = false; },
      url: '/pages/templatedetail/templatedetail?id=' + item.id + '&scope=my' +
        (this.data.operationScope === 'MERCHANT' ? '&operationScope=MERCHANT' : ''),
    })
  },
  // 删除模板
  deleteItem(e) {
    const index = e.currentTarget.dataset.index;
    const item = this.data.list[index];
    if (!item || !item.id || this._templateDeleteInFlight && this._templateDeleteInFlight[String(item.id)]) return;
    
    modal.show({
      dangerKey: 'template.delete', dangerParams: { name: item.title },   // 三段式文案在 utils/danger-actions.js
      success: (res) => {
        if (res.confirm) {
          this.deleteTemplate(item.id);
        }
      }
    });
  },
 
  templateStatus(e) {
    if (this._templateActing || this.data.loading || this.data.loadingMore) return;
    const item = this.data.list.find(row => String(row.id) === String(e.currentTarget.dataset.id));
    if (!item) return;
    const view = marketView(item);
    if (!view._canSubmitMarket && !view._canToggleMarket) return;
    this._templateActing = true;
    const release = () => { this._templateActing = false; };
    try {
      app.sendRequest({
        hideLoading: true,
        url: view._canSubmitMarket ? `/api/template/${item.id}/market-submit` : '/api/template/updateLibraryStatus',
        method: 'POST',
        data: Object.assign(view._canSubmitMarket ? {} : { template_id: item.id, publish_status: view._marketPublishTarget },
          this.data.operationScope ? { scope: this.data.operationScope } : {}),
        success: (res) => {
          release();
          if (res && res.code == '200') {
            toast(view._canSubmitMarket ? '已提交广场审核' : '操作成功');
            this.refreshList();
          } else { toast((res && res.msg) || '操作失败，请重试'); }
        },
        fail: () => { release(); toast('网络异常，请重试'); },
        complete: release
      });
    } catch (error) { release(); toast('网络异常，请重试'); }
  },

  // 获取难度文本
  getDifficultyText(difficulty) {
    const difficultyMap = {
      '高': '高难度',
      '中': '中等难度',
      '低': '低难度'
    };
    return difficultyMap[difficulty] || difficulty;
  },

  // 获取验证方式文本
  getValidationMethodText(method) {
    const methodMap = {
      1: '选择题',
      2: '问答题',
      3: '混合题'
    };
    return methodMap[method] || '未知类型';
  },

  // 获取状态文本
  getStatusText(status) {
    const statusMap = {
      1: '已发布',
      0: '未发布',
      2: '审核中'
    };
    return statusMap[status] || '未知状态';
  },

  // 格式化日期
  formatDate(dateString) {
    if (!dateString) return '';
    const date = new Date(toTimestamp(dateString));
    const now = new Date();
    const diff = now - date;
    const days = Math.floor(diff / (1000 * 60 * 60 * 24));
    
    if (days === 0) {
      return '今天';
    } else if (days === 1) {
      return '昨天';
    } else if (days < 7) {
      return `${days}天前`;
    } else {
      return `${date.getMonth() + 1}-${date.getDate()}`;
    }
  },

  // 刷新列表
  refreshList() {
    if (this.data.loading || this.data.loadingMore) return false;
    this.setData({
      page: 1,
      hasMore: true,
      loadMoreError: ''
    });
    this.getList(false, 1);
    return true;
  },

  // 加载更多
  loadMore() {
    if (this.data.loading || this.data.loadingMore || !this.data.hasMore) return;
    this.getList(true, this.data.page + 1);
  },

  retryLoadMore() {
    if (this.data.loading || this.data.loadingMore || !this.data.hasMore) return;
    this.getList(true, this.data.page + 1);
  },

  // 获取模板列表
  getList: function (isLoadMore = false, requestedPage) {
    if (this.data.loading || this.data.loadingMore || this._unloaded) return;
    const epoch = (this._listEpoch || 0) + 1;
    this._listEpoch = epoch;
    const owner = typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '';
    const current = () => !this._unloaded && epoch === this._listEpoch
      && owner === (typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '');
    const pageNum = isLoadMore ? (requestedPage || this.data.page + 1) : 1;
    if (isLoadMore) this.setData({ loadingMore: true, loadMoreError: '' });
    else this.setData({ loading: true, errorMsg: '' });
    const that = this;
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/template/my-list',
      method: "POST",
      data: {
        is_quote: '',
        keyword: this.data.searchKeyword,
        category_id: '',
        scope: this.data.operationScope,
        pageNum: pageNum,
        pageSize: this.data.pageSize
      },
      success: function (res) {
        if (!current()) return;
        if (res && res.code == "200" && res.data && isRecordList(res.data.rows)) {
          const data = res.data.rows;
          const numericTotal = Number(res.data.total);
          const totalKnown = Number.isFinite(numericTotal) && numericTotal >= 0;
          const total = totalKnown ? numericTotal : 0;
          
          // 处理数据，添加下拉状态等
          const processedData = data.map(item => that.decorateItem(item));
          
          if (isLoadMore) {
            // 加载更多
            const nextLength = that.data.list.length + data.length;
            that.setData({
              list: [...that.data.list, ...processedData],
              page: pageNum,
              total: total,
              hasMore: totalKnown ? nextLength < total : data.length >= that.data.pageSize,
              loadMoreError: ''
            });
          } else {
            // 刷新列表
            that.setData({
              list: processedData,
              page: 1,
              total: total,
              hasMore: totalKnown ? data.length < total : data.length >= that.data.pageSize,
              errorMsg: '' });
          }
        } else {
          that.handleListError(isLoadMore, app.getRequestErrorMessage(res, '玩法草稿加载失败'));
        }
      },
      fail: function (res) {
        if (!current()) return;
        that.handleListError(isLoadMore, app.getRequestErrorMessage(res, '玩法草稿加载失败'));
      },
      complete: function () {
        if (!current()) return;
        if (isLoadMore) that.setData({ loadingMore: false });
        else that.setData({ loading: false });
        if (!isLoadMore && that._pullDownRefreshing) {
          that._pullDownRefreshing = false;
          wx.stopPullDownRefresh();
        }
      }
    });
  },

  handleListError(isLoadMore, message) {
    if (isLoadMore) {
      this.setData({ loadMoreError: message });
      return;
    }
    if (this.data.list.length > 0) {
      this.setData({  errorMsg: '' });
      return;
    }
    this.setData({ errorMsg: message });
  },

  // 删除模板API调用
  deleteTemplate: function (id, index) {
    if (!id || this._unloaded) return;
    const key = String(id);
    this._templateDeleteInFlight = this._templateDeleteInFlight || {};
    if (this._templateDeleteInFlight[key]) return;
    this._templateDeleteInFlight[key] = true;
    const owner = typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '';
    const current = () => !this._unloaded && owner === (typeof app.getUserID === 'function' ? String(app.getUserID() || '') : '');
    const release = () => { delete this._templateDeleteInFlight[key]; };
    const that = this;
    app.sendRequest({
      url: '/api/template/delete',
      method: "POST",
      data: Object.assign({ template_id : id }, this.data.operationScope ? { scope: this.data.operationScope } : {}),
      success: function (res) {
        if (!current()) return;
        if (res.code == "200") {
          toast.success('删除成功');
          // 从列表中移除
          const templateId = String(id);
          const list = that.data.list.filter(item => String(item.id) !== templateId);
          const removed = that.data.list.length - list.length;
          that._listEpoch = (that._listEpoch || 0) + 1;
          that.setData({ list, total: Math.max(0, (Number(that.data.total) || 0) - removed), loading: false, loadingMore: false });
          that.refreshList();
        } else {
          toast(res.msg || '删除失败');
        }
      },
      fail: function (res) {
        if (current()) toast('网络错误，请重试');
      },
      complete: release
    });
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    this.data.operationScope = options && options.scope === 'MERCHANT' ? 'MERCHANT' : '';
    this.getList();
  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    const isMerchant = this.data.operationScope === 'MERCHANT' || policy.isMerchantView({ role: app.getUserRole(), userType: app.getUserType() });
    this.setData({ isMerchant: isMerchant }); this._navigating = false;
    if (isMerchant) merchantTheme.merchantPageShow();
    else merchantTheme.merchantPageRestore();
    // 页面显示时刷新数据，确保数据最新
    this.refreshList();
  },

  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { merchantTheme.merchantPageRestore(); this._unloaded = true; this._listEpoch = (this._listEpoch || 0) + 1; },

  /**
   * 页面相关事件处理函数--监听用户下拉动作
   */
  onPullDownRefresh() {
    this._pullDownRefreshing = true;
    if (!this.refreshList()) {
      this._pullDownRefreshing = false;
      wx.stopPullDownRefresh();
    }
  },

  /**
   * 页面上拉触底事件的处理函数
   */
  onReachBottom() {
    this.loadMore();
  }
});
