'use strict';
// 角色(Cast)编辑 —— docs/design/cast-contract-1119.md(业主 2026-11-19 批准 A + B + D2)。
// 数据只有一份:formData.castConfig(界面形状,见 utils/publish/story-cast.js);setData 'formData.castConfig'
// 就走现有的本地草稿 / 云端自动保存。关掉开关只置 enabled=false,角色与台词、信任、归属都留着,只是不露面。
const storyCast = require('../utils/publish/story-cast.js');
const cyToast = require('../../../utils/toast.js');
const modal = require('../../../utils/modal.js');

const STORY_LINE_ITEM = { kind: 'line', label: '对白', icon: 'comment' };
const REC_MAX_MS = 60000;
const VOICE_CHIPS = storyCast.PRESETS.map((p) => ({ value: p.value, label: p.label })).concat([{ value: 'none', label: '不出声' }]);
// 成员弹窗里各字段的锚点(问题 field → scroll-into-view 的 id)
const MEMBER_ANCHOR = { name: 'cm-name', identity: 'cm-identity', avatar: 'cm-avatar', color: 'cm-color', voice: 'cm-voice', clues: 'cm-clues', ending_low: 'cm-ending', ending_mid: 'cm-ending', ending_high: 'cm-ending' };

function clone(v) { return v == null ? v : JSON.parse(JSON.stringify(v)); }
function anchorOf(field) { return MEMBER_ANCHOR[field] || (/^clue\d+$/.test(field) ? 'cm-' + field : 'cm-name'); }
// TTS 没配置:服务端回 code=503 + data.errorCode=CAST_TTS_NOT_CONFIGURED(#873)。编辑器只把「试听」收起来,不弹错(契约 Voice 一节)。
function ttsNotConfigured(res) {
  const data = (res && res.data) || {};
  return data.errorCode === 'CAST_TTS_NOT_CONFIGURED' || Number(res && res.code) === 503;
}

const data = {
  castCard: { on: false, count: 0, faces: [] },
  castOn: false,
  castSheetShow: false,
  castView: { list: [], playerName: false, playerIdentity: false, identities: [], atMax: false, idAtMax: false },
  castError: { field: '', message: '' },
  castEdit: { show: false, isNew: false, m: null, initial: '', errField: '', errMsg: '', scrollTo: '', recording: false, avatarPreview: '', clueLabels: [], atClueMax: false },
  castVoiceChips: VOICE_CHIPS,
  castColors: storyCast.COLORS,
  castClueOptions: [],
  castTtsOff: false,
  castPreviewing: '',
  castRowMenu: { show: false, id: '', items: [] },
  storySpeakers: [],
  storySpeakerNameOf: {},
  storyLineKey: '',
  storyLineRec: '',
  storyBranchTrust: [],
  chapterEndingCastOptions: [],
  chapterEndingCastIndex: 0,
};

