const app = getApp();
const { readReducedMotion } = require('../utils/motion-preference.js');
const storage = { get: key => wx.getStorageSync(key) || null, set: (key, value) => wx.setStorageSync(key, value) };
let previousSession = null; let sessionEpoch = 0;
function identity() {
  const id = app.getUserID();
  const token = app.getAuthorization();
  const issued = wx.getStorageSync('authorization_issued_at') || '';
  const session = JSON.stringify([id, token, issued]);
  if (session !== previousSession) { previousSession = session; sessionEpoch++; }
  return id && token && token !== app.DEV_TOKEN ? String(id) + ':' + issued + ':' + sessionEpoch : '';
}
function options(page) {
  const send = app.sendRequest.bind(app);
  return { storage, getOwner: () => app.getUserID(), getMemberId: () => app.getUserID(),
    getActor: identity, getIdentity: identity,
    sendRequest(request) {
      const bound = page._identity;
      if (!bound || bound !== identity()) { request.fail(new Error('登录会话已变化')); return; }
      send(Object.assign({}, request, {
        success(response) {
          if (bound !== identity()) { request.fail(new Error('登录会话已变化')); return; }
          page._responseCode = response && response.code;
          request.success(response);
        },
        fail(error) { request.fail(error); }
      }));
    }
  };
}
function uuid() { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const r = Math.floor(Math.random() * 16); return (c === 'x' ? r : (r & 3) | 8).toString(16); }); }
function ids(value) { const result = String(value || '').split(/[,，\s]+/).filter(Boolean).map(Number); if (!result.length || result.some(id => !Number.isSafeInteger(id) || id <= 0)) throw new Error('请填写有效编号'); return result; }
function dateMs(value) { if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value || '')) throw new Error('请按 YYYY-MM-DDTHH:mm 填写上海时间'); const time = Date.parse(value + ':00+08:00'); if (!Number.isFinite(time)) throw new Error('时间无效'); return time; }
function page(config) {
  const initial = Object.assign({ reducedMotion: readReducedMotion(), status: 'idle', message: '', busy: false, result: null, items: [], hasMore: false, counts: null, coverage: '', candidates: [], inviteToken: '', completions: [], completionLoaded: false, completionMore: false, answers: {}, pickerShow: false, pickerKind: 'ROUTE', selectedTitle: '', selectedRoutes: [], completions: [], completionLoaded: false, completionMore: false }, config.data);
  return Object.assign({
    data: initial,
    onLoad(query) { this._query = query || {}; this._queryIdentity = identity(); this._sequence = 0; },
    onShow() {
      this._active = true;
      const current = identity();
      if (this._identity !== current || !this.client) {
        if (this.client && this.client.dispose) this.client.dispose();
        this._command = null; this._identity = current; this.clearPrivate();
        if (!current) { this.setData({ status: 'no-permission', message: '请先登录后使用' }); return; }
        try { this.client = config.client(options(this)); } catch (error) { this.setData({ status: 'error', message: error.message }); return; }
      }
      if (config.show) { try { config.show.call(this, this._queryIdentity === current ? this._query : {}); } catch (error) { this.clearPrivate(); this.setData({ status: error.code === 'OUTCOME_UNKNOWN' ? 'unknown' : 'error', message: error.message }); } }
    },
    invalidateSession(owner, client) { if (this._identity !== owner || this.client !== client) return; if (client && client.dispose) client.dispose(); if (config.hide) config.hide.call(this); this.client = null; this._command = null; this._identity = ''; this._query = {}; this._queryIdentity = ''; this.clearPrivate(); this.setData({ status: 'no-permission', message: '登录会话已变化，请重新进入' }); },
    clearPrivate() { this._sequence++; this._cursor = null; this.setData(Object.assign({}, initial, { status: 'idle' })); },
    onHide() { this._active = false; this.clearPrivate(); if (this.client && this.client.invalidate) this.client.invalidate(); if (config.hide) config.hide.call(this); if (config.disposeOnHide && this.client) { if (this.client.dispose) this.client.dispose(); this.client = null; this._command = null; } },
    onUnload() { this.onHide(); if (this.client && this.client.dispose) this.client.dispose(); this.client = null; this._command = null; this._identity = ''; this._query = {}; this._queryIdentity = ''; },
    input(event) { if (this.data.busy || !this._active) return; const key = event.currentTarget.dataset.field; if (Object.prototype.hasOwnProperty.call(initial, key)) this.setData({ [key]: event.detail.value }); },
    openReference(event) { if (this.data.busy || !this._active || this._identity !== identity() || this._command || this.client && this.client.state && this.client.state().pending) return; this._pickerTarget = event.currentTarget.dataset.target || ''; this.setData({ pickerShow: true, pickerKind: event.currentTarget.dataset.kind || 'ROUTE' }); },
    closeReference() { this.setData({ pickerShow: false }); },
    pickReference(event) { const value = event.detail; if (this.data.busy || !this._active || this._identity !== identity() || !this.data.pickerShow || !value || value.kind !== this.data.pickerKind || value.availability !== 'CURRENT_VISIBLE_CATALOGUE' || !Number.isSafeInteger(Number(value.id)) || Number(value.id) <= 0) return; this.setData({ pickerShow: false }); if (this.selectReference) this.selectReference(value, this._pickerTarget); },
    copyInvitation() { const value = this.data.inviteToken || this.data.result && this.data.result.inviteCode; if (!this._active || this._identity !== identity() || !value) return; wx.setClipboardData({ data: value }); },
    dateInput(event) { const { field, part } = event.currentTarget.dataset; if (!Object.prototype.hasOwnProperty.call(initial, field + 'Date') || !['date', 'time'].includes(part) || this.data.busy) return; const suffix = part === 'date' ? 'Date' : 'Time'; this.setData({ [field + suffix]: event.detail.value }); },
    async run(work, render, write) {
      if (!this._active || this.data.busy) return;
      if (!this._identity || this._identity !== identity()) { this.invalidateSession(this._identity, this.client); return; }
      const sequence = ++this._sequence; const owner = this._identity; const client = this.client; this._responseCode = null;
      this.setData({ busy: true, status: 'loading', message: '', result: null, items: [], hasMore: false, counts: null, coverage: '', candidates: [], inviteToken: '', completions: [], completionLoaded: false, completionMore: false });
      try {
        let value = await work();
        if (owner !== identity()) { this.invalidateSession(owner, client); return; }
        if (!this._active || sequence !== this._sequence || this.client !== client) return;
        if (config.project) value = config.project(value);
        const patch = render ? render(value) : { result: value };
        if (Object.prototype.hasOwnProperty.call(patch, 'cursor')) { this._cursor = patch.cursor; delete patch.cursor; }
        this.setData(Object.assign({ status: 'ready', busy: false }, patch));
        if (patch.items && !patch.items.length) this.setData({ status: 'empty' });
      } catch (error) {
        if (owner !== identity()) { this.invalidateSession(owner, client); return; }
        if (!this._active || sequence !== this._sequence || this.client !== client) return;
        if (write && error.code === 'REJECTED' && typeof client.pending === 'function' && client.pending().length === 0) this._command = null;
        const code = this._responseCode;
        const unknown = write && (error.unknown || error.code === 'OUTCOME_UNKNOWN' || !code || code >= 500);
        this.setData({ busy: false, result: null, items: [], hasMore: false, counts: null, coverage: '', candidates: [], inviteToken: '', completions: [], completionLoaded: false, completionMore: false,
          status: code === 401 || code === 403 ? 'no-permission' : code === 409 ? 'conflict' : unknown ? 'unknown' : 'error', message: error.message || '请求失败，请重新读取' });
      }
    },
    onRetry() { if (this.data.status === 'unknown' && this.recover) this.recover(); else if (this.refresh) this.refresh(); },
    original(action, input) {
      if (this._command && JSON.stringify(this._command) !== JSON.stringify({ action, input })) throw new Error('请先恢复原操作结果');
      this._command = this._command || { action, input: JSON.parse(JSON.stringify(input)) };
      return this._command.input;
    },
    confirm(value) { this._command = null; return { result: value, objectId: value.id || '', answers: {} }; }
  }, config.methods);
}
module.exports = { page, options, identity, storage, uuid, ids, dateMs };
