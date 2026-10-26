const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const wire = require('../../utils/free-explore-bundle.js');
const source = fs.readFileSync(path.join(__dirname, '../../subpackageMember/free-explore/index.js'), 'utf8');
const ID = '9223372036854775807';
function raw(owner = '11', status = 'BOOKED') {
  const scope = { holderMemberId: owner, registrationId: ID };
  return { registrationId: ID, registrationNo: 'order-A', paymentStatus: 2, registrationStatus: 2,
    bundle: { order: scope, units: [{ ...scope, id: '31', chapterId: '7', status: 'RESERVED' }],
      reservations: [{ ...scope, id: '41', unitId: '31', status, startAt: '2026-10-28 12:00:00' }], exitCases: [] } };
}
function harness(options = { registrationId: ID }) {
  let owner = '11', token = 'token-A', page, confirm;
  const requests = [], quotes = [], storage = new Map(); let storageFails = false;
  const app = { getUserID: () => owner, getAuthorization: () => token, sendRequest: r => {
    if (r.url.endsWith('/cancel-quote')) {
      quotes.push(r);
      r.success({ code: 200, data: { holderMemberId: owner, registrationId: ID, reservationId: '41',
        useWaiver: r.data.useWaiver, outcome: r.data.useWaiver ? 'WAIVED' : 'LATE_CANCELLED',
        expectedOutcome: r.data.useWaiver ? 'WAIVED:2026-10' : 'LATE_CANCELLED', waiverMonth: '2026-10', canCancel: true } });
    } else requests.push(r);
  } };
  const wx = { getStorageSync: k => storage.get(k), setStorageSync: (k, v) => {
    if (storageFails) throw Error('full'); storage.set(k, JSON.parse(JSON.stringify(v)));
  }, removeStorageSync: k => storage.delete(k) };
  vm.runInNewContext(source, { getApp: () => app, wx, Date, Page: definition => { page = definition; },
    require: name => name.includes('modal') ? { show: options => { confirm = options; } } : wire });
  page.setData = patch => Object.assign(page.data, patch);
  page.onLoad(options); page.onShow();
  return { page, requests, quotes, storage, confirm: result => confirm.success(result), answer: (index, data) => requests[index].success({ code: 200, data }),
    cancel: waiver => { page.cancelReservation({ currentTarget: { dataset: { id: '41', waiver } } }); confirm.success({ confirm: true }); },
    switchOwner: () => { owner = '22'; token = 'token-B'; }, failStorage: () => { storageFails = true; } };
}
test('exact IDs reject already rounded numeric values and decode only this holder', () => {
  assert.equal(wire.exactId(ID), ID); assert.equal(wire.exactId(Number(ID)), '');
  assert.throws(() => wire.order(raw('22'), '11', ID));
  assert.equal(wire.order(raw(), '11', ID).registrationId, ID);
});
test('page reads actual order and keeps payment and activation separate', () => {
  const h = harness(); h.answer(0, raw());
  assert.equal(h.requests[0].data.registrationId, ID);
  assert.equal(h.page.data.detail.paymentStatus, 2); assert.equal(h.page.data.detail.registrationStatus, 2);
  assert.equal(h.page.data.detail.reservations[0].canCancel, true);
});
test('cancellation preserves original input on timeout and BOOKED negative readback', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(true);
  assert.equal(h.requests[1].method, 'POST'); assert.deepEqual(JSON.parse(h.requests[1].data), { useWaiver: true, expectedOutcome: 'WAIVED:2026-10' });
  assert.equal(h.quotes.length, 1);
  h.requests[1].fail({}); const pending = h.page.data.pending;
  h.page.reload(); h.answer(2, raw());
  assert.equal(h.page.data.pending, pending); assert.equal(h.storage.size, 1);
  h.page.cancelReservation({ currentTarget: { dataset: { id: '41', waiver: false } } });
  assert.equal(h.requests.length, 3);
});
test('re-entry recovers pending without resending and positive terminal read clears it', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(false); h.requests[1].fail({});
  const id = h.page.data.pending.requestId;
  h.page.onHide(); h.page.onShow(); assert.equal(h.page.data.pending.requestId, id);
  assert.equal(h.requests.filter(r => r.method === 'POST').length, 1);
  h.answer(2, raw('11', 'CANCELLED')); assert.equal(h.page.data.pending, null); assert.equal(h.storage.size, 0);
});
test('malformed positive and wrong reservation receipt remain UNKNOWN', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(false);
  h.answer(1, { ...raw().bundle.reservations[0], id: '99', status: 'CANCELLED' });
  assert.ok(h.page.data.pending); assert.equal(h.storage.size, 1);
});
test('late response after switching account never paints old order or clears old pending', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(true); h.switchOwner();
  h.answer(1, { ...raw().bundle.reservations[0], status: 'WAIVED' });
  assert.equal(h.page.data.detail, null); assert.equal(h.page.data.pending, null); assert.equal(h.storage.size, 1);
  h.answer(2, raw('22')); assert.equal(h.page.data.detail.registrationNo, 'order-A');
});
test('storage failure prevents the cancellation request', () => {
  const h = harness(); h.answer(0, raw()); h.failStorage(); h.cancel(false);
  assert.equal(h.requests.length, 1); assert.equal(h.page.data.pending, null);
});
test('explicit waiver refusal releases pending without claiming success', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(true);
  h.requests[1].success({ code: 409, businessCode: 'BUNDLE_WAIVER_USED', msg: '本月已使用' });
  assert.equal(h.page.data.pending, null); assert.equal(h.page.data.error, '本月已使用');
});
test('quote calls read-only API and labels result as neither application nor arrival', () => {
  const h = harness(); h.answer(0, raw()); h.page.loadQuote();
  assert.equal(h.requests[1].method, 'GET');
  h.answer(1, { route: 'PLATFORM_BALANCE', amount: '2000.01', reason: '部分使用' });
  assert.match(h.page.data.quote.notice, /尚未提交退款/); assert.match(h.page.data.quote.notice, /不代表到账/);
});
test('legacy order remains readable without inventing entitlements', () => {
  const h = harness(); h.answer(0, { registrationId: ID, bundle: null });
  assert.equal(h.page.data.detail.legacy, true); assert.equal(h.page.data.detail.units.length, 0);
});
test('late hidden-page response is ignored', () => {
  const h = harness(); h.page.onHide(); h.answer(0, raw()); assert.equal(h.page.data.detail, null);
});

