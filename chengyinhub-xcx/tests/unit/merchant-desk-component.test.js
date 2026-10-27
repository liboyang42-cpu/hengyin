const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const file = path.resolve(__dirname, '../../pages/merchant/components/desk/index.js');
const clone = value => JSON.parse(JSON.stringify(value));
const ID = '9007199254740993';
function harness(storage = new Map(), features) {
  let definition, user = '501', token = 'session-a';
  const requests = [], timers = [], readTimers = [], events = [], navigation = [];
  const app = { globalData: { features }, getUserID: () => user, getAuthorization: () => token,
    sendRequest: options => { requests.push(options); return { abort() {} }; } };
  const wx = { navigateTo: options => navigation.push(options), scanCode: options => { wx.scan = options; }, getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, clone(value)), removeStorageSync: key => storage.delete(key) };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Component: value => { definition = value; }, getApp: () => app,
    wx, setTimeout: callback => { readTimers.push(callback); return callback; }, clearTimeout() {}, require: name => {
      const module = require(path.resolve(path.dirname(file), name));
      if (name.endsWith('verification-readback')) return { createVerificationWorkflow: options => module.createVerificationWorkflow(Object.assign({}, options, {
        setTimer: callback => { timers.push(callback); return callback; }, clearTimer() {},
      })) };
      return module;
    }, console });
  const component = { data: clone(definition.data), properties: { access: { active: true, merchant: { id: '11' }, canManageProjects: true, canReadVerifyRecords: true } },
    triggerEvent(name, detail) { events.push({ name, detail: clone(detail) }); },
    setData(patch) { Object.assign(this.data, clone(patch)); } };
  Object.entries(definition.methods).forEach(([key, method]) => { component[key] = method.bind(component); });
  definition.lifetimes.attached.call(component);
  const event = id => ({ currentTarget: { dataset: { id } } });
  return { component, requests, storage, event, timers, readTimers, events, navigation, wx,
    switchUser() { user = '502'; token = 'session-b'; component.activate(); },
    destroy() { definition.lifetimes.detached.call(component); },
    reply(index, data) { requests[index].success({ code: 200, data }); } };
}
function prepare(h) {
  h.component.selectProduct(h.event(ID));
  h.reply(0, { productId: ID, editable: true, skus: [{ skuId: '9007199254740995', skuName: '双人', price: '12.30', stock: 4,
    ruleVersion: 7, bookable: 1, slotMinutes: 60, capacityPerSlot: 2, pic: 'existing-attachment' }] });
  h.component.editSku(h.event('9007199254740995'));
}
function applied(request, action = 'SKU_SAVE') { return { requestId: request.data.requestId, state: 'APPLIED', action,
  targetId: '9007199254740995', targetType: action === 'REVIEW_EVIDENCE' ? 'verification_review_evidence' : 'pms_sku' }; }
