/**
 * 消耗与效果半屏(原型 A e-after / e-after-add,2026-10-28 晚):句子列表 + 就地展开。
 * 草稿仍是 afterEditor.draft 的 costs / effects / next,保存仍走 saveAfterSheet(outcome-after.apply)。
 * 数值只有几个:「添加消耗」就地一排药丸;「添加效果」开一层,种类(增加 / 恢复到至少)一列,点一种就地展开名字。
 */
const clone = v => JSON.parse(JSON.stringify(v));
const MAX_ROWS = 20;
const EFFECT_KINDS = [{ key: 'INC', label: '增加' }, { key: 'FLOOR', label: '恢复到至少' }];

const data = {
  afterOpen: '',               // 一次只展开一行:cost-<i> / eff-<i> / costAdd / next
  afterNewValue: false,        // 「新建数值」药丸变成输入框
  afterAdd: { show: false, open: '' },
  afterEffectKinds: EFFECT_KINDS,
};

const methods = {
  toggleAfterRow(e) {
    const key = e.currentTarget.dataset.open;
    if (key === 'next' && this.data.r5Editor.nextDisabledReason) return;
    this.setData({ afterOpen: this.data.afterOpen === key ? '' : key, afterNewValue: false });
  },
  stepAfterRow(e) {
    const { kind, index, delta } = e.currentTarget.dataset;
    const field = kind === 'costs' ? 'amount' : 'value';
    const row = (this.data.afterEditor.draft[kind] || [])[Number(index)];
    if (!row) return;
    const value = Math.max(1, Math.min(999, (Number(row[field]) || 0) + Number(delta)));
    this.inputAfterRow({ currentTarget: { dataset: { kind, index, field } }, detail: { value: String(value) } });
  },
  pickAfterEffectOp(e) {
    const { index, op } = e.currentTarget.dataset;
    this.inputAfterRow({ currentTarget: { dataset: { kind: 'effects', index, field: 'op' } }, detail: { value: op } });
  },
  addAfterCost(e) {
    const key = e.currentTarget.dataset.key;
    const rows = this.data.afterEditor.draft.costs || [];
    if (!key || rows.length >= MAX_ROWS || rows.some(row => row.statKey === key)) return;
    this.setData({ 'afterEditor.draft.costs': rows.concat({ statKey: key, amount: 1 }), afterOpen: 'cost-' + rows.length, afterNewValue: false });
  },
  openAfterNewValue() { this.setData({ afterNewValue: true }); },
  confirmAfterNewValue(e) {
    this.declareAfterValueSlot(e);
    this.setData({ afterNewValue: false });
  },
  removeAfterRowInline(e) {
    this.removeAfterRow(e);
    this.setData({ afterOpen: '' });
  },
  pickAfterNextRow(e) {
    this.pickAfterNextStep({ detail: { value: Number(e.currentTarget.dataset.index) } });
    this.setData({ afterOpen: '' });
  },
  openAfterAdd() { this.setData({ afterAdd: { show: true, open: '' } }); },
  closeAfterAdd() { this.setData({ afterAdd: { show: false, open: '' } }); },
  toggleAfterAddKind(e) {
    const key = e.currentTarget.dataset.key;
    this.setData({ 'afterAdd.open': this.data.afterAdd.open === key ? '' : key });
  },
  pickAfterAddName(e) {
    const { op, key } = e.currentTarget.dataset;
    const rows = clone(this.data.afterEditor.draft.effects || []);
    if (!key || rows.length >= MAX_ROWS) return;
    rows.push({ var: key, op: op === 'FLOOR' ? 'FLOOR' : 'INC', value: 1 });
    this.setData({ 'afterEditor.draft.effects': rows, afterOpen: 'eff-' + (rows.length - 1), afterAdd: { show: false, open: '' } });
  },
};

module.exports = { data, methods, EFFECT_KINDS };
