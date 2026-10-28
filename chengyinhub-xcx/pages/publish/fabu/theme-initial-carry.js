// 初始状态 ·「开局带着」:玩家开局时就带着的线索、标签(journeyRules.attributes 里 clue.* / tag.* 且 initial=1 的行,
// 后端 JourneyInitialState 开局写进状态)。添加叠一层:搜索 + 多选主题已有的线索 / 标签,第一行就地新建。
// 物品开局放进背包还没有后端字段,这里先不列物品。
const app = getApp();
const { isBizOk } = require('../../../utils/response-shape.js');
const keyRules = require('../components/topic-state-keys/rules.js');
const toast = require('../../../utils/toast.js');

const KIND_TEXT = { clue: '线索', tag: '标签' };
const kindOf = key => String(key || '').slice(0, String(key || '').indexOf('.'));
const carryKey = key => kindOf(key) === 'clue' || kindOf(key) === 'tag';

function carryRows(attributes) {
  return (attributes || []).filter(row => carryKey(row.key) && Number(row.initial) === 1)
    .map(row => ({ key: row.key, label: row.label, kindText: KIND_TEXT[kindOf(row.key)] }));
}

const EMPTY_PICKER = { show: false, state: 'loading', query: '', keys: [], groups: [], selected: {}, count: 0, creating: false, newKind: 'clue', newName: '', saving: false, error: '' };
const data = { carryPicker: EMPTY_PICKER };

const methods = {
  _carryRowsOf(attributes) { return carryRows(attributes); },
  _carryRefresh(attributes) {
    this.setData({ 'initialStateEditor.carry': carryRows(attributes) });
  },
  _carryLocked() {
    const rules = this.data.initialStateEditor.rules;
    return this.data.editScope === 'WHITELIST' || !rules || !!rules.attributeBinding;
  },
  removeCarry(e) {
    if (this._carryLocked()) return;
    const key = e.currentTarget.dataset.key;
    const attributes = (this.data.initialStateEditor.rules.attributes || []).filter(row => row.key !== key);
    this.setData({ 'initialStateEditor.rules.attributes': attributes, 'initialStateEditor.error': '' });
    this._carryRefresh(attributes);
  },
  _carryGroups(picker) {
    const taken = new Set((this.data.initialStateEditor.carry || []).map(row => row.key));
    const q = String(picker.query || '').trim();
    return ['clue', 'tag'].map(kind => ({ kind, label: KIND_TEXT[kind],
      rows: picker.keys.filter(k => k.kind === kind && k.label && (!q || k.label.includes(q)))
        .map(k => ({ key: k.stateKey, label: k.label, added: taken.has(k.stateKey), on: !!picker.selected[k.stateKey] })) }))
      .filter(group => group.rows.length);
  },
  _carrySet(patch) {
    const picker = Object.assign({}, this.data.carryPicker, patch);
    picker.groups = this._carryGroups(picker);
    picker.count = Object.keys(picker.selected).length;
    this.setData({ carryPicker: picker });
  },
  /** 名单取初始状态这一屏已读到的注册表快照(loadAttributeRegistry),不另发请求。 */
  openCarryPicker() {
    if (this._carryLocked()) return;
    const snapshot = this.data.attributeRegistrySnapshot;
    const keys = snapshot && Array.isArray(snapshot.definitions) ? keyRules.normalizeKeys(snapshot.definitions) : null;
    this._carrySeq = (this._carrySeq || 0) + 1;
    this._carrySet(Object.assign({}, EMPTY_PICKER, { show: true, state: keys ? 'ready' : 'error', keys: keys || [] }));
  },
  retryCarryPicker() { this.loadAttributeRegistry(); this.closeCarryPicker(); },
  closeCarryPicker() { this._carrySeq = (this._carrySeq || 0) + 1; this.setData({ 'carryPicker.show': false }); },
  onCarryQuery(e) { this._carrySet({ query: e.detail.value, newName: e.detail.value, error: '' }); },
  onCarryToggle(e) {
    const key = e.currentTarget.dataset.key;
    if ((this.data.initialStateEditor.carry || []).some(row => row.key === key)) return;
    const selected = Object.assign({}, this.data.carryPicker.selected);
    if (selected[key]) delete selected[key]; else selected[key] = true;
    this._carrySet({ selected });
  },
  onCarryCreateOpen() { this._carrySet({ creating: !this.data.carryPicker.creating, newName: this.data.carryPicker.query, error: '' }); },
  onCarryKind(e) { this._carrySet({ newKind: e.currentTarget.dataset.kind, error: '' }); },
  onCarryNewName(e) { this._carrySet({ newName: e.detail.value, error: '' }); },
  createCarryKey() {
    const picker = this.data.carryPicker;
    const name = String(picker.newName || '').trim();
    if (!name || picker.saving || this._carryLocked()) return;
    const kind = picker.newKind;
    const error = keyRules.nameError(kind, name, picker.keys.filter(k => k.kind === kind).map(k => k.label));
    if (error) { this._carrySet({ error }); return; }
    const key = keyRules.newKey(kind, name, picker.keys);
    const seq = this._carrySeq;
    this._carrySet({ saving: true, error: '' });
    app.sendRequest({
      url: '/api/topic/state-keys', method: 'POST', hideLoading: true, silentError: true,
      data: JSON.stringify({ topicId: Number(this.data.editingTopicId), scope: this.data.operationScope, keys: [key] }),
      header: { 'Content-Type': 'application/json' },
      success: res => {
        if (seq !== this._carrySeq) return;
        if (!isBizOk(res)) { this._carryCreateFailed(seq); return; }
        const row = keyRules.normalizeKeys([key])[0];
        const selected = Object.assign({}, this.data.carryPicker.selected, { [key.stateKey]: true });
        this._carrySet({ saving: false, creating: false, query: '', newName: '', keys: this.data.carryPicker.keys.concat(row), selected });
      },
      fail: () => this._carryCreateFailed(seq),
      successStatusAbnormal: () => this._carryCreateFailed(seq),
    });
  },
  /** 新建没成功:收起「保存中」,轻提示一句(不带后端原话,原话可能含键名)。 */
  _carryCreateFailed(seq) {
    if (seq !== this._carrySeq) return;
    this._carrySet({ saving: false });
    toast('没有保存成功，请再试一次。');
  },
  confirmCarryPicker() {
    const picker = this.data.carryPicker;
    if (!picker.count || this._carryLocked()) return;
    const attributes = (this.data.initialStateEditor.rules.attributes || []).map(row =>
      (picker.selected[row.key] ? Object.assign({}, row, { initial: 1 }) : row));
    picker.keys.filter(k => picker.selected[k.stateKey] && !attributes.some(row => row.key === k.stateKey))
      .forEach(k => attributes.push({ key: k.stateKey, label: k.label, initial: 1, min: 0, max: 1, visible: true }));
    if (attributes.length > 32) { this._carrySet({ error: '最多 32 项。' }); return; }
    this.setData({ 'initialStateEditor.rules.attributes': attributes, 'initialStateEditor.rules.stateEnabled': true,
      'initialStateEditor.error': '', 'carryPicker.show': false });
    this._carryRefresh(attributes);
  },
};

module.exports = { data, methods, carryRows };
