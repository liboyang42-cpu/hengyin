// BL-76 商家核销记录(只读)视图模型 + 接线合同。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const vm = require('../../pages/merchant/verify-records/view-model.js');

test('shapeRecordList 只认数组,丢掉无 id 行,状态只按 status===1 判已核销', () => {
  assert.equal(vm.shapeRecordList(null), null);
  assert.equal(vm.shapeRecordList({ rows: [] }), null);
  const items = vm.shapeRecordList([
    { id: 'a1', registrationId: 12, name: '老码头', status: 1, validationMethod: 2, createTime: '2026-10-14 18:05:33' },
    { id: 'a2', registrationId: 0, name: '', status: 0, createTime: '2026-10-14 09:00:00' },
    { registrationId: 5, status: 1 },
    null,
  ]);
  assert.equal(items.length, 2);
  assert.deepEqual(items[0], {
    id: 'a1', nameText: '老码头', registrationText: '报名 #12', methodText: '图片验证',
    statusKey: 'VERIFIED', statusText: '已核销', statusTone: 'success', dateKey: '2026-10-14', timeText: '18:05',
  });
  assert.equal(items[1].nameText, '未命名据点');
  assert.equal(items[1].registrationText, '');
  assert.equal(items[1].methodText, '');
  assert.equal(items[1].statusKey, 'UNVERIFIED');
});

test('countByStatus / groupRecords 按状态筛、按日期分组且保持后端倒序', () => {
  const items = vm.shapeRecordList([
    { id: '1', status: 1, createTime: '2026-10-15 10:00:00' },
    { id: '2', status: 0, createTime: '2026-10-15 09:00:00' },
    { id: '3', status: 1, createTime: '2026-10-14 08:00:00' },
    { id: '4', status: 1 },
  ]);
  assert.deepEqual(vm.countByStatus(items), { VERIFIED: 3, UNVERIFIED: 1 });
  const groups = vm.groupRecords(items, 'VERIFIED');
  assert.deepEqual(groups.map(g => [g.label, g.items.map(i => i.id)]), [
    ['2026-10-15', ['1']], ['2026-10-14', ['3']], ['时间未知', ['4']],
  ]);
  assert.deepEqual(vm.groupRecords(items, 'UNVERIFIED').map(g => g.items.length), [1]);
});

test('页面只读接线:先 access/me 判 canReadVerifyRecords,再读 verification-records;回调不用 this 方法简写', () => {
  const js = fs.readFileSync(path.join(root, 'pages/merchant/verify-records/index.js'), 'utf8');
  assert.match(js, /url: '\/api\/merchant\/access\/me'/);
  assert.match(js, /url: '\/api\/merchant\/verification-records'/);
  assert.match(js, /canReadVerifyRecords/);
  assert.doesNotMatch(js, /success\s*\(res\)\s*\{/);
  assert.doesNotMatch(js, /fail\s*\(\)\s*\{/);
  const wxml = fs.readFileSync(path.join(root, 'pages/merchant/verify-records/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-nav-bar custom-back bind:back="onNavBack" \/>/);
  assert.match(wxml, /<cy-page-title title="核销记录"/);
  assert.match(wxml, /pageState === 'no-permission'/);
});

test('工作台「更多」入口按 canReadVerifyRecords 显示并跳独立页', () => {
  const wxml = fs.readFileSync(path.join(root, 'pages/merchant/index/index.wxml'), 'utf8');
  const js = fs.readFileSync(path.join(root, 'pages/merchant/index/index.js'), 'utf8');
  assert.match(wxml, /wx:if="\{\{merchantAccess\.canReadVerifyRecords\}\}" bindtap="onMoreItem" data-action="verifyRecords"/);
  assert.match(js, /action === 'verifyRecords'\) this\.goVerifyRecords\(\)/);
  assert.match(js, /goVerifyRecords\(\) \{\s*if \(!this\.data\.merchantAccess\.canReadVerifyRecords\) return;\s*wx\.navigateTo\(\{ url: '\/pages\/merchant\/verify-records\/index' \}\)/);
});
