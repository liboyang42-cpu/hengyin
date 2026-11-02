// BL-76 商家核销记录(只读)视图模型 + 接线合同。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const vm = require('../../pages/merchant/verify-records/view-model.js');

test('M-03 shapeRecordList 只认分页载荷,行来自 redemption 真源,写出站点名', () => {
  assert.equal(vm.shapeRecordList(null), null);
  assert.equal(vm.shapeRecordList([]), null, '旧的 cms_registration_scan 数组载荷不再认');
  const items = vm.shapeRecordList({ total: 3, rows: [
    { recordKey: 'redemption:5', recordId: '5', chapterName: '第四站·一页手写的回信', topicName: '梧桐里·武康路探店日', fulfillmentState: 'ACTIVE', occurredAt: '2026-11-02 13:24:10' },
    { recordId: '6', storeName: '梧桐里书房', fulfillmentState: 'REVERSED', occurredAt: '2026-11-02 09:00:00' },
    { chapterName: '无 id' },
    null,
  ] });
  assert.equal(items.length, 2);
  assert.deepEqual(items[0], {
    id: 'redemption:5', nameText: '第四站·一页手写的回信', subText: '梧桐里·武康路探店日',
    statusKey: 'VERIFIED', statusText: '已核销', statusTone: 'success', dateKey: '2026-11-02', timeText: '13:24',
  });
  assert.equal(items[1].nameText, '梧桐里书房');
  assert.equal(items[1].statusKey, 'REVERSED');
});

test('countByStatus / groupRecords 按状态筛、按日期分组且保持后端倒序', () => {
  const items = vm.shapeRecordList({ rows: [
    { recordId: '1', occurredAt: '2026-10-15 10:00:00' },
    { recordId: '2', fulfillmentState: 'REVERSED', occurredAt: '2026-10-15 09:00:00' },
    { recordId: '3', occurredAt: '2026-10-14 08:00:00' },
    { recordId: '4' },
  ] });
  assert.deepEqual(vm.countByStatus(items), { VERIFIED: 3, REVERSED: 1 });
  const groups = vm.groupRecords(items, 'VERIFIED');
  assert.deepEqual(groups.map(g => [g.label, g.items.map(i => i.id)]), [
    ['2026-10-15', ['1']], ['2026-10-14', ['3']], ['时间未知', ['4']],
  ]);
  assert.deepEqual(vm.groupRecords(items, 'REVERSED').map(g => g.items.length), [1]);
});

test('页面只读接线:先 access/me 判 canReadVerifyRecords,再读与核销与退款同源的 finance/redemptions;回调不用 this 方法简写', () => {
  const js = fs.readFileSync(path.join(root, 'pages/merchant/verify-records/index.js'), 'utf8');
  assert.match(js, /url: '\/api\/merchant\/access\/me'/);
  assert.match(js, /url: '\/api\/merchant\/finance\/redemptions'/);
  assert.doesNotMatch(js, /verification-records/);
  assert.match(js, /data: JSON\.stringify\(\{ filter: 'all', pageNum, pageSize: PAGE_SIZE \}\),\s*header: \{ 'Content-Type': 'application\/json' \}/, '@RequestBody 端点必须发 JSON');
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
  assert.match(js, /goVerifyRecords\(\) \{\s*if \(!this\.data\.merchantAccess\.canReadVerifyRecords \|\| this\._navigating\) return;[^\n]*\s*this\._navigating = true;\s*wx\.navigateTo\(\{ url: '\/pages\/merchant\/verify-records\/index', complete: \(\) => \{ this\._navigating = false; \} \}\)/);
});
