const modal = require('../../utils/modal');
const { readReducedMotion } = require('../../utils/motion-preference.js');
const factories = {
  goals: require('../utils/features/exploration-goals').createExplorationGoalsClient,
  anniversary: require('../utils/features/exploration-anniversary').createExplorationAnniversaryClient,
  export: require('../utils/features/exploration-export').createExplorationExportClient
};
function requestId() { return 'tools_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2).padEnd(12, '0'); }
function safeNumber(value) { if (!/^[1-9]\d*$/.test(String(value)) || !Number.isSafeInteger(Number(value))) throw new Error('请选择有效编号或正整数'); return Number(value); }
function member(app) { return String(app.getUserID() || ''); }
function epoch(app) { return String(wx.getStorageSync('authorization_issued_at') || '') + ':' + String(app.globalData.authorization || ''); }
function identity(app) { return member(app) && app.globalData.authorization && app.globalData.authorization !== 'dev-token' ? member(app) + ':' + epoch(app) : ''; }
const stateNames = { CLEAR: '未发现固定场次冲突', SCHEDULE: '账户报名日程', ACTIVE: '进行中', SCHEDULED: '尚未开始', ACHIEVED: '已达成', ENDED: '已结束', CANCELLED: '已取消', DELETED: '已删除', UNAVAILABLE: '当前不可查看', VISIBLE: '当前可查看', UNKNOWN: '结果待确认', APPLIED: '已应用', CONFLICT: '存在冲突，请确认或读取最新版本', NOT_FOUND: '记录不存在', ITEM_NOT_FOUND: '事项不存在', LIMIT: '达到数量上限', VERSION_CONFLICT: '版本冲突，请重新读取', LEDGER_LIMIT: '账本数量达到上限', LEDGER_NOT_FOUND: '账本不存在', EXPENSE_LIMIT: '支出数量达到上限', EXPENSE_NOT_FOUND: '支出不存在', REJECTED: '操作未应用', CREATED: '创建已确认', UPDATED: '修改已确认', SAME: '相同', DIFFERENT: '不同', COMPLETE: '全部读取完成', MORE: '尚有后续数据', SOURCE_CHANGED: '来源已变化，请重新准备', NO_CONFLICT: '未发现固定场次冲突', CONFLICT_FOUND: '存在冲突', INCOMPLETE: '时间资料不完整，不能断言无冲突' };
// 日期等表单字段:只有用到的页面(探索目标/导出)才挂 onField,别的页不带这个 handler
function fieldChange(page, e) { const key = e.currentTarget.dataset.field; if (!Object.prototype.hasOwnProperty.call(page.data, key) || page.data.pending || page.data.busy) return; page.setData({ [key]: e.detail.value }); }
function stateName(value) { return stateNames[value] || value || '未知'; }
function runtime(kind, spec) {
  const initial = () => Object.assign({ reducedMotion: readReducedMotion(), busy: false, error: '', message: '', rows: [], detail: null, pending: false, hasMore: false, fileReady: false, referencePicker: false, referenceKind: 'ROUTE' }, JSON.parse(JSON.stringify(spec.data || {})));
  return Object.assign({
    data: initial(),
    onLoad(query) { this._app = getApp(); this._query = query || {}; this._closed = false; this._generation = 0; this._files = []; this._sequence = 0; },
    onShow() {
      this._hidden = false;
      const who = identity(this._app);
      if (!who) { this.clearPrivate(); this.setData({ error: '请通过现有登录流程登录后再使用' }); return; }
      if (this._identity === who && this.client && this._hiddenData) { this.setData(this._hiddenData); this._hiddenData = null; }
      if (this._identity !== who || !this.client) {
        if (this.client) this.client.dispose(); this.clearPrivate(); this._identity = who;
        const opts = { sendRequest: this._app.sendRequest.bind(this._app), getIdentity: () => identity(this._app), getActor: () => identity(this._app), getMemberId: () => member(this._app), getLoginEpoch: () => epoch(this._app), makeCommandId: requestId, storage: { get: key => wx.getStorageSync(key) || null, set: (key, value) => value === null ? wx.removeStorageSync(key) : wx.setStorageSync(key, value) } };
        try { this.client = factories[kind](opts); if (spec.start) spec.start.call(this, this._query); }
        catch (e) { this.setData({ error: e.message }); }
      }
    },
    onHide() { this._hidden = true; this._hiddenData = JSON.parse(JSON.stringify(this.data)); this.setData(initial()); },
    onUnload() { this._closed = true; this.clearPrivate(); },
    clearPrivate() {
      this._generation++; if (this.client) this.client.dispose(); this.client = null; this._identity = '';
      this._hiddenData = null; this._original = null; this._next = null; this._exportParts = []; this._exportSnapshot = null; this._file = null;
      this.deleteFiles(); this.setData(initial());
    },
    deleteFiles() { const files = this._files || []; this._files = []; if (files.length && wx.getFileSystemManager) { const fs = wx.getFileSystemManager(); files.forEach(filePath => fs.unlink({ filePath, fail() {} })); } },
    current(generation, who) { return !this._closed && generation === this._generation && who === identity(this._app) && who === this._identity; },
    async run(task, project) {
      if (this.data.busy) return;
      if (!this.client || identity(this._app) !== this._identity) { this.clearPrivate(); this.setData({ error: '登录状态已变化，请重新打开此页' }); return; }
      const generation = this._generation, who = this._identity; this.setData({ busy: true, error: '', message: '' });
      try { const result = await task(); if (!this.current(generation, who)) return; if (this._hiddenData) { this.setData(this._hiddenData); this._hiddenData = null; } if (project) await project(result); }
      catch (e) { if (this.current(generation, who)) { if (this._hiddenData) { this.setData(this._hiddenData); this._hiddenData = null; } this.setData({ error: e.message || '操作失败，请重试' }); } }
      finally { if (this.current(generation, who)) { this.setData({ busy: false }); this.syncPending(); if (this._hidden) { this._hiddenData = JSON.parse(JSON.stringify(this.data)); this.setData(initial()); } } else if (!this._closed && generation === this._generation && identity(this._app) !== who) this.clearPrivate(); }
    },
    syncPending() { if (!this.client) return; try { const value = this.client.pendingRequestId ? this.client.pendingRequestId() : this.client.pending ? this.client.pending() : null; this.setData({ pending: Array.isArray(value) ? value.length > 0 : !!value }); } catch (_) { this.setData({ pending: false }); } },
    fresh() { if (this.data.pending) throw new Error('请先恢复原操作，不能提交其他内容'); return requestId(); },
    confirm(task, title) { if (this.data.busy || !this.client) return; const generation = this._generation, who = this._identity; modal.show({ title: title || '确认删除？', content: '此操作会修改你的私人记录。', success: result => { if (result.confirm && this.current(generation, who)) task(); } }); },
    resetEditor() { if (this.data.pending || this.data.busy) { this.setData({ error: '请先恢复原操作' }); return; } this.setData({ detail: null, message: '', error: '' }); if (spec.reset) spec.reset.call(this); },
    async accepted(result) {
      this.setData({ message: stateName(result.outcome || result.state) });
      if (result.outcome === 'UNKNOWN') return;
      const successful = (kind === 'goals' && !!result.id && !!result.requestId) || ['APPLIED','CREATED','UPDATED','DELETED'].includes(result.outcome) || result.state === 'APPLIED';
      if (!successful) { this.setData({ error: (result.outcome === 'CONFLICT' ? '版本冲突' : stateName(result.reason || result.outcome || result.state)) + '；请刷新后重试，原内容未被覆盖' }); return; }
      if (spec.refresh) await spec.refresh.call(this, result);
    },
    retryOriginal() { this.run(() => spec.retry.call(this), result => this.accepted(result)); },
    openReference() { if (this.referenceCurrent()) this.setData({ referencePicker: true }); },
    closeReference() { this.setData({ referencePicker: false }); },
    referenceCurrent() { if (!this.client || identity(this._app) !== this._identity) { this.clearPrivate(); this.setData({ error: '登录状态已变化，请重新打开此页' }); return false; } return !this.data.busy && (!this.data.pending || spec.allowPendingInput); },
    openRoute(e) { const id = e.currentTarget.dataset.id; if (id) wx.navigateTo({ url: '/pages/topic/index/index?id=' + encodeURIComponent(id) }); }
  }, spec.methods || {});
}
module.exports = { runtime, requestId, safeNumber, identity, stateName, fieldChange };
