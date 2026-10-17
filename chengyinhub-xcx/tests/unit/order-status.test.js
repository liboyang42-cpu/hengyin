const test = require('node:test');
const assert = require('node:assert/strict');
const { summarizeOrderState, canRequestOrderRefund } = require('../../utils/order-status.js');

const NOW = new Date('2026-07-21T12:00:00+08:00').getTime();

test('人工案件进度优先于已核销主单的普通完成态，不虚报原路退款', () => {
  for (const verificationStatus of [0, 1]) {
    for (const manualRefundCaseStatus of ['NO_REFUND', 'PENDING_ASSESSMENT', 'MANUAL_REFUND_REQUIRED',
      'MANUAL_REFUND_RECORDED', 'MANUAL_REFUND_EVIDENCE_VERIFIED']) {
      const row = registration({ verificationStatus, manualRefundCaseStatus,
        refundInfo: { refundable: false, reason: '人工案件权威处理结果' } });
      const summary = summarizeOrderState(row, NOW);
      assert.equal(summary.key, 'manual_refund');
      assert.equal(summary.refundText, row.refundInfo.reason);
      assert.equal(summary.text, '人工售后');
    }
  }
});

function registration(extra) {
  return Object.assign({
    registrationStatus: 2,
    verificationStatus: 0,
    cmsTopic: { startDate: '2026-07-21 13:00:00', endDate: '2026-07-21 18:00:00' },
    refundInfo: { refundable: true, reason: '可全额退款,原路退回(预计1-3个工作日)' }
  }, extra || {});
}

test('订单状态覆盖待支付、未开始、进行中与已完成', () => {
  assert.equal(summarizeOrderState(registration({ registrationStatus: 1 }), NOW).key, 'pending_payment');
  assert.equal(summarizeOrderState(registration(), NOW).key, 'not_started');
  assert.equal(summarizeOrderState(registration({ cmsTopic: { startDate: '2026-07-21 10:00:00', endDate: '2026-07-21 18:00:00' } }), NOW).key, 'in_progress');
  assert.equal(summarizeOrderState(registration({ verificationStatus: 1 }), NOW).key, 'completed');
});

test('退款状态以后台 payoutStatus 为准，不把处理中伪装成已退款', () => {
  assert.equal(summarizeOrderState(registration({ registrationStatus: 3, refundApplication: { payoutStatus: 0 } }), NOW).key, 'refunding');
  assert.equal(summarizeOrderState(registration({ registrationStatus: 3, refundApplication: { payoutStatus: 1 } }), NOW).key, 'refunded');
  assert.equal(summarizeOrderState(registration({ registrationStatus: 3, refundApplication: { payoutStatus: 3 } }), NOW).key, 'refunding');
});

test('payoutStatus=2 PENDING_MANUAL 归入退款中并提示人工处理', () => {
  const summary = summarizeOrderState(registration({
    registrationStatus: 3,
    refundApplication: { payoutStatus: 2 }
  }), NOW);
  assert.equal(summary.key, 'refunding');
  assert.equal(summary.text, '退款处理中');
  assert.equal(summary.refundText, '原路退款异常，平台正在人工处理');
});

test('payoutStatus=4 MANUAL_VERIFIED 归入已退款', () => {
  const summary = summarizeOrderState(registration({
    registrationStatus: 3,
    refundApplication: { payoutStatus: 4 }
  }), NOW);
  assert.equal(summary.key, 'refunded');
  assert.equal(summary.text, '已退款');
  assert.equal(summary.refundText, '退款已原路退回');
});

