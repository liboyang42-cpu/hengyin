// check-1104 j1 F13 / p2 F-2(第十轮 f1 第 10 项):商家数字同源。
// 资产页按余额流水归类:活动结算给商户的分润是 eventType=3 收入,要归「活动分润」而不是「其他收入」;
// 3 的支出是提现,不是收入。营销窗口改为含今天,页面只认服务端新口径。
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');

const view = require(path.join(__dirname, '../../subpackageA/utils/earnings-view.js'));
const aggregate = require(path.join(__dirname, '../../pages/merchant/utils/merchant-aggregate.js'));

test('活动分润(eventType=3 收入)归活动分润,提现(3 支出)不进本月收入', () => {
  const now = new Date(2026, 10, 4, 12, 0, 0);
  const rows = [
    { id: 39, eventType: 3, changeType: 1, changeBalance: '56.74', changeReason: '活动分润 - 梧桐里读书夜', createTime: '2026-11-04 12:00:00' },
    { id: 41, eventType: 3, changeType: 2, changeBalance: '20.00', changeReason: '提现', createTime: '2026-11-04 13:00:00' },
  ];
  assert.strictEqual(view.sourceOf(rows[0]).label, '活动分润');
  const month = view.monthIncome(rows, now, 3);
  assert.strictEqual(month.total, '56.74');
  assert.strictEqual(month.items.length, 1);
  assert.strictEqual(month.items[0].tag, '活动分润');
  const sources = view.sourceBreakdown(rows, now);
  assert.deepStrictEqual(sources.map((s) => [s.label, s.totalText]), [['活动分润', '56.74']]);
});

function analytics(basis) {
  const day = 24 * 60 * 60 * 1000;
  const end = Date.UTC(2026, 10, 4, 16, 0, 0); // 上海 11-05 00:00:今天(11-04)整天在窗口内
  const stage = (code, label) => ({ code, label, currentCount: code === 'VERIFY' ? 1 : 0, previousCount: 0, changeRate: null });
  return {
    semantics: 'INDEPENDENT_EVENT_COUNTS', windowDays: 30,
    window: { timezone: 'Asia/Shanghai', basis, previousStartInclusive: end - 60 * day,
      currentStartInclusive: end - 30 * day, endExclusive: end },
    stages: [stage('BROWSE', '浏览'), stage('SIGNUP', '报名'), stage('PAYMENT', '支付'), stage('VERIFY', '核销'), stage('REFUND_APPLY', '退款申请')],
    touchpoints: { coverage: 'PARTIAL', attributed: false, items: [
      { code: 'IN_APP_TOPIC', label: '主题入口', eventCount: 0 },
      { code: 'IN_APP_ACTIVITY', label: '活动入口', eventCount: 0 },
      { code: 'UNATTRIBUTED', label: '未归因', eventCount: 0 }] },
  };
}

test('营销窗口含今天:接受 DAYS_INCL_TODAY,当天核销计入;旧「截至昨日」口径不再当作当前窗口', () => {
  const shaped = aggregate.normalizeMarketingAnalytics(analytics('DAYS_INCL_TODAY'));
  assert.ok(shaped, '新口径必须被接受');
  assert.strictEqual(shaped.window.basis, 'DAYS_INCL_TODAY');
  assert.strictEqual(shaped.stages.find((s) => s.code === 'VERIFY').currentCount, 1);
  assert.strictEqual(aggregate.normalizeMarketingAnalytics(analytics('COMPLETE_CALENDAR_DAYS')), null);
});
