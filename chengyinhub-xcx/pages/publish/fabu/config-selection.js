'use strict';
const app = getApp();
const toast = require('../../../utils/toast.js');
const configDraft = require('../../../utils/publish/config-draft.js');
const serverDraft = require('../utils/publish/server-draft.js');
const localDraft = require('../../../utils/publish/pro-editor-draft.js');
const { sendUiStateRequest } = require('../../../utils/ui-state-request.js');
const data = {
  configPicker: { show: false, kind: '', status: 'idle', rows: [], error: '' },
  configSourceUsed: false, configSelectionEligible: false, configDraftNeedsReadback: false, configDraftSaving: false, configDraftError: '', configDraftUnknown: false, isDraft: false,
};
const methods = {
  _refreshConfigSource() {
    try { this.setData({ configSourceUsed: !!configDraft.sourceOf(this.data.formData.themeRuntimeConfig) }); }
    catch (error) { this.setData({ configSourceUsed: false, configDraftError: '配置来源无法读取，原草稿已保留' }); }
  },
  openConfigPicker(e) {
    if (!this.data.configSelectionEligible || this.data.editingTopicId || this.data.configDraftSaving || this.data.configDraftUnknown) return;
    const kind = e.currentTarget.dataset.kind;
    if (!['route', 'style', 'world'].includes(kind)) return;
    const epoch = this._configSelectionEpoch = (this._configSelectionEpoch || 0) + 1;
    const member = String(app.getUserID());
    const current = () => epoch === this._configSelectionEpoch && !this.data.editingTopicId && member === String(app.getUserID());
    this.setData({ configPicker: { show: true, kind, status: 'loading', rows: [], error: '' } });
    sendUiStateRequest(app, kind === 'world' ? '/api/world/home' : kind === 'route' ? '/api/preset/route/list' : '/api/preset/style/list', {
      method: kind === 'world' ? 'POST' : 'GET',
      success: res => {
        if (!current()) return;
        const rows = kind === 'world' ? res.data && res.data.worlds : res.data;
        if (res.code != 200 || !Array.isArray(rows)) { this.setData({ 'configPicker.status': 'error', 'configPicker.error': res.msg || '配置读取不完整' }); return; }
        this.setData({ 'configPicker.rows': rows.map(row => Object.assign({}, row, { label: row.name || row.worldName || '未命名配置' })), 'configPicker.status': rows.length ? 'ready' : 'empty' });
      },
      fail: () => { if (current()) this.setData({ 'configPicker.status': 'error', 'configPicker.error': '配置暂时无法读取，请重试' }); },
    });
  },
  retryConfigPicker() { this.openConfigPicker({ currentTarget: { dataset: { kind: this.data.configPicker.kind } } }); },
  closeConfigPicker() { this._configSelectionEpoch = (this._configSelectionEpoch || 0) + 1; this.setData({ 'configPicker.show': false }); },
  selectDraftConfig(e) {
    if (!this.data.configSelectionEligible || this.data.editingTopicId || this.data.configPicker.status !== 'ready') return;
    const kind = this.data.configPicker.kind, row = this.data.configPicker.rows.find(item => String(item.id) === String(e.currentTarget.dataset.id));
    if (!row) return;
    const epoch = this._configSelectionEpoch = (this._configSelectionEpoch || 0) + 1;
    const captured = JSON.stringify(this.data.formData), member = String(app.getUserID());
    const current = () => epoch === this._configSelectionEpoch && !this.data.editingTopicId && member === String(app.getUserID());
    const apply = source => {
      if (!current()) return;
      if (JSON.stringify(this.data.formData) !== captured) { this.setData({ 'configPicker.status': 'error', 'configPicker.error': '草稿已变动，请重新选用；当前内容已保留' }); return; }
      try {
        const next = configDraft.buildConfigDraft(this.data.formData, source, kind);
        this.setData({ formData: next, configSourceUsed: true, configDraftError: '', 'configPicker.show': false });
        this._ensureLocalIds(); this._materializeCityStoryChapters(); this.updateAllStatistics(); this._persistDraftEnvelope();
      } catch (error) { this.setData({ 'configPicker.status': 'error', 'configPicker.error': error.message }); }
    };
    if (kind === 'style') { apply(row); return; }
    this.setData({ 'configPicker.status': 'loading' });
    sendUiStateRequest(app, kind === 'route' ? '/api/preset/route/info' : '/api/world/home', { method: kind === 'route' ? 'GET' : 'POST',
      data: kind === 'route' ? { id: row.id } : { world_id: row.id },
      success: res => {
        if (!current()) return;
        const sourceId = kind === 'route' ? res.data && res.data.id : res.data && res.data.world && res.data.world.id;
        if (res.code != 200 || String(sourceId) !== String(row.id)) { this.setData({ 'configPicker.status': 'error', 'configPicker.error': '配置编号或内容不一致，请重试' }); return; }
        apply(res.data);
      },
      fail: () => { if (current()) this.setData({ 'configPicker.status': 'error', 'configPicker.error': '配置读取失败，草稿未改动' }); },
    });
  },
  saveConfigDraft() {
    if (!this.data.configSourceUsed || (this.data.editingTopicId && !this.data.isDraft && !this.data.configDraftNeedsReadback) || this.data.configDraftSaving || this.data.configDraftUnknown) return;
    let payload;
    try { payload = this._doSubmit({ draft: true }); }
    catch (error) { this.setData({ configDraftError: '草稿配置无法读取，原内容已保留' }); return; }
    if (!payload) return;
    this._saveConfigPayload(payload);
  },
  _saveConfigPayload(payload, publish) {
    if (this.data.configDraftSaving || this.data.configDraftUnknown || (this.data.editingTopicId && !this.data.isDraft && !this.data.configDraftNeedsReadback)) return;
    if (!String(payload.name || '').trim()) { toast('请先填写主题名称'); return; }
    if (!this._persistDraftEnvelope()) return;
    const retry = this._configDraftWorkflow && this._configDraftWorkflow.getState() === 'readbackFailed';
    const captured = retry ? this._configSavedFormData : JSON.stringify(this.data.formData);
    const member = String(app.getUserID());
    if (!member || member === '0' || (this.data.draftMemberId && String(this.data.draftMemberId) !== member)
        || (this._configDraftMemberId && this._configDraftMemberId !== member)) {
      this.setData({ configDraftError: '登录账号已变动，请用原账号核对草稿' }); return;
    }
    this._configDraftMemberId = member;
    const pending = retry ? this._configSavedPending : JSON.parse(JSON.stringify(this.data.pendingMaterials));
    if (!retry) { this._configSavedFormData = captured; this._configSavedPending = pending; }
    if (!this._configDraftWorkflow) this._configDraftWorkflow = serverDraft.createServerDraftWorkflow((url, body, json, callback) => {
      sendUiStateRequest(app, url, { method: 'POST', data: json ? JSON.stringify(body) : body,
        header: json ? { 'Content-Type': 'application/json' } : undefined,
        success: res => { if (member === String(app.getUserID())) callback(res); },
        fail: () => { if (member === String(app.getUserID())) callback({ code: 0, networkError: true }); },
      });
    }, this.data.editingTopicId);
    const newDraftUuid = this.data.draftUuid;
    const handlers = {
      beforeSave: () => {
        this.setData({ configDraftSaving: true, configDraftError: '' });
        if (this._persistDraftEnvelope()) return true;
        this.setData({ configDraftSaving: false }); return false;
      },
      receipt: id => {
        this.setData({ editingTopicId: String(id), editLoaded: true, configDraftNeedsReadback: true });
        this._persistDraftEnvelope();
      },
      onSuccess: detail => {
        this.setData({ configDraftSaving: false });
        if (JSON.stringify(this.data.formData) !== captured) {
          this.setData({ configDraftError: '保存期间草稿已变动，本机内容已保留；请从我的项目核对已保存版本', configDraftUnknown: true });
          this._persistDraftEnvelope(); return;
        }
        this.applyEditingTopic(detail, { savedReadback: true });
        this.setData({ pendingMaterials: pending, configDraftError: '', configDraftUnknown: false, configDraftNeedsReadback: false, isDraft: true });
        const persisted = this._persistDraftEnvelope();
        if (!persisted) { this.setData({ configDraftError: '草稿已核对，本机存储失败，请从我的项目重开', configDraftUnknown: true }); return; }
        if (newDraftUuid) localDraft.removeDraft(wx, { draftUuid: newDraftUuid }, { memberId: member });
        if (publish) this.sendData(Object.assign({}, payload, { _configDraftId: detail.topic.id }));
        else toast('草稿已保存并核对');
      },
      onFail: message => {
        this.setData({ configDraftSaving: false, configDraftError: message, configDraftUnknown: this._configDraftWorkflow.getState() === 'unknownCreate', configDraftNeedsReadback: this._configDraftWorkflow.getState() === 'readbackFailed' });
        this._persistDraftEnvelope();
      },
    };
    const started = this._configDraftWorkflow.getState() === 'readbackFailed'
      ? this._configDraftWorkflow.retryReadback(handlers) : this._configDraftWorkflow.save(payload, handlers);
    if (started && this._configDraftWorkflow.getState() === 'reading') this.setData({ configDraftSaving: true, configDraftError: '' });
  },
  onConfigPreviewCoverError() { this.setData({ 'publishCheck.preview.cover': '' }); },
};
module.exports = { data, methods };
