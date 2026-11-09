// qx-b4 §21:固定玩法预设 → 个人草稿。控件按 requiredFields.kind;请求只送声明键;同 requestId 重查。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const p = require('../../pages/publish/utils/publish/gameplay-presets.js');

const T06 = {
  presetKey: 'T06-building-observation', title: '建筑观察（结构预设）', purpose: '把构件按依据分类',
  cityBinding: { appliesAt: 'THEME_NODE_BINDING', requiredRebinds: ['place'] },
  requiredFields: [
    { key: 'title', label: '玩法标题', kind: 'text', maxLength: 40 },
    { key: 'bin1', label: '分类一名称', kind: 'text', maxLength: 20 },
    { key: 'bin2', label: '分类二名称', kind: 'text', maxLength: 20 },
    { key: 'item1', label: '构件一', kind: 'text', maxLength: 20 },
    { key: 'item2', label: '构件二', kind: 'text', maxLength: 20 },
    { key: 'item3', label: '构件三', kind: 'text', maxLength: 20 },
    { key: 'answer', label: '每个构件归哪一类', kind: 'bin_map', items: ['b1', 'b2', 'b3'], bins: ['k1', 'k2'] },
    { key: 'choice1', label: '选项一', kind: 'text', maxLength: 20 },
    { key: 'choice2', label: '选项二', kind: 'text', maxLength: 20 },
    { key: 'rightAnswer', label: '正确选项', kind: 'choice', options: ['c1', 'c2'] },
    { key: 'timerSeconds', label: '限时秒数', kind: 'number', min: 30, max: 1800 },
    { key: 'order', label: '正确顺序', kind: 'order', options: ['i1', 'i2', 'i3'] },
    { key: 'weird', label: '未知控件', kind: 'matrix' }
  ],
  authorTodos: ['现场确认每个构件可见']
};

test('列表去掉「（结构预设）」尾巴,保留服务端用途原文', () => {
  const rows = p.list({ catalogVersion: 3, presets: [T06, { title: '无键' }] });
  assert.equal(rows.length, 1);
  assert.equal(rows[0].title, '建筑观察');
  assert.equal(rows[0].purpose, '把构件按依据分类');
});

test('控件按 kind;选项显示作者填写的同组文字,没填显示第 N 项', () => {
  const values = { item1: '斗拱', item2: '', bin1: '承重', bin2: '装饰', choice1: '明代' };
  const rows = p.formRows(T06, values);
  assert.deepEqual(rows.map(r => r.kind), ['text', 'text', 'text', 'text', 'text', 'text', 'bin_map', 'text', 'text', 'choice', 'number', 'order']);
  const bin = rows.find(r => r.key === 'answer');
  assert.deepEqual(bin.items.map(i => i.label), ['斗拱', '第 2 项', '第 3 项']);
  assert.deepEqual(bin.items[0].bins.map(b => b.label), ['承重', '装饰']);
  const choice = rows.find(r => r.key === 'rightAnswer');
  assert.deepEqual(choice.options.map(o => o.label), ['明代', '第 2 项']);
  assert.equal(rows.find(r => r.key === 'timerSeconds').hint, '30–1800');
});

test('请求体只送声明键、空值不送,ORDER 按当前顺序送,未知 kind 不送', () => {
  const values = { title: '城门', rightAnswer: 'c2', answer: { b1: 'k1' }, timerSeconds: ' 90 ', order: ['i2', 'i1', 'i3'], weird: 'x', stray: 'y' };
  const body = p.draftBody(T06, values, 'pd-abc');
  assert.deepEqual(body, { presetKey: 'T06-building-observation', requestId: 'pd-abc',
    fields: { title: '城门', answer: { b1: 'k1' }, rightAnswer: 'c2', timerSeconds: '90', order: ['i2', 'i1', 'i3'] } });
  assert.deepEqual(p.draftBody(T06, {}, 'x').fields, { order: ['i1', 'i2', 'i3'] });
});

test('排序上下移;requestId 合规 8–64 位', () => {
  assert.deepEqual(p.moveItem(['a', 'b', 'c'], 0, 1), ['b', 'a', 'c']);
  assert.deepEqual(p.moveItem(['a', 'b', 'c'], 0, -1), ['a', 'b', 'c']);
  const id = p.newRequestId();
  assert.match(id, /^[A-Za-z0-9._:-]{8,64}$/);
});

test('回包:有 draftId 才算建好;回执 UNKNOWN 记为未确认;拒绝保留原文', () => {
  assert.deepEqual(p.draftResult({ code: 200, data: { draftId: '77', authorTodos: ['补头像'] } }), { state: 'CREATED', draftId: '77', todos: ['补头像'] });
  assert.deepEqual(p.draftResult({ code: 500, msg: 'x', writeReceipt: { state: 'UNKNOWN' } }), { state: 'UNKNOWN' });
  const msg = '预设还缺这些必填内容，补齐后才能建草稿：限时秒数（timerSeconds）、指认问题（question）';
  assert.deepEqual(p.draftResult({ code: 500, msg }), { state: 'REJECTED', message: msg });
});

test('页面:错误写在按钮上方不弹窗;未知时按钮改「查询原请求」并沿用原请求体', () => {
  const dir = path.join(__dirname, '../../pages/publish/preset');
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  assert.match(js, /this\.data\.unknown && this\._lastBody \? this\._lastBody/);
  assert.doesNotMatch(js, /showModal|showToast/);
  assert.match(wxml, /unknown \? '查询原请求' : '建草稿'/);
  const app = JSON.parse(fs.readFileSync(path.join(__dirname, '../../app.json'), 'utf8'));
  const pub = app.subPackages.find(s => s.root === 'pages/publish');
  assert.ok(pub.pages.includes('preset/index'), '预设页放在发布分包,不进主包');
});

test('本地必填:空文字、未选单选、分类没覆盖全部项都算缺;排序永远有值', () => {
  assert.deepEqual(p.missing(T06, { title: ' ', answer: { b1: 'k1' }, rightAnswer: 'c1' }),
    ['title', 'bin1', 'bin2', 'item1', 'item2', 'item3', 'answer', 'choice1', 'choice2', 'timerSeconds']);
  const rows = p.formRows(T06, {}, ['title']);
  assert.equal(rows[0].missing, true);
  assert.equal(rows[1].missing, false);
});
