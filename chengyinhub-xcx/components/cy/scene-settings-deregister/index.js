const { initialData, methods } = require('../../../utils/deregister-flow.js');

Component({
  properties: {
    theme: { type: String, value: 'player' },
  },
  data: initialData(),
  lifetimes: {
    attached() { this._detached = false; this._hidden = false; this.loadStatus(); },
    detached() { this._detached = true; this.dispose(); },
  },
  pageLifetimes: {
    hide() { this._hidden = true; this.dispose(); },
    show() { const wasHidden = this._hidden; this._hidden = false; if (wasHidden && !this._detached) { this.setData({ submitting: false, smsSending: false, canceling: false, confirmed: false, smscode: '', canApply: false, verificationMethod: 'UNAVAILABLE', applyError: '', smsError: '', cancelError: '' }); this.loadStatus(); } },
  },
  // 显式列出事件入口，让 WXML 死链门禁能从组件本体核对；业务实现仍只在 shared flow 一处。
  methods: {
    _readCurrent(token, memberId, res, code) { return methods._readCurrent.call(this, token, memberId, res, code); },
    _readAuthOutcome(res, code) { return methods._readAuthOutcome.call(this, res, code); },
    loginToRead() { return methods.loginToRead.call(this); },
    _statusFailure(message) { return methods._statusFailure.call(this, message); },
    loadStatus() { return methods.loadStatus.call(this); },
    loadPrecheck(token) { return methods.loadPrecheck.call(this, token); },
    goCancellationNotice() { return methods.goCancellationNotice.call(this); },
    closeNoticeSheet() { return methods.closeNoticeSheet.call(this); },
    inputCode(event) { return methods.inputCode.call(this, event); },
    toggleConfirmed(event) { return methods.toggleConfirmed.call(this, event); },
    refreshApplyState() { return methods.refreshApplyState.call(this); },
    sendSms() { return methods.sendSms.call(this); },
    apply() { return methods.apply.call(this); },
    cancel() { return methods.cancel.call(this); },
    dispose() { return methods.dispose.call(this); },
  },
});
