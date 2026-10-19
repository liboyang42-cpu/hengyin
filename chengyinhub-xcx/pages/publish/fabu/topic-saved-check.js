// Existing GET /api/topic/publish-check inspects server-saved content only.
const PAGE_SIZE = 20;
function empty() {
  return { show: false, busy: false, loaded: false, error: '', rows: [], page: 0, pages: 0,
    total: 0, errors: 0, warnings: 0, clipped: false };
}
function exactId(value) {
  if (typeof value !== 'number' && typeof value !== 'string') return '';
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  const text = String(value == null ? '' : value);
  return /^[1-9]\d{0,18}$/.test(text) && (text.length < 19 || text <= '9223372036854775807') ? text : '';
}
function boundedText(raw, limit) {
  const text = raw.replace(/[\u0000-\u001f\u007f]/g, ' ');
  if (text.length <= limit) return text;
  let end = limit - 1;
  if (/[\uD800-\uDBFF]/.test(text.charAt(end - 1))) end--;
  return text.slice(0, end) + '…';
}
function presentSavedCheck(raw) {
  if (!Array.isArray(raw) || raw.length > 500) throw new Error('检查结果格式或数量异常，请重试');
  let size = 0, errors = 0, warnings = 0, clipped = false;
  const rows = Array.from(raw, row => {
    if (!row || typeof row !== 'object' || Array.isArray(row) || !['error', 'warn'].includes(row.level)
        || typeof row.code !== 'string' || !/^[A-Z][A-Z0-9_]{0,63}$/.test(row.code)
        || typeof row.message !== 'string' || !row.message.trim()) throw new Error('检查结果不完整，请重试');
    size += row.message.length;
    if (size > 200000) throw new Error('检查结果过长，请返回配置逐项核对');
    if (row.level === 'error') errors++; else warnings++;
    clipped = clipped || row.message.length > 800;
    // No raw node IDs, unknown nested fields or server objects enter page data.
    return { level: row.level, label: row.level === 'error' ? '配置问题' : '说明', message: boundedText(row.message, 800) };
  });
  return { rows, total: rows.length, errors, warnings, clipped };
}
function draftSignature(data, loadToken, owner) {
  const source = JSON.stringify(data.formData);
  if (typeof source !== 'string' || source.length > 1000000) return '';
  return JSON.stringify([owner, exactId(data.editingTopicId), data.operationScope || '', data.draftMemberId,
    data.draftUuid, data.baseRevision, data.editScope, loadToken, source]);
}
function signedOut(owner) { return owner === undefined || owner === null || owner === '' || owner === 0 || owner === '0'; }
function methods(app) {
  return {
    _savedCheckSnapshot() {
      try {
        const d = this.data, owner = exactId(app.getUserID && app.getUserID()), topicId = exactId(d.editingTopicId);
        if (this._savedCheckDisposed || this._savedCheckHidden || !owner || !topicId || d.editLoaded !== true || d.editLoading || d.editLoadError
            || (d.draftMemberId && exactId(d.draftMemberId) !== owner) || !['', 'MERCHANT'].includes(d.operationScope || '')) return null;
        const signature = draftSignature(d, this._editLoadToken, owner);
        return signature ? { owner, topicId, scope: d.operationScope || '', signature } : null;
      } catch (_) { return null; }
    },
    _retireSavedTopicCheck() {
      this._savedCheckContext = null; this._savedCheckValue = null;
      this.setData({ savedTopicCheck: empty() });
    },
    _savedCheckOnShow() {
      this._savedCheckHidden = false;
      if (this._savedCheckContext) this._savedCheckLive(this._savedCheckContext);
    },
    _savedCheckOnHide(unload) {
      this._savedCheckHidden = true;
      if (unload) this._savedCheckDisposed = true;
      this._retireSavedTopicCheck();
    },
    _savedCheckLive(context) {
      if (!context || context !== this._savedCheckContext) return false;
      const snapshot = this._savedCheckSnapshot();
      if (!this.data.savedTopicCheck.show || !snapshot || snapshot.signature !== context.signature) {
        this._retireSavedTopicCheck(); return false;
      }
      return true;
    },
    _savedCheckError(context, message) {
      if (!this._savedCheckLive(context)) return false;
      context.settled = true; this._savedCheckValue = null;
      this.setData({ savedTopicCheck: Object.assign(empty(), { show: true, error: boundedText(message, 500) }) });
      return false;
    },
    _savedCheckFailure(context, response, status, message) {
      if (!context || context.settled || context !== this._savedCheckContext) return false;
      const authFailure = Number(status) === 401 || (response && [401, 2].includes(Number(response.code)));
      try {
        // Production reLogin clears the session before invoking fail. Retire the old
        // owner's sheet first; a generic local notice must not resurrect its data.
        if (authFailure && app.getUserID && signedOut(app.getUserID())) {
          const d = this.data;
          const stillCurrent = !this._savedCheckHidden && !this._savedCheckDisposed && d.savedTopicCheck.show
            && d.editLoaded === true && !d.editLoading && !d.editLoadError
            && draftSignature(d, this._editLoadToken, context.owner) === context.signature;
          context.settled = true;
          this._retireSavedTopicCheck();
          if (stillCurrent && !this._savedCheckHidden && !this._savedCheckDisposed && signedOut(app.getUserID())
              && draftSignature(this.data, this._editLoadToken, context.owner) === context.signature
              && typeof app.tips === 'function') app.tips('登录已失效，请重新进入编辑器');
          return false;
        }
      } catch (_) { /* An uncertain identity cannot authorize a sign-in notice for this request. */ }
      return this._savedCheckError(context, message);
    },
    openSavedTopicCheck() {
      if (this._savedCheckDisposed || this._savedCheckHidden) return false;
      if (this.data.savedTopicCheck.busy && this._savedCheckLive(this._savedCheckContext)) return false;
      this._retireSavedTopicCheck();
      const context = this._savedCheckSnapshot();
      if (!context) {
        this.setData({ savedTopicCheck: Object.assign(empty(), { show: true,
          error: exactId(this.data.editingTopicId) ? '已保存主题的身份或加载状态不可确认，请重新进入编辑器' : '尚无已保存主题可检查，请先通过原保存入口保存草稿' }) });
        return false;
      }
      context.settled = false; this._savedCheckContext = context;
      this.setData({ savedTopicCheck: Object.assign(empty(), { show: true, busy: true }) });
      try {
        app.sendRequest({ url: '/api/topic/publish-check', method: 'GET', silentError: true,
          data: { topicId: context.topicId, scope: context.scope },
          success: result => {
            if (!this._savedCheckLive(context) || context.settled) return;
            if (!result || result.code != 200) {
              this._savedCheckError(context, result && typeof result.msg === 'string' && result.msg.trim() ? result.msg : '线上已保存内容检查失败，请重试'); return;
            }
            let value;
            try { value = presentSavedCheck(result.data); }
            catch (error) { this._savedCheckError(context, error.message); return; }
            context.settled = true; this._savedCheckValue = value;
            this.setData({ savedTopicCheck: Object.assign(empty(), { show: true, loaded: true, total: value.total,
              errors: value.errors, warnings: value.warnings, clipped: value.clipped, pages: Math.max(1, Math.ceil(value.total / PAGE_SIZE)) }) });
            this._showSavedCheckPage(1);
          },
          fail: (response, status) => { this._savedCheckFailure(context, response, status, '线上已保存内容检查未完成，请检查网络后重试'); },
          // The existing request wrapper can call only complete on HTTP errors (e.g. 502).
          complete: response => { this._savedCheckFailure(context, response, response && response.statusCode, '线上已保存内容检查未完成，请重试'); },
        });
      } catch (_) { this._savedCheckError(context, '线上已保存内容检查未打开，请重试'); }
      return true;
    },
    retrySavedTopicCheck() {
      return this.data.savedTopicCheck.show ? this.openSavedTopicCheck() : false;
    },
    _showSavedCheckPage(page) {
      if (!this._savedCheckLive(this._savedCheckContext) || !this._savedCheckValue) return false;
      const value = this._savedCheckValue, pages = Math.max(1, Math.ceil(value.total / PAGE_SIZE));
      if (!Number.isInteger(page) || page < 1 || page > pages) return false;
      this.setData({ savedTopicCheck: Object.assign({}, this.data.savedTopicCheck, { page,
        rows: value.rows.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE).map(row => Object.assign({}, row)) }) });
      return true;
    },
    previousSavedCheckPage() { return this._showSavedCheckPage(this.data.savedTopicCheck.page - 1); },
    nextSavedCheckPage() { return this._showSavedCheckPage(this.data.savedTopicCheck.page + 1); },
    closeSavedTopicCheck() { this._retireSavedTopicCheck(); },
  };
}
module.exports = { presentSavedCheck, methods, data: { savedTopicCheck: empty() } };