const methods = {
  _castConfig() { return (this.data.formData && this.data.formData.castConfig) || null; },

  // 所有派生视图一处算:首页卡、列表、谁在说话、添加栏。updateAllStatistics 每次都调,回填 / 恢复 / 撤销都覆盖到。
  _refreshCastViews(cfgArg) {
    const cfg = cfgArg === undefined ? this._castConfig() : cfgArg;
    // 打字时每次都会走到这里:配置没变就不重算、不 setData
    const memo = JSON.stringify(cfg) + '|' + (this.data.storyAddItems || []).length;
    if (memo === this._castViewMemo) return;
    this._castViewMemo = memo;
    const on = storyCast.isEnabled(cfg);
    const list = storyCast.listView(cfg);
    const items = this.data.storyAddItems || [];
    const hasLine = items.some((i) => i.kind === 'line');
    const patch = {
      castCard: storyCast.cardView(cfg), castOn: on,
      castView: {
        list, playerName: !!(cfg && cfg.playerName), playerIdentity: !!(cfg && cfg.playerIdentity),
        identities: (cfg && cfg.identities) || [], atMax: list.length >= storyCast.MAX_CAST,
        idAtMax: ((cfg && cfg.identities) || []).length >= storyCast.MAX_IDENTITIES,
      },
      storySpeakers: storyCast.speakerOptions(cfg), storySpeakerNameOf: storyCast.speakerNameMap(cfg),
    };
    // 添加栏:「对白」紧跟「段落」,只在开关开着时出现
    if (on && !hasLine) {
      const at = Math.max(0, items.findIndex((i) => i.kind === 'text')) + 1;
      patch.storyAddItems = items.slice(0, at).concat([STORY_LINE_ITEM], items.slice(at));
    } else if (!on && hasLine) {
      patch.storyAddItems = items.filter((i) => i.kind !== 'line');
    }
    this.setData(patch);
  },

  _writeCast(cfg, extra) {
    this.setData(Object.assign({ 'formData.castConfig': cfg }, extra || {}));
    this._refreshCastViews(cfg);
  },

  // ── 主题首页「角色」卡 ──
  onCastSwitch(e) {
    const on = !!(e && e.detail && e.detail.value);
    const cfg = clone(this._castConfig()) || (on ? storyCast.emptyCastConfig() : null);
    if (!cfg) return;
    cfg.enabled = on;
    this._writeCast(cfg);
  },
  openCastSheet() {
    if (!this._castConfig()) this._writeCast(storyCast.emptyCastConfig());
    this.setData({ castSheetShow: true, castError: { field: '', message: '' } });
  },
  // 「完成」:玩家身份开着时先把身份清单过一遍,有问题就停在那一行红字(#871)
  doneCastSheet() {
    const cfg = this._castConfig();
    if (cfg && cfg.playerIdentity) {
      const issue = storyCast.identityIssues(cfg.identities)[0]
        || (!(cfg.identities || []).length ? { field: 'identities', message: '打开了玩家身份，至少加一个身份' } : null);
      if (issue) { this.setData({ castError: { field: issue.field, message: issue.message } }); return; }
    }
    this.closeCastSheet();
  },
  closeCastSheet() { this.setData({ castSheetShow: false, castError: { field: '', message: '' }, 'castRowMenu.show': false }); },

  // ── 列表:玩家起名 / 玩家身份 / 身份清单 ──
  onCastPlayerSwitch(e) {
    const field = e.currentTarget.dataset.field;
    if (field !== 'playerName' && field !== 'playerIdentity') return;
    const cfg = clone(this._castConfig()) || storyCast.emptyCastConfig();
    cfg[field] = !!(e.detail && e.detail.value);
    this._writeCast(cfg, { castError: { field: '', message: '' } });
  },
  addCastIdentity() {
    const cfg = clone(this._castConfig()) || storyCast.emptyCastConfig();
    if ((cfg.identities || []).length >= storyCast.MAX_IDENTITIES) {
      this.setData({ castError: { field: 'identities', message: '玩家身份最多 ' + storyCast.MAX_IDENTITIES + ' 个' } });
      return;
    }
    cfg.identities = (cfg.identities || []).concat([storyCast.newIdentity(cfg)]);
    this._writeCast(cfg, { castError: { field: '', message: '' } });
  },
  onCastIdentityInput(e) {
    const i = Number(e.currentTarget.dataset.i);
    const field = e.currentTarget.dataset.field === 'sub' ? 'sub' : 'title';
    const cfg = clone(this._castConfig());
    if (!cfg || !cfg.identities || !cfg.identities[i]) return;
    cfg.identities[i][field] = e.detail.value;
    this._writeCast(cfg, this.data.castError.field === 'identity' + i ? { castError: { field: '', message: '' } } : null);
  },
  removeCastIdentity(e) {
    const i = Number(e.currentTarget.dataset.i);
    const cfg = clone(this._castConfig());
    if (!cfg || !cfg.identities || !cfg.identities[i]) return;
    cfg.identities.splice(i, 1);
    this._writeCast(cfg, { castError: { field: '', message: '' } });
  },

  // ── 列表:角色行(点开编辑;右侧 ⋯ = 上移 / 下移 / 删除)──
  addCastMember() {
    const cfg = this._castConfig() || storyCast.emptyCastConfig();
    if ((cfg.cast || []).length >= storyCast.MAX_CAST) {
      this.setData({ castError: { field: 'add', message: '角色最多 ' + storyCast.MAX_CAST + ' 位' } });
      return;
    }
    this._openMember(storyCast.newMember(cfg), true);
  },
  editCastMember(e) {
    const id = e.currentTarget.dataset.id;
    const hit = ((this._castConfig() || {}).cast || []).find((m) => m.id === id);
    if (hit) this._openMember(clone(hit), false);
  },
  openCastRowMenu(e) {
    const id = e.currentTarget.dataset.id;
    const cast = (this._castConfig() || {}).cast || [];
    const at = cast.findIndex((m) => m.id === id);
    if (at < 0) return;
    const values = [];
    if (at > 0) values.push('up');
    if (at < cast.length - 1) values.push('down');
    values.push('delete');
    this._castRowMenuValues = values;
    const labels = { up: '上移', down: '下移', delete: '删除' };
    this.setData({ castRowMenu: { show: true, id, items: values.map((v) => ({ label: labels[v], danger: v === 'delete' })) } });
  },
  closeCastRowMenu() { this.setData({ 'castRowMenu.show': false }); },
  onCastRowMenu(e) {
    const value = (this._castRowMenuValues || [])[Number(e && e.detail && e.detail.index)];
    const id = this.data.castRowMenu.id;
    this.setData({ 'castRowMenu.show': false });
    if (value === 'delete') { this._confirmRemoveMember(id); return; }
    const cfg = clone(this._castConfig());
    const at = cfg ? cfg.cast.findIndex((m) => m.id === id) : -1;
    const to = value === 'up' ? at - 1 : value === 'down' ? at + 1 : -1;
    if (at < 0 || to < 0 || to >= cfg.cast.length) return;
    const moved = cfg.cast.splice(at, 1)[0];
    cfg.cast.splice(to, 0, moved);
    this._writeCast(cfg);
  },
  _confirmRemoveMember(id, after) {
    const member = ((this._castConfig() || {}).cast || []).find((m) => m.id === id);
    if (!member) return;
    modal.show({
      title: '删除「' + (String(member.name || '').trim() || '未命名角色') + '」？',
      content: '这位说过的对白会变成没有名字的段落。',
      success: (res) => {
        if (!res.confirm) return;
        const cfg = clone(this._castConfig());
        cfg.cast = cfg.cast.filter((m) => m.id !== id);
        this._writeCast(cfg);
        if (typeof after === 'function') after();
      },
    });
  },

  // ── 编辑角色弹窗 ──
  _openMember(member, isNew) {
    const options = storyCast.clueChapterOptions(this.data.formData.chapters);
    this.setData({
      castClueOptions: options,
      castEdit: Object.assign({}, this.data.castEdit, {
        show: true, isNew, m: member, errField: '', errMsg: '', scrollTo: '', recording: false, initial: storyCast.initialOf(member.name),
        avatarPreview: (this._castAvatarPreview || {})[member.id] || '',
        clueLabels: (member.clues || []).map((c) => storyCast.clueChapterLabel(options, c.chapterId)),
        atClueMax: (member.clues || []).length >= storyCast.MAX_CLUES,
      }),
    });
  },
  _patchMember(mutate, clearField) {
    const m = clone(this.data.castEdit.m);
    if (!m) return;
    mutate(m);
    const patch = {
      'castEdit.m': m,
      'castEdit.initial': storyCast.initialOf(m.name),
      'castEdit.clueLabels': (m.clues || []).map((c) => storyCast.clueChapterLabel(this.data.castClueOptions, c.chapterId)),
      'castEdit.atClueMax': (m.clues || []).length >= storyCast.MAX_CLUES,
    };
    if (clearField && (this.data.castEdit.errField === clearField || clearField === '*')) Object.assign(patch, { 'castEdit.errField': '', 'castEdit.errMsg': '' });
    this.setData(patch);
  },
  closeCastMember() {
    this._stopCastRecording(true);
    this._stopCastPreview();
    this.setData({ 'castEdit.show': false, 'castEdit.recording': false });
  },
  onCastMemberInput(e) {
    const field = e.currentTarget.dataset.field;
    if (field !== 'name' && field !== 'identity') return;
    this._patchMember((m) => { m[field] = e.detail.value; }, field);
  },
  onCastColor(e) {
    const color = e.currentTarget.dataset.color;
    if (storyCast.COLORS.indexOf(color) < 0) return;
    this._patchMember((m) => { m.color = color; }, 'color');
  },
  onCastVoice(e) {
    const value = e.currentTarget.dataset.value;
    this._patchMember((m) => {
      m.voice = value === 'none' ? { mode: 'none' } : { mode: 'preset', preset: value };
    }, 'voice');
  },
  pickCastAvatar() {
    const app = getApp();
    const edit = this.data.castEdit;
    if (!edit.show || !edit.m) return;
    const id = edit.m.id;
    const token = this._castAvatarToken = (this._castAvatarToken || 0) + 1;
    const current = () => !this._unloaded && this.data.castEdit.show && this.data.castEdit.m && this.data.castEdit.m.id === id && token === this._castAvatarToken;
    wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'],
      success: (res) => {
        const file = res.tempFiles && res.tempFiles[0];
        if (!current() || !file) return;
        app.getUploadClient().uploadOne(file.tempFilePath, { fileSize: file.size, formData: { fileName: 'avatar.jpg', fileType: 'jpg' }, onDone: (up) => {
          if (!current()) return;
          if (!up.ok || !up.data || !up.data.assetId) {
            this.setData({ 'castEdit.errField': 'avatar', 'castEdit.errMsg': up.msg || '头像上传失败，请重试', 'castEdit.scrollTo': 'cm-avatar' });
            return;
          }
          this._castAvatarPreview = Object.assign({}, this._castAvatarPreview, { [id]: file.tempFilePath });
          this.setData({ 'castEdit.avatarPreview': file.tempFilePath });
          this._patchMember((m) => { m.avatarUrl = 'media-asset:' + up.data.assetId; }, 'avatar');
        } });
      },
      fail: (err) => { if (current() && !/cancel/.test((err && err.errMsg) || '')) this.setData({ 'castEdit.errField': 'avatar', 'castEdit.errMsg': '无法选择图片，请重试' }); },
    });
  },

  // 试听:拿预设声音念一句示例。TTS 没配置就把「试听」收起来,不弹错。
  previewCastVoice() {
    const app = getApp();
    const m = this.data.castEdit.m;
    if (!m || !m.voice || m.voice.mode !== 'preset' || this.data.castTtsOff) return;
    this._stopCastPreview();
    const preset = m.voice.preset;
    const token = this._castPreviewToken = (this._castPreviewToken || 0) + 1;
    this.setData({ castPreviewing: preset });
    app.sendRequest({
      url: '/api/topic/cast/voice', method: 'GET', hideLoading: true, autoErrorToast: false,
      data: { topicId: this.data.editingTopicId || '', preset },   // 试听念服务端固定的示例句(CastVoicePresets.SAMPLE_LINE),不上传文本
      success: (res) => {
        if (this._unloaded || token !== this._castPreviewToken) return;
        const url = res && Number(res.code) === 200 && res.data && res.data.url;
        if (!url) {
          if (ttsNotConfigured(res)) this.setData({ castTtsOff: true, castPreviewing: '' });
          else { this.setData({ castPreviewing: '' }); cyToast((res && res.msg) || '试听没有成功，请重试'); }
          return;
        }
        this._playCastUrl(url, token);
      },
      fail: () => { if (!this._unloaded && token === this._castPreviewToken) { this.setData({ castPreviewing: '' }); cyToast('试听没有成功，请重试'); } },
    });
  },
  _playCastUrl(url, token) {
    const audio = this._castAudio = wx.createInnerAudioContext();
    audio.src = url;
    const done = () => { if (token === this._castPreviewToken && !this._unloaded) this.setData({ castPreviewing: '' }); };
    audio.onEnded(done); audio.onError(done);
    audio.play();
  },
  _stopCastPreview() {
    this._castPreviewToken = (this._castPreviewToken || 0) + 1;
    if (this._castAudio) { try { this._castAudio.destroy(); } catch (e) { /* 已销毁 */ } }
    this._castAudio = null;
    if (this.data.castPreviewing) this.setData({ castPreviewing: '' });
  },

  // ── 录音(角色声音 / 单句对白共用一台 recorder)──
  _castRecorder() {
    if (this._castRec) return this._castRec;
    if (typeof wx.getRecorderManager !== 'function') return null;
    const rec = wx.getRecorderManager();
    rec.onStop((res) => this._onCastRecStop(res));
    rec.onError(() => {
      const target = this._castRecTarget;
      this._castRecTarget = null;
      if (this._unloaded || !target) return;
      this.setData({ 'castEdit.recording': false, storyLineRec: '' });
      cyToast('录音没有开始，请检查麦克风权限');
    });
    this._castRec = rec;
    return rec;
  },
  _startCastRecording(target) {
    const rec = this._castRecorder();
    if (!rec) { cyToast('当前微信版本不支持录音'); return false; }
    this._castRecTarget = target;
    rec.start({ duration: REC_MAX_MS, format: 'mp3', sampleRate: 44100, numberOfChannels: 1, encodeBitRate: 96000 });
    return true;
  },
  _stopCastRecording(discard) {
    if (!this._castRecTarget) return;
    if (discard) this._castRecTarget = Object.assign({}, this._castRecTarget, { discard: true });
    try { this._castRec && this._castRec.stop(); } catch (e) { /* 没在录 */ }
  },
  _onCastRecStop(res) {
    const target = this._castRecTarget;
    this._castRecTarget = null;
    if (this._unloaded || !target) return;
    this.setData({ 'castEdit.recording': false, storyLineRec: '' });
    if (target.discard) return;
    if (!res || !res.tempFilePath || (Number(res.duration) || 0) < 500) { cyToast('录音太短，请再录一次'); return; }
    getApp().getUploadClient().uploadOne(res.tempFilePath, { fileSize: res.fileSize, timeoutMs: 60000,
      formData: { fileName: 'voice.mp3', fileType: 'mp3' }, onDone: (up) => {
        if (this._unloaded) return;
        if (!up.ok || !up.data || !up.data.assetId) { cyToast(up.msg || '录音上传失败，请重试'); return; }
        const url = 'media-asset:' + up.data.assetId;
        if (target.kind === 'member') {
          const m = this.data.castEdit.m;
          if (this.data.castEdit.show && m && m.id === target.id) this._patchMember((x) => { x.voice = { mode: 'record', url }; }, 'voice');
        } else if (target.kind === 'line') {
          const chapter = this._storyChapter();
          if (!chapter || chapter._localId !== target.chapterLocalId) return;
          try { this._storyCommand({ type: 'updateLine', blockKey: target.blockKey, audioUrl: url }); }
          catch (error) { cyToast(getApp().getRequestErrorMessage(error, '保存录音失败')); }
        }
      } });
  },
  toggleCastRecord() {
    const m = this.data.castEdit.m;
    if (!m) return;
    if (this.data.castEdit.recording) { this._stopCastRecording(false); return; }
    this._stopCastPreview();
    if (this._startCastRecording({ kind: 'member', id: m.id })) this.setData({ 'castEdit.recording': true });
  },

  // ── 你知道的(线索)/ 结局台词 ──
  addCastClue() {
    const m = this.data.castEdit.m;
    if (!m) return;
    if ((m.clues || []).length >= storyCast.MAX_CLUES) {
      this.setData({ 'castEdit.errField': 'clues', 'castEdit.errMsg': '「你知道的」最多 ' + storyCast.MAX_CLUES + ' 条', 'castEdit.scrollTo': 'cm-clues' });
      return;
    }
    this._patchMember((x) => { x.clues = (x.clues || []).concat([{ text: '', chapterId: null }]); }, 'clues');
  },
  onCastClueInput(e) {
    const i = Number(e.currentTarget.dataset.i);
    this._patchMember((x) => { if (x.clues[i]) x.clues[i].text = e.detail.value; }, 'clue' + i);
  },
  onCastClueChapter(e) {
    const i = Number(e.currentTarget.dataset.i);
    const opt = this.data.castClueOptions[Number(e.detail.value)];
    if (!opt) return;
    this._patchMember((x) => { if (x.clues[i]) x.clues[i].chapterId = opt.value; }, 'clue' + i);
  },
  removeCastClue(e) {
    const i = Number(e.currentTarget.dataset.i);
    this._patchMember((x) => { x.clues.splice(i, 1); }, '*');
  },
  onCastEndingLine(e) {
    const band = e.currentTarget.dataset.band;
    if (['low', 'mid', 'high'].indexOf(band) < 0) return;
    this._patchMember((x) => { x.endingLines = Object.assign({}, x.endingLines, { [band]: e.detail.value }); }, 'ending_' + band);
  },

  // 完成:有问题就滚到那一栏、红字说清楚(同 #871);没问题才写回清单
  saveCastMember() {
    const edit = this.data.castEdit;
    const m = edit.m;
    if (!m) return;
    const cfg = clone(this._castConfig()) || storyCast.emptyCastConfig();
    const chapterIds = (this.data.formData.chapters || []).filter((c) => c && c.id != null && c.id !== '').map((c) => c.id);
    const issues = storyCast.memberIssues(m, cfg.cast, chapterIds);
    if (issues.length) {
      this.setData({ 'castEdit.errField': issues[0].field, 'castEdit.errMsg': issues[0].message, 'castEdit.scrollTo': '' },
        () => this.setData({ 'castEdit.scrollTo': anchorOf(issues[0].field) }));
      return;
    }
    const at = cfg.cast.findIndex((x) => x.id === m.id);
    if (at >= 0) cfg.cast[at] = m;
    else {
      if (cfg.cast.length >= storyCast.MAX_CAST) { this.setData({ 'castEdit.errField': 'name', 'castEdit.errMsg': '角色最多 ' + storyCast.MAX_CAST + ' 位' }); return; }
      cfg.cast.push(m);
    }
    this._stopCastRecording(true);
    this._stopCastPreview();
    this._writeCast(cfg, { 'castEdit.show': false, castError: { field: '', message: '' } });
  },
  deleteCastMember() {
    const m = this.data.castEdit.m;
    if (!m) return;
    if (this.data.castEdit.isNew) { this.closeCastMember(); return; }
    this._confirmRemoveMember(m.id, () => this.closeCastMember());
  },

  // ── 故事编辑器:「谁在说话」 ──
  _openSpeakerSheet(blockKey) {
    this.setData({ storySheet: 'speaker', storyLineKey: blockKey || '' });
  },
  onStoryLineSpeakerTap(e) {
    if (this.data.storyEditor.preview || !this.data.castOn) return;
    this._openSpeakerSheet(e.currentTarget.dataset.blockkey);
  },
  onStorySpeakerPick(e) {
    const value = e.currentTarget.dataset.value;
    const blockKey = this.data.storyLineKey;
    this.setData({ storySheet: '', storyLineKey: '' });
    if (blockKey) {
      try { this._pushUndo(); this._storyCommand({ type: 'updateLine', blockKey, speaker: value }); }
      catch (error) { cyToast(getApp().getRequestErrorMessage(error, '换说话的人失败')); }
      return;
    }
    const index = Number.isInteger(this._storyAddAt) ? this._storyAddAt : (((this._storyChapter() || {}).blocks) || []).length;
    this._storyAddAt = null;
    this._pushUndo();
    if (value === 'narrator') {
      this.insertStoryTextAt({ currentTarget: { dataset: { index } } });
      this._storyUndoBar('已添加段落');
      return;
    }
    if (this._insertStoryMedia('line', index, { speaker: value })) this._storyUndoBar('已添加对白');
  },
  onStoryLineInput(e) {
    try { this._storyCommand({ type: 'updateLine', blockKey: e.currentTarget.dataset.blockkey, content: e.detail.value }); }
    catch (error) { cyToast(getApp().getRequestErrorMessage(error, '修改失败')); }
  },
  toggleStoryLineRecord(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    if (this.data.storyLineRec) { this._stopCastRecording(this.data.storyLineRec !== blockKey); return; }
    const chapter = this._storyChapter();
    if (!chapter) return;
    if (this._startCastRecording({ kind: 'line', blockKey, chapterLocalId: chapter._localId })) this.setData({ storyLineRec: blockKey });
  },
  clearStoryLineAudio(e) {
    try { this._storyCommand({ type: 'updateLine', blockKey: e.currentTarget.dataset.blockkey, audioUrl: '' }); }
    catch (error) { cyToast(getApp().getRequestErrorMessage(error, '修改失败')); }
  },

  // ── 分支选项的「信任」(D2):每个角色一个 ±stepper,0 收起 ──
  _refreshBranchTrust(options) {
    const cfg = this._castConfig();
    this.setData({ storyBranchTrust: (options || []).map((opt) => storyCast.trustRows(cfg, opt && opt.trust)) });
  },
  onStoryBranchTrust(e) {
    const i = Number(e.currentTarget.dataset.i);
    const castId = e.currentTarget.dataset.cast;
    const opt = (this.data.storyBranch.options || [])[i];
    if (!opt) return;
    const order = ((this._castConfig() || {}).cast || []).map((m) => m.id);
    const value = e.detail && e.detail.value != null ? Number(e.detail.value) : Number(e.currentTarget.dataset.v);
    this._storyBranchCommand({ option: { index: i, trust: storyCast.setTrust(opt.trust, castId, value, order) } });
  },

  // ── 结局章「归属角色」(D2)──
  _syncChapterEndingCast(ending) {
    const options = storyCast.endingForOptions(this._castConfig());
    const at = ending && ending.kind === 'cast' ? options.findIndex((o) => o.value === ending.endingFor) : -1;
    this.setData({ chapterEndingCastOptions: options, chapterEndingCastIndex: Math.max(0, at) });
  },
  onChapterEndingCast(e) {
    const opt = this.data.chapterEndingCastOptions[Number(e.detail.value)];
    if (!opt) return;
    const ending = opt.value ? { fallback: false, kind: 'cast', endingFor: opt.value }
      : { fallback: false, kind: 'thought', thoughtKey: '', thoughtName: '', tag: '', tagLabel: '', nodeId: null, nodeName: '' };
    const kinds = this.data.chapterEndingKinds || [];
    this.setData({ 'chapterForm.ending': ending, chapterEndingCastIndex: Number(e.detail.value),
      chapterEndingKindIndex: Math.max(0, kinds.findIndex((k) => k.key === ending.kind)), chapterEndingPickIndex: -1 },
    () => this._refreshChapterEndingError());
  },

  _castCleanup() {
    this._stopCastRecording(true);
    this._stopCastPreview();
  },
};

// 保存闸:上送前把整份配置过一遍,第一处问题打开角色弹窗并定位(与 #871 同一条「点了告诉你缺什么」)
// 草稿 / 自动保存:写了一半的不带、带不了就整份不带(服务端不改这一列),不拦整份主题的保存
function payloadOf(page, draft) {
  const cfg = page._castConfig();
  if (!cfg) return null;
  const chapterIds = (page.data.formData.chapters || []).filter((c) => c && c.id != null && c.id !== '').map((c) => c.id);
  return storyCast.serializeCastConfig(cfg, chapterIds, { draft: !!draft });
}

module.exports = { data, methods, payloadOf, STORY_LINE_ITEM };
