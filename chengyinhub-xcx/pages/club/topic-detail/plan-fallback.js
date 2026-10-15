const modal = require('../../../utils/modal.js');
const reasons = { FALLBACK_NOT_CONFIGURED: '本场未开放兜底结局', NO_EFFECTIVE_TEAMS: '没有可参与的队伍', PLAN_AVAILABLE: '仍有通过或待共识的方案', BALLOT_NOT_FINISHED: '尚未完成表决或全队拒绝确认', BALLOT_CHANGED: '库存已变化，需重新表决', SESSION_ENDED: '本局已结束', SESSION_PAUSED: '本局已暂停', RULE_BLOCKED: '当前服务时段不可收尾', NOT_STAFF: '仅本场工作人员可操作' };
function present(control, revision) {
  const world = control && control.world;
  if (!world || typeof world.fallbackAvailable !== 'boolean') return { planFallbackKnown: false, planFallbackAvailable: false, planFallbackReason: '' };
  const synchronized = control.revision === revision;
  return { planFallbackKnown: true, planFallbackAvailable: synchronized && world.fallbackAvailable === true && (control.availableActions || []).includes('PLAN_FALLBACK'),
    planFallbackReason: !synchronized ? '场次状态已更新，请重新加载后核对' : reasons[world.fallbackDisabledReason] || world.fallbackDisabledReason || '', planFallbackRevision: control.revision };
}
function reasonPayload(value) {
  const reason = String(value || '').trim();
  if (!reason || reason.length > 500) throw new Error('请填写 1–500 字的收尾原因');
  return { reason };
}
function methods(request) {
  const sendRequest = request || (options => getApp().sendRequest({ ...options, url: '/api/game/session/shared/control', method: 'GET' }));
  const handlers = {
    applyPlanFallbackReceipt(result) {
      const receipt = result && result.raw && result.raw.receipt;
      const value = receipt && receipt.result && receipt.result.result;
      if (!receipt || receipt.action !== 'PLAN_FALLBACK' || receipt.outcome !== 'APPLIED' || !value || value.fallback !== true) return;
      this.setData({ planFallbackSheet: false, planFallbackResult: { title: value.title || '兜底结局已确认', endingKey: value.endingKey, reason: value.reason || this.data.planFallbackDraft }, planFallbackError: '' });
    },
    loadPlanFallback() {
      const activityId = this._activityId, revision = this.data.revision;
      const ticket = (this._fallbackLoadTicket || 0) + 1; this._fallbackLoadTicket = ticket;
      this.setData({ planFallbackKnown: false, planFallbackAvailable: false });
      if (!activityId) return Promise.resolve(false);
      return new Promise(resolve => sendRequest({ url: '/api/game/session/shared/control', method: 'GET', data: { activityId }, autoErrorToast: false,
        success: res => {
          if (ticket !== this._fallbackLoadTicket || String(activityId) !== String(this._activityId) || revision !== this.data.revision) { resolve(false); return; }
          this.setData(present(Number(res.code) === 200 ? res.data : null, revision)); resolve(true);
        }, fail: () => resolve(false), successStatusAbnormal: () => resolve(false) }));
    },
    openPlanFallback() {
      if (!this.data.planFallbackAvailable || this.data.writeLocked || this.data.loadState !== 'ready') return;
      this.setData({ planFallbackSheet: true, planFallbackError: '' });
    },
    closePlanFallback() { if (!this.data.writeLocked) this.setData({ planFallbackSheet: false }); },
    inputPlanFallbackReason(e) { if (!this.data.writeLocked) this.setData({ planFallbackDraft: e.detail.value, planFallbackError: '' }); },
    confirmPlanFallback() {
      if (!this.data.planFallbackAvailable || this.data.writeLocked || this.data.loadState !== 'ready' || this.data.planFallbackRevision !== this.data.revision) return false;
      let payload;
      try { payload = reasonPayload(this.data.planFallbackDraft); } catch (error) { this.setData({ planFallbackError: error.message }); return false; }
      const revision = this.data.revision, epoch = this.data.authorityEpoch;
      modal.show({ title: '确认兜底收尾', content: '将写入作者配置的兜底结局，并释放未完成的托管与交易，不消耗物资。原因：' + payload.reason, confirmText: '确认收尾',
        success: result => {
          if (!result.confirm || this.data.writeLocked) return;
          if (!this.data.planFallbackAvailable || revision !== this.data.revision || epoch !== this.data.authorityEpoch) { this.setData({ planFallbackError: '场次状态已变化，请重新核对' }); return; }
          this.executeAction('PLAN_FALLBACK', payload);
        } }); return true;
    },
  };
  return handlers;
}
module.exports = { present, reasonPayload, methods, data: { planFallbackKnown: false, planFallbackAvailable: false, planFallbackReason: '', planFallbackRevision: null, planFallbackSheet: false, planFallbackDraft: '', planFallbackError: '', planFallbackResult: null } };
