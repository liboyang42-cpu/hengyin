const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(uiRoot, rel), 'utf8');

function loadHelpers() {
  const source = read('src/utils/questo-admin.js');
  const names = [...source.matchAll(/export (?:function|const) (\w+)/g)].map(m => m[1]);
  const body = source.replace(/export (function|const) /g, '$1 ') + `\nreturn { ${names.join(', ')} };`;
  return new Function(body)();
}

const h = loadHelpers();

test('未知数字不显示为 0', () => {
  assert.equal(h.countText(null), '—');
  assert.equal(h.countText(undefined), '—');
  assert.equal(h.countText(''), '—');
  assert.equal(h.countText(0), '0');
  assert.equal(h.moneyText(null, 'CNY'), '—');
  assert.equal(h.moneyText(0, 'CNY'), '¥0.00');
  assert.equal(h.moneyText(12.5, 'USD'), '12.50 USD');
  assert.equal(h.fenText(null), '—');
  assert.equal(h.fenText(-1234, 'CNY'), '¥-12.34');
});

test('§18 视频应计只显示已记录，不显示可提现或已到账', () => {
  assert.equal(h.accrualPayoutLabel(0), '已记录（未开放兑付）');
  assert.equal(h.accrualPayoutLabel(3), '已被替代');
  assert.equal(h.accrualPayoutLabel(4), '已撤销');
  for (const state of [1, 2, null, '__proto__']) {
    const label = h.accrualPayoutLabel(state);
    assert.equal(label, '状态待确认');
    assert.doesNotMatch(label, /提现|到账|已付|打款/);
  }
});

test('§18 F 未核验原视频组：已通过也不显示应计', () => {
  assert.equal(h.groupVerifyLabel({ verifiedGroupKey: null }), '未核验');
  assert.equal(h.groupVerifyLabel({ verifiedGroupKey: 'g-1' }), '已核验（g-1）');
  assert.equal(h.accrualBlockedByGroup({ status: 2, verifiedGroupKey: null }), true);
  assert.equal(h.accrualBlockedByGroup({ status: 2, verifiedGroupKey: 'g-1' }), false);
  assert.equal(h.accrualBlockedByGroup({ status: 1, verifiedGroupKey: null }), false);
});

test('§18 G 归因：UNBOUND/OWNER_NOT_SET 不出现数字', () => {
  const unbound = h.attributionView({ status: 'UNBOUND', confirmed: false, paidOrders: null, paidAmount: null });
  assert.equal(unbound.confirmed, false);
  assert.equal(unbound.text, '未绑定推广来源，暂无确认数据');
  assert.equal(unbound.paidOrders, undefined);
  const owner = h.attributionView({ status: 'OWNER_NOT_SET', confirmed: false });
  assert.equal(owner.text, '该活动未设置归属作者，成交数据不可用');
  const bound = h.attributionView({ status: 'BOUND', confirmed: true, attributedOrders: 3, paidOrders: 2, paidAmount: 30, refundedOrders: null, refundedAmount: null, amountCurrency: 'CNY' });
  assert.equal(bound.paidOrders, '2');
  assert.equal(bound.paidAmount, '¥30.00');
  assert.equal(bound.refundedOrders, '—');
  assert.equal(bound.refundedAmount, '—');
});

test('审核动作只对服务端允许的状态出现', () => {
  assert.equal(h.canReviewSubmission(0), true);
  assert.equal(h.canReviewSubmission(1), true);
  assert.equal(h.canReviewSubmission(5), true);
  assert.equal(h.canReviewSubmission(2), false);
  assert.equal(h.canReviewSubmission(null), false);
  assert.equal(h.canRevokeSubmission(2), true);
  assert.equal(h.canRevokeSubmission(3), false);
});

test('§20.2 撤销待退款确认不显示撤销终态；明文码只出现一次', () => {
  assert.equal(h.voucherStatusLabel({ status: 1, revokePending: true }), '待渠道退款确认');
  assert.equal(h.voucherStatusLabel({ status: 2 }), '已撤销');
  assert.equal(h.issuedCode({ code: 'ABCD', codeAvailable: true, replayed: false }), 'ABCD');
  assert.equal(h.issuedCode({ code: 'ABCD', codeAvailable: false }), null);
  assert.equal(h.issuedCode({ code: 'ABCD', replayed: true }), null);
});

