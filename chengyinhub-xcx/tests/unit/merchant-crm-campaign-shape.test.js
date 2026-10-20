// BL-153（触达任务部分）：pages/merchant/crm-campaigns/view-model.js 的 shapeCampaign 单条映射用例
// 依赖 #144（BL-100），须在其后合并；不改被测代码。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { shapeCampaign } = require('../../pages/merchant/crm-campaigns/view-model.js');

test('shapeCampaign: 完整行映射', () => {
  assert.deepEqual(shapeCampaign({
    id: 3, title: ' 周末回访 ', channel: 'in_app', status: 'partial_failed',
    createTime: '2026-10-15 18:05:33',
    recipientCount: 10, deliveredCount: 7, noConsentCount: 1, frequencySkippedCount: 1,
    failedCount: 1, retryableCount: 0,
  }), {
    id: 3,
    titleText: '周末回访',
    channelText: '站内消息',
    statusText: '部分未送达',
    statusTone: 'warning',
    timeText: '2026-10-15 18:05',
    recipientCount: 10,
    deliveredCount: 7,
    noConsentCount: 1,
    frequencySkippedCount: 1,
    failedCount: 1,
    retryableCount: 0,
    summaryText: '送达 7 / 10 人',
  });
});

test('shapeCampaign: 空行 / 非对象 / 非法 id 返回 null', () => {
  for (const v of [null, undefined, 'x', 1, {}, { id: 0 }, { id: -2 }, { id: 'abc' }, { id: 1.5 }, { id: Number.MAX_SAFE_INTEGER + 2 }]) {
    assert.equal(shapeCampaign(v), null, JSON.stringify(v));
  }
  assert.equal(shapeCampaign({ id: '8' }).id, 8);
});

test('shapeCampaign: 未知 / 缺失 status 兜底「其他状态」，不冒充成功', () => {
  for (const status of ['DONE', 'FAILED', '', null, undefined, 1]) {
    const row = shapeCampaign({ id: 1, status });
    assert.equal(row.statusText, '其他状态', String(status));
    assert.equal(row.statusTone, 'neutral');
  }
  assert.equal(shapeCampaign({ id: 1, status: ' success ' }).statusText, '已完成');
});

test('shapeCampaign: 计数缺失 / 非法 / 负数一律 0，小数向下取整', () => {
  const row = shapeCampaign({
    id: 1, recipientCount: null, deliveredCount: 'abc', noConsentCount: -3,
    frequencySkippedCount: undefined, failedCount: 2.9, retryableCount: Infinity,
  });
  assert.equal(row.recipientCount, 0);
  assert.equal(row.deliveredCount, 0);
  assert.equal(row.noConsentCount, 0);
  assert.equal(row.frequencySkippedCount, 0);
  assert.equal(row.failedCount, 2);
  assert.equal(row.retryableCount, 0);
  assert.equal(row.summaryText, '送达 0 / 0 人');
  assert.equal(shapeCampaign({ id: 1, recipientCount: '5', deliveredCount: '2' }).summaryText, '送达 2 / 5 人');
});

test('shapeCampaign: 缺字段兜底（标题、渠道、时间）', () => {
  const row = shapeCampaign({ id: 9 });
  assert.equal(row.titleText, '未命名触达');
  assert.equal(row.channelText, '');
  assert.equal(row.timeText, '');
  assert.equal(shapeCampaign({ id: 9, channel: 'SMS' }).channelText, '', '未知渠道不展示原始代码');
  assert.equal(shapeCampaign({ id: 9, channel: 'coupon' }).channelText, '优惠券');
  assert.equal(shapeCampaign({ id: 9, createTime: 'not a date' }).timeText, '');
});