test('actual component preserves exact IDs, rule version and media during SKU save; positive receipt triggers fresh detail', () => {
  const h = harness(); prepare(h); h.component.saveSku();
  const write = h.requests[1];
  assert.equal(write.data.skuId, '9007199254740995'); assert.equal(write.data.productId, ID);
  assert.equal(write.data.ruleVersion, 7); assert.equal(write.data.pic, 'existing-attachment');
  assert.equal(write.data.price, '12.30'); assert.equal(h.component.data.busy, true);
  h.reply(1, applied(write));
  assert.equal(h.component.data.pending, false); assert.equal(h.storage.size, 0);
  assert.ok(h.requests.some(r => r.url.endsWith('products/detail') && r !== h.requests[0])); h.destroy();
});
test('timeout retains original request and frozen form; negative receipt never unlocks or creates a second POST', () => {
  const h = harness(); prepare(h); h.component.saveSku(); const write = h.requests[1]; write.fail({});
  const id = h.component.data.requestId;
  h.component.skuInput({ currentTarget: { dataset: { field: 'stock' } }, detail: { value: 99 } });
  assert.equal(h.component.data.sku.stock, 4); h.component.saveSku(); assert.equal(h.requests.length, 2);
  h.component.recover(); assert.equal(h.requests[2].data.requestId, id);
  h.reply(2, { requestId: id, state: 'NOT_FOUND' }); assert.equal(h.component.data.pending, true);
  assert.equal(h.component.data.requestId, id); assert.equal(h.storage.size, 1); h.destroy();
});
test('reentry restores original request without replaying payload; matching positive receipt releases pending', () => {
  const h = harness(); prepare(h); h.component.saveSku(); h.requests[1].fail({});
  const requestId = h.component.data.requestId; h.destroy();
  const again = harness(h.storage); assert.equal(again.component.data.requestId, requestId);
  assert.equal(again.requests.length, 0); again.component.recover();
  again.reply(0, { requestId, state: 'APPLIED', action: 'SKU_SAVE', targetType: 'pms_sku', targetId: ID });
  assert.equal(again.component.data.pending, false); assert.equal(h.storage.size, 0); again.destroy();
});
test('mismatched receipt and in-flight response cannot release original UNKNOWN', () => {
  const h = harness(); prepare(h); h.component.saveSku(); h.requests[1].success({ code: 409, errorCode: 'F4_IN_FLIGHT' });
  const requestId = h.component.data.requestId; h.component.recover();
  h.reply(2, { requestId: 'another-request', state: 'APPLIED', action: 'SKU_SAVE', targetType: 'pms_sku', targetId: ID });
  assert.equal(h.component.data.pending, true); assert.equal(h.component.data.requestId, requestId); h.destroy();
});
test('explicit rejected version keeps draft and releases pending; changed account ignores late success', () => {
  const h = harness(); prepare(h); h.component.saveSku();
  h.requests[1].success({ code: 409, errorCode: 'F4_VERSION_CONFLICT', msg: '请刷新版本' });
  assert.equal(h.component.data.pending, false); assert.equal(h.component.data.sku.ruleVersion, 7);
  assert.equal(h.component.data.error, '请刷新版本');
  h.component.saveSku(); const write = h.requests[2]; h.switchUser();
  write.success({ code: 200, data: applied(write) });
  assert.equal(h.component.data.detail, null); assert.equal(h.component.data.notice, '');
  assert.equal(h.storage.size, 1); h.destroy();
});
test('late previous product and slot reads cannot replace current selection', () => {
  const h = harness(); h.component.selectProduct(h.event(ID)); h.component.selectProduct(h.event('2'));
  h.reply(1, { productId: '2', skus: [], editable: true }); h.reply(0, { productId: ID, skus: [], editable: true });
  assert.equal(h.component.data.detail.productId, '2');
  h.component.loadSlots(h.event('8')); assert.equal(h.requests[2].data.days, 14);
  h.component.selectProduct(h.event('3')); h.reply(2, [{ slotId: '99', skuId: '8' }]);
  assert.equal(h.component.data.slots.length, 0); h.destroy();
});
test('unsafe legacy numeric ID fails closed rather than rounding into another product', () => {
  const h = harness(); h.component.loadProducts(); h.reply(0, [{ productId: 9007199254740993 }]);
  assert.equal(h.component.data.products.length, 0); assert.match(h.component.data.error, /无法确认/); h.destroy();
});
test('evidence submits original review ID and note only; rereads verdict instead of declaring changed conclusion', () => {
  const h = harness(); h.component.selectReview(h.event(ID));
  h.reply(0, { reviewId: ID, verdict: 'PENDING', canSubmitEvidence: true, evidence: [] });
  h.component.noteInput({ detail: { value: '补充说明' } }); h.component.saveEvidence();
  const write = h.requests[1]; assert.equal(write.data.reviewId, ID); assert.equal(write.data.verdict, undefined);
  h.reply(1, applied(write, 'REVIEW_EVIDENCE'));
  assert.ok(h.requests.some((r, i) => i > 1 && r.url.endsWith('verdicts/detail'))); h.destroy();
});
test('finance renders source amounts without conflating payable and paid, preserves outside routes and frozen data', () => {
  const h = harness(); h.component.loadFinance();
  const row = { readOnly: true, bases: ['CHAPTER_OFFER', 'COOP_ORDER', 'REGISTRATION_SPLIT', 'NONE'].map(route => ({ route, claimCount: 0, byStatus: {} })), outsideFourBases: [{ route: 'CLUB_DIRECT', claimCount: 2, byStatus: { CREATED: 2 } }] };
  Object.assign(row.bases[0], { payableNetWithClaim: -12.34, payableNetWithoutClaim: 20, payableRowsWithoutClaim: 1 });
  h.reply(0, row); h.reply(1, { rows: [{ batchId: ID, periodYm: '2026-10', amountTotal: 20, holdState: 'FROZEN' }], hasMore: false, next: null });
  assert.deepEqual(h.component.data.finance, row); assert.equal(h.component.data.frozen[0].batchId, ID);
  assert.equal(h.component.data.finance.paid, undefined); h.destroy();
});
test('durable metadata contains no form, evidence, media or raw identity', () => {
  const h = harness(); prepare(h); h.component.saveSku(); h.requests[1].fail({});
  const serialized = JSON.stringify([...h.storage]);
  for (const forbidden of ['双人', 'existing-attachment', 'skuName', 'productId', '501:11']) assert.equal(serialized.includes(forbidden), false);
  h.destroy();
});
test('same merchant permission refresh clears previously visible finance data', () => {
  const h = harness(); h.component.data.finance = { sensitive: true };
  h.component.properties.access = Object.assign({}, h.component.properties.access, { canReadFinance: false, permissions: [] });
  h.component.activate(); assert.equal(h.component.data.finance, null); h.destroy();
});
test('same-account permission refresh clears drafts and preserves UNKNOWN recovery', () => {
  const h = harness(); prepare(h); h.component.saveSku(); h.requests[1].fail({});
  const requestId = h.component.data.requestId;
  h.component.properties.access = Object.assign({}, h.component.properties.access, { canVerify: true });
  h.component.activate(); assert.equal(h.component.data.sku, null);
  assert.equal(h.component.data.requestId, requestId); h.destroy();
});
test('product editing preserves existing images and original price, then reads the returned target', () => {
  const h = harness(); h.component.selectProduct(h.event(ID));
  h.reply(0, { productId: ID, editable: true, productName: '商品', price: 30, originalPrice: 40, stock: 4, pic: 'cover', albumPics: 'a,b', skus: [] });
  h.component.editProduct(); h.component.productInput({ currentTarget: { dataset: { field: 'productName' } }, detail: { value: '商品新名' } });
  h.component.saveProduct(); const write = h.requests[1];
  assert.equal(write.data.productId, ID); assert.equal(write.data.originalPrice, 40); assert.deepEqual(Array.from(write.data.albumPics), ['a', 'b']);
  h.reply(1, { requestId: write.data.requestId, state: 'APPLIED', action: 'PRODUCT_SAVE', targetType: 'pms_product', targetId: ID });
  assert.equal(h.requests[2].data.productId, ID); h.destroy();
});
test('delist requires an explicit confirmation, never posts from the initial button', () => {
  const h = harness(); h.component.data.detail = { productId: ID, editable: false };
  h.component.requestDelist(); assert.equal(h.requests.length, 0);
  h.component.confirmDelist(); assert.equal(h.requests[0].url, '/api/merchant/desk/products/delist');
  assert.equal(h.requests[0].data.productId, ID); h.requests[0].fail({}); h.destroy();
});
test('actual workflow deadline freezes form; late success cannot unlock pending', () => {
  const h = harness(); prepare(h); h.component.saveSku(); const write = h.requests[1];
  h.timers[0](); assert.equal(h.component.data.pending, true);
  const requestId = h.component.data.requestId;
  write.success({ code: 200, data: applied(write) });
  assert.equal(h.component.data.pending, true); assert.equal(h.component.data.requestId, requestId);
  assert.equal(h.requests.length, 2); h.destroy();
});
test('storage failure prevents POST, and a valid new product carries no invented identifier', () => {
  const h = harness(); h.component.newProduct();
  for (const [field, value] of Object.entries({ productName: '新商品', price: '30', stock: '2' }))
    h.component.productInput({ currentTarget: { dataset: { field } }, detail: { value } });
  const set = h.wx.setStorageSync; h.wx.setStorageSync = () => { throw Error('quota'); };
  h.component.saveProduct(); assert.equal(h.requests.length, 0); assert.equal(h.component.data.busy, false);
  h.wx.setStorageSync = set; h.component.saveProduct();
  assert.equal(h.requests[0].data.productId, undefined); assert.equal(h.requests[0].url, '/api/merchant/desk/products/save');
  h.reply(0, { requestId: h.requests[0].data.requestId, state: 'APPLIED', action: 'PRODUCT_SAVE', targetType: 'pms_product', targetId: ID });
  assert.ok(h.requests.some(r => r.url.endsWith('products/detail') && r.data.productId === ID)); h.destroy();
});
function legacyCandidate() { return { kind: 'LEGACY_TICKET', accepted: true, form: { submitEndpoint: '/api/registration/scan_qr_code', method: 'POST', offline: false,
  fields: [{ name: 'code', widget: 'hidden', value: 'VER1234567890123ABCDEF' }, { name: 'type', widget: 'select', options: [{ value: 'activity' }, { value: 'topic' }] }] } }; }