test('a unit status is not a valid reservation receipt', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(false);
  h.answer(1, { ...raw().bundle.reservations[0], status: 'AVAILABLE' });
  assert.ok(h.page.data.pending);
});
test('missing reservation in readback cannot prove cancellation failed', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(true); h.requests[1].fail({});
  h.page.reload(); const data = raw(); data.bundle.reservations = []; h.answer(2, data);
  assert.ok(h.page.data.pending); assert.equal(h.storage.size, 1);
});

test('expired grant does not advertise an AVAILABLE unit as usable', () => {
  const data = raw(); data.bundle.units[0].status = 'AVAILABLE'; data.bundle.units[0].grantId = '51';
  data.bundle.balances = [{ grantId: '51', holderMemberId: '11', expired: true }];
  assert.equal(wire.order(data, '11', ID).units[0].statusText, '当前不在有效期内');
});
test('paid order is not presented as activated or booked when registration is still pending', () => {
  const data = raw(); data.registrationStatus = 1;
  const view = wire.order(data, '11', ID);
  assert.equal(view.paymentText, '已支付'); assert.equal(view.registrationText, '待支付');
});

test('merchant-exited reservation history is valid terminal data', () => {
  const h = harness(); h.answer(0, raw('11', 'MERCHANT_EXITED'));
  assert.equal(h.page.data.detail.reservations[0].statusText, '商家已退出');
});
test('changed cancellation quote is refused without leaving a pending penalty', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(false);
  h.requests[1].success({ code: 409, businessCode: 'BUNDLE_CANCEL_QUOTE_CHANGED', msg: '重新试算' });
  assert.equal(h.page.data.pending, null); assert.equal(h.page.data.error, '重新试算');
});
test('swap requires selected server option and explicit consent, unknown remains until matching history', () => {
  const h = harness(); h.answer(0, raw());
  h.page.loadExitOptions({ currentTarget: { dataset: { id: '31' } } });
  h.answer(1, { holderMemberId: '11', registrationId: ID, unitId: '31', merchantExited: true,
    targets: [{ offerId: '82', merchantName: '乙店' }] });
  h.page.consentSwap({ currentTarget: { dataset: { id: '99' } } }); assert.equal(h.requests.length, 2);
  h.page.consentSwap({ currentTarget: { dataset: { id: '82' } } }); assert.equal(h.requests.length, 2);
  h.confirm({ confirm: true });
  const intent = JSON.parse(h.requests[2].data);
  assert.equal(intent.targetOfferId, '82'); assert.equal(intent.decision, 'SWAP_CONSENTED');
  h.requests[2].fail({}); h.page.onHide(); h.page.onShow();
  assert.equal(h.page.data.swapPending.idempotencyKey, intent.idempotencyKey);
  h.answer(3, raw()); assert.ok(h.page.data.swapPending);
  h.page.reload(); const done = raw('11', 'MERCHANT_EXITED');
  done.bundle.exitCases = [{ id: '61', holderMemberId: '11', registrationId: ID, unitId: '31',
    targetOfferId: '82', idempotencyKey: intent.idempotencyKey, decision: 'SWAP_CONSENTED' }];
  h.answer(4, done); assert.equal(h.page.data.swapPending, null); assert.equal(h.storage.size, 0);
});
test('foreign exit options are rejected before showing consent', () => {
  const h = harness(); h.answer(0, raw()); h.page.loadExitOptions({ currentTarget: { dataset: { id: '31' } } });
  h.answer(1, { holderMemberId: '22', registrationId: ID, unitId: '31', targets: [] });
  assert.equal(h.page.data.exitOptions, null);
});

