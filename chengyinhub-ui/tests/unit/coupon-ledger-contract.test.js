const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');
const ledgerSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/coupon/ledger.vue'), 'utf8');
const couponSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/coupon/index.vue'), 'utf8');
const legacySource = fs.readFileSync(path.join(uiRoot, 'src/views/business/couponhistory/index.vue'), 'utf8');
const routerSource = fs.readFileSync(path.join(uiRoot, 'src/router/index.js'), 'utf8');

function loadCouponLedger(getCoupon, listCouponhistory, reverseDeps) {
  const script = ledgerSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ getCoupon \} from '@\/api\/business\/coupon'$/m, 'const { getCoupon } = dependencies;')
    .replace(/^import \{ listCouponhistory, reverseCouponRedemption, getCouponAuditTrail \} from '@\/api\/business\/couponhistory'$/m, 'const { listCouponhistory, reverseCouponRedemption, getCouponAuditTrail } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  const audit = reverseDeps || {};
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({
    getCoupon,
    listCouponhistory,
    reverseCouponRedemption: audit.reverseCouponRedemption || (() => Promise.resolve({})),
    getCouponAuditTrail: audit.getCouponAuditTrail || (() => Promise.resolve({ data: [] }))
  });
}

function createVm(component, couponId = '51') {
  const vm = Object.assign(component.data(), {
    couponId,
    $route: { params: { couponId } },
    $router: { push: () => {}, replace: () => Promise.resolve() }
  });
  for (const name of ['loadCouponContext', 'loadCoupon', 'getList', 'queryForLedger']) {
    vm[name] = component.methods[name].bind(vm);
  }
  return vm;
}

test('优惠券流水路由是受权限保护的隐藏详情子页', () => {
  assert.match(routerSource, /path:\s*'\/coupon'/);
  assert.match(routerSource, /permissions:\s*\['business:couponhistory:list'\]/);
  assert.match(routerSource, /path:\s*'ledger\/:couponId\(\\\\d\+\)'/);
  assert.match(routerSource, /name:\s*'couponLedger'/);
});

test('优惠券流水只按当前详情券 ID 查询，优惠券摘要失败不遮蔽流水', async () => {
  const pending = [];
  const component = loadCouponLedger(
    () => Promise.reject(new Error('coupon summary forbidden')),
    query => new Promise(resolve => pending.push({ query, resolve }))
  );
  const vm = createVm(component, '51');

  const summaryRequest = vm.loadCoupon();
  const ledgerRequest = vm.getList();
  assert.deepEqual(pending[0].query, {
    pageNum: 1,
    pageSize: 10,
    couponId: '51',
    memberId: null,
    couponCode: null,
    useStatus: null,
    orderSn: null
  });

  await summaryRequest;
  assert.equal(vm.couponError, true);
  assert.equal(vm.ledgerLoading, true);

  pending[0].resolve({ rows: [{ id: 1, couponId: 51 }], total: 1 });
  await ledgerRequest;
  assert.deepEqual(vm.ledgerList, [{ id: 1, couponId: 51 }]);
  assert.equal(vm.ledgerLoading, false);
});

test('切换优惠券详情时只保留新券的流水，不接受旧请求回包', async () => {
  const couponPending = [];
  const ledgerPending = [];
  const component = loadCouponLedger(
    couponId => new Promise(resolve => couponPending.push({ couponId, resolve })),
    query => new Promise(resolve => ledgerPending.push({ query, resolve }))
  );
  const vm = createVm(component, '51');

  const first = vm.loadCouponContext('51');
  let nextCalled = false;
  const second = component.beforeRouteUpdate.call(vm, { params: { couponId: '52' } }, {}, () => { nextCalled = true; });

  assert.equal(nextCalled, true);
  assert.equal(vm.couponId, '52');
  assert.equal(ledgerPending[0].query.couponId, '51');
  assert.equal(ledgerPending[1].query.couponId, '52');
  couponPending[1].resolve({ data: { id: 52, name: '券 52' } });
  ledgerPending[1].resolve({ rows: [{ id: 2, couponId: 52 }], total: 1 });
  await second;
  couponPending[0].resolve({ data: { id: 51, name: '券 51' } });
  ledgerPending[0].resolve({ rows: [{ id: 1, couponId: 51 }], total: 1 });
  await first;

  assert.equal(vm.coupon.id, 52);
  assert.deepEqual(vm.ledgerList, [{ id: 2, couponId: 52 }]);
});

test('优惠券列表下钻新流水页，旧 couponhistory 页不再承载 CRUD', () => {
  assert.match(couponSource, /openLedger\(row\)/);
  assert.match(couponSource, /name:\s*'couponLedger'/);
  assert.match(couponSource, /couponId:\s*row\.id/);
  assert.match(legacySource, /name:\s*'couponLedger'/);
  assert.doesNotMatch(legacySource, /addCouponhistory|updateCouponhistory|delCouponhistory|handleAdd|handleUpdate|handleDelete/);
});

test('券核销撤销是受独立权限保护的动作，原因必填且同步更新摘要计数与列表', async () => {
  assert.match(ledgerSource, /v-hasPermi="\['business:couponhistory:reverseRedeem'\]"/);
  assert.match(ledgerSource, /reverseCouponRedemption\(row\.id, \{ reason: value\.trim\(\) \}\)/);
  assert.match(ledgerSource, /inputValidator:\s*value => !!\(value && value\.trim\(\)\)/);
  assert.match(ledgerSource, /getCouponAuditTrail\(historyId\)/);

  const reversed = [];
  const component = loadCouponLedger(
    () => Promise.resolve({ data: { id: 51, useCount: 1 } }),
    () => Promise.resolve({ rows: [], total: 0 }),
    { reverseCouponRedemption: (id, data) => { reversed.push({ id, data }); return Promise.resolve({}); } }
  );
  const vm = createVm(component, '51');
  vm.coupon = { id: 51, useCount: 2 };
  vm.$prompt = () => Promise.resolve({ value: '  店员扫错券  ' });
  vm.$modal = { msgSuccess: () => {} };
  vm.getList = () => { vm.listRefreshed = true; return Promise.resolve(); };

  await component.methods.handleReverse.call(vm, { id: 301, useStatus: 1 });
  await new Promise(resolve => setImmediate(resolve));

  assert.deepEqual(reversed, [{ id: 301, data: { reason: '店员扫错券' } }]);
  assert.equal(vm.listRefreshed, true);
  assert.equal(vm.coupon.useCount, 1);
});
