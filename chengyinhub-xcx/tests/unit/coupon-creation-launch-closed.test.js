const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const ROOT = path.resolve(__dirname, '../..');
const read = file => fs.readFileSync(path.join(ROOT, file), 'utf8');

function mount(file, component = false) {
  const requests = [], navigations = [], events = [];
  const app = { sendRequest: options => requests.push(options), getUserID: () => 7 };
  const wx = { navigateBack: () => navigations.push('back'), redirectTo: o => navigations.push(o.url),
    navigateTo: o => navigations.push(o.url), showToast() {}, getStorageSync() {} };
  global.getApp = () => app; global.wx = wx;
  let definition;
  vm.runInNewContext(read(file), { require: createRequire(path.join(ROOT, file)), getApp: () => app,
    wx, getCurrentPages: () => [{}, {}], Page: d => { definition = d; }, Component: d => { definition = d; } });
  const instance = Object.assign({}, component ? definition.methods : definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); },
    triggerEvent(name, detail) { events.push({ name, detail }); }
  });
  return { instance, requests, navigations, events };
}

test('default launch config and shared submit helper refuse every new coupon type without a request', () => {
  const form = require('../../utils/coupon-form.js');
  assert.equal(form.CREATION_ENABLED, false);
  for (const couponType of [1, 2, 3, 4]) {
    let error;
    form.submitCoupon({ sendRequest() { assert.fail('closed creation must not send'); } }, {
      name: '新券', startTime: '2026-11-01 00:00:00', endTime: '2026-12-01 00:00:00', publishCount: 5, couponType
    }, { onError: message => { error = message; } });
    assert.equal(error, '暂不开放新建优惠券');
  }
});

test('list entry, standalone deep link and reward creation handlers cannot open or submit new coupons', () => {
  const list = mount('subpackageMember/coupon/coupon.js');
  list.instance.GoAdd();
  assert.deepEqual(list.navigations, []);
  const page = mount('subpackageMember/couponInfo/couponInfo.js');
  page.instance.onLoad({ scope: 'MERCHANT' });
  page.instance.onSubmit();
  assert.equal(page.instance.data.couponCreationEnabled, false);
  assert.deepEqual(page.navigations, ['back']);
  assert.deepEqual(page.requests, []);
  const reward = mount('pages/publish/components/reward-selector/index.js', true);
  for (const kind of ['COUPON', 'PASS']) {
    reward.instance.data.rewardKind = kind;
    reward.instance.createCoupon(); reward.instance.onCreateSubmit();
    assert.equal(reward.instance.data.creating, false);
  }
  assert.deepEqual(reward.requests, []);
});

test('existing coupons and passes still load and can be selected and cleared', () => {
  const h = mount('pages/publish/components/reward-selector/index.js', true);
  h.instance.openSelector();
  assert.equal(h.requests[0].url, '/api/coupon/mypublishlist');
  h.requests[0].success({ code: 200, data: [{ id: 11, name: '旧优惠券', couponType: 0 }, { id: 12, name: '旧体验卡', couponType: 3 }] });
  assert.equal(h.instance.data.visibleCoupons[0].id, 11);
  h.instance.chooseCoupon({ currentTarget: { dataset: { item: { id: 11, name: '旧优惠券' } } } });
  assert.equal(h.events.find(e => e.name === 'change').detail.couponId, 11);
  h.instance.onRewardKindChange({ currentTarget: { dataset: { kind: 'PASS' } } });
  assert.equal(h.instance.data.visibleCoupons[0].id, 12);
  h.instance.clearCoupon();
  assert.equal(h.events.filter(e => e.name === 'change').at(-1).detail.couponId, 0);
});

test('all rendered creation surfaces are gated and admin directory only saves existing coupons', () => {
  assert.doesNotMatch(read('subpackageMember/coupon/coupon.wxml'), /GoAdd|发布优惠券|发一张到店券/);
  assert.match(read('subpackageMember/couponInfo/couponInfo.wxml'), /theme-topic-editor" wx:if="\{\{couponCreationEnabled\}\}"/);
  const reward = read('pages/publish/components/reward-selector/index.wxml');
  assert.match(reward, /wx:if="\{\{allowCreate && couponCreationEnabled\}\}" slot="footer"/);
  assert.match(reward, /show="\{\{creating && couponCreationEnabled\}\}"/);
  assert.doesNotMatch(read('subpackageMember/components/cy/profile/index.js'), /text: '发布优惠券'/);
  const admin = fs.readFileSync(path.join(ROOT, '../chengyinhub-ui/src/views/business/coupon/index.vue'), 'utf8');
  assert.doesNotMatch(admin, /handleAdd|addCoupon|@click="handleAdd"/);
  assert.match(admin, /if \(!this\.form\.id\) return/);
  assert.match(admin, /const request = updateCoupon\(payload\)/);
});