// 4.1 资损文案(2026-09-15 T3f):取值真源 = 后端 RefundApplication。
//   status:0 待审核 / 1 批准 / 2 驳回(RefundServiceImpl.reviewRefund)。
//   payout_status:建单即 5(saveRefund),批准后 6 派发中 → 0 微信已受理 → 1/2/3/4。
// payoutStatus 不在 1–4 时,只能说真实走到的那一步,不许出「预计 1-3 个工作日」这种固定承诺。
test('payoutStatus 不在 1–4:按审核/派发事实出文案,不出固定到账承诺', () => {
  const pick = app => summarizeOrderState(registration({ registrationStatus: 3, refundApplication: app }), NOW);
  const review = pick({ status: 0, payoutStatus: 5, refundAmount: 69 });
  assert.equal(review.key, 'refunding');
  assert.equal(review.text, '退款审核中');
  assert.match(review.refundText, /审核/);

  for (const payoutStatus of [5, 6, null]) {
    const s = pick({ status: 1, payoutStatus, refundAmount: 69 });
    assert.equal(s.key, 'refunding');
    assert.match(s.refundText, /已批准/);
  }
  const accepted = pick({ status: 1, payoutStatus: 0, refundAmount: 69 });
  assert.match(accepted.refundText, /微信已受理/);

  for (const app of [{ status: 0, payoutStatus: 5 }, { status: 1, payoutStatus: 5 }, { status: 1, payoutStatus: 0 },
    { payoutStatus: 5 }, { status: 2, payoutStatus: 5 }, {}]) {
    assert.doesNotMatch(pick(Object.assign({ refundAmount: 69 }, app)).refundText, /1-3|个工作日/, JSON.stringify(app));
  }
});

test('驳回的退款单不再冒充退款中,回落到订单真实状态', () => {
  const s = summarizeOrderState(registration({
    refundApplication: { status: 2, payoutStatus: 5, refundAmount: 69, rejectReason: '已过退款截止时间' }
  }), NOW);
  assert.equal(s.key, 'not_started');
  assert.doesNotMatch(s.text, /退款/);
});

test('status 为 null 不当成待审核;已到账(payout 1/4)不被零元判断吞掉', () => {
  const s = summarizeOrderState(registration({ registrationStatus: 3, refundApplication: { status: null, payoutStatus: 5, refundAmount: 69 } }), NOW);
  assert.notEqual(s.text, '退款审核中');
  const done = summarizeOrderState(registration({ registrationStatus: 3, refundApplication: { status: 1, payoutStatus: 1, refundAmount: 0 } }), NOW);
  assert.equal(done.key, 'refunded');
});

test('零元单(退款额 ≤ 0)不出任何退款承诺', () => {
  for (const refundAmount of [0, '0.00']) {
    const s = summarizeOrderState(registration({ refundApplication: { status: 1, payoutStatus: 5, refundAmount } }), NOW);
    assert.notEqual(s.key, 'refunding');
    assert.notEqual(s.key, 'refunded');
  }
});

test('仍在进行的已支付订单错过退款窗口时显示不可退款', () => {
  const row = registration({
    cmsTopic: { startDate: '2026-07-21 10:00:00', endDate: '2026-07-21 18:00:00' },
    refundInfo: { refundable: false, reason: '已错过主题有效期,不可退款' }
  });
  const summary = summarizeOrderState(row, NOW);
  assert.equal(summary.key, 'non_refundable');
  assert.equal(summary.refundText, '已错过主题有效期,不可退款');
});


test('ORDER-01:首次合法扫码的已支付订单已完成，退款仍读真实资格', () => {
  const row = registration({ paymentStatus: 2, firstSuccessfulScanAt: '2026-07-21 11:00:00',
    refundInfo: { refundable: true, reason: '零元未核销订单可直接退出' } });
  const summary = summarizeOrderState(row, NOW);
  assert.equal(summary.key, 'completed');
  assert.equal(summary.refundText, row.refundInfo.reason);
  assert.equal(row.verificationStatus, 0);
  assert.equal(canRequestOrderRefund(row), true);

  const nonRefundable = { ...row, refundInfo: { refundable: false, reason: '已过退款截止' } };
  assert.equal(summarizeOrderState(nonRefundable, NOW).key, 'completed');
  assert.equal(summarizeOrderState(nonRefundable, NOW).refundText, '已过退款截止');
  assert.equal(canRequestOrderRefund(nonRefundable), false);
});

test('ORDER-01:缺失或非法扫码时间不产出完成态', () => {
  for (const firstSuccessfulScanAt of [null, undefined, '', 0, false, true, {}, [],
    '2026-02-30 11:00:00', '扫码失败', Number.NaN, Number.POSITIVE_INFINITY]) {
    const row = registration({ paymentStatus: 2, firstSuccessfulScanAt });
    assert.equal(summarizeOrderState(row, NOW).key, 'not_started');
  }
});

