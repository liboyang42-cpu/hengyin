const cyLoading = require('../../utils/loading.js');
const toast = require('../../utils/toast.js');
const app = getApp();
const { isRecordList } = require('../../utils/response-shape.js');
const { isValidMobile } = require('../../utils/form-state.js');
const nicknameCheck = require('./nickname-check.js');
const profileReview = require('../../utils/profile-review.js');
const identitySync = require('../../utils/identity-sync.js');

// 输入停顿多久再问后端:太短每个字都打一枪,太长用户等不到反馈。
const NICKNAME_CHECK_DEBOUNCE_MS = 400;
// 被占用时一次列几个推荐:suggest 每次只回一个候选,按服务端 cursor 连取。
const NICKNAME_SUGGEST_BATCH = 3;

function boundPhoneFromResponse(res) {
  if (!res) return '';
  var candidates = [];
  if (typeof res.data === 'string') candidates.push(res.data);
  if (res.data && typeof res.data === 'object' && typeof res.data.phone === 'string') {
    candidates.push(res.data.phone);
  }
  if (typeof res.msg === 'string') candidates.push(res.msg);
  for (var i = 0; i < candidates.length; i++) {
    var phone = String(candidates[i]).trim();
    if (isValidMobile(phone)) return phone;
  }
  return '';
}

