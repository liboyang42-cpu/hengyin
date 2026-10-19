// 发布者实名改绑申请(X036):用途说明 + 已登记状态 + 改绑申请与进度。
// 口径与 utils/publisher-identity.js 一致:只回状态不回显、不叫「认证」、改绑由平台人工审核。
// 首次登记仍在收费发布 / 入驻的最后一步完成,本页不承担首次登记。
const modal = require('../../utils/modal.js');
const toast = require('../../utils/toast.js');
const publisherIdentity = require('../../utils/publisher-identity.js');

Page({
  data: {
    state: 'loading', // loading | unregistered | ready | error
    progress: null,
    realName: '',
    idCard: '',
    reason: '',
    consented: false,
    consentText: publisherIdentity.CONSENT_TEXT,
    submitting: false,
    cancelling: false,
    formError: '',
  },

  onShow() {
    this.load();
  },

  onBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.switchTab({ url: '/pages/member/index/index' });
  },

  load() {
    const that = this;
    const epoch = (this._epoch || 0) + 1;
    this._epoch = epoch;
    this.setData({ state: 'loading' });
    publisherIdentity.loadIdentityStatus(function (registered) {
      if (epoch !== that._epoch) return;
      if (!registered) { that.setData({ state: 'unregistered', progress: null }); return; }
      publisherIdentity.loadChangeProgress(function (result) {
        if (epoch !== that._epoch) return;
        if (!result.ok) { that.setData({ state: 'error' }); return; }
        that.setData({ state: 'ready', progress: result.progress });
      });
    });
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field;
    const value = e.detail.value;
    if (field === 'realName') this.setData({ realName: value, formError: '' });
    else if (field === 'idCard') this.setData({ idCard: value, formError: '' });
    else if (field === 'reason') this.setData({ reason: value, formError: '' });
  },

  onConsent(e) {
    this.setData({ consented: !!(e && e.detail && e.detail.checked), formError: '' });
  },

  onSubmit() {
    if (this.data.submitting) return;
    const form = {
      realName: this.data.realName,
      idCard: this.data.idCard,
      reason: this.data.reason,
      consented: this.data.consented,
    };
    const check = publisherIdentity.checkChangeForm(form);
    if (!check.ok) { this.setData({ formError: check.message }); return; }
    const that = this;
    this.setData({ submitting: true, formError: '' });
    publisherIdentity.submitIdentityChange(form, function (result) {
      if (!result.ok) {
        that.setData({ submitting: false, formError: result.message });
        return;
      }
      // 提交成功即清空本地填写值:页面不再持有证件明文。
      that.setData({ submitting: false, realName: '', idCard: '', reason: '', consented: false });
      toast('改绑申请已提交，等待平台审核');
      that.load();
    });
  },

  onCancel() {
    const progress = this.data.progress;
    if (!progress || !progress.pending || this.data.cancelling) return;
    const that = this;
    modal.show({
      title: '撤回改绑申请？',
      content: '撤回后当前登记保持不变，之后仍可重新提交。',
      confirmText: '撤回申请',
      cancelText: '继续等待',
      success(r) {
        if (!r || !r.confirm) return;
        that.setData({ cancelling: true });
        publisherIdentity.cancelIdentityChange(progress.id, function (result) {
          that.setData({ cancelling: false });
          toast(result.ok ? '已撤回改绑申请' : result.message);
          that.load();
        });
      },
    });
  },
});