test('purchase UNKNOWN readback retains original key and never initiates another order', () => {
  const h = harness({ requestId: 'purchase-original' });
  assert.match(h.requests[0].url, /order\/receipt$/);
  h.answer(0, { requestId: 'purchase-original', receipt: 'NOT_FOUND', outcome: 'UNKNOWN' });
  assert.equal(h.page._registrationId, ''); assert.match(h.page.data.notice, /不代表未购买/);
  h.page.reload(); assert.equal(h.requests[1].data.requestId, 'purchase-original');
  h.answer(1, { requestId: 'purchase-original', receipt: 'FOUND', order: raw() });
  assert.equal(h.page._registrationId, ID); assert.equal(h.requests[2].data.registrationId, ID);
  assert.ok(h.requests.every(request => request.method === 'GET'));
});
test('mismatched purchase receipt cannot substitute an order', () => {
  const h = harness({ requestId: 'purchase-original' });
  h.answer(0, { requestId: 'other', receipt: 'FOUND', order: raw() });
  assert.equal(h.page.data.detail, null); assert.equal(h.page._registrationId, '');
});
test('catalog uses F3 chapter offers and no SKU or purchase fallback', () => {
  const h = harness({ topicId: '70' });
  h.answer(0, { topicId: '70', productType: 2, topicName: '探索', saleEnabled: false,
    items: [{ chapterId: '7', name: '第一章', unitsPerHolder: 1, stores: [{ offerId: '81', merchantName: '甲店', available: false }] }] });
  assert.equal(h.page.data.catalog.items[0].stores[0].offerId, '81');
  assert.equal(h.page.data.catalog.items[0].stores[0].available, false);
  assert.equal(h.page.data.catalog.saleEnabled, false); assert.equal(h.requests.length, 1);
});
test('swap refusal does not silently pick another target', () => {
  const h = harness(); h.answer(0, raw());
  h.page.loadExitOptions({ currentTarget: { dataset: { id: '31' } } });
  h.answer(1, { holderMemberId: '11', registrationId: ID, unitId: '31', merchantExited: true,
    targets: [{ offerId: '82', merchantName: '乙店' }, { offerId: '83', merchantName: '丙店' }] });
  h.page.consentSwap({ currentTarget: { dataset: { id: '82' } } }); h.confirm({ confirm: true });
  h.requests[2].success({ code: 409, businessCode: 'BUNDLE_STORE_UNAVAILABLE', msg: '乙店已关闭' });
  assert.equal(h.page.data.swapPending, null); assert.equal(h.page.data.exitOptions, null);
  assert.equal(h.requests.filter(request => request.method === 'POST').length, 1);
});

