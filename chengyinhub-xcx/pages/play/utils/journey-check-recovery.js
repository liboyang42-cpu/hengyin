'use strict';
const { id } = require('../../../utils/orient-score-view.js');
const { checkReceiptView } = require('./playkit-view.js');
const URLS = { roll: '/api/play/check/roll', reroll: '/api/play/check/reroll', settle: '/api/play/check/settle' };
module.exports.methods = send => ({
  _journeyOriginal() {
    const kit = this.data.journeyCheck || {};
    const sessionId = id((this._routeState || {}).sessionId);
    const owner = id(this._boundPlayOwner());
    if (!owner || !sessionId || !id(kit.nodeId) || !/^[A-Za-z0-9_-]{1,64}$/.test(kit.checkId || '')) return null;
    return { owner, sessionId, nodeId: id(kit.nodeId), checkId: kit.checkId,
      topicId: id(this.data.topicId), activityId: String(this.data.activityId || 0) };
  },
  _journeyStorageKey(scope) {
    return 'journey-original:' + [scope.owner, scope.sessionId, scope.nodeId, scope.checkId].join(':');
  },
  _restoreJourneyCheck() {
    const scope = this._journeyOriginal();
    if (!scope) return;
    try {
      const pending = wx.getStorageSync(this._journeyStorageKey(scope));
      if (pending) this.setData({ 'journeyCheck.unknown': true, 'journeyCheck.disabledReason': '原检定结果待确认，请再次查询' });
    } catch (_) { this.setData({ 'journeyCheck.unknown': true, 'journeyCheck.disabledReason': '原检定记录无法读取，暂不提交新动作' }); }
  },
  _legacyCheckAction(action) {
    const scope = this._journeyOriginal(), kit = this.data.journeyCheck || {};
    if (!kit.show || !this._isPlayAccountCurrent() || this._checkActing) return;
    if (!scope) { this.setData({ 'journeyCheck.disabledReason': '原局标识不完整，请重新读取路线后再操作' }); return; }
    const key = this._journeyStorageKey(scope);
    let pending;
    try {
      pending = wx.getStorageSync(key);
      if (pending && (!URLS[pending.action] || JSON.stringify(pending.scope) !== JSON.stringify(scope))) throw new Error('Invalid original check');
      if (action === 'recover' && !pending) return;
      if (pending && action !== 'recover') { this._restoreJourneyCheck(); return; }
      if (!pending) {
        if (!URLS[action]) return;
        pending = { scope, action, actionId: 'check:' + scope.nodeId + ':' + scope.checkId + ':' + action };
        wx.setStorageSync(key, pending);
      }
    } catch (_) { this.setData({ 'journeyCheck.unknown': true, 'journeyCheck.disabledReason': '原检定记录无法保存或读取，暂不提交新动作' }); return; }
    const intent = this._checkWriteIntent = { payload: scope, key, loadEpoch: this._playLoadEpoch };
    const current = () => this._isJourneyCheckIntentCurrent(intent);
    const unknown = () => {
      if (!current()) return;
      this._checkActing = false;
      this.setData({ 'journeyCheck.loading': false, 'journeyCheck.unknown': true,
        'journeyCheck.disabledReason': '原检定结果待确认，请再次查询；未查到不代表未写入' });
    };
    this._checkActing = true;
    this.setData({ 'journeyCheck.loading': true, 'journeyCheck.unknown': true });
    const read = action === 'recover';
    const body = read ? { sessionId: scope.sessionId, nodeId: scope.nodeId, checkId: scope.checkId, action: pending.action }
      : { topicId: scope.topicId, activityId: scope.activityId, sessionId: scope.sessionId, nodeId: scope.nodeId, checkId: scope.checkId };
    return send(read ? '/api/play/check/receipt' : URLS[action], read ? 'GET' : 'POST', body).then(response => {
      if (!current()) return;
      if (!read && response && ['CHECK_REJECTED', 'CHECK_BAD_REQUEST'].includes(response.errorCode)) {
        wx.removeStorageSync(key);
        this._checkActing = false; this._checkWriteIntent = null;
        this.setData({ 'journeyCheck.loading': false, 'journeyCheck.unknown': false,
          'journeyCheck.disabledReason': response.msg || '本次检定未被接受，请确认后重新操作' });
        return;
      }
      const receipt = response && response.data;
      if (Number(response && response.code) !== 200 || !receipt || id(receipt.sessionId) !== scope.sessionId
          || id(receipt.nodeId) !== scope.nodeId || receipt.checkId !== scope.checkId || receipt.actionId !== pending.actionId) {
        unknown(); return;
      }
      wx.removeStorageSync(key);
      this._checkActing = false; this._checkWriteIntent = null;
      this.setData({ 'journeyCheck.receipt': checkReceiptView(receipt), 'journeyCheck.loading': false,
        'journeyCheck.unknown': false, 'journeyCheck.disabledReason': '' });
    }).catch(unknown);
  }
});