test('ORDER-01:扫码时间不覆盖人工售后、退款、取消、未支付或未知状态', () => {
  const row = registration({ paymentStatus: 2, firstSuccessfulScanAt: '2026-07-21 11:00:00' });
  for (const [extra, expected] of [
    [{ manualRefundCaseStatus: 'PENDING_ASSESSMENT' }, 'manual_refund'],
    [{ refundApplication: { status: 0, payoutStatus: 5, refundAmount: 9 } }, 'refunding'],
    [{ refundApplication: { status: 1, payoutStatus: 1, refundAmount: 9 } }, 'refunded'],
    [{ registrationStatus: 3 }, 'cancelled'],
    [{ registrationStatus: 4 }, 'expired'],
    [{ registrationStatus: 1, paymentStatus: 0 }, 'pending_payment'],
    [{ registrationStatus: null }, 'unknown'],
    [{ paymentStatus: 0 }, 'not_started'],
    [{ paymentStatus: null }, 'not_started'],
  ]) {
    assert.equal(summarizeOrderState({ ...row, ...extra }, NOW).key, expected);
  }
});

test('ORDER-01:退款按钮只认后端明确可退和真实付款核销状态', () => {
  const row = registration({ paymentStatus: 2, firstSuccessfulScanAt: '2026-07-21 11:00:00' });
  for (const extra of [
    { refundInfo: null }, { refundInfo: {} }, { refundInfo: { refundable: null } },
    { refundInfo: { refundable: 'true' } }, { refundInfo: { refundable: false } },
    { registrationStatus: 1 }, { registrationStatus: 3 }, { paymentStatus: 0 },
    { paymentStatus: null }, { verificationStatus: 1 }, { manualRefundCaseStatus: 'NO_REFUND' },
    { refundApplication: { status: 0, payoutStatus: 5, refundAmount: 9 } },
    { refundApplication: { status: 1, payoutStatus: 1, refundAmount: 0 } },
  ]) {
    assert.equal(canRequestOrderRefund({ ...row, ...extra }), false);
  }
  assert.equal(canRequestOrderRefund({ ...row,
    refundApplication: { status: 2, payoutStatus: 5, refundAmount: 9 } }), true,
  '已驳回退款申请不阻断后端明确允许的再次自助退出');
});


test('真实活动取消使已核销免费票退出待使用，保留支付/报名原字段不猜退款', () => {
  const row=registration({verificationStatus:1,paymentStatus:2,payableAmount:0,pointUsed:0,
    cmsActivity:{cancelTime:'2026-07-21 11:00:00',startDate:'2026-07-21 13:00:00'}});
  const before=JSON.stringify(row);const state=summarizeOrderState(row,NOW);
  assert.equal(state.key,'cancelled');assert.equal(state.text,'活动已取消');assert.ok(!/退款已|已退款/.test(state.refundText));
  assert.equal(['not_started','in_progress'].includes(state.key),false);assert.equal(JSON.stringify(row),before);
});
test('已核销付费活动取消仍以真实人工和退款进度优先，未取消已核销保持完成', () => {
  const row=registration({verificationStatus:1,paymentStatus:2,payableAmount:99,
    cmsActivity:{cancelTime:'2026-07-21 11:00:00'}});
  assert.equal(summarizeOrderState({...row,manualRefundCaseStatus:'MANUAL_REFUND_REQUIRED'},NOW).key,'manual_refund');
  assert.equal(summarizeOrderState({...row,refundApplication:{payoutStatus:1}},NOW).key,'refunded');
  assert.equal(summarizeOrderState({...row,cmsActivity:{cancelTime:null}},NOW).key,'completed');
});
test('没有真实取消时刻的正常待使用订单不变，非法取消值不推造取消', () => {
  for(const cancelTime of [undefined,null,'','invalid-date']){
    const row=registration({cmsActivity:{cancelTime,startDate:'2026-07-21 13:00:00',endDate:'2026-07-21 18:00:00'}});
    assert.equal(summarizeOrderState(row,NOW).key,'not_started');
  }
});
