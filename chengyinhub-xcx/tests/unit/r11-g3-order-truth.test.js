// 第十一轮 g3 第 1 项(p1 F-1/F-2、j3 F9/F12):订单与参与状态说真话。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { summarizeOrderState, formatRefundDeadline } = require('../../utils/order-status.js');
const { shapeOrderPreview, isOpenOrder } = require('../../subpackageMember/components/cy/profile/order-preview.js');

const ROOT = path.resolve(__dirname, '../..');
const NOW = Date.parse('2026-11-04T12:00:00+08:00');
const paid = (extra) => Object.assign({ id: 75, registrationStatus: 2, paymentStatus: 2, verificationStatus: 0,
  wechatPaymentAmount: 69, payableAmount: 69 }, extra);

test('p1 F-1:十天后的探索场次,买完是「未开始」,不是「进行中」', () => {
  const row = paid({ ownerType: 2, purchaseKind: 3,
    cmsActivity: { startDate: '2026-11-14 10:00:00', endDate: '2026-11-14 23:59:00' },
    exploreProgress: { required: 4, redeemed: 0, active: true },
    refundInfo: { refundable: true } });
  const s = summarizeOrderState(row, NOW);
  assert.equal(s.key, 'not_started');
  assert.equal(s.text, '未开始');
  assert.equal(s.progressText, '已核销 0/4 家');
  assert.equal(summarizeOrderState(row, Date.parse('2026-11-14T11:00:00+08:00')).key, 'in_progress');
});

test('j3 F9:自玩通行证状态按能不能玩,不以「不可退款」当状态;过期是「已结束」', () => {
  const pass = paid({ ownerType: 1, purchaseKind: 2, ticketId: null, expiresAt: '2026-12-04 23:59:59', firstSuccessfulScanAt: '2026-11-04 10:00:00',
    cmsTopic: { startDate: '2026-11-03', endDate: '2026-12-04' },
    refundInfo: { refundable: false, reason: '已开始，不可退款' } });
  const s = summarizeOrderState(pass, NOW);
  assert.equal(s.key, 'in_progress');
  assert.equal(s.refundText, '已开始，不可退款');
  const after = summarizeOrderState(pass, Date.parse('2026-12-05T08:00:00+08:00'));
  assert.equal(after.key, 'completed');
  assert.equal(after.text, '已结束');
  // 场次票错过退款窗口仍按原口径
  const ticket = paid({ ownerType: 2, cmsActivity: { startDate: '2026-11-04 10:00:00', endDate: '2026-11-04 18:00:00' },
    refundInfo: { refundable: false, reason: '已开始，不可退款' } });
  assert.equal(summarizeOrderState(ticket, NOW).key, 'non_refundable');
});

test('date-only 结束日整天都算,date-only 值不显示 00:00', () => {
  const row = paid({ ownerType: 1, ticketId: 5, cmsTopic: { startDate: '2026-11-01', endDate: '2026-11-04' },
    refundInfo: { refundable: true } });
  assert.equal(summarizeOrderState(row, NOW).key, 'in_progress');
  assert.equal(formatRefundDeadline('2026-11-04'), '2026-11-04');
  assert.equal(formatRefundDeadline('2026-11-04 23:10:00'), '2026-11-04 23:10');
});

test('我的 订单卡:自玩通行证不写「待核销」,主题日期不带 00:00', () => {
  const pass = paid({ ownerType: 1, purchaseKind: 2, expiresAt: '2026-12-04 23:59:59', firstSuccessfulScanAt: '2026-11-04 10:00:00',
    cmsTopic: { name: 'T', startDate: '2026-11-03', endDate: '2026-12-04' }, refundInfo: { refundable: false } });
  assert.equal(isOpenOrder(pass, NOW), true);
  const card = shapeOrderPreview(pass, null, NOW);
  assert.equal(card.statusText, '进行中');
  assert.equal(card.timeText, '11月3日 – 12月4日');
});

