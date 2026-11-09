// §26.1 合作局共享帮助面板(play 页 mixin)。纯规则在 coop-help.js;这里只管请求、在途与回执。
const coop = require('./coop-help.js');

const data = { coopHelp: null };

function methods(req) {
  return {
    openCoopHelp(e) {
      const view = this.data.themeBag || {};
      const questKey = e.currentTarget.dataset.key;
      if (!coop.canHelp(view.cooperation, questKey)) return Promise.resolve(false);
      this._coopSeq = (this._coopSeq || 0) + 1;
      this.setData({ coopHelp: { questKey, loading: true, error: '', rows: [], skip: null, freeText: '', content: '', contentLevel: null, skipConfirm: false, busyLevel: null, unknown: null } });
      return this._coopSend('VIEW', null, null);
    },
    closeCoopHelp() { if (!(this.data.coopHelp && this.data.coopHelp.busyLevel != null)) { this._coopSeq = (this._coopSeq || 0) + 1; this.setData({ coopHelp: null }); } },
    useCoopHelp(e) {
      const sheet = this.data.coopHelp;
      const level = Number(e.currentTarget.dataset.level);
      const row = sheet && sheet.rows.find(r => r.level === level);
      if (!row || row.state === 'off' || sheet.busyLevel != null) return Promise.resolve(false);
      // 未知后的重试:同一层沿用原 requestId
      const pending = sheet.unknown && sheet.unknown.operation === 'USE' && sheet.unknown.level === level ? sheet.unknown.requestId : coop.newRequestId();
      return this._coopSend('USE', level, pending);
    },
    askCoopSkip() { const s = this.data.coopHelp; if (s && s.skip && s.skip.usable && s.busyLevel == null) this.setData({ 'coopHelp.skipConfirm': true }); },
    cancelCoopSkip() { this.setData({ 'coopHelp.skipConfirm': false }); },
    confirmCoopSkip() {
      const s = this.data.coopHelp;
      if (!s || !s.skip || !s.skip.usable || s.busyLevel != null) return Promise.resolve(false);
      const pending = s.unknown && s.unknown.operation === 'SKIP' ? s.unknown.requestId : coop.newRequestId();
      return this._coopSend('SKIP', s.skip.level, pending);
    },
    _coopSend(operation, level, requestId) {
      const view = this.data.themeBag || {};
      const sheet = this.data.coopHelp;
      const body = coop.helpBody({ activityId: view.activityId, cooperation: view.cooperation, questKey: sheet.questKey, operation, level, requestId });
      if (!body) { this.setData({ 'coopHelp.loading': false, 'coopHelp.error': '本题合作状态已变化，请刷新' }); return Promise.resolve(false); }
      const seq = this._coopSeq;
      this.setData({ 'coopHelp.busyLevel': operation === 'VIEW' ? null : level, 'coopHelp.error': '' });
      return req('/api/play/help/shared', 'POST', body, { 'content-type': 'application/json' }, true).then(res => {
        if (seq !== this._coopSeq || !this.data.coopHelp) return false;
        const r = coop.outcome(res);
        const patch = { 'coopHelp.loading': false, 'coopHelp.busyLevel': null };
        if (r.state === 'UNKNOWN') {
          Object.assign(patch, { 'coopHelp.error': operation === 'VIEW' ? '提示没有读到' : '结果未确认，再点一次会查询原请求', 'coopHelp.unknown': operation === 'VIEW' ? null : { operation, level, requestId } });
          this.setData(patch); return false;
        }
        if (r.state === 'REJECTED') { Object.assign(patch, { 'coopHelp.error': r.message, 'coopHelp.unknown': null, 'coopHelp.skipConfirm': false }); this.setData(patch); return false; }
        const d = r.data;
        patch['coopHelp.unknown'] = null;
        if (Array.isArray(d.levels)) { const lv = coop.levelRows(d.levels); patch['coopHelp.rows'] = lv.rows; patch['coopHelp.skip'] = lv.skip; }
        if (d.remainingFreeUses != null) patch['coopHelp.freeText'] = coop.freeText(d.remainingFreeUses);
        if (operation === 'USE') {
          patch['coopHelp.content'] = String(d.content || '');
          patch['coopHelp.contentLevel'] = level;
          const rows = (this.data.coopHelp.rows || []).map(row => row.level === level ? Object.assign({}, row, { state: 'unlocked', actionText: '重看' }) : row);
          patch['coopHelp.rows'] = rows;
          patch['coopHelp.chargeText'] = d.chargedPoints > 0 ? '已扣 ' + d.chargedPoints + ' 积分' : '';
        }
        if (operation === 'SKIP') {
          // 跳过:0 分 + 辅助完成;不放成功动画,刷新权威状态
          patch['coopHelp.skipConfirm'] = false;
          this.setData(patch);
          this.setData({ coopHelp: null });
          return this.refreshThemeBackpack ? this.refreshThemeBackpack().then(() => true) : true;
        }
        if (d.cooperation && this.data.themeBag) patch['themeBag.cooperation'] = d.cooperation;
        this.setData(patch);
        return true;
      });
    }
  };
}

module.exports = { data, methods };