function maskPhone(raw) {
  const digits = String(raw || '').replace(/\D/g, '');
  if (digits.length < 7) return '';
  return digits.slice(0, 3) + '****' + digits.slice(-4);
}
Page({

  /**
   * 页面的初始数据
   */
  data: {
    // 自定义导航:顶栏高度 = 状态栏 + 导航条,页面自留同高占位,内容不被顶栏压住
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    popDavid: false,
    popDavid2: false,
    popDavid3: false,
    popDavid4: false,
    userInfo: {
      name: '',
      introduction: '',
      website: '',
      caseIntroducton: '',
      avatar: '',
      tag_ids: '',
      casePics: '', // 个人项目图片链接字符串
      wechat: '',
      phone: ''
    },
    phoneMasked: '',
    avatarReviewText: '',
    casePicsList: [], // 个人项目图片数组
    tempCasePics: [], // 临时个人项目图片数组（编辑时使用）
    tempCaseIntroducton: '', // 临时个人项目描述
    tempWechat: '', // 临时微信二维码
    tempIntroduction: '', // 城市签名草稿；完成前不写回 userInfo
    tempName: '', // 昵称草稿；完成前不写回 userInfo
    // 昵称校验(N4):'' 未检查/与原昵称相同 | checking | available | taken | invalid | error(查询失败,可重试)
    nicknameState: '',
    nicknameHint: '',
    // 被占用时列出 /nickname/suggest 的候选让用户点选(2026-10-15 用户决定:不自动填入)
    nicknameSuggestions: [],
    nicknameSuggesting: false,
    nicknameSuggestError: '',

    // 活动类型选择 - 多选相关
    selectedCategoryIds: [], // 选中的分类ID数组
    selectedCategoryNames: [], // 选中的分类名称数组
    categorySheetVisible: false,
    categorySheetIds: '',
    selectedCategoryNamesStr: '', // 选中的分类名称字符串，用于显示
    categoryList: [],
    canSave: false,
    loading: true,
    refreshing: false,
    loaded: false,
    loadError: '',
    loadErrorKind: 'network',
    loadErrorSub: '',
    saving: false,
    saveError: '',
    saveSucceeded: false,
    saveSteps: ['保存资料'],
  },

  //个人介绍
  openPop() {
    this.setData({
      popDavid: true,
      tempIntroduction: this.data.userInfo.introduction || ''
    })
  },
  //取消
  cancel() {
    this.setData({
      popDavid: false
    })
  },
  //完成
  confirm() {
    this.setData({
      popDavid: false,
      'userInfo.introduction': this.data.tempIntroduction
    }, () => this.refreshSaveState())
  },

  //个人项目
  openPop2() {
    this.setData({
      popDavid2: true,
      tempCasePics: [...this.data.casePicsList], // 复制当前图片数组
      tempCaseIntroducton: this.data.userInfo.caseIntroducton
    })
  },
  //取消
  cancel2() {
    this.setData({
      popDavid2: false
    })
  },
  //完成
  confirm2() {
    // 更新个人项目图片和描述
    const casePicsString = this.data.tempCasePics.join(';');
    this.setData({
      popDavid2: false,
      casePicsList: [...this.data.tempCasePics],
      'userInfo.casePics': casePicsString,
      'userInfo.caseIntroducton': this.data.tempCaseIntroducton
    })
  },

  //个人微信二维码
  openPop3() {
    this.setData({
      popDavid3: true,
      tempWechat: this.data.userInfo.wechat
    })
  },
  //取消
  cancel3() {
    this.setData({
      popDavid3: false
    })
  },
  //完成
  confirm3() {
    this.setData({
      popDavid3: false,
      'userInfo.wechat': this.data.tempWechat
    })
  },

  //个人名称
  openPop4() {
    this.resetNicknameCheck();
    this.setData({
      popDavid4: true,
      tempName: this.data.userInfo.name || '',
      nicknameState: '',
      nicknameHint: '',
      nicknameSuggestions: [],
      nicknameSuggestError: ''
    })
  },
  //取消
  cancel4() {
    this.resetNicknameCheck();
    this.setData({
      popDavid4: false
    })
  },
  //完成
  confirm4() {
    const state = this.data.nicknameState;
    // 已确认被占用/不合规的昵称不收进草稿;查询失败(error)或仍在查(checking)不拦 ——
    // 保存时服务端会再判一次,拦住反而让「网络抖一下」变成改不了昵称。
    const issue = nicknameCheck.localIssue(this.data.tempName);
    if (issue) {
      this.setData({ nicknameState: 'invalid', nicknameHint: issue });
      return;
    }
    if (state === 'taken' || state === 'invalid') return;
    this.resetNicknameCheck();
    this.setData({
      popDavid4: false,
      'userInfo.name': nicknameCheck.normalize(this.data.tempName)
    }, () => this.refreshSaveState())
  },

  // ===== 昵称校验 / 推荐(N4) =====
  resetNicknameCheck() {
    if (this._nicknameTimer) {
      clearTimeout(this._nicknameTimer);
      this._nicknameTimer = null;
    }
    // 序号前进 = 让所有在途响应作废,晚到的不会覆盖当前输入的结论
    this._nicknameSeq = (this._nicknameSeq || 0) + 1;
  },

  scheduleNicknameCheck(raw) {
    this.resetNicknameCheck();
    const value = nicknameCheck.normalize(raw);
    if (this.data.nicknameSuggestions.length || this.data.nicknameSuggesting || this.data.nicknameSuggestError) {
      this.setData({ nicknameSuggestions: [], nicknameSuggesting: false, nicknameSuggestError: '' });
    }
    if (value && value === nicknameCheck.normalize(this._savedName)) {
      this.setData({ nicknameState: '', nicknameHint: '' });
      return;
    }
    const issue = nicknameCheck.localIssue(value);
    if (issue) {
      this.setData({ nicknameState: 'invalid', nicknameHint: issue });
      return;
    }
    this.setData({ nicknameState: 'checking', nicknameHint: '正在检查昵称是否可用…' });
    const seq = this._nicknameSeq;
    this._nicknameTimer = setTimeout(() => {
      this._nicknameTimer = null;
      this.requestNicknameCheck(value, seq);
    }, NICKNAME_CHECK_DEBOUNCE_MS);
  },

  requestNicknameCheck(value, seq) {
    const that = this;
    const context = this.profileContext();
    const stale = () => that._unloaded || that._hidden || seq !== that._nicknameSeq || !that.currentProfileContext(context);
    const onFailure = () => {
      if (stale()) return;
      that.setData({ nicknameState: 'error', nicknameHint: '无法验证昵称，可直接保存' });
    };
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/user/nickname/check',
      method: 'POST',
      data: { nickname: value },
      success(res) {
        if (stale()) return;
        const result = nicknameCheck.readCheckResult(res);
        that.setData({ nicknameState: result.state, nicknameHint: result.text });
        if (result.state === 'taken') that.loadNicknameSuggestions(value, false);
      },
      successStatusAbnormal: onFailure,
      fail: onFailure
    });
  },

  retryNicknameCheck() {
    const value = nicknameCheck.normalize(this.data.tempName);
    this.resetNicknameCheck();
    if (nicknameCheck.localIssue(value)) {
      this.scheduleNicknameCheck(value);
      return;
    }
    this.setData({ nicknameState: 'checking', nicknameHint: '正在检查昵称是否可用…' });
    this.requestNicknameCheck(value, this._nicknameSeq);
  },

  /**
   * 拉一批推荐昵称(被占用时自动触发;「换一批」沿用服务端 cursor 往后翻)。
   * 只列出,不替用户填 —— 点选哪个由用户决定。
   */
  loadNicknameSuggestions(raw, more) {
    if (this.data.nicknameSuggesting || this.data.saving) return;
    const base = more ? this._suggestBase : nicknameCheck.normalize(raw);
    if (!base || nicknameCheck.localIssue(base)) {
      this.setData({ nicknameSuggestions: [], nicknameSuggestError: '先写一个 2–24 字、符合规则的昵称，再看推荐' });
      return;
    }
    if (!more || base !== this._suggestBase) {
      this._suggestBase = base;
      this._suggestCursor = null;
    }
    const seq = this._nicknameSeq;
    // 轮次号:旧一轮作废时只在没有更新一轮接手时才清「推荐中」
    const run = (this._suggestRun = (this._suggestRun || 0) + 1);
    const that = this;
    const collected = [];
    const context = this.profileContext();
    const stale = () => that._unloaded || that._hidden || seq !== that._nicknameSeq || !that.currentProfileContext(context);
    const finish = (failText) => {
      if (that._unloaded) return;
      if (stale()) { if (run === that._suggestRun) that.setData({ nicknameSuggesting: false }); return; }
      that.setData({
        nicknameSuggesting: false,
        nicknameSuggestions: collected.slice(),
        nicknameSuggestError: collected.length ? '' : (failText || '暂时没拿到推荐，可以再试一次')
      });
    };
    const step = (attempt) => {
      const data = { baseName: base };
      if (that._suggestCursor !== null && that._suggestCursor !== undefined) data.cursor = that._suggestCursor;
      app.sendRequest({
        hideLoading: true,
        autoErrorToast: false,
        url: '/api/user/nickname/suggest',
        method: 'POST',
        data: data,
        success(res) {
          if (stale()) { finish(); return; }
          const result = nicknameCheck.readSuggestResult(res);
          if (!result.ok) { finish(result.text); return; }
          that._suggestCursor = result.cursor;
          if (collected.indexOf(result.nickname) === -1) collected.push(result.nickname);
          if (collected.length < NICKNAME_SUGGEST_BATCH && attempt + 1 < NICKNAME_SUGGEST_BATCH
              && result.cursor !== null) step(attempt + 1);
          else finish();
        },
        successStatusAbnormal() { finish(); },
        fail() { finish(); }
      });
    };
    this.setData({ nicknameSuggesting: true, nicknameSuggestError: '' });
    step(0);
  },

  moreNicknameSuggestions() {
    this.loadNicknameSuggestions(this._suggestBase || this.data.tempName, !!this._suggestBase);
  },

  pickNicknameSuggestion(e) {
    if (this.data.saving) return;
    const name = e && e.currentTarget && e.currentTarget.dataset.name;
    if (typeof name !== 'string' || this.data.nicknameSuggestions.indexOf(name) === -1) return;
    this.resetNicknameCheck();
    this.setData({
      tempName: name,
      nicknameState: 'available',
      nicknameHint: '',
      nicknameSuggestions: [],
      nicknameSuggestError: ''
    });
  },

  // 上传个人项目图片
  uploadCasePics: function() {
    const that = this;
    const context = this.profileContext();
    const remainingCount = 9 - that.data.tempCasePics.length;
    
    if (remainingCount <= 0) {
      app.tips('最多只能上传9张图片');
      return;
    }

    app.chooseImage(function(res) {
      that.applyProfileResult(context, () => {
        const newPics = [...that.data.tempCasePics, ...res];
        that.setData({ tempCasePics: newPics });
      });
    }, remainingCount);
  },

  // 删除个人项目图片
  deleteCasePic: function(e) {
    const index = e.currentTarget.dataset.index;
    const tempCasePics = [...this.data.tempCasePics];
    tempCasePics.splice(index, 1);
    this.setData({
      tempCasePics: tempCasePics
    });
  },
  removeCategory: function (e) {
    const categoryId = parseInt(e.currentTarget.dataset.id);
    const that = this;

    // 从 selectedCategoryIds 中移除
    const newSelectedIds = that.data.selectedCategoryIds.filter(id => id !== categoryId);

    // 从 selectedCategoryNames 中移除对应的名称
    const newSelectedNames = that.data.selectedCategoryNames.filter((name, index) => {
      return that.data.selectedCategoryIds[index] !== categoryId;
    });

    // 从 categoryList 中移除（更新显示的分类列表）
    const newCategoryList = that.data.categoryList.filter(item => item.id !== categoryId);

    that.setData({
      selectedCategoryIds: newSelectedIds,
      selectedCategoryNames: newSelectedNames,
      categoryList: newCategoryList,
      'formData.activityCategoryids': newSelectedIds.join(',')
    });

    // console.log('移除分类后:', {
//       selectedIds: newSelectedIds,
//       selectedNames: newSelectedNames,
//       categoryList: newCategoryList
//     });

    toast.success('移除成功', { duration: 1000 });
  },

  // 上传微信二维码
  uploadWechat: function() {
    const that = this;
    const context = this.profileContext();
    
    app.chooseImage(function(res) {
      that.applyProfileResult(context, () => {
        that.setData({ tempWechat: res[0] });
      });
    }, 1);
  },

  // 删除微信二维码
  deleteWechat: function() {
    this.setData({
      tempWechat: ''
    });
  },

  // 个人项目描述输入变化
  onCaseIntroductonChange: function(e) {
    this.setData({
      tempCaseIntroducton: e.detail.value
    });
  },

  // 分类选择弹窗化(2026-07-31):半屏 cy-category-sheet + 事件回传,不再跳转独立页面
  navigateToCategorySelect: function () {
    this.setData({
      categorySheetVisible: true,
      categorySheetIds: this.data.selectedCategoryIds.join(','),
    });
  },

  onCategorySelect: function (e) {
    this.updateCategorySelection(e.detail.selectedIds, e.detail.selectedCategories);
  },

  onCategorySheetClose: function () {
    this.setData({ categorySheetVisible: false });
  },

  // 从分类选择弹窗回调更新选择 - 需要同时接收ID和名称
  updateCategorySelection: function (selectedIds, selectedCategories) {
    const that = this;

    const selectedCategoryIds = selectedIds || [];
    const selectedCategoryNames = selectedCategories ? selectedCategories.map(item => item.categoryName) : [];
    const selectedCategoryNamesStr = selectedCategoryNames.join('，');

    // 更新 categoryList 数组（这是您主要需要的）
    that.setData({
      selectedCategoryIds: selectedCategoryIds,
      selectedCategoryNames: selectedCategoryNames,
      selectedCategoryNamesStr: selectedCategoryNamesStr,
      // 更新 categoryList 数组
      categoryList: selectedCategories || []
    });

  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    var that = this
    this._unloaded = false;
    this._hidden = false;
    that.getUserData()
  },

  onUnload() {
    this._unloaded = true;
    this.releaseProfilePhoneLoading();
    this._profileReadSeq = (this._profileReadSeq || 0) + 1;
    this._profileDeferred = [];
    this._profileLoading = false;
    this.resetNicknameCheck();
    if (this._profileReturnTimer) {
      clearTimeout(this._profileReturnTimer);
      this._profileReturnTimer = null;
    }
  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    this._hidden = false;
    this.profileContext();
    const pending = this._profileDeferred || [];
    this._profileDeferred = [];
    pending.forEach(item => this.applyProfileResult(item.context, item.apply));
    if (this.data.saveSucceeded) this.scheduleProfileReturn();
    if (!this.data.loaded && !this._profileLoading) this.getUserData();
  },

  onHide() {
    this._hidden = true;
    this.resetNicknameCheck();
    this.releaseProfilePhoneLoading();
    this._profileViewEpoch = (this._profileViewEpoch || 0) + 1;
    this._profileReadSeq = (this._profileReadSeq || 0) + 1;
    this._profileLoading = false;
    this.setData({ loading: false, refreshing: false });
    if (this._profileReturnTimer) {
      clearTimeout(this._profileReturnTimer);
      this._profileReturnTimer = null;
    }
  },

  scheduleProfileReturn() {
    if (this._profileReturnTimer) return;
    const context = this.profileContext();
    const viewEpoch = this._profileViewEpoch || 0;
    const timer = setTimeout(() => {
      if (this._profileReturnTimer !== timer) return;
      this._profileReturnTimer = null;
      if (!this.currentProfileContext(context) || this._hidden || viewEpoch !== (this._profileViewEpoch || 0)) return;
      this.exitPage();
    }, 2000);
    this._profileReturnTimer = timer;
  },

  profileContext() {
    const owner = String(app.getUserID && app.getUserID() || '');
    if (this._profileOwner !== undefined && owner !== this._profileOwner) {
      this.releaseProfilePhoneLoading();
      this._profileEpoch = (this._profileEpoch || 0) + 1;
      this._profileReadSeq = (this._profileReadSeq || 0) + 1;
      this._profileLoading = false;
      this._profileDeferred = [];
      this._loadedProfileName = undefined;
      this._savedName = undefined;
      this._suggestBase = undefined;
      this._suggestCursor = null;
      this.resetNicknameCheck();
      if (this._profileReturnTimer) clearTimeout(this._profileReturnTimer);
      this._profileReturnTimer = null;
      this.setData({
        userInfo: { name: '', introduction: '', website: '', caseIntroducton: '',
          avatar: '', tag_ids: '', casePics: '', wechat: '', phone: '' },
        phoneMasked: '', casePicsList: [], tempCasePics: [], tempCaseIntroducton: '',
        tempWechat: '', tempIntroduction: '', tempName: '', nicknameState: '', nicknameHint: '',
        nicknameSuggestions: [], nicknameSuggesting: false, nicknameSuggestError: '', selectedCategoryIds: [],
        selectedCategoryNames: [], selectedCategoryNamesStr: '', categoryList: [],
        categorySheetVisible: false, categorySheetIds: '', popDavid: false,
        popDavid2: false, popDavid3: false, popDavid4: false, loaded: false,
        canSave: false, saving: false, saveError: '', saveSucceeded: false,
        loading: false, refreshing: false, loadError: '', loadErrorSub: ''
      });
    }
    this._profileOwner = owner;
    return { owner, epoch: this._profileEpoch || 0 };
  },

  currentProfileContext(context) {
    if (this._unloaded) return false;
    const current = this.profileContext();
    return context.owner === current.owner && context.epoch === current.epoch;
  },

  applyProfileResult(context, apply) {
    if (!this.currentProfileContext(context)) return;
    if (this._hidden) {
      (this._profileDeferred || (this._profileDeferred = [])).push({ context, apply });
      return;
    }
    apply();
  },

  // 输入框内容变化处理
  onInputChange: function (e) {
    const field = e.currentTarget.dataset.field;
    const value = e.detail.value;
    if (field === 'introduction') this.setData({ tempIntroduction: value });
    else if (field === 'name') {
      this.setData({ tempName: value });
      this.scheduleNicknameCheck(value);
    }
  },

  refreshSaveState: function () {
    const canSave = !!(this.data.userInfo.name || '').trim();
    if (canSave !== this.data.canSave) this.setData({ canSave });
  },

  retryLoad: function () {
    this.getUserData();
  },

  getUserData: function () {
    const context = this.profileContext();
    if (this._unloaded || this._hidden || this._profileLoading) return;
    const seq = this._profileReadSeq = (this._profileReadSeq || 0) + 1;
    this._profileLoading = true;
    const that = this;
    const hadLoaded = !!this.data.loaded;
    this.setData({
      loading: !hadLoaded,
      refreshing: hadLoaded,
      loadError: '',
      loadErrorSub: ''
    });
    app.sendRequest({
      hideLoading: true,
      url: '/api/user/info',
      autoErrorToast: false,   // F-R2U-01:读取失败(含刷新)一律不弹 toast
      data: {
        member_id: app.getUserID()
      },
      method: "POST",
      success: function (res) {
        if (!that.currentProfileContext(context) || that._hidden || seq !== that._profileReadSeq) return;
        that._profileLoading = false;
        if (res && res.code == "200" && res.data && typeof res.data === 'object' && !Array.isArray(res.data)) {
          that._loadedProfileName = res.data.name;
          // 处理个人项目图片字符串转数组
          let casePicsList = [];
          if (typeof res.data.casePics === 'string' && res.data.casePics.trim() !== '') {
            casePicsList = res.data.casePics.split(';').filter(url => url.trim() !== '');
          }

          that._savedName = typeof res.data.name === 'string' ? res.data.name : '';
          that.setData({
            userInfo: {
              ...that.data.userInfo,
              ...res.data
            },
            phoneMasked: maskPhone(res.data.phone),
            casePicsList: casePicsList,
            loading: false,
            refreshing: false,
            loaded: true,
            loadError: '',
            loadErrorSub: ''
          }, () => that.refreshSaveState());
          that.loadProfileReview(context);

          if (isRecordList(res.data.sysCategoryList) && res.data.sysCategoryList.length > 0) {
            const selectedIds = res.data.sysCategoryList.map(item => item.id);
            const selectedNames = res.data.sysCategoryList.map(item => item.categoryName);
            const selectedNamesStr = selectedNames.join('，');

            that.setData({
              categoryList: res.data.sysCategoryList,
              selectedCategoryIds: selectedIds,
              selectedCategoryNames: selectedNames,
              selectedCategoryNamesStr: selectedNamesStr
            });
          }

        } else {
          that.setData({
            loading: false,
            refreshing: false,
            loadError: hadLoaded ? '' : '资料暂时不可用',   // 有旧资料时静默降级,wxml 只在 !loaded 渲染
            loadErrorKind: 'data',
            loadErrorSub: hadLoaded ? '' : ((res && res.msg) || '请稍后重新加载')
          });
        }
      },
      fail: function (res) {
        if (!that.currentProfileContext(context) || that._hidden || seq !== that._profileReadSeq) return;
        that._profileLoading = false;
        that.setData({
          loading: false,
          refreshing: false,
          loadError: hadLoaded ? '' : '网络没连上',   // 同上:有旧资料时静默
          loadErrorKind: 'network',
          loadErrorSub: hadLoaded ? '' : '检查网络连接后重试'
        });
      }
    })
  },

  /** W10b #25:头像审核回执只读服务端回执;读不到就不显示,不编造「审核中/已通过」。 */
  loadProfileReview: function (context) {
    const that = this;
    const seq = (this._profileReviewSeq || 0) + 1;
    this._profileReviewSeq = seq;
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/user/profile/review',
      method: 'GET',
      success: function (res) {
        if (!that.currentProfileContext(context) || seq !== that._profileReviewSeq) return;
        const receipt = res && res.code == '200' ? res.data : null;
        that.setData({ avatarReviewText: profileReview.memberAvatarText(receipt) });
      },
      fail: function () {
        if (!that.currentProfileContext(context) || seq !== that._profileReviewSeq) return;
        that.setData({ avatarReviewText: '' });
      }
    });
  },

  saveInfo: function () {
    const context = this.profileContext();
    if (this._unloaded || this._hidden) return;
    const that = this;
    const userInfo = this.data.userInfo;
    if (!this.data.canSave || this.data.saving || this.data.saveSucceeded) return;

    // 基本验证
    if (!userInfo.name || userInfo.name.trim() === '') {
      app.tips('请输入姓名');
      return;
    }

    const data = {
      name: userInfo.name === this._loadedProfileName ? undefined : userInfo.name,
      avatar: userInfo.avatar,
      introduction: userInfo.introduction,
      website: userInfo.website,
      caseIntroducton: userInfo.caseIntroducton,
      casePics: userInfo.casePics, // 个人项目图片字符串
      wechat: userInfo.wechat, // 微信二维码
      tagIds: this.data.selectedCategoryIds.join(',')
    };

    this.setData({ saving: true, saveError: '', saveSucceeded: false });

    app.sendRequest({
      url: '/api/user/update',
      data: JSON.stringify(data),
      method: "POST",
      header: {
        'Content-Type': 'application/json'
      },
      success: function (res) {
        that.applyProfileResult(context, () => {
        if (res.code == "200") {
          // 只回写本次真正提交的字段:昵称未改时 data.name 是 undefined,写进会话会把现有昵称清空。
          if (typeof data.name === 'string' && data.name.trim()) app.setNickname(data.name);
          if (typeof data.avatar === 'string' && data.avatar) app.setAvatar(data.avatar);
          // G-5(2026-10-27):头像/昵称保存成功全局广播,我的页、主页等不必等下次冷启动
          identitySync.publishIdentity('member', { nickname: data.name, avatar: data.avatar });
          that.setData({ saving: false, saveError: '', saveSucceeded: true });
          toast.success('资料已保存', { duration: 2000 });
          that.scheduleProfileReturn();
        } else if (res && (res.errorCode === nicknameCheck.ERROR_TAKEN || res.errorCode === nicknameCheck.ERROR_INVALID)) {
          // 保存时服务端判昵称不可用:直接把昵称面板打开并带上原因,被占用时列出推荐供点选
          const taken = res.errorCode === nicknameCheck.ERROR_TAKEN;
          that.resetNicknameCheck();
          that.setData({
            saving: false,
            saveError: res.msg || (taken ? '昵称已被占用，请换一个' : '昵称不符合规则'),
            popDavid4: true,
            tempName: userInfo.name,
            nicknameState: taken ? 'taken' : 'invalid',
            nicknameHint: taken ? '这个昵称已被占用，换一个试试' : (nicknameCheck.localIssue(userInfo.name) || '昵称不符合规则'),
            nicknameSuggestions: [],
            nicknameSuggestError: ''
          });
          if (taken) that.loadNicknameSuggestions(userInfo.name, false);
        } else {
          that.setData({
            saving: false,
            saveError: res.msg || '资料暂时没有保存，请重试'
          });
        }
        });
      },
      fail: function (res) {
        that.applyProfileResult(context, () => {
          that.setData({ saving: false, saveError: '网络没连上，请检查后重试' });
        });
      }
    });
  },

  exitPage: function () {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/member/index/index' });
  },

  uploadPic() {
    let that = this;
    const context = this.profileContext();
    app.chooseImage(function (res) {
      that.applyProfileResult(context, () => {
        // 换了还没保存的新头像:旧头像的审核回执不再对应屏幕上这张,先不显示。
        that.setData({ 'userInfo.avatar': res[0], avatarReviewText: '' });
      });
    }, 1, { crop: true, cropScale: '1:1' });
  },

  releaseProfilePhoneLoading(token = this._profilePhoneLoading) {
    if (!token || !token.active) return;
    token.active = false;
    if (this._profilePhoneLoading === token) this._profilePhoneLoading = null;
    cyLoading.hide();
  },

  getPhoneNumber(e) {
    const that = this;
    const context = this.profileContext();
    if (this._unloaded || this._hidden) return;
    if (!e || !e.detail || e.detail.errMsg !== 'getPhoneNumber:ok') {
      toast('您已取消授权');
      return;
    }
    const code = e.detail.code;
    if (!code) {
      toast('获取手机号失败');
      return;
    }
    if (this._profilePhoneLoading) return;
    const loading = this._profilePhoneLoading = { active: true };
    cyLoading.show('获取中...');
    app.sendRequest({
      url: '/api/getwxbindphone',
      method: 'POST',
      data: { code: code },
      success(res) {
        that.releaseProfilePhoneLoading(loading);
        that.applyProfileResult(context, () => {
        if (res.code == '200') {
          const phone = boundPhoneFromResponse(res);
          if (!phone) {
            toast(res.msg || '获取手机号失败');
            return;
          }
          that.setData({
            'userInfo.phone': phone,
            phoneMasked: maskPhone(phone)
          });
        } else {
          toast(res.msg || '获取手机号失败');
        }
        });
      },
      fail() {
        that.releaseProfilePhoneLoading(loading);
        that.applyProfileResult(context, () => {
          toast('网络错误，请重试');
        });
      }
    });
  },
})