function reviewPage(registrationId = ID, canCreate = true, items = []) {
  return { mode: 'public', pageNum: 1, hasMore: false, items,
    eligibility: { canCreate, requestedRegistrationId: ID, registrationId: canCreate ? registrationId : null } };
}
function reviewHarness() {
  const h = harness(), data = raw(); data.bundle.reservations[0].merchantId = '9001'; h.answer(0, data);
  h.page.loadReviews({ currentTarget: { dataset: { memberId: '9001' } } });
  return h;
}
function eligibleReview() {
  const h = reviewHarness(); h.answer(1, { memberId: '9001', id: '8001' }); h.answer(2, reviewPage());
  h.page.inputReview({ detail: { value: '实际体验评价' } }); return h;
}
test('review maps member only and uses POST query merchantRowId without losing long IDs', () => {
  const h = reviewHarness();
  assert.equal(h.requests[1].url, '/api/merchant/public-home');
  assert.deepEqual(JSON.parse(h.requests[1].data), { memberId: '9001' });
  h.answer(1, { memberId: '9001', id: ID });
  assert.equal(h.requests[2].url, '/api/merchant/reviews/public?merchantRowId=' + ID + '&registrationId=' + ID + '&pageNum=1&pageSize=20');
  assert.equal(h.requests[2].method, 'POST');
  h.answer(2, reviewPage()); assert.equal(h.page.data.review.canCreate, true);
});
test('mapping alone gives no eligibility and unsafe or mismatched mapping fails closed', () => {
  for (const mapped of [{ memberId: '9001', id: Number(ID) }, { memberId: '9002', id: '8001' }]) {
    const h = reviewHarness(); h.answer(1, mapped);
    assert.equal(h.requests.length, 2); assert.equal(h.page.data.review, null);
    h.page.submitReview(); assert.equal(h.requests.length, 2);
  }
  const h = reviewHarness(); h.requests[1].success({ code: 500, msg: '商家不可见' });
  assert.equal(h.requests.length, 2); assert.equal(h.page.data.review, null);
});
test('review qualification cannot substitute another order or rounded order ID', () => {
  for (const id of ['123', Number(ID)]) {
    const h = reviewHarness(); h.answer(1, { memberId: '9001', id: '8001' }); h.answer(2, reviewPage(id));
    assert.ok(!h.page.data.review || !h.page.data.review.canCreate);
    h.page.submitReview(); assert.equal(h.requests.length, 3);
  }
});
test('no unused redemption is respected despite successful public mapping', () => {
  const h = reviewHarness(); h.answer(1, { memberId: '9001', id: '8001' }); h.answer(2, reviewPage(null, false));
  assert.equal(h.page.data.review.canCreate, false); h.page.submitReview(); assert.equal(h.requests.length, 3);
});
test('review UNKNOWN re-entry freezes payload and replays only after explicit recovery', () => {
  const h = eligibleReview(); h.page.submitReview(); const original = h.requests[3].data;
  assert.equal(JSON.parse(original).registrationId, ID); assert.equal(JSON.parse(original).merchantRowId, '8001');
  h.requests[3].fail({}); h.page.inputReview({ detail: { value: '不能覆盖原内容' } }); h.page.submitReview();
  assert.equal(h.requests.length, 4);
  h.page.onHide(); h.page.onShow(); assert.equal(h.requests.length, 5);
  assert.equal(h.page.data.reviewPending.content, '实际体验评价');
  h.page.resumeReview(); assert.equal(h.requests[5].data, original);
  h.answer(5, { reviewId: '123', status: 'PENDING_REVIEW', replayed: true });
  assert.equal(h.page.data.reviewPending, null); assert.equal(h.storage.size, 0);
  assert.match(h.page.data.notice, /尚未公开/);
});
test('review storage failure prevents send and invalid draft cannot submit', () => {
  const h = eligibleReview(); h.page.inputReview({ detail: { value: '短' } }); h.page.submitReview();
  assert.equal(h.requests.length, 3);
  h.page.inputReview({ detail: { value: '实际体验评价' } }); h.failStorage(); h.page.submitReview();
  assert.equal(h.requests.length, 3); assert.equal(h.page.data.reviewPending, null);
});
test('unsafe receipt and later rejection never erase an UNKNOWN review', () => {
  const h = eligibleReview(); h.page.submitReview(); const id = h.page.data.reviewPending.requestId;
  h.answer(3, { reviewId: Number(ID), status: 'PENDING_REVIEW' });
  assert.equal(h.page.data.reviewPending.requestId, id);
  h.page.resumeReview(); h.requests[4].success({ code: 403, msg: '资格已变化' });
  assert.equal(h.page.data.reviewPending.requestId, id); assert.equal(h.storage.size, 1);
});
test('late mapping and review receipts cannot cross account or hidden-page boundaries', () => {
  const h = reviewHarness(); h.switchOwner(); h.answer(1, { memberId: '9001', id: '8001' });
  assert.equal(h.page.data.review, null); assert.ok(!h.requests.some(r => r.url.includes('/reviews/public')));
  const second = eligibleReview(); second.page.submitReview(); second.page.onHide();
  second.answer(3, { reviewId: '123', status: 'PENDING_REVIEW' });
  assert.equal(second.storage.size, 1); assert.equal(second.page.data.reviewPending, null);
});
test('review pagination preserves first page on failure and retries same page', () => {
  const h = reviewHarness(); h.answer(1, { memberId: '9001', id: '8001' });
  const first = reviewPage(ID, true, [{ id: '1', status: 'VISIBLE', content: '公开评价', rating: 4 }]);
  first.hasMore = true; h.answer(2, first); h.page.moreReviews(); h.requests[3].fail({});
  assert.equal(h.page.data.review.pageNum, 1); assert.equal(h.page.data.review.items.length, 1);
  h.page.moreReviews(); assert.equal(h.requests[3].url, h.requests[4].url);
  h.answer(4, { ...reviewPage(), pageNum: 2, items: [{ id: '2', status: 'VISIBLE', content: '另一评价', rating: 5 }] });
  assert.equal(h.page.data.review.pageNum, 2); assert.equal(h.page.data.review.items.length, 2);
});
test('unknown merchant from event is not accepted as an order merchant', () => {
  const h = harness(); h.answer(0, raw());
  h.page.loadReviews({ currentTarget: { dataset: { memberId: '9001' } } }); assert.equal(h.requests.length, 1);
});

