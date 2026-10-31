const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const read = p => fs.readFileSync(`${__dirname}/../../${p}`, 'utf8');

test('RUN-001: 客户页没有活动外广播入口或发送代码，活动导演台仍有广播', () => {
  for (const ext of ['js', 'wxml']) {
    assert.doesNotMatch(read(`pages/merchant/customer/index.${ext}`), /openCast|sendCast|\/crm\/broadcast|定向广播/);
  }
  assert.match(read('pages/club/topic-detail/index.wxml'), /定向广播/);
});

test('RUN-002: 管理与筛选独立弹层，列表仍可批量选人', () => {
  const wxml = read('pages/merchant/customer/index.wxml');
  // 2026-10-28 m679:管理半屏改成 Threads 式菜单,不带标题
  assert.match(wxml, /<cy-sheet inset show="\{\{toolsOpen\}\}" bind:close="closeTools">/);
  // 2026-10-30 业主定稿 P-5 M-03:iOS 式头 —— 左「取消」、中间「筛选客户」、右「重置」,不再用面板自带的标题 + ×
  assert.match(wxml, /<cy-sheet[^>]*show="\{\{filterOpen\}\}"[^>]*closable="\{\{false\}\}"/);
  assert.match(wxml, /<text class="cu-fx-hd__t">筛选客户<\/text>/);
  assert.match(wxml, /data-memberid="\{\{item.memberId\}\}"/);
  assert.match(wxml, /wx:if="\{\{selecting\}\}"/);
});

test('客户页不再提供合规触达弹窗，分群仍用于客户筛选', () => {
  const wxml = read('pages/merchant/customer/index.wxml');
  assert.doesNotMatch(wxml, /合规触达|campaignOpen|previewCampaign|submitCampaign/);
  assert.match(wxml, /bindtap="applySavedSegment"/);
});
