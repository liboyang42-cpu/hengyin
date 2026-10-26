// cy-privacy-sheet · 隐私与定位设置的半屏弹窗(从设置页发起,不再整页跳走)
//
// ⚠️ 只承接「设置」这一路:说明 + 官方指引入口 + 撤回漫游定位同意。
//    微信隐私授权待决策那一路(原生 <button open-type="agreePrivacyAuthorization">)
//    仍留在 pages/privacy 路由页 —— wx.onNeedPrivacyAuthorization 回调靠 navigateTo
//    压那一页,而且那个原生按钮不能收编成普通 view,搬进弹窗会让授权彻底失效。
//    故 pages/privacy 路由页保留不删。
const toast = require('../../../../utils/toast.js');
const app = getApp();
const { reader, sameReader } = require('../../../../utils/user-character.js');
const presencePrivacy = require('../../../../utils/roam-presence-privacy.js');
const bgTracker = require('../../../../utils/location/bg-tracker.js');

Component({
  options: { multipleSlots: true },
  properties: {
    show: { type: Boolean, value: false },
    theme: { type: String, value: 'player' },
  },
  data: {
    submitting: false,
  },
  lifetimes: {
    attached() { this._privacyHidden = false; this.setData({ submitting: false }); },
    detached() { this.invalidatePrivacyAction(); },
  },
  pageLifetimes: {
    hide() { this.invalidatePrivacyAction(); },
    show() { this._privacyHidden = false; this.setData({ submitting: false }); },
  },
  methods: {
    invalidatePrivacyAction() {
      this._privacyHidden = true;
      this._privacyActionEpoch = (this._privacyActionEpoch || 0) + 1;
    },
    privacyActionCurrent() {
      const captured = reader(app);
      const epoch = this._privacyActionEpoch = (this._privacyActionEpoch || 0) + 1;
      return () => !this._privacyHidden && epoch === this._privacyActionEpoch && sameReader(app, captured);
    },
    /* ⚠️ 只发事件,**不**自己 setData({show:false}):show 是父级传进来的属性,组件自己改一次,
       父级那份状态并没有跟着变;等父级下次想再打开时 setData(true) 与它自己记着的 true 无差异,
       不会重新下发,弹窗就再也打不开了。开关的所有权留在父级(cy-sheet 本身也是这么做的)。 */
    onClose() {
      // 提交中不让关:撤回请求在途时关掉弹窗,用户看不到成功/失败结果
      if (this.data.submitting) return;
      this.triggerEvent('close');
    },

    openOwnMedia() {
      if (this.data.submitting) return;
      wx.navigateTo({ url: '/pages/privacy/index', success: () => this.triggerEvent('close'),
        fail: () => toast('隐私设置无法打开，请重试') });
    },

    openPrivacyContract() {
      wx.openPrivacyContract({
        fail() {
          toast('隐私保护指引暂不可用');
        },
      });
    },

    disablePresenceSharing() {
      if (this.data.submitting) return;
      this.setData({ submitting: true });
      const current = this.privacyActionCurrent();
      Promise.resolve().then(() => current() ? presencePrivacy.setSharing(app, null, false) : Promise.reject(new Error('账号已变化')))
        .then(() => { if (current()) toast('已关闭附近的人位置分享'); })
        .catch(error => { if (current()) toast(error.message); })
        .finally(() => { if (current()) this.setData({ submitting: false }); });
    },

    withdrawRoamLocationConsent() {
      if (this.data.submitting) return;
      this.setData({ submitting: true });
      const current = this.privacyActionCurrent();
      // 撤回先作用于本机用途；审计失败仍由 consent-client 保留原账号/requestId 补传。
      bgTracker.revoke('roam');
      let audit;
      try {
        audit = current() ? app.recordConsent({ docType: 'privacy_policy', scene: 'roam_location', eventType: 'REVOKE' })
          : Promise.reject(new Error('账号已变化'));
      } catch (error) { audit = Promise.reject(error); }
      Promise.resolve(audit).then(() => {
        if (!current()) return;
        toast('已撤回漫游定位同意');
      }).catch(() => {
        if (!current()) return;
        toast('本机漫游已停，记录待确认');
      }).finally(() => {
        if (current()) this.setData({ submitting: false });
      });
    },
  },
});