test('hide clears every view field and account re-entry starts from the same empty state', () => {
  const h = harness(), expected = JSON.parse(JSON.stringify(h.page.data));
  expected.loading = false;
  for (const key of Object.keys(h.page.data)) h.page.data[key] = 'old-account-data';
  h.page.onHide();
  assert.deepEqual(JSON.parse(JSON.stringify(h.page.data)), expected);
  h.switchOwner(); h.page.onShow();
  assert.deepEqual(JSON.parse(JSON.stringify(h.page.data)), { ...expected, loading: true });
});

test('walk-in active fulfillment opens merchant mapping without any reservation', () => {
  const h = harness(), data = raw(); data.bundle.reservations = [];
  data.bundle.fulfilledMerchants = [{ holderMemberId: '11', registrationId: ID, merchantId: '9001' }];
  h.answer(0, data);
  h.page.loadReviews({ currentTarget: { dataset: { memberId: '9001' } } });
  assert.deepEqual(JSON.parse(h.requests[1].data), { memberId: '9001' });
  h.answer(1, { memberId: '9001', id: '8001' }); h.answer(2, reviewPage('123'));
  assert.equal(h.page.data.review.canCreate, false);
  h.page.submitReview(); assert.equal(h.requests.length, 3);
});
test('fulfilled merchant projection rejects another holder, order, and unsafe ID', () => {
  for (const row of [
    { holderMemberId: '22', registrationId: ID, merchantId: '9001' },
    { holderMemberId: '11', registrationId: '123', merchantId: '9001' },
    { holderMemberId: '11', registrationId: ID, merchantId: Number(ID) },
  ]) {
    const data = raw(); data.bundle.fulfilledMerchants = [row];
    assert.throws(() => wire.order(data, '11', ID));
  }
  assert.deepEqual(wire.order(raw(), '11', ID).fulfilledMerchants, []);
});