test('manual legacy candidate requires an explicit claimed type and delegates only after host integration is ready', () => {
  const h = harness(); h.component.data.codeText = 'VER1234567890123ABCDEF'; h.component.manualCandidate();
  assert.equal(h.requests[0].url, '/api/merchant/desk/redeem/resolve'); assert.equal(h.requests[0].data.mode, 'MANUAL');
  h.reply(0, legacyCandidate()); h.component.confirmCandidate(); assert.equal(h.events.length, 0);
  h.component.properties.redemptionReady = true; h.component.confirmCandidate(); assert.equal(h.events.length, 0);
  h.component.candidateType({ detail: { value: 'activity' } }); h.component.confirmCandidate();
  assert.equal(h.events.length, 1); assert.equal(h.events[0].detail.payload.type, 'activity');
  assert.equal(h.requests.length, 1); h.component.confirmCandidate(); assert.equal(h.events.length, 1);
  const intentId = h.events[0].detail.intentId;
  h.component.acceptRedemptionResult({ intentId: 'old', state: 'APPLIED' }); assert.equal(h.component.data.redeemState, 'HANDED_OFF');
  h.component.acceptRedemptionResult({ intentId, state: 'UNKNOWN' }); h.component.nextCandidate(); assert.equal(h.component.data.redeemState, 'UNKNOWN');
  h.component.acceptRedemptionResult({ intentId, state: 'APPLIED' }); h.component.nextCandidate(); assert.equal(h.component.data.candidate, null); h.destroy();
});
test('candidate URL and field contract are allowlisted; no arbitrary server-directed command is executed', () => {
  const h = harness(); h.component.resolveCandidate('code', 'SCAN');
  const row = legacyCandidate(); row.form.submitEndpoint = '/api/arbitrary/write'; h.reply(0, row);
  assert.equal(h.component.data.candidate, null); assert.equal(h.component.data.readStates.candidate, 'ERROR');
  h.component.resolveCandidate('code', 'SCAN'); h.reply(1, { accepted: false, message: '这是节点码', redirectPath: '/pages/arbitrary' });
  assert.equal(h.component.data.candidate.accepted, false); assert.equal(h.events.length, 0); h.destroy();
});
test('OCR only extracts candidates; selection resolves again and never submits redemption', () => {
  const h = harness(); h.component.data.ocrText = '识别文字'; h.component.extractCandidates();
  h.reply(0, { candidates: [Object.assign(legacyCandidate(), { code: 'VER1234567890123ABCDEF', masked: 'VER***DEF' })] });
  assert.equal(h.requests.length, 1); h.component.chooseCandidate({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(h.requests[1].url, '/api/merchant/desk/redeem/resolve'); assert.equal(h.requests[1].data.mode, 'OCR');
  assert.equal(h.events.length, 0); h.destroy();
});
test('a late scanner result cannot replace a more recent manual candidate', () => {
  const h = harness(); h.component.scanCandidate(); h.component.resolveCandidate('manual-code', 'MANUAL');
  h.wx.scan.success({ result: 'late-scan-code' }); assert.equal(h.requests.length, 1); assert.equal(h.requests[0].data.code, 'manual-code'); h.destroy();
});
test('read timeout removes old facts, exposes retry, and ignores late response', () => {
  const h = harness(); h.component.data.products = [{ productId: ID }]; h.component.loadProducts();
  assert.equal(h.component.data.products.length, 0); h.readTimers[0](); assert.equal(h.component.data.readStates.products, 'ERROR');
  h.reply(0, [{ productId: ID }]); assert.equal(h.component.data.products.length, 0);
  h.component.retryRead({ currentTarget: { dataset: { field: 'products' } } }); h.reply(1, []);
  assert.equal(h.component.data.readStates.products, 'EMPTY'); h.destroy();
});
test('reentry evidence receipt locates the scoped review before reading its result', () => {
  const h = harness(); h.component.selectReview(h.event(ID));
  h.reply(0, { reviewId: ID, verdict: 'PENDING', canSubmitEvidence: true, evidence: [] });
  h.component.noteInput({ detail: { value: '补充' } }); h.component.saveEvidence();
  h.requests[1].fail({}); const requestId = h.component.data.requestId; h.destroy();
  const again = harness(h.storage); again.component.recover();
  again.reply(0, { requestId, state: 'APPLIED', action: 'REVIEW_EVIDENCE', targetType: 'verification_review_evidence', targetId: '8', reviewId: ID });
  assert.ok(again.requests.some(request => request.url.endsWith('verdicts/detail') && request.data.reviewId === ID));
  assert.equal(again.component.data.lastReceipt.requestId, requestId); again.destroy();
});
test('invalid target table cannot convert UNKNOWN to APPLIED', () => {
  const h = harness(); prepare(h); h.component.saveSku();
  h.reply(1, Object.assign(applied(h.requests[1]), { targetType: 'other_table' }));
  assert.equal(h.component.data.pending, true); assert.equal(h.storage.size, 1); h.destroy();
});
test('anchor preserves coordinate and version, requires explicit full WiFi replacement and never stores BSSIDs', () => {
  const h = harness(); h.component.data.anchor = { configured: true, anchorLat: 31.2, anchorLng: 121.5, radiusM: 100, version: 4, wifiCount: 2 };
  h.component.editAnchor(); h.component.saveAnchor(); assert.equal(h.requests.length, 0);
  h.component.anchorInput({ currentTarget: { dataset: { field: 'wifiText' } }, detail: { value: 'aa:bb:cc:dd:ee:ff' } });
  h.component.anchorInput({ currentTarget: { dataset: { field: 'wifiConfirmed' } }, detail: { value: true } }); h.component.saveAnchor();
  assert.equal(h.requests[0].data.expectedVersion, 4); assert.equal(h.requests[0].data.anchorLat, 31.2);
  assert.equal(JSON.stringify([...h.storage]).includes('aa:bb:cc:dd:ee:ff'), false);
  h.requests[0].success({ code: 422, errorCode: 'F4_ANCHOR_TOO_FAR', msg: '超过门店范围' });
  assert.equal(h.component.data.pending, false); assert.equal(h.component.data.anchorDraft.expectedVersion, 4); h.destroy();
});
test('existing page navigation is permission guarded and does not clear pending metadata', () => {
  const h = harness(); h.component.openExisting({ currentTarget: { dataset: { page: 'ledger' } } }); assert.equal(h.navigation.length, 0);
  h.component.properties.access.canReadFinance = true; h.component.activate();
  h.component.openExisting({ currentTarget: { dataset: { page: 'ledger' } } }); assert.equal(h.navigation[0].url, '/pages/merchant/ledger/index'); h.destroy();
});
test('existing F3 reservation query uses GET and a half-open 14-day China-time window, projecting only display facts', () => {
  const h = harness(); h.component.properties.access.canVerify = true; h.component.activate();
  h.component.reservationDateInput({ detail: { value: '2026-10-26' } }); h.component.loadReservations();
  const request = h.requests[0]; assert.equal(request.url, '/api/free-explore/bundle/merchant/reservations'); assert.equal(request.method, 'GET');
  assert.equal(request.data.from, Date.parse('2026-10-26T00:00:00+08:00')); assert.equal(request.data.to - request.data.from, 14 * 86400000);
  h.reply(0, [{ id: ID, registrationId: '2', unitId: '3', chapterId: '4', offerId: '5', merchantId: '501', holderMemberId: 'secret-holder',
    startAt: '2026-10-26 12:00:00', status: 'BOOKED', requestFingerprint: 'private-fingerprint', idempotencyKey: 'private-key' }]);
  assert.equal(h.component.data.reservations[0].id, ID); assert.equal(h.component.data.reservations[0].skuId, undefined);
  const text = JSON.stringify(h.component.data.reservations); assert.equal(text.includes('secret-holder'), false); assert.equal(text.includes('private-'), false);
  h.destroy();
});
test('invalid reservation date, unsafe IDs and out-of-window results do not create a fake empty or successful list', () => {
  const h = harness(); h.component.properties.access.canVerify = true; h.component.activate();
  h.component.reservationDateInput({ detail: { value: '2026-02-30' } }); h.component.loadReservations(); assert.equal(h.requests.length, 0);
  h.component.reservationDateInput({ detail: { value: '2026-10-26' } }); h.component.loadReservations();
  h.reply(0, [{ id: 9007199254740993, registrationId: '2', unitId: '3', chapterId: '4', offerId: '5', startAt: '2026-10-26 12:00:00', status: 'BOOKED' }]);
  assert.equal(h.component.data.readStates.reservations, 'ERROR'); assert.equal(h.component.data.reservations.length, 0);
  h.component.loadReservations(); h.reply(1, [{ id: '1', registrationId: '2', unitId: '3', chapterId: '4', offerId: '5', startAt: '2026-11-09 00:00:00', status: 'BOOKED' }]);
  assert.equal(h.component.data.readStates.reservations, 'ERROR'); h.destroy();
});
test('malformed reconciliation is an explicit error; review link enters existing management mode', () => {
  const h = harness(); h.component.loadFinance(); h.reply(0, { bases: [], outsideFourBases: [] });
  assert.equal(h.component.data.finance, null); assert.equal(h.component.data.readStates.finance, 'ERROR');
  h.component.properties.access.canReadMarketing = true; h.component.activate();
  h.component.openExisting({ currentTarget: { dataset: { page: 'reviews' } } });
  assert.equal(h.navigation[0].url, '/pages/merchant/reviews/index?mode=manage'); h.destroy();
});
test('slot pages retain the fixed China-date window and exact cursor; failed continuation keeps prior facts retryable', () => {
  const h = harness(); h.component.loadSlots(h.event('8'));
  assert.equal(h.requests[0].url, '/api/merchant/desk/products/sku/slots/page');
  const fromDate = h.requests[0].data.fromDate;
  h.reply(0, { skuId: '8', fromDate, days: 14, rows: [{ slotId: ID, skuId: '8', slotStart: fromDate + ' 10:00:00' }],
    hasMore: true, next: { afterStartAt: Date.parse(fromDate + 'T10:00:00+08:00'), afterSlotId: ID } });
  h.component.nextSlots(); assert.equal(h.requests[1].data.afterSlotId, ID); assert.equal(h.requests[1].data.fromDate, fromDate);
  h.requests[1].fail({ msg: 'retry' }); assert.equal(h.component.data.slots.length, 1); assert.equal(h.component.data.slotMore, true);
  h.component.nextSlots(); assert.equal(h.requests[2].data.afterSlotId, ID);
  h.reply(2, { skuId: '8', fromDate, days: 14, rows: [{ slotId: '9007199254740995', skuId: '8', slotStart: fromDate + ' 11:00:00' }], hasMore: false, next: null });
  assert.equal(h.component.data.slots.length, 2); assert.equal(h.component.data.slotMore, false); h.destroy();
});

test('slot paging rejects another SKU and a nonadvancing cursor, and late pages cannot cross selection', () => {
  const h = harness(); h.component.loadSlots(h.event('8'));
  let fromDate = h.requests[0].data.fromDate;
  h.reply(0, { skuId: '9', fromDate, days: 14, rows: [], hasMore: false, next: null });
  assert.equal(h.component.data.readStates.slots, 'ERROR');
  h.component.loadSlots(h.event('8')); fromDate = h.requests[1].data.fromDate;
  h.reply(1, { skuId: '8', fromDate, days: 14,
    rows: [{ slotId: ID, skuId: '8', slotStart: fromDate + ' 10:00:00' }],
    hasMore: true, next: { afterStartAt: Date.parse(fromDate + 'T09:00:00+08:00'), afterSlotId: ID } });
  assert.equal(h.component.data.readStates.slots, 'ERROR'); assert.equal(h.component.data.slotMore, false);
  h.component.loadSlots(h.event('8')); h.component.loadSlots(h.event('9'));
  h.reply(2, { skuId: '8', fromDate, days: 14, rows: [], hasMore: false, next: null });
  assert.equal(h.component.data.slotSkuId, '9'); assert.equal(h.component.data.readStates.slots, 'LOADING'); h.destroy();
});

test('explicit read targets and input updates preserve other fields; identity reset clears internal correlations', () => {
  const h = harness();
  for (const field of ['products', 'detail', 'slots', 'arrivals', 'finance', 'frozen', 'claims', 'reviews', 'review',
    'metrics', 'reservations', 'stores', 'anchor', 'candidate', 'candidates', 'notice', 'lastReceipt']) {
    h.component.setReadField(field, { marker: field });
    assert.equal(h.component.data[field].marker, field);
  }
  h.component.codeInput({ currentTarget: { dataset: { field: 'codeText' } }, detail: { value: 'manual' } });
  h.component.codeInput({ currentTarget: { dataset: { field: 'ocrText' } }, detail: { value: 'ocr' } });
  assert.equal(h.component.data.codeText, 'manual'); assert.equal(h.component.data.ocrText, 'ocr');
  assert.equal(h.component.data.candidate, null); assert.equal(h.component.data.candidates.length, 0);
  h.component._slotCursor = { afterSlotId: ID }; h.component._intentId = 'old'; h.component._inputMode = 'SCAN';
  h.switchUser();
  assert.equal(h.component._slotCursor, null); assert.equal(h.component._intentId, ''); assert.equal(h.component._inputMode, '');
  assert.equal(h.component.data.products.length, 0); assert.equal(h.component.data.finance, null);
  assert.equal(h.component.data.codeText, ''); assert.equal(h.component.data.ocrText, ''); h.destroy();
});
test('unit fallbacks preserve known zero and show unknown instead of inventing missing financial facts', () => {
  const source = fs.readFileSync(file.replace('.js', '.wxml'), 'utf8');
  for (const field of ['anchor.radiusM', 'arrivals.redeemedCount', 'item.claimCount', 'item.payableRowsWithoutClaim']) {
    const expression = field + " === 0 ? '0' : (" + field + " || '未知')";
    assert.ok(source.includes('{{' + expression + '}}'));
    const [root, key] = field.split('.');
    for (const [value, expected] of [[0, '0'], [12, 12], [null, '未知'], [undefined, '未知']]) {
      assert.equal(vm.runInNewContext(expression, { [root]: { [key]: value } }), expected);
    }
  }
});
test('claims pagination preserves confirmed page on failure, serializes navigation, and refresh invalidates old details', () => {
  const h = harness(); h.component.loadClaims({ currentTarget: { dataset: { route: 'CHAPTER_OFFER' } } });
  const rows = Array.from({ length: 20 }, (_, i) => ({ claimId: String(i + 1), route: 'CHAPTER_OFFER', status: 'CREATED' }));
  h.reply(0, { route: 'CHAPTER_OFFER', pageNum: 1, rows });
  h.component.nextClaims(); h.component.nextClaims(); assert.equal(h.requests.length, 2);
  assert.equal(h.component.data.claims.length, 20); assert.equal(h.component.data.claimPage, 1);
  h.requests[1].fail({ msg: 'offline' }); assert.equal(h.component.data.claims.length, 20);
  h.component.retryRead({ currentTarget: { dataset: { field: 'claims' } } });
  assert.equal(h.requests[2].data.pageNum, 2);
  h.component.loadFinance(); assert.equal(h.component.data.route, ''); assert.equal(h.component.data.claims.length, 0);
  h.reply(2, { route: 'CHAPTER_OFFER', pageNum: 2, rows: [{ claimId: ID, route: 'CHAPTER_OFFER' }] });
  assert.equal(h.component.data.claims.length, 0); assert.equal(h.component.data.claimPage, 1); h.destroy();
});
test('frozen history loads beyond 24 with exact cursor, preserves prior rows on failure and rejects late refresh pages', () => {
  const h = harness(); h.component.loadFinance();
  assert.equal(h.requests[1].url, '/api/merchant/desk/frozen/page');
  h.reply(1, { rows: [{ batchId: ID, periodYm: '2026-10', amountTotal: '12.34' }], hasMore: true,
    next: { afterPeriodYm: '2026-10', afterBatchId: ID } });
  h.component.nextFrozen(); h.component.nextFrozen(); assert.equal(h.requests.length, 3);
  assert.equal(h.requests[2].data.afterBatchId, ID); h.requests[2].fail({ msg: 'offline' });
  assert.equal(h.component.data.frozen.length, 1); h.component.retryRead({ currentTarget: { dataset: { field: 'frozen' } } });
  assert.equal(h.requests[3].data.afterBatchId, ID);
  h.reply(3, { rows: [{ batchId: '7', periodYm: '2026-09', amountTotal: '9.00' }], hasMore: false, next: null });
  assert.equal(h.component.data.frozen.length, 2); assert.equal(h.component.data.frozenMore, false);
  h.component.loadFinance(); h.component.loadFinance();
  h.reply(5, { rows: [{ batchId: '5', periodYm: '2026-08' }], hasMore: false, next: null });
  assert.equal(h.component.data.frozen.length, 0); assert.equal(h.component.data.readStates.frozen, 'LOADING'); h.destroy();
});
test('frozen continuation rejects duplicate or forward records without advancing confirmed cursor', () => {
  const h = harness(); h.component.loadFinance();
  h.reply(1, { rows: [{ batchId: ID, periodYm: '2026-10' }], hasMore: true,
    next: { afterPeriodYm: '2026-10', afterBatchId: ID } });
  h.component.nextFrozen();
  h.reply(2, { rows: [{ batchId: ID, periodYm: '2026-10' }], hasMore: false, next: null });
  assert.equal(h.component.data.readStates.frozen, 'ERROR'); assert.equal(h.component.data.frozen.length, 1);
  h.component.nextFrozen(); assert.equal(h.requests[3].data.afterBatchId, ID);
  h.reply(3, { rows: [{ batchId: '5', periodYm: '2026-11' }], hasMore: false, next: null });
  assert.equal(h.component.data.readStates.frozen, 'ERROR'); assert.equal(h.component.data.frozenMore, true); h.destroy();
});
test('D2: product and SKU section is hidden by default and shown only when merchantDeskProducts is on', () => {
  const off = harness(); assert.equal(off.component.data.productsOn, false);
  off.component.reloadResult({ action: 'ANCHOR_SAVE' });
  assert.equal(off.requests.some(r => r.url.endsWith('products')), false, 'switch off never reads the product list'); off.destroy();
  const on = harness(new Map(), { merchantDeskProducts: true }); assert.equal(on.component.data.productsOn, true); on.destroy();
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/merchant/components/desk/index.wxml'), 'utf8');
  const start = wxml.indexOf('<block wx:if="{{productsOn}}">'), end = wxml.indexOf('</block>', start);
  assert.ok(start > 0 && end > start, 'product section must sit inside the productsOn block');
  const section = wxml.slice(start, end);
  for (const tap of ['loadProducts', 'newProduct', 'saveProduct', 'editSku', 'newSku', 'saveSku', 'requestDelist', 'loadSlots'])
    assert.match(section, new RegExp('bindtap="' + tap + '"'), tap + ' must be inside the gated section');
  assert.equal(wxml.slice(0, start).includes('bindtap="saveSku"') || wxml.slice(end).includes('bindtap="saveSku"'), false);
});