function loadDetail(reply) {
  const app = { globalData: {}, getUserID: () => 9, tips() {}, getImgUrl: (u) => u,
    getRequestErrorMessage: (r, f) => (r && r.msg) || f,
    sendRequest(req) { if (req.url === '/api/registration/info') req.success({ code: 200, data: reply }); } };
  const wx = { getStorageSync: () => '', setStorageSync() {}, showLoading() {}, hideLoading() {} };
  const file = path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-detail/index.js');
  let def;
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component: (v) => { def = v; }, getApp: () => app, wx, console, setTimeout, clearTimeout,
    require(id) {
      if (id.endsWith('/modal.js')) return { show() {} };
      if (id.endsWith('/loading.js')) return { show() {}, hide() {} };
      if (id.endsWith('/toast.js')) { const t = () => {}; t.success = t; return t; }
      return require(path.resolve(path.dirname(file), id));
    },
  }, { filename: file });
  const c = Object.assign({}, def, def.methods);
  c.data = JSON.parse(JSON.stringify(def.data));
  c.setData = (p, cb) => { Object.assign(c.data, p); if (cb) cb(); };
  c.triggerEvent = () => {};
  c.loadCompletion = c.loadMarketingConsent = c.loadInvoice = () => {};
  c._orderId = reply.id;
  c._orderGeneration = 1;
  c.getData(reply.id);
  return c.data;
}

test('订单详情:自玩通行证只说一次不可退款,没有核销/集合点一步,票价不写「—」', () => {
  const d = loadDetail(paid({ ownerType: 1, ownerId: 3, purchaseKind: 2, ticketId: null, originalAmount: 39,
    payableAmount: 39, wechatPaymentAmount: 39, expiresAt: '2026-12-04 23:59:59', serverNow: NOW,
    paymentTime: '2026-11-03 23:10:00', createTime: '2026-11-03 23:09:00',
    cmsTopic: { id: 3, name: 'T', productType: 1, startDate: '2026-11-03', endDate: '2026-12-04' },
    refundInfo: { refundable: false, reason: '已开始，不可退款' } }));
  assert.equal(d.loadState, 'ready');
  assert.equal(d.info.statusText, '可使用');   // 付了款还没开局:能玩、没玩,不写「进行中」
  const visible = JSON.stringify([d.info.statusText, d.stateNotice, d.orderTimeline]);
  assert.ok(!/不可退款/.test(visible), visible);
  assert.ok(!d.stateNotice.tag);
  assert.equal(d.orderTimeline.map((r) => r.key).join(','), 'created,paid');
  assert.equal(d.ticketPriceShown, 39);
  assert.equal(d.whenText, '可玩至 12 月 4 日');   // 第十二轮 k7 N6:通行证只有「可玩至」一个日期,不再摆主题开始日
});

test('订单详情:状态不在卡上重复,核销时间为 date-only 时不补 00:00,未发生的步骤不写「完成后更新」', () => {
  const d = loadDetail(paid({ ownerType: 2, ownerId: 9, purchaseKind: 3, serverNow: NOW,
    verificationStatus: 0, createTime: '2026-11-04 09:00:00', paymentTime: '2026-11-04 09:01:00',
    cmsActivity: { id: 9, startDate: '2026-11-14 10:00:00', endDate: '2026-11-14 23:59:00' },
    exploreProgress: { required: 4, redeemed: 0, active: true }, refundInfo: { refundable: true } }));
  assert.equal(d.info.statusText, '未开始');
  assert.deepEqual({ title: d.stateNotice.title, sub: d.stateNotice.sub, tag: d.stateNotice.tag },
    { title: '', sub: '已核销 0/4 家', tag: '' });
  assert.ok(d.orderTimeline.every((r) => r.time !== '完成后更新'));
  const v = loadDetail(paid({ ownerType: 2, ownerId: 9, verificationStatus: 1, verificationTime: '2026-11-04',
    serverNow: NOW, paymentTime: '2026-11-03 23:10:00',
    cmsActivity: { id: 9, startDate: '2026-11-03 10:00:00', endDate: '2026-11-04 23:00:00' } }));
  assert.equal(v.verificationTimeText, '2026-11-04');
});