test('exact review scope must echo current order and cannot accept legacy or rounded scope', () => {
  for (const scope of [undefined, '123', Number(ID)]) {
    const h = reviewHarness(); h.answer(1, { memberId: '9001', id: '8001' });
    const data = reviewPage(); data.eligibility.requestedRegistrationId = scope; h.answer(2, data);
    assert.equal(h.page.data.review, null); h.page.submitReview(); assert.equal(h.requests.length, 3);
  }
});
test('exact order eligibility enables current order even with other orders at same merchant', () => {
  const h = eligibleReview();
  assert.ok(h.requests[2].url.includes('registrationId=' + ID));
  h.page.submitReview(); assert.equal(JSON.parse(h.requests[3].data).registrationId, ID);
});

test('refund approval and rejected or manual outcomes never display original-channel arrival', () => {
  for (const state of ['PREPARED', 'BOUND', 'APPROVED', 'REJECTED_HELD', 'MANUAL_REVIEW_HELD', 'RECONCILIATION_REQUIRED']) {
    const data = raw(); data.bundle.refundHold = { holderMemberId: '11', registrationId: ID,
      sourceRegistrationId: ID, refundId: '777', state };
    data.paymentStatus = 4; // Existing registration projection is updated at approval, not payout.
    const h = harness(); h.answer(0, data);
    assert.doesNotMatch(h.page.data.detail.paymentText, /已退款|已到账/);
    assert.equal(h.page.data.detail.refundHold.state, state);
    assert.doesNotMatch(h.page.data.detail.refundHold.text, /已确认到账/);
    assert.equal(h.page.data.detail.refundHold.refundId, '777');
  }
});
test('zero cash cancellation has no synthetic refund id and success is explicit', () => {
  const data = raw(); data.bundle.refundHold = { holderMemberId: '11', registrationId: ID,
    sourceRegistrationId: ID, refundId: null, state: 'ZERO_CASH_COMPLETED' };
  assert.match(wire.order(data, '11', ID).refundHold.text, /没有现金退款/);
  data.bundle.refundHold.refundId = '777'; data.bundle.refundHold.state = 'SUCCEEDED';
  assert.match(wire.order(data, '11', ID).refundHold.text, /已确认到账/);
  data.bundle.refundHold.refundId = Number(ID);
  assert.throws(() => wire.order(data, '11', ID));
  data.bundle.refundHold.refundId = '777'; data.bundle.refundHold.holderMemberId = '22';
  assert.throws(() => wire.order(data, '11', ID));
});

test('refund freeze is a definitive cancellation refusal and does not claim cancellation success', () => {
  const h = harness(); h.answer(0, raw()); h.cancel(false);
  h.requests[1].success({ code: 409, businessCode: 'BUNDLE_REFUND_PENDING', msg: '退款冻结中' });
  assert.equal(h.page.data.pending, null);
  assert.equal(h.storage.size, 0);
  assert.equal(h.page.data.error, '退款冻结中');
  assert.equal(h.page.data.detail.reservations[0].status, 'BOOKED');
});
