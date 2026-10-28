// BL-100 商家触达任务记录(只读)视图模型 + 接线合同。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const vm = require('../../pages/merchant/crm-campaigns/view-model.js');

test('shapeCampaignList 只认数组,丢掉无合法 id 行,状态取后端常量真值', () => {
  assert.equal(vm.shapeCampaignList(null), null);
  assert.equal(vm.shapeCampaignList({ rows: [] }), null);
  const items = vm.shapeCampaignList([
    { id: 7, title: '周末回访', channel: 'IN_APP', status: 'PARTIAL_FAILED', recipientCount: 10, deliveredCount: 8,
      noConsentCount: 1, frequencySkippedCount: 0, failedCount: 1, retryableCount: 0, createTime: '2026-10-14 18:05:33' },
    { id: 0, title: 'x' },
    { id: 'abc' },
    null,
    { id: 9, status: 'WHATEVER', channel: 'SMS' },
  ]);
  assert.equal(items.length, 2);
  assert.equal(items[0].titleText, '周末回访');
  assert.equal(items[0].channelText, '站内消息');
  assert.equal(items[0].statusText, '部分未送达');
  assert.equal(items[0].statusTone, 'warning');
  assert.equal(items[0].timeText, '2026-10-14 18:05');
  assert.equal(items[0].summaryText, '送达 8 / 10 人');
  assert.equal(items[1].titleText, '未命名触达');
  assert.equal(items[1].channelText, '');
  assert.equal(items[1].statusText, '其他状态');
  assert.equal(items[1].summaryText, '送达 0 / 0 人');
});

test('状态表覆盖后端 MerchantCrmCampaign / Recipient 常量', () => {
  for (const s of ['READY', 'PROCESSING', 'SUCCESS', 'PARTIAL_FAILED', 'NO_ELIGIBLE']) assert.ok(vm.CAMPAIGN_STATUS[s], s);
  for (const s of ['PENDING', 'DELIVERED', 'SKIPPED_NO_CONSENT', 'SKIPPED_FREQUENCY', 'RETRYABLE_BUSY', 'FAILED']) {
    assert.ok(vm.RECIPIENT_STATUS[s], s);
  }
});

test('shapeCampaignDetail 逐人结果只用 VO 字段,失败原因只在未送达时显示', () => {
  assert.equal(vm.shapeCampaignDetail(null), null);
  const d = vm.shapeCampaignDetail({
    id: 3, status: 'SUCCESS', recipientCount: 2, deliveredCount: 1,
    recipients: [
      { recipientId: 11, customerName: '张三', status: 'DELIVERED', failureMessage: 'ignored', deliveredAt: '2026-10-15 09:01:00' },
      { recipientId: null, customerName: '', status: 'FAILED', failureMessage: '优惠券库存不足' },
    ],
  });
  assert.equal(d.recipients.length, 2);
  assert.deepEqual(d.recipients[0], { key: 'r11', nameText: '张三', statusText: '已送达', statusTone: 'success', reasonText: '', timeText: '2026-10-15 09:01' });
  assert.equal(d.recipients[1].key, 'i1');
  assert.equal(d.recipients[1].nameText, '客户');
  assert.equal(d.recipients[1].reasonText, '优惠券库存不足');
  assert.deepEqual(vm.shapeCampaignDetail({ id: 4 }).recipients, []);
});

test('formatTime 兼容毫秒数 / ISO / 空值', () => {
  assert.equal(vm.formatTime(null), '');
  assert.equal(vm.formatTime('not a date'), '');
  assert.match(vm.formatTime(Date.UTC(2026, 9, 15, 1, 2)), /^2026-10-1\d \d{2}:02$/);
  assert.match(vm.formatTime('2026-10-15T01:02:00.000+00:00'), /^2026-10-1\d \d{2}:02$/);
});

test('页面只读接线:先 access/me 判 canReadMarketing,只调 GET 列表/详情,不调创建/投递/重试', () => {
  const js = fs.readFileSync(path.join(root, 'pages/merchant/crm-campaigns/index.js'), 'utf8');
  assert.match(js, /url: '\/api\/merchant\/access\/me'/);
  assert.match(js, /url: '\/api\/merchant\/crm\/campaigns',\s*method: 'GET'/);
  assert.match(js, /url: `\/api\/merchant\/crm\/campaigns\/\$\{id\}`,\s*method: 'GET'/);
  assert.match(js, /canReadMarketing/);
  const code = js.replace(/^\s*\/\/.*$/gm, '');
  assert.doesNotMatch(code, /\/dispatch|\/retry|\/preview/);
  assert.equal((code.match(/crm\/campaigns/g) || []).length, 2);
  assert.doesNotMatch(js, /success\s*\(res\)\s*\{/);
  assert.doesNotMatch(js, /fail\s*\(\)\s*\{/);
  const wxml = fs.readFileSync(path.join(root, 'pages/merchant/crm-campaigns/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-nav-bar custom-back bind:back="onNavBack" \/>/);
  assert.match(wxml, /<cy-page-title title="触达记录"/);
  assert.match(wxml, /pageState === 'no-permission'/);
  assert.match(wxml, /<cy-sheet show="\{\{detailOpen\}\}"/);
});

test('客户页「管理」半屏按 canReadMarketing 显示「触达记录」并跳独立页', () => {
  const wxml = fs.readFileSync(path.join(root, 'pages/merchant/customer/index.wxml'), 'utf8');
  const js = fs.readFileSync(path.join(root, 'pages/merchant/customer/index.js'), 'utf8');
  // 2026-10-27 M-04:「管理」半屏改为操作列表行(cu-op),门控与去向不变
  // 2026-10-28 m679:Threads 式菜单行(tm-row),门控与去向不变
  assert.match(wxml, /wx:if="\{\{canCampaigns\}\}" class="tm-row" bindtap="goCampaigns"/);
  assert.match(js, /canCampaigns: access\.canReadMarketing/);
  assert.match(js, /goCampaigns\(\) \{\s*if \(!this\.data\.canCampaigns \|\| this\._navigating\) return;[^\n]*\s*this\._navigating = true;\s*this\.setData\(\{ toolsOpen: false \}\);\s*wx\.navigateTo\(\{ url: '\/pages\/merchant\/crm-campaigns\/index', complete: \(\) => \{ this\._navigating = false; \} \}\)/);
});
