const toast = require('../../../utils/toast.js');
function present(view) {
  const contents = Array.isArray(view.contents) ? view.contents : [];
  const items = contents.filter(i => i.kind === 'ITEM' && i.stat);
  const clues = contents.filter(i => !i.stat).concat((view.events || []).map(e => ({ id: e.id, name: e.name || '线索', content: e.content })));
  const slots = [];
  contents.filter(i => i.kind === 'ITEM').forEach(i => {
    for (let n = 0; n < i.quantity; n++) slots.push({ filled: true });
  });
  while (slots.length < Number(view.capacity || 0)) slots.push({ filled: false });
  return Object.assign({}, view, { items, clues, slots, pendingRows: Object.keys(view.pending || {}).map(id => ({ id, name: view.pending[id].name, quantity: view.pending[id].quantity })) });
}
function methods(req) {
  const bagMethods = {
    _applyThemeView(view) {
      const previous = this.data.themeBag;
      if (previous.sessionId === view.sessionId) { const ids = new Set((previous.events || []).map(e => e.id)); const added = (view.events || []).find(e => !ids.has(e.id)); if (added) toast(added.name || '获得新线索'); }
      this.setData({ themeBag: present(view), themeBagError: '' });
    },
    _startThemePoll() {
      if (this._themeHidden || this._themeTimer || !this.data.themeBag.sessionId || !(this.data.themeBag.timeEggs || this.data.themeBag.stepEggs) || this.data.themeBag.status !== 'ACTIVE') return;
      this._themeTimer = setInterval(() => { if (!this._themeSending) (this.data.themeBag.stepEggs ? this._syncThoughts([]) : Promise.resolve()).then(() => this.refreshThemeBackpack()); }, 30000);
    },
    _stopThemePoll() { if (this._themeTimer) clearInterval(this._themeTimer); this._themeTimer = null; },
    refreshThemeBackpack() {
      if (this.data.isMock || this.data.isPreview || !this.data.topicId) return Promise.resolve(false);
      if (this._themeLoading) return this._themeLoading;
      const epoch = this._themeEpoch || 0;
      const loading = req('/api/play/theme/state', 'GET', Object.assign({ topicId: this.data.topicId }, this._sessionParams()), null, true).then(res => {
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (res.code == 200 && res.data) {
          this._applyThemeView(res.data);
          if (this._routeState && String(this._routeState.sessionId) === String(res.data.sessionId) && this._routeState.version < res.data.version && !this._themeSending) this.refreshRouteState();
          if (this.data.chapterFull) this._enterThemeChapter();
          if (res.data.status !== 'ACTIVE') this._stopThemePoll(); else this._startThemePoll();
          if (res.data.stepEggs) { this._askWeRunOnce(); if (!this._themeStepBaseline) { this._themeStepBaseline = true; this._syncThoughts([]); } }
          return true;
        }
        // Older themes have no inventory configuration. Retain life HUD from journey rules.
        if (this.data.themeBackpackOpen) this.setData({ themeBagError: res.msg || '背包暂未加载，点击重试' });
        return false;
      }).finally(() => { if (this._themeLoading === loading) this._themeLoading = null; });
      this._themeLoading = loading;
      return loading;
    },
    openThemeBackpack() { this.setData({ themeBackpackOpen: true, themeBackpackTab: 'items', themeBagError: '' }); return this.refreshThemeBackpack(); },
    closeThemeBackpack() { this.setData({ themeBackpackOpen: false }); },
    changeThemeBackpackTab(e) { this.setData({ themeBackpackTab: e.detail.key }); },
    _themeCommand(operation, contentId) {
      if (this._themeSending) return Promise.resolve(false);
      if (this._themePending && (this._themePending.operation !== operation || this._themePending.contentId !== contentId)) { toast('请先重试上一条操作'); return Promise.resolve(false); }
      const view = this.data.themeBag;
      if (!view.sessionId || this.data.isPreview || this.data.isMock) return Promise.resolve(false);
      const command = this._themePending || { sessionId: view.sessionId, expectedVersion: view.version, actionId: 'bag_' + Date.now().toString(36) + Math.random().toString(36).slice(2), operation, contentId };
      this._themePending = command; this._themeSending = true;
      this.setData({ themeBagBusy: true, themeBagError: '' });
      const epoch = this._themeEpoch || 0;
      return req('/api/play/theme/action', 'POST', command, { 'content-type': 'application/json' }, true).then(res => {
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (res.code == 200 && res.data) {
          this._themePending = null; this.setData({ themeBagUnknown: false }); this._applyThemeView(res.data);
          return this.refreshThemeBackpack().then(() => this.refreshRouteState()).then(() => true);
        }
        if (res.netFail) this.setData({ themeBagUnknown: true, themeBagError: '操作结果未确认，点击重试' });
        else { this._themePending = null; this.setData({ themeBagUnknown: false, themeBagError: res.msg || '操作失败' }); return this.refreshThemeBackpack().then(() => false); }
        return false;
      }).finally(() => { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); });
    },
    onThemeBagAction(e) { const d = e.currentTarget.dataset; return this._themeCommand(d.operation, d.id); },
    retryThemeBagAction() { return this._themePending ? this._themeCommand(this._themePending.operation, this._themePending.contentId) : this.refreshThemeBackpack(); },
    _themeChapterIndex() {
      const chapterId = (this.data.chapter || {}).id;
      const binding = (this.data.themeBag.chapterBindings || []).find(c => String(c.chapterId) === String(chapterId));
      return binding ? binding.index : -1;
    },
    _endThemeChapter() {
      const index = this._themeChapterIndex();
      if ((this.data.nodes || []).some(n => String(n.chapterId) === String((this.data.chapter || {}).id) && !n.done)) return Promise.resolve(true);
      if (!(this.data.themeBag.chapterIndexes || []).includes(index)) return Promise.resolve(true);
      return this.refreshThemeBackpack().then(() => this._themeCommand('CHAPTER_END', String(index)));
    },
    _enterThemeChapter() {
      if (!this.data.themeBag.sessionId || this.data.isPreview || this.data.isMock) return;
      const index = this._themeChapterIndex();
      if (index < 0 || !(this.data.themeBag.chapterIndexes || []).includes(index) || this._themeChapterEntered === index || this._themeSending) return;
      this._themeChapterEntered = index;
      this._themeCommand('CHAPTER_START', String(index)).then(ok => { if (!ok) this._themeChapterEntered = null; });
    },
  };
  return bagMethods;
}
module.exports = { present, methods, data: { themeBag: { contents: [], items: [], clues: [], slots: [], pendingRows: [], used: 0, capacity: 6, hpEnabled: false }, themeBackpackOpen: false, themeBackpackTab: 'items', themeBagTabs: [{ key: 'items', label: '物品' }, { key: 'clues', label: '线索' }], themeBagBusy: false, themeBagUnknown: false, themeBagError: '' } };
