const sessionClient = require('../../../utils/game-session-client.js');

function catalogue(view) {
  return view.world && Array.isArray(view.world.itemCatalog) ? view.world.itemCatalog : (view.contents || []).filter(item => item.kind === 'ITEM');
}
function itemText(rows, view) {
  return (rows || []).map(row => {
    const item = catalogue(view).find(item => item.id === row.itemId);
    return (item ? item.name : '物资 ' + row.itemId) + ' ×' + row.quantity;
  }).join('、') || '无';
}
function present(view, nodes) {
  const world = view.world || {};
  const allowed = action => (view.availableActions || []).includes(action);
  const station = row => {
    const node = (nodes || []).find(node => String(node.nodeId) === String(row.nodeId));
    return node ? node.name : row.nodeId ? '节点资料尚未加载' : '';
  };
  return {
    tradeTargets: (world.tradeTargets || []).map(row => Object.assign({}, row, {
      stationName: station(row), completed: row.completed === true,
      enabled: row.disabled === false && allowed('TRADE_OPEN'),
      reason: row.disabled !== false ? row.disabledReason || '交易暂不可用' : !allowed('TRADE_OPEN') ? '仅当前队长可操作' : '',
    })),
    tradeRows: (world.trades || []).map(row => Object.assign({}, row, {
      stationName: station(row), giveText: itemText(row.give, view), takeText: itemText(row.take, view),
      statusText: { OPEN: '等待双方队长确认', COMPLETED: '已交换', CANCELLED: '已取消' }[row.status] || row.status,
      confirmed: (row.confirmedTeams || []).some(id => String(id) === String(view.teamId)),
      canConfirm: row.status === 'OPEN' && row.disabled === false && allowed('TRADE_CONFIRM') && !(row.confirmedTeams || []).some(id => String(id) === String(view.teamId)),
      canCancel: row.status === 'OPEN' && allowed('TRADE_CANCEL'),
    })),
    tradeLockedText: itemText(Object.keys(world.locked || {}).map(itemId => ({ itemId, quantity: world.locked[itemId] })), view),
  };
}
function payload(form) {
  const result = { tradeKey: form.tradeKey, toTeamId: form.toTeamId, give: [], take: [] };
  for (const row of form.items) for (const side of ['give', 'take']) {
    const raw = String(row[side]).trim();
    if (!/^\d+$/.test(raw) || Number(raw) > 999) throw new Error(row.name + '的数量须为 0–999 的整数');
    if (Number(raw)) result[side].push({ itemId: row.id, quantity: Number(raw) });
  }
  if (!result.give.length && !result.take.length) throw new Error('请填写至少一项交换物资');
  return result;
}
function recoveryText(result, hpName = '生命值') {
  result = result.facilityResult || result;
  if (result.recoveryKey && Number.isSafeInteger(result.hpDelta)) {
    const effect = result.hpDelta > 0 ? '恢复' + hpName + ' ' + result.hpDelta : hpName + '已满，未产生效果';
    return effect + '，' + (result.consumed === true ? '已消耗一次' : '未消耗')
      + (Number.isSafeInteger(result.remaining) && result.remaining >= 0 ? '，剩余 ' + result.remaining + ' 次' : '');
  }
  const payer = result.paidByOwnerId != null ? '本人' : result.paidByTeamId != null ? '队伍 ' + result.paidByTeamId + ' ' : '';
  if (result.noEffect === true && (result.consumed === false || !payer && result.consumed !== true)) return '已满，未消耗';
  if (payer || result.consumed === true) return (result.noEffect === true ? '未产生效果' : '工具已生效') + '，' + (payer ? '仅' + payer + '支付一次' : '已消耗一次');
  return '';
}
function methods(req) {
  function client() {
    return sessionClient.createGameSessionClient({ sendRequest(options) {
      req(options.url, options.method, options.data, options.header, true).then(res => res.netFail ? options.fail() : options.success(res), options.fail);
    } });
  }
  const tradeMethods = {
    selectThemeTradeTarget(e) {
      const view = this.data.themeBag;
      const target = view.tradeTargets.find(row => String(row.teamId) === String(e.currentTarget.dataset.id));
      if (!target || !target.enabled || this._themeSending || this._tradePending || this._planPending) return;
      this.setData({ themeTradeError: '', themeTradeForm: { toTeamId: target.teamId, tradeKey: 'trade_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10),
        items: catalogue(view).map(item => ({ id: item.id, name: item.name, give: '0', take: '0' })) }, themeTradePreview: null });
    },
    inputThemeTradeQuantity(e) {
      const form = this.data.themeTradeForm;
      const { index, side } = e.currentTarget.dataset;
      if (!form || this._themeSending || this._tradePending || !form.items[index] || !['give', 'take'].includes(side)) return;
      this.setData({ ['themeTradeForm.items[' + index + '].' + side]: e.detail.value, themeTradePreview: null, themeTradeError: '' });
    },
    closeThemeTradeForm() { if (!this._themeSending && !this._tradePending) this.setData({ themeTradeForm: null, themeTradePreview: null, themeTradeError: '' }); },
    async previewThemeTrade() {
      if (!this._canThemeTrade('TRADE_OPEN')) return false;
      let data;
      try { data = payload(this.data.themeTradeForm); } catch (error) { this.setData({ themeTradeError: error.message }); return false; }
      const view = this.data.themeBag;
      const epoch = this._themeEpoch || 0;
      this._themeSending = true; this.setData({ themeBagBusy: true, themeTradeError: '', themeTradePreview: null });
      try {
        const res = await req('/api/game/session/shared/preview', 'POST', { activityId: view.activityId, action: 'TRADE_OPEN', payload: data }, { 'content-type': 'application/json' }, true);
        if (epoch !== (this._themeEpoch || 0)) return false;
        const preview = res.data || {};
        const result = preview.result || {};
        if (res.code != 200 || preview.previewOnly !== true || result.preview !== true || result.tradeKey !== data.tradeKey
          || String(result.toTeamId) !== String(data.toTeamId) || String(result.fromTeamId) !== String(view.teamId)
          || JSON.stringify(result.give) !== JSON.stringify(data.give) || JSON.stringify(result.take) !== JSON.stringify(data.take)
          || ![result.fromUsed, result.toUsed, result.capacity].every(n => Number.isSafeInteger(n) && n >= 0)
          || !Number.isSafeInteger(preview.expectedRevision) || !Number.isSafeInteger(preview.authorityEpoch)) {
          this.setData({ themeTradeError: res.msg || '交易预览未确认，请重新预览' }); return false;
        }
        if (preview.expectedRevision !== view.revision || preview.authorityEpoch !== view.authorityEpoch) {
          await this.refreshThemeBackpack();
          if (epoch !== (this._themeEpoch || 0)) return false;
          const current = this.data.themeBag;
          if (preview.expectedRevision !== current.revision || preview.authorityEpoch !== current.authorityEpoch) {
            this.setData({ themeTradeError: '状态尚未同步，请刷新后重新预览' }); return false;
          }
        }
        this.setData({ themeTradePreview: { payload: data, revision: preview.expectedRevision, authorityEpoch: preview.authorityEpoch,
          fromUsed: result.fromUsed, toUsed: result.toUsed, capacity: result.capacity, giveText: itemText(result.give, view), takeText: itemText(result.take, view) } });
        return true;
      } catch (_) { this.setData({ themeTradeError: '交易预览未确认，请重新预览' }); return false; }
      finally { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    _canThemeTrade(action, tradeKey) {
      const view = this.data.themeBag;
      if (view.scope !== 'TEAM' || this.data.isMock || this.data.isPreview || this._themeSending || this._tradePending || this._themePending || this._gameplayPending || this._planPending) return false;
      if (!(view.availableActions || []).includes(action)) { this.setData({ themeTradeError: '仅当前队长可操作' }); return false; }
      const row = tradeKey ? view.tradeRows.find(row => row.tradeKey === tradeKey) : view.tradeTargets.find(row => this.data.themeTradeForm && String(row.teamId) === String(this.data.themeTradeForm.toTeamId));
      if (!row || tradeKey && (row.status !== 'OPEN' || action === 'TRADE_CONFIRM' && row.confirmed)
        || action !== 'TRADE_CANCEL' && row.disabled !== false) { this.setData({ themeTradeError: row && row.disabledReason || '交易状态已变化，请刷新' }); return false; }
      return true;
    },
    submitThemeTrade() {
      if (!this._canThemeTrade('TRADE_OPEN')) return Promise.resolve(false);
      const preview = this.data.themeTradePreview, view = this.data.themeBag;
      if (!preview || preview.revision !== view.revision || preview.authorityEpoch !== view.authorityEpoch) {
        this.setData({ themeTradePreview: null, themeTradeError: '状态已变化，请重新预览' }); return Promise.resolve(false);
      }
      return this._sendThemeTrade('TRADE_OPEN', preview.payload);
    },
    onThemeTradeAction(e) {
      const { key, action, version } = e.currentTarget.dataset;
      if (!['TRADE_CONFIRM', 'TRADE_CANCEL'].includes(action) || !this._canThemeTrade(action, key)) return Promise.resolve(false);
      const row = this.data.themeBag.tradeRows.find(row => row.tradeKey === key);
      if (Number(version) !== row.version) { this.setData({ themeTradeError: '交易版本已变化，请确认当前清单' }); return Promise.resolve(false); }
      return this._sendThemeTrade(action, { tradeKey: row.tradeKey, version: row.version });
    },
    async _sendThemeTrade(action, payload) {
      const view = this.data.themeBag;
      const command = { activityId: view.activityId, expectedRevision: view.revision, authorityEpoch: view.authorityEpoch,
        requestId: 'trade_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10), action, payload };
      this._tradePending = command; this._themeSending = true;
      const epoch = this._themeEpoch || 0;
      this.setData({ themeBagBusy: true, themeTradeError: '' });
      try { const result = await client().submitAction('player', command); return epoch === (this._themeEpoch || 0) && await this._finishThemeTrade(result, command); }
      finally { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    async checkThemeTradeReceipt() {
      const command = this._tradePending;
      if (!command || this._themeSending) return false;
      this._themeSending = true; this.setData({ themeBagBusy: true });
      const epoch = this._themeEpoch || 0;
      try {
        const result = await client().readReceipt(command.activityId, command.requestId);
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (result.receipt && result.receipt.action !== command.action) { this.setData({ themeTradeError: '交易结果暂未确认，请稍后确认' }); return false; }
        return await this._finishThemeTrade(result, command);
      } finally { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    async _finishThemeTrade(result, command) {
      const applied = result.status === 'success' || result.status === 'matched';
      if (applied || result.status === 'business-error' && (!this.data.themeTradeUnknown || result.receipt)) {
        this._tradePending = null;
        this.setData({ themeTradeUnknown: false, themeTradeError: applied ? '' : result.message, themeTradePreview: null });
        if (applied && command.action === 'TRADE_OPEN') this.setData({ themeTradeForm: null });
        await this.refreshThemeBackpack(); return applied;
      }
      this.setData({ themeTradeUnknown: true, themeTradeError: result.message || '交易结果待确认，请确认后再操作' }); return false;
    },
  };
  return tradeMethods;
}
module.exports = { itemText, catalogue, present, payload, recoveryText, methods, data: { themeTradeForm: null, themeTradePreview: null, themeTradeError: '', themeTradeUnknown: false, themeRecoveryText: '' } };