test('§20.1 团体/学校 null 是未声明，不是不接待', () => {
  assert.equal(h.eligibilityLabel(null), '未声明');
  assert.equal(h.eligibilityLabel(undefined), '未声明');
  assert.equal(h.eligibilityLabel(false), '不接待');
  assert.equal(h.eligibilityLabel(1), '接待');
  assert.equal(h.translationStateLabel('PENDING'), '待审校');
  assert.equal(h.translationStateLabel('APPROVED'), '已审校');
});

test('§27.4 repeatSignupV1：零成熟样本显示未成熟，不显示 0%', () => {
  assert.equal(h.repeatSignupRateText({ matureMembers: 0, retentionRate: null }), '未成熟/无可计算样本');
  assert.equal(h.repeatSignupRateText({ matureMembers: 10, retentionRate: 0.25 }), '25.0%');
  assert.equal(h.repeatSignupRateText({ matureMembers: 10, retentionRate: 0 }), '0.0%');
  assert.equal(h.repeatSignupRateText(null), '—');
  assert.equal(h.v2RetentionText({ mature: false, cohort: { retentionRate: 0 } }, 'retentionRate'), '未成熟');
  assert.equal(h.v2RetentionText({ mature: true, cohort: { retentionRate: null } }, 'retentionRate'), '—');
});

test('§19 分配状态：unknown 不当 0，冲回单列', () => {
  assert.equal(h.allocationStatusLabel(1), '已入账 · 待可结算');
  assert.equal(h.allocationStatusLabel(5), '已全额冲回');
  assert.equal(h.allocationStatusLabel(null), '状态待确认');
  assert.equal(h.lineEntryLabel(2), '退款冲回');
  assert.equal(h.tipStatusLabel(5), '收款未开通');
  assert.equal(h.tipStatusLabel(3), '已取消');
});

test('业务拒绝保留 reasonCode 与原文', () => {
  const e = h.businessError({ code: 409, msg: '核验版本冲突，请重新读取后重试', reasonCode: 'GROUP_VERIFY_VERSION_CONFLICT' });
  assert.equal(e.reasonCode, 'GROUP_VERIFY_VERSION_CONFLICT');
  assert.equal(e.message, '核验版本冲突，请重新读取后重试');
  assert.equal(h.businessError(new Error('x')).reasonCode, null);
});

test('新页面没有打款、标已付、现金兑付或批量批准入口', () => {
  const pages = [
    'src/views/business/videobounty/index.vue',
    'src/views/business/hosttip/index.vue',
    'src/views/business/contentlanding/index.vue',
    'src/views/business/channel/index.vue',
    'src/views/business/revenue/index.vue',
    'src/views/business/analytics/postgame.vue'
  ];
  for (const page of pages) {
    const template = read(page).split('<script>')[0];
    assert.doesNotMatch(template, /可提现|已到账|标已付|批量批准|现金兑付|>\s*打款\s*</, page);
  }
  const bounty = read('src/views/business/videobounty/index.vue');
  assert.match(bounty, /cashPayoutEnabled: 0/);
  assert.match(bounty, /参考值，非已批准费率/);
});

test('营销分配页只调用只读端点', () => {
  const api = read('src/api/business/revenue.js');
  assert.doesNotMatch(api, /method: 'post'|advance-settleable|\/accrue|\/reverse|posting\/consume/);
});

test('rawBusinessError 只改变调用方显式选择的请求', () => {
  const request = read('src/utils/request.js');
  assert.match(request, /res\.config && res\.config\.rawBusinessError/);
  const order = request.indexOf('code === 401');
  assert.ok(order > 0 && order < request.indexOf('rawBusinessError'), '401 仍先走重新登录');
});

test('U07-05 结算卡片：未知显示 —，确认对账不写成已结算/已打款', () => {
  const page = read('src/views/business/settlement/index.vue').split('<script>')[0];
  assert.doesNotMatch(page, /\|\| 0 }}/);
  assert.match(page, /已对账：\{\{ moneyText\(reconciliation\.settledAmount\) \}\}/);
  assert.doesNotMatch(page, /已结算：/);
});

test('§27.7 后台售后按实际交付行发起', () => {
  const page = read('src/views/business/finance/refund/index.vue');
  assert.match(page, /label="交付行 ID" prop="sourceId"/);
  assert.match(page, /<el-option label="报名订单" value="registration" \/>/);
});

test('U03-04 工作台版本：未公开不补 0', () => {
  const page = read('src/views/business/topic/workspace.vue');
  assert.match(page, /'尚未公开'/);
  assert.match(page, /publishedConfigVersion/);
});
