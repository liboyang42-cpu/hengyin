const app = getApp();
const modal = require('../../../../utils/modal.js');
const STATUS = { ACTIVE: '处置未撤销', REVERSED: '原处置已撤销', EXPIRED: '处置已到期', SUBMITTED: '申诉已提交', REVIEWING: '申诉处理中', UPHELD: '维持原处置' };
const value = (row, camel, snake) => row[camel] == null ? row[snake] : row[camel];
Component({
  data: { state: 'loading', rows: [], cursor: null, busyId: '', unknownId: '', errorText: '' },
  lifetimes: { attached() { this.load(); }, detached() { this._closed = true; } },
  methods: {
    load(e) {
      const more = e && e.currentTarget && e.currentTarget.dataset.more;
      const cursor = more ? this.data.cursor : null;
      const seq = this._seq = (this._seq || 0) + 1;
      const owner = app.getUserID();
      this.setData({ state: 'loading', errorText: '' });
      const current = () => !this._closed && seq === this._seq && owner === app.getUserID();
      const fail = () => { if (current()) this.setData({ state: 'error', rows: [], errorText: '处置记录暂未取回，请重新查询。' }); };
      app.sendRequest({
        url: '/api/v1/community/me/enforcements', method: 'GET', data: { cursor, limit: 20 }, hideLoading: true, silentError: true,
        success: res => {
          if (!current()) return;
          const items = res && [200, '200'].includes(res.code) && res.data && res.data.items;
          if (!Array.isArray(items) || items.some(row => !row || !Number.isSafeInteger(Number(row.id)) || Number(row.id) < 1)) return fail();
          const rows = items.map(row => {
            const appealId = value(row, 'appealId', 'appeal_id');
            return { id: String(row.id), appealId: appealId || '',
              reason: value(row, 'reasonCode', 'reason_code') || '处置理由暂未提供',
              status: STATUS[row.status] || '处置状态待确认',
              appealStatus: appealId ? STATUS[value(row, 'appealStatus', 'appeal_status')] || '申诉状态待确认' : '尚无申诉记录',
              decision: value(row, 'decisionReason', 'decision_reason') || '' };
          });
          const unknownId = rows.some(row => row.id === this.data.unknownId && row.appealId) ? '' : this.data.unknownId;
          this.setData({ state: 'ready', rows, cursor: res.data.nextCursor || null, unknownId });
        }, fail: () => fail(), successStatusAbnormal: fail
      });
    },
    appeal(e) {
      const row = this.data.rows.find(item => item.id === String(e.currentTarget.dataset.id));
      if (!row || row.appealId || this.data.busyId || this.data.unknownId) return;
      const owner = app.getUserID();
      modal.show({ title: '文字申诉', content: '目前仅支持文字说明，提交后不会自动解除处置。', editable: true, placeholderText: '说明申诉理由',
        success: result => {
          if (!result.confirm || this._closed || owner !== app.getUserID() || this.data.busyId || this.data.unknownId) return;
          const reason = String(result.content || '').trim();
          if (!reason) return;
          const requestId = 'appeal_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10);
          this.setData({ busyId: row.id, errorText: '' });
          const current = () => !this._closed && owner === app.getUserID();
          const unknown = () => {
            if (!current()) return;
            this.setData({ busyId: '', unknownId: row.id, errorText: '提交结果待确认，请查询原案，暂不要重复提交。' });
          };
          app.sendRequest({
            url: '/api/v1/community/appeals', method: 'POST', hideLoading: true, silentError: true,
            header: { 'Content-Type': 'application/json' }, data: JSON.stringify({ enforcementId: row.id, reason, requestId, evidenceAssetIds: [] }),
            success: res => {
              if (!current()) return;
              if (!res || ![200, '200'].includes(res.code)) {
                this.setData({ busyId: '', errorText: '申诉未被受理，请查询当前处置后再处理。' });
                return;
              }
              unknown();
              this.load();
            }, fail: () => unknown(), successStatusAbnormal: unknown
          });
        }
      });
    }
  }
});
