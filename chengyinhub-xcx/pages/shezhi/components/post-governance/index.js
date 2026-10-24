const toast = require('../../../../utils/toast.js');
const intent = require('../../../../utils/publish/publish-intent.js');
const { enforcementRows } = require('./view-model.js');
const app = getApp();
const REASON_MAX = 500;

// 申诉意图按 账号 + 处置 持久化(publish-intent):结果未知后重试/冷恢复复用同一 requestId,
// 先回读 me/enforcements 核对是否已提交,不直接断言“未提交”。
function scopeOf(enforcementId) { return 'community:appeal:' + enforcementId; }
function ok(res) { return !!(res && (res.code === 200 || res.code === '200')); }
function requestError(res, fallback) {
  return (app.getRequestErrorMessage && app.getRequestErrorMessage(res, fallback)) || fallback;
}

Component({
  properties: {
    show: { type: Boolean, value: false },
  },
  data: {
    state: 'idle',
    rows: [],
    error: '',
    nextCursor: null,
    loadingMore: false,
    editingId: null,
    reason: '',
    reasonMax: REASON_MAX,
    submitting: false,
    notice: '',
  },
  observers: {
    show(value) {
      if (value) this.load();
      else this._epoch = (this._epoch || 0) + 1;
    },
  },
  lifetimes: {
    detached() { this._epoch = (this._epoch || 0) + 1; },
  },
  methods: {
    _owner() { return String((app.getUserID && app.getUserID()) || ''); },

    load(options) {
      const more = !!(options && options.more === true);
      const owner = this._owner();
      const epoch = this._epoch = (this._epoch || 0) + 1;
      if (!Number(owner)) { this.setData({ state: 'login', rows: [], nextCursor: null }); return Promise.resolve(null); }
      if (more) this.setData({ loadingMore: true });
      else this.setData({ state: this.data.rows.length ? this.data.state : 'loading', error: '' });
      const current = () => epoch === this._epoch && owner === this._owner();
      return new Promise((resolve) => {
        const failed = (res) => {
          if (current() && more) this.setData({ loadingMore: false, error: requestError(res, '更多处置记录暂未取回') });
          else if (current()) this.setData({ state: 'error', rows: [], nextCursor: null, error: requestError(res, '处置记录暂未取回') });
          resolve(null);
        };
        app.sendRequest({
          hideLoading: true, silentError: true,
          url: '/api/v1/community/me/enforcements', method: 'GET',
          data: more && this.data.nextCursor ? { cursor: this.data.nextCursor } : {},
          success: (res) => {
            if (!current()) return resolve(null);
            const page = ok(res) && res.data && typeof res.data === 'object' ? enforcementRows(res.data.items) : null;
            if (!page) { failed(ok(res) ? null : res); return; }
            const merged = more ? enforcementRows(this._raw.concat(res.data.items)) : page;
            this._raw = (more ? this._raw : []).concat(res.data.items);
            const rows = merged.map((row) => this._withPending(row, owner));
            const cursor = res.data.nextCursor;
            this.setData({ state: rows.length ? 'ready' : 'empty', rows, loadingMore: false, error: '',
              nextCursor: page.length && (typeof cursor === 'number' || typeof cursor === 'string') ? cursor : null });
            resolve(rows);
          },
          successStatusAbnormal: (res) => failed(res),
          fail: (res) => {
            if (current() && more) this.setData({ loadingMore: false, error: requestError(res, '更多处置记录暂未取回') });
            else if (current()) this.setData({ state: 'error', rows: [], nextCursor: null, error: requestError(res, '处置记录暂未取回') });
            resolve(null);
          },
        });
      });
    },

    // 已能读到申诉记录就清掉本地未决意图;还没读到的保留“上次结果未确认”提示。
    _withPending(row, owner) {
      const pending = intent.pending(wx, scopeOf(row.id), owner);
      if (pending && !row.canAppeal) { intent.settle(wx, scopeOf(row.id), owner, pending.key, 'success'); return row; }
      return Object.assign({}, row, { pendingUnknown: !!(pending && row.canAppeal && pending.status === 'unknown') });
    },

    loadMore() { if (!this.data.loadingMore && this.data.nextCursor) this.load({ more: true }); },
    retry() { this.load(); },

    startAppeal(e) {
      const id = Number(e.currentTarget.dataset && e.currentTarget.dataset.id);
      if (!id || this.data.submitting) return;
      this.setData({ editingId: id, reason: '', notice: '' });
    },
    cancelAppeal() { if (!this.data.submitting) this.setData({ editingId: null, reason: '' }); },
    onReasonInput(e) { this.setData({ reason: String((e.detail && e.detail.value) || '').slice(0, REASON_MAX) }); },

    submitAppeal() {
      const enforcementId = this.data.editingId;
      const reason = String(this.data.reason || '').trim();
      const owner = this._owner();
      if (!enforcementId || this.data.submitting) return;
      if (!reason) { this.setData({ notice: '请写下申诉理由' }); return; }
      if (!Number(owner)) { this.setData({ notice: '请先登录后再申诉' }); return; }
      const scope = scopeOf(enforcementId);
      const started = intent.begin(wx, { scope, memberId: owner, payload: [enforcementId, reason] });
      this.setData({ submitting: true, notice: '' });
      const sameOwner = () => owner === this._owner();
      const done = (notice, close) => {
        if (!sameOwner()) return;
        if (close) this.setData({ submitting: false, editingId: null, reason: '', notice: '' });
        else this.setData({ submitting: false, notice });
      };
      // 断网或响应不明:先回读处置列表核对,读到申诉才算已提交;否则保留同一 requestId 供重试。
      const unknown = () => {
        intent.settle(wx, scope, owner, started.key, 'unknown');
        if (!sameOwner()) return;
        this.setData({ notice: '提交结果未确认，正在确认…' });
        this.load().then((rows) => {
          const row = (rows || []).find((item) => item.id === enforcementId);
          if (row && !row.canAppeal) done('', true);
          else done(rows ? '没有查到这条申诉，可以再次提交（不会重复提交）' : '提交结果暂未确认，请稍后刷新确认');
        });
      };
      app.sendRequest({
        hideLoading: true, silentError: true,
        url: '/api/v1/community/appeals', method: 'POST',
        data: JSON.stringify({ enforcementId, reason, requestId: started.key }),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          if (ok(res)) {
            intent.settle(wx, scope, owner, started.key, 'success');
            if (!sameOwner()) return;
            done('', true);
            toast.success('申诉已提交');
            this.load();
            return;
          }
          // 409 = 该处置已有进行中的申诉(含同一 requestId 已落库),回读核对即可
          if (Number(res && res.code) === 409) { unknown(); return; }
          intent.settle(wx, scope, owner, started.key, 'rejected');
          done(requestError(res, '申诉未提交'));
        },
        successStatusAbnormal: (res, status) => {
          if (Number(status) >= 400 && Number(status) < 500 && Number(status) !== 408 && Number(status) !== 409) {
            intent.settle(wx, scope, owner, started.key, 'rejected');
            done(requestError(res, '申诉未提交'));
          } else unknown();
        },
        fail: () => unknown(),
      });
    },
  },
});
