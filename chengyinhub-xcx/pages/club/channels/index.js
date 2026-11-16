// C1 channels-structure(2026-11-15):俱乐部频道。成员看到能看的频道(公告频道只读);
// 主理人/管理员还能新建频道或分类、改名/话题/慢速、归档、调整顺序、在分类之间移动。
// 权限只由服务端判定:管理清单接口对非管理员返回 403,页面据此只显示成员视图。
const app = getApp();
const toast = require('../../../utils/toast.js');
const modal = require('../../../utils/modal.js');
const ch = require('../../../utils/club-channels.js');

const SLOWMODE_OPTIONS = [
  { label: '关闭', value: 0 },
  { label: '5 秒', value: 5 },
  { label: '30 秒', value: 30 },
  { label: '1 分钟', value: 60 },
  { label: '5 分钟', value: 300 },
  { label: '1 小时', value: 3600 }
];
const KIND_OPTIONS = [
  { label: '文字频道', value: ch.KIND_TEXT },
  { label: '公告频道(仅主理人和管理员发言)', value: ch.KIND_ANNOUNCEMENT },
  { label: '分类', value: ch.KIND_CATEGORY }
];

function ok(res) {
  return res && (res.code === 200 || res.code === '200');
}

function slowmodeLabel(sec) {
  const hit = SLOWMODE_OPTIONS.find((o) => o.value === Number(sec || 0));
  return hit ? hit.label : Number(sec || 0) + ' 秒';
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight || 20,
    navBarHeight: app.globalData.navBarHeight || 44,
    clubId: '',
    state: 'loading', // loading | ready | error | no-access | invalid
    errMsg: '',
    isManager: false,
    sections: [],
    reordering: false,
    busy: false,
    editorShow: false,
    editor: null, // { mode: 'create'|'edit', id, version, name, topic, kind, kindLabel, parentId, parentLabel, slowmodeSec, slowmodeLabel }
    pickerShow: false,
    pickerTitle: '',
    pickerItems: []
  },

  onLoad(options) {
    const clubId = options && /^[1-9][0-9]{0,18}$/.test(String(options.clubId || '')) ? String(options.clubId) : '';
    if (!clubId) { this.setData({ state: 'invalid', errMsg: '缺少俱乐部信息' }); return; }
    this.setData({ clubId });
    this.load();
  },

  onPullDownRefresh() {
    this.load(() => wx.stopPullDownRefresh());
  },

  load(done) {
    const epoch = (this._epoch = (this._epoch || 0) + 1);
    const finish = () => { if (typeof done === 'function') done(); };
    const base = '/api/guilds/' + ch.GUILD_CLUB + '/' + this.data.clubId + '/channels';
    if (!this.data.sections.length) this.setData({ state: 'loading' });
    app.sendRequest({
      url: base + '/manage', method: 'GET', hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (epoch !== this._epoch) return;
        if (ok(res) && Array.isArray(res.data)) {
          this._inventory = res.data;
          this.setData({ isManager: true, sections: ch.manageSections(res.data), state: 'ready', errMsg: '' });
          finish();
          return;
        }
        this.loadDirectory(epoch, finish);
      },
      successStatusAbnormal: () => { if (epoch === this._epoch) this.loadDirectory(epoch, finish); },
      fail: () => { if (epoch === this._epoch) this.loadDirectory(epoch, finish); }
    });
  },

  loadDirectory(epoch, finish) {
    const url = '/api/guilds/' + ch.GUILD_CLUB + '/' + this.data.clubId + '/channels';
    app.sendRequest({
      url: url, method: 'GET', data: { with_unread: '1' }, hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (epoch !== this._epoch) return;
        if (ok(res) && res.data) {
          this._inventory = null;
          this.setData({ isManager: false, reordering: false, sections: ch.directorySections(res.data), state: 'ready', errMsg: '' });
        } else {
          this.setData({ state: 'error', errMsg: ch.errorText(res, '频道加载失败') });
        }
        finish();
      },
      successStatusAbnormal: (body, status) => {
        if (epoch !== this._epoch) return;
        const denied = Number(status) === 403 || Number(status) === 404;
        this.setData({ state: denied ? 'no-access' : 'error', errMsg: ch.errorText(body, '频道加载失败') });
        finish();
      },
      fail: () => {
        if (epoch !== this._epoch) return;
        this.setData({ state: 'error', errMsg: '网络异常，请重试' });
        finish();
      }
    });
  },

  onRetry() { this.load(); },

  // 邀请:复用俱乐部分享路径 /pages/club/detail/index?id=,不另造频道邀请链接。
  onShareAppMessage() {
    const id = this.data.clubId;
    return { title: '邀请你加入我们的俱乐部', path: id ? '/pages/club/detail/index?id=' + id : '/pages/talent/list/index' };
  },

  onBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.redirectTo({ url: '/pages/club/detail/index?id=' + this.data.clubId });
  },

  // ---------------------------------------------------------------- open a channel

  onOpenChannel(e) {
    const { id, name } = e.currentTarget.dataset;
    if (this.data.reordering || !id) return;
    if (this._navigating) return;
    this._navigating = true;
    wx.navigateTo({
      // SOC-10:频道按群打开(type=5),不是 1:1 私信:举报/拉黑针对具体消息的作者,输入栏按频道权限。
      url: '/subpackageB/pages/im/chat/index?conversationId=' + id + '&type=5&name=' + encodeURIComponent(name || ''),
      complete: () => { this._navigating = false; }
    });
  },

  // ---------------------------------------------------------------- writes

  write(options, successText) {
    if (this.data.busy) return;
    this.setData({ busy: true });
    app.sendRequest({
      url: options.url, method: options.method || 'POST', data: options.data, header: ch.writeHeader(),
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        this.setData({ busy: false });
        if (ok(res)) {
          if (successText) toast.success(successText);
          if (typeof options.after === 'function') options.after();
          this.load();
          return;
        }
        toast.error(ch.errorText(res));
        this.load();
      },
      successStatusAbnormal: (body) => {
        this.setData({ busy: false });
        toast.error(ch.errorText(body));
        this.load();
      },
      fail: () => {
        this.setData({ busy: false });
        // 结果不确定:同一请求可安全重放,但这里直接刷新,让列表显示服务端的真实状态。
        toast.error('网络异常，已刷新列表');
        this.load();
      }
    });
  },

  toggleReorder() {
    if (!this.data.isManager) return;
    this.setData({ reordering: !this.data.reordering });
  },

  findRow(id) {
    return (this._inventory || []).find((r) => String(r.id) === String(id)) || null;
  },

  onMove(e) {
    const row = this.findRow(e.currentTarget.dataset.id);
    const items = row ? ch.planMove(this._inventory, row, e.currentTarget.dataset.dir) : [];
    if (!items.length) return;
    this.write({ url: '/api/guilds/' + ch.GUILD_CLUB + '/' + this.data.clubId + '/channels/reorder', data: ch.reorderBody(items) });
  },

  onMoveTo(e) {
    const row = this.findRow(e.currentTarget.dataset.id);
    if (!row) return;
    const targets = [{ label: '未分组', value: '0' }].concat(ch.categories(this._inventory).map((c) => ({ label: c.name, value: String(c.id) })))
      .filter((t) => t.value !== String(row.parent_id));
    if (!targets.length) return;
    this.openPicker('移到分类', targets, (t) => {
      const items = ch.planMoveTo(this._inventory, row, t.value);
      if (items.length) this.write({ url: '/api/guilds/' + ch.GUILD_CLUB + '/' + this.data.clubId + '/channels/reorder', data: ch.reorderBody(items) }, '已移动');
    });
  },

  onArchive(e) {
    const row = this.findRow(e.currentTarget.dataset.id);
    if (!row) return;
    const archive = Number(row.status) === 0;
    if (!archive && row.archive_reason !== 'manual') { toast.error('这个频道当前不能取消归档'); return; }
    modal.show({
      title: archive ? '归档「' + row.name + '」?' : '取消归档「' + row.name + '」?',
      content: archive ? '归档后频道只读,成员仍能查看历史消息。可以随时取消归档。' : '取消归档后成员可以重新在这里发言。',
      confirmText: archive ? '归档' : '取消归档',
      success: (r) => {
        if (!r || !r.confirm) return;
        this.write({ url: '/api/channels/' + row.id + '/archive', data: ch.archiveBody(row, archive) }, archive ? '已归档' : '已取消归档');
      }
    });
  },

  // ---------------------------------------------------------------- editor

  onCreate() {
    if (!this.data.isManager) return;
    this.setData({
      editorShow: true,
      editor: { mode: 'create', name: '', topic: '', kind: ch.KIND_TEXT, kindLabel: KIND_OPTIONS[0].label,
        parentId: '0', parentLabel: '未分组', slowmodeSec: 0, slowmodeLabel: slowmodeLabel(0) }
    });
  },

  onEdit(e) {
    const id = e.currentTarget.dataset.id;
    if (!id || this.data.busy) return;
    // 管理清单不带话题与慢速:编辑前读一次频道详情(同时拿到最新版本号)。
    app.sendRequest({
      url: '/api/channels/' + id, method: 'GET', hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!ok(res) || !res.data) { toast.error(ch.errorText(res, '频道读取失败')); return; }
        const d = res.data;
        this._editing = d;
        this.setData({
          editorShow: true,
          editor: { mode: 'edit', id: String(d.id), name: d.name || '', topic: d.topic || '', kind: Number(d.kind),
            kindLabel: ch.kindLabel(d.kind), slowmodeSec: Number(d.slowmode_sec || 0), slowmodeLabel: slowmodeLabel(d.slowmode_sec) }
        });
      },
      successStatusAbnormal: (body) => toast.error(ch.errorText(body, '频道读取失败')),
      fail: () => toast.error('网络异常，请重试')
    });
  },

  onEditorClose() { this.setData({ editorShow: false, editor: null }); this._editing = null; },
  onNameInput(e) { this.setData({ 'editor.name': (e.detail && e.detail.value) || '' }); },
  onTopicInput(e) { this.setData({ 'editor.topic': (e.detail && e.detail.value) || '' }); },

  onPickKind() {
    if (!this.data.editor || this.data.editor.mode !== 'create') return;
    this.openPicker('频道类型', KIND_OPTIONS, (o) => {
      this.setData({ 'editor.kind': o.value, 'editor.kindLabel': o.label });
      if (o.value === ch.KIND_CATEGORY) this.setData({ 'editor.parentId': '0', 'editor.parentLabel': '未分组' });
    });
  },

  onPickParent() {
    if (!this.data.editor || this.data.editor.mode !== 'create' || this.data.editor.kind === ch.KIND_CATEGORY) return;
    const options = [{ label: '未分组', value: '0' }].concat(ch.categories(this._inventory).map((c) => ({ label: c.name, value: String(c.id) })));
    this.openPicker('放在哪个分类', options, (o) => this.setData({ 'editor.parentId': o.value, 'editor.parentLabel': o.label }));
  },

  onPickSlowmode() {
    if (!this.data.editor || this.data.editor.kind === ch.KIND_CATEGORY) return;
    this.openPicker('慢速模式', SLOWMODE_OPTIONS, (o) => this.setData({ 'editor.slowmodeSec': o.value, 'editor.slowmodeLabel': o.label }));
  },

  onEditorSave() {
    const ed = this.data.editor;
    if (!ed || this.data.busy) return;
    if (!ch.validName(ed.name)) { toast.error('名称 1–64 个字，不能包含 / 或 #'); return; }
    if (ed.mode === 'create') {
      this.write({ url: '/api/guilds/' + ch.GUILD_CLUB + '/' + this.data.clubId + '/channels',
        data: ch.createBody({ name: ed.name, topic: ed.topic, kind: ed.kind, parentId: ed.parentId, slowmodeSec: ed.slowmodeSec }),
        after: () => this.onEditorClose() }, ed.kind === ch.KIND_CATEGORY ? '分类已创建' : '频道已创建');
      return;
    }
    const body = this._editing ? ch.editBody(this._editing, { name: ed.name, topic: ed.topic, slowmodeSec: ed.slowmodeSec }) : null;
    if (!body) { toast('没有修改'); return; }
    this.write({ url: '/api/channels/' + ed.id + '/edit', data: body, after: () => this.onEditorClose() }, '已保存');
  },

  // ---------------------------------------------------------------- shared picker

  openPicker(title, options, onPick) {
    this._pickerOptions = options;
    this._onPick = onPick;
    this.setData({ pickerShow: true, pickerTitle: title, pickerItems: options.map((o) => o.label) });
  },

  onPickerSelect(e) {
    const index = Number(e.detail && e.detail.index);
    const option = this._pickerOptions && this._pickerOptions[index];
    const cb = this._onPick;
    this.setData({ pickerShow: false });
    this._onPick = null;
    if (option && typeof cb === 'function') cb(option);
  },

  onPickerCancel() {
    this._onPick = null;
    this.setData({ pickerShow: false });
  }
});
