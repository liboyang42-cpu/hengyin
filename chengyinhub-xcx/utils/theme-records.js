const { clearRunSession, clearServerRunSession, readServerPausedRun } = require('../pages/play/utils/play-run-session.js');
const PAGE_SIZE = 20;
function historyRow(view) {
  const result = view.result || {};
  return { ...view, resultTitle: result.title || (result.success === true ? '本局完成' : result.reason ? '本局结束' : '游玩中'),
    resultSummary: result.summary || '', endedText: result.endedAt ? new Date(result.endedAt).toLocaleDateString() : '',
    hasCard: !!view.result };
}
function resultCard(card, view) {
  const valueRows = [];
  if (typeof card.hp === 'number') valueRows.push({ name: card.hpName || view.hpName || '生命值', value: card.hp });
  if (typeof card.luck === 'number') valueRows.push({ name: card.luckName || view.luckName || '幸运值', value: card.luck });
  if (typeof card.actions === 'number') valueRows.push({ name: view.actionName || '行动次数', value: card.actions });
  if (typeof card.rescues === 'number') valueRows.push({ name: '获救次数', value: card.rescues });
  return { ...card, title: card.title || '本局结果', valueRows, sessionId: view.sessionId };
}
function methods(req, playPage) {
  const recordMethods = {
    openThemeHistory() {
      if (this.data.isMock || this.data.isPreview) return Promise.resolve(false);
      this._themeRecordsEpoch = (this._themeRecordsEpoch || 0) + 1;
      this.setData({ themeHistoryOpen: true, themeHistoryRows: [], themeHistoryMore: true, themeResultCard: null,
        themeCardSessionId: 0, themeRecordsState: null, themeRecordsBusy: false, themeRecordsError: '' });
      return this.loadMoreThemeHistory();
    },
    closeThemeHistory() {
      this._themeRecordsEpoch = (this._themeRecordsEpoch || 0) + 1;
      this.setData({ themeHistoryOpen: false, themeRecordsBusy: false });
    },
    async loadMoreThemeHistory() {
      if (this.data.themeRecordsBusy || !this.data.themeHistoryMore) return false;
      const topicId = this.data.topicId || this.data.id;
      if (!topicId) return false;
      const epoch = this._themeRecordsEpoch || 0, rows = this.data.themeHistoryRows;
      const query = { topicId, limit: PAGE_SIZE };
      if (rows.length) query.beforeSessionId = rows[rows.length - 1].sessionId;
      this.setData({ themeRecordsBusy: true, themeRecordsError: '' });
      try {
        const res = await req('/api/play/theme/history', 'GET', query, null, true);
        if (epoch !== (this._themeRecordsEpoch || 0)) return false;
        if (res.code != 200 || !Array.isArray(res.data)) { this.setData({ themeRecordsError: '游玩记录暂未加载，请稍后重试' }); return false; }
        const seen = new Set(rows.map(row => String(row.sessionId)));
        this.setData({ themeHistoryRows: rows.concat(res.data.filter(row => !seen.has(String(row.sessionId))).map(historyRow)),
          themeHistoryMore: res.data.length === PAGE_SIZE });
        return true;
      } catch (_) { if (epoch === (this._themeRecordsEpoch || 0)) this.setData({ themeRecordsError: '游玩记录暂未加载，请稍后重试' }); return false; }
      finally { if (epoch === (this._themeRecordsEpoch || 0)) this.setData({ themeRecordsBusy: false }); }
    },
    async openThemeResultCard(e) {
      if (this.data.isMock || this.data.isPreview || this.data.themeReplayBusy) return false;
      const id = e && e.currentTarget && e.currentTarget.dataset.id;
      const view = id ? this.data.themeHistoryRows.find(row => String(row.sessionId) === String(id)) : this.data.themeBag;
      if (!view || !view.sessionId || !(view.result || view.scope === 'TEAM' && view.resultCard)) return false;
      const epoch = this._themeRecordsEpoch = (this._themeRecordsEpoch || 0) + 1;
      if (view.scope === 'TEAM') {
        this.setData({ themeCardSessionId: view.sessionId, themeResultCard: view.resultCard ? resultCard(view.resultCard, view) : null,
          themeRecordsState: null, themeRecordsBusy: false, themeRecordsError: view.resultCard ? '' : '本队结果暂未加载，请刷新后重试' });
        return !!view.resultCard;
      }
      this.setData({ themeCardSessionId: view.sessionId, themeResultCard: null, themeRecordsState: null, themeRecordsBusy: true, themeRecordsError: '' });
      try {
        const card = await req('/api/play/theme/card', 'GET', { sessionId: view.sessionId }, null, true);
        if (epoch !== this._themeRecordsEpoch) return false;
        if (card.code != 200 || !card.data) { this.setData({ themeRecordsError: '本局结果暂未加载，请稍后重试' }); return false; }
        this.setData({ themeResultCard: resultCard(card.data, view) });
        const state = await req('/api/play/theme/state', 'GET', { topicId: view.topicId || this.data.topicId || this.data.id, sessionId: view.sessionId }, null, true);
        if (epoch !== this._themeRecordsEpoch) return false;
        if (state.code == 200 && state.data && String(state.data.sessionId) === String(view.sessionId)) this.setData({ themeRecordsState: state.data });
        return true;
      } catch (_) { if (epoch === this._themeRecordsEpoch) this.setData({ themeRecordsError: '本局结果暂未加载，请稍后重试' }); return false; }
      finally { if (epoch === this._themeRecordsEpoch) this.setData({ themeRecordsBusy: false }); }
    },
    retryThemeResultCard() {
      if (this.data.themeBag.scope === 'TEAM') return this.refreshThemeBackpack().then(() => this.openThemeResultCard());
      const row=this.data.themeHistoryRows.find(item => String(item.sessionId) === String(this.data.themeCardSessionId));
      return this.openThemeResultCard(row ? { currentTarget: { dataset: { id: row.sessionId } } } : undefined);
    },
    closeThemeResultCard() {
      this._themeRecordsEpoch = (this._themeRecordsEpoch || 0) + 1;
      this.setData({ themeCardSessionId: 0, themeResultCard: null, themeRecordsState: null, themeRecordsBusy: false });
    },
    async _enterThemeReplay(view) {
      if (!view || !view.sessionId || this._themeRecordsDisposed) return false;
      const scope = this._runSessionScope ? this._runSessionScope() : { memberId: getApp().getUserID(), topicId: view.topicId, activityId: view.activityId };
      if (playPage) {
        if (this._pauseSessionClock) this._pauseSessionClock();
        this._runStarted = false; this._restoredRun = false;
        this._runRestoreEpoch = (this._runRestoreEpoch || 0) + 1;
        if (this._syncRunState) this._syncRunState();
      }
      if (!this._themeReplayClearAt) this._themeReplayClearAt = Date.now();
      const clear = () => clearServerRunSession(req, scope, this._themeReplayClearAt);
      let cleared = false;
      if (!this._themeReplayClearStarted) {
        this._themeReplayClearStarted = true;
        cleared = this._queueRunSync ? await this._queueRunSync(clear) : await clear();
      }
      if (!cleared) {
        const read = await readServerPausedRun(req, scope);
        cleared = read.ok && !read.record && read.endedAt >= this._themeReplayClearAt;
      }
      if (!cleared || !clearRunSession(wx, scope)) { this.setData({ themeRecordsError: '新一局已开启，旧行程用时暂未清理，请核对新一局' }); return false; }
      if (this._themeRecordsDisposed) return false;
      const url = '/pages/play/index?topicId=' + encodeURIComponent(view.topicId) + (view.activityId ? '&activityId=' + encodeURIComponent(view.activityId) : '');
      const navigate = playPage ? wx.redirectTo : wx.navigateTo;
      navigate({ url });
      return true;
    },
    async checkThemeReplay() {
      const old = this._themeReplayAttempt;
      if (!old || this.data.themeReplayBusy) return false;
      this.setData({ themeReplayBusy: true });
      try {
        const res = await req('/api/play/theme/state', 'GET', { topicId: old.topicId, ...(old.activityId ? { activityId: old.activityId } : {}) }, null, true);
        if (this._themeRecordsDisposed) return false;
        if (res.code == 200 && res.data && res.data.playInstanceId === 'replay:' + old.sessionId && String(res.data.sessionId) !== String(old.sessionId) && (!this._themeReplayConfirmedId || String(res.data.sessionId) === String(this._themeReplayConfirmedId))) return await this._enterThemeReplay(res.data);
        this.setData({ themeRecordsError: '尚未确认新一局，请先查看游玩记录' }); return false;
      } catch (_) { this.setData({ themeRecordsError: '尚未确认新一局，请先查看游玩记录' }); return false; }
      finally { this.setData({ themeReplayBusy: false }); }
    },
    async replayThemeSession() {
      const old = this.data.themeRecordsState || this.data.themeBag;
      if (this.data.isMock || this.data.isPreview || !old || old.scope === 'TEAM' || old.replayAllowed !== true || this.data.themeReplayBusy) return false;
      if (this._themeReplayAttempt) return this.checkThemeReplay();
      this.setData({ themeReplayBusy: true, themeRecordsError: '' });
      try {
        const fresh = await req('/api/play/theme/state', 'GET', { topicId: old.topicId || this.data.topicId || this.data.id, sessionId: old.sessionId }, null, true);
        if (this._themeRecordsDisposed) return false;
        if (fresh.code != 200 || !fresh.data || fresh.data.replayAllowed !== true || String(fresh.data.sessionId) !== String(old.sessionId)) {
          this.setData({ themeRecordsError: '这局暂不能再玩，请先查看游玩记录', themeRecordsState: fresh.code == 200 ? fresh.data : null }); return false;
        }
        this._themeReplayAttempt = { ...fresh.data, topicId: fresh.data.topicId || this.data.topicId || this.data.id };
        this.setData({ themeReplayUnconfirmed: true });
        const replay = await req('/api/play/theme/replay', 'POST', { sessionId: old.sessionId }, { 'content-type': 'application/json' }, true);
        if (this._themeRecordsDisposed) return false;
        if (replay.code == 200 && replay.data && String(replay.data.sessionId) !== String(old.sessionId)) {
          const state = await req('/api/play/theme/state', 'GET', { topicId: this._themeReplayAttempt.topicId, sessionId: replay.data.sessionId }, null, true);
          if (state.code == 200 && state.data && String(state.data.sessionId) === String(replay.data.sessionId) && state.data.playInstanceId === 'replay:' + old.sessionId) this._themeReplayConfirmedId = state.data.sessionId;
        }
      } catch (_) { }
      finally { this.setData({ themeReplayBusy: false }); }
      return this._themeReplayAttempt ? this.checkThemeReplay() : false;
    },
  };
  return recordMethods;
}
module.exports = { historyRow, resultCard, methods, data: { themeHistoryOpen: false, themeHistoryRows: [], themeHistoryMore: false,
  themeCardSessionId: 0, themeResultCard: null, themeRecordsState: null, themeRecordsBusy: false, themeRecordsError: '', themeReplayBusy: false, themeReplayUnconfirmed: false } };
