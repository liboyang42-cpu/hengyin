const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

const ruoyiSource = fs.readFileSync(path.join(uiRoot, 'src/utils/ruoyi.js'), 'utf8');
const realToTimestamp = new Function(ruoyiSource.replace(/^export function /gm, 'function ') + '\nreturn toTimestamp;')();

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadRefundWorkbench(dependencies) {
  const source = read('src/views/business/finance/refund/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listRefund, addRefund, reviewRefund \} from '@\/api\/business\/refund'$/m, 'const { listRefund, addRefund, reviewRefund } = dependencies;')
    .replace(/^import auth from '@\/plugins\/auth'$/m, 'const auth = dependencies.auth;')
    .replace(/^import (ActivityCancelRefundCases|ManualPayoutQueue|Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies);
}

function loadManualPayoutQueue(dependencies) {
  const source = read('src/views/business/finance/refund/ManualPayoutQueue.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[^}]*\} from '@\/api\/business\/refund'$/m, 'const { listManualPayout, claimManualPayout, releaseManualPayout, takeoverManualPayout, handleManualPayout, verifyManualPayout, refreshPayout } = dependencies;')
    .replace(/^import \{ toTimestamp \} from '@\/utils\/ruoyi'$/m, '')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', 'toTimestamp', script.replace(/export\s+default\s*/, 'return '))(dependencies, realToTimestamp);
}

test('退款处理台收为 finance/refund，旧列表与人工队列入口都保留无损跳转', () => {
  const router = read('src/router/index.js');
  const legacyList = read('src/views/business/refund/index.vue');
  const legacyManual = read('src/views/business/refund/manual.vue');
  const canonical = read('src/views/business/finance/refund/index.vue');

  assert.match(router, /path:\s*['"]\/finance\/refund['"][\s\S]*?permissions:\s*\[['"]business:refund:list['"],\s*['"]business:refund:review['"]\][\s\S]*?path:\s*['"]['"][\s\S]*?name:\s*['"]financeRefund['"]/);
  assert.match(router, /path:\s*['"]\/refund['"][\s\S]*?path:\s*['"]manual['"][\s\S]*?views\/business\/refund\/manual/);
  assert.match(legacyList, /name:\s*['"]financeRefund['"]/);
  assert.match(legacyManual, /name:\s*['"]financeRefund['"][\s\S]*?queue:\s*['"]manual['"]/);
  assert.match(canonical, /<manual-payout-queue\s+v-if="isManualQueue"\s+:route-query="\$route.query"/);
  assert.match(canonical, /goManualQueue\(\)\s*\{\s*this\.\$router\.push\(\{\s*name:\s*['"]financeRefund['"],\s*query:\s*\{\s*queue:\s*['"]manual['"]\s*}\s*}\)/);
});

test('人工兜底仍复用既有退款接口，并把双人复核边界呈现给操作人', () => {
  const manualQueue = read('src/views/business/finance/refund/ManualPayoutQueue.vue');
  const api = read('src/api/business/refund.js');

  for (const method of ['listManualPayout', 'claimManualPayout', 'handleManualPayout', 'verifyManualPayout', 'refreshPayout']) {
    assert.match(manualQueue, new RegExp(`\\b${method}\\b`));
  }
  assert.match(manualQueue, /处理人与复核人必须是不同的两个人/);
  assert.match(manualQueue, /payoutStatus === 2/);
  assert.match(manualQueue, /payoutStatus === 3/);
  assert.match(api, /export function listManualPayout\(query\)/);
  assert.match(api, /export function claimManualPayout\(data\)/);
  assert.match(api, /export function releaseManualPayout\(data\)/);
  assert.match(api, /export function takeoverManualPayout\(data\)/);
  assert.doesNotMatch(manualQueue, /\bdelete\s*\(/i);
  assert.match(manualQueue, /本操作不代表用户已确认到账/);
  assert.doesNotMatch(manualQueue, /确认该退款已真实到用户账户/);
});

test('主退款台把渠道成功与人工凭证复核拆开，后者不计入到账', () => {
  const source = read('src/views/business/finance/refund/index.vue');
  const component = loadRefundWorkbench({
    listRefund: () => Promise.resolve({ rows: [], total: 0 }),
    addRefund: () => Promise.resolve(),
    reviewRefund: () => Promise.resolve(),
    auth: { hasPermi: () => true }
  });
  const vm = Object.assign(component.data(), {
    refundList: [
      { status: 1, payoutStatus: 1, refundAmount: 10 },
      { status: 1, payoutStatus: 4, refundAmount: 20 }
    ],
    money: component.methods.money
  });

  const overview = component.computed.overview.call(vm);
  assert.equal(overview.find(item => item.label === '原路渠道成功').value, 1);
  assert.equal(overview.find(item => item.label === '人工凭证已复核').value, 1);
  assert.equal(overview.find(item => item.label === '人工凭证已复核').note, '不代表用户到账');
  assert.doesNotMatch(source, /已确认到账/);
});

test('人工处理必须先领取 lease 再提交，handle 载荷带上 leaseToken（合同锁步）', async () => {
  const claims = [];
  const handles = [];
  const component = loadManualPayoutQueue({
    listManualPayout: () => Promise.resolve({ rows: [], total: 0 }),
    claimManualPayout: payload => { claims.push(payload); return Promise.resolve({ data: 'lease-xyz' }); },
    handleManualPayout: payload => { handles.push(payload); return Promise.resolve(); },
    verifyManualPayout: () => Promise.resolve(),
    refreshPayout: () => Promise.resolve()
  });
  const vm = Object.assign(component.data(), {
    $refs: { form: { validate: cb => cb(true) } },
    $modal: { msgSuccess: () => {} },
    getList: () => {}
  });

  component.methods.openHandle.call(vm, { id: 77 });
  await Promise.resolve();
  await Promise.resolve();
  assert.deepEqual(claims, [{ refundId: 77 }], '打开处理弹窗前必须先领取 lease');
  assert.equal(vm.form.leaseToken, 'lease-xyz', 'leaseToken 必须落进表单，供 handle 提交');
  assert.equal(vm.open, true);

  component.methods.submitHandle.call(vm);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(handles.length, 1);
  assert.equal(handles[0].refundId, 77);
  assert.equal(handles[0].leaseToken, 'lease-xyz', 'handle 载荷缺 leaseToken 会被后端 CAS 拒掉');
});

test('[R6] 存量脏回执不给可点 href：协议注入/scheme-relative/反斜杠/控制字符一律禁用跳转', () => {
  const component = loadManualPayoutQueue({
    listManualPayout: () => Promise.resolve({ rows: [], total: 0 }),
    handleManualPayout: () => Promise.resolve(),
    verifyManualPayout: () => Promise.resolve(),
    refreshPayout: () => Promise.resolve()
  });
  const safeReceiptHref = component.methods.safeReceiptHref;

  // 本次修复前落库的存量值仍可能是任意 scheme/host —— 后端闸只管新写入，渲染这层必须自己挡
  const hostile = [
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'data:text/html;base64,PHN2Zz4=',
    'file:///etc/passwd',
    'http://www.chengyinhub.com/r.png',
    '//evil.example.com/r.png',
    'https://user:pass@evil.example.com/r.png',
    '/\\evil.example.com/r.png',
    '/profile/../../etc/passwd',
    'https://www.chengyinhub.com/r\tx.png',
    'receipt.png',
    null,
    undefined
  ];
  for (const value of hostile) {
    assert.equal(safeReceiptHref(value), '', `脏回执不该拿到可点 href: ${value}`);
  }

  // 合规值必须照常可点，别把安全闸做成"回执一律看不了"
  assert.equal(safeReceiptHref('/profile/upload/2026/07/25/r.png'), '/profile/upload/2026/07/25/r.png');
  assert.equal(safeReceiptHref('  https://www.chengyinhub.com/r.png  '), 'https://www.chengyinhub.com/r.png');
});

test('[R6] 非白名单HTTPS主机不给可点 href，含后缀仿冒域名', () => {
  // 对应后端 RefundPayoutReceiptUrlGateTest#unlistedHostIsRejectedIncludingSuffixLookalikes：
  // 前端 safeReceiptHref 只是展示层防御，但同样必须精确匹配白名单 host，不认后缀/子串仿冒。
  const component = loadManualPayoutQueue({
    listManualPayout: () => Promise.resolve({ rows: [], total: 0 }),
    handleManualPayout: () => Promise.resolve(),
    verifyManualPayout: () => Promise.resolve(),
    refreshPayout: () => Promise.resolve()
  });
  const safeReceiptHref = component.methods.safeReceiptHref;

  const unlisted = [
    'https://evil.example.com/receipt.png',
    'https://www.chengyinhub.com.evil.example.com/receipt.png',
    'https://evil.example.com/www.chengyinhub.com/receipt.png',
    'https://www.chengyinhub.com:8443/receipt.png'
  ];
  for (const value of unlisted) {
    assert.equal(safeReceiptHref(value), '', `非白名单 host 不该拿到可点 href: ${value}`);
  }

  assert.equal(safeReceiptHref('https://www.chengyinhub.com/receipt.png'),
    'https://www.chengyinhub.com/receipt.png', '白名单内的 host 必须照常可点');
});

test('[R6] 回执输入框把服务端合同讲清楚，渲染必须过 safeReceiptHref', () => {
  const manualQueue = read('src/views/business/finance/refund/ManualPayoutQueue.vue');
  assert.match(manualQueue, /服务端按白名单校验/);
  assert.match(manualQueue, /safeReceiptHref\(scope\.row\.payoutReceiptUrl\)/);
});

test('放弃处理必须调释放接口，否则这单卡在租约里谁也领不到', async () => {
  const releases = [];
  const component = loadManualPayoutQueue({
    listManualPayout: () => Promise.resolve({ rows: [], total: 0 }),
    claimManualPayout: () => Promise.resolve({ data: 'lease-xyz' }),
    releaseManualPayout: payload => { releases.push(payload); return Promise.resolve(); },
    takeoverManualPayout: () => Promise.resolve({ data: null }),
    handleManualPayout: () => Promise.resolve(),
    verifyManualPayout: () => Promise.resolve(),
    refreshPayout: () => Promise.resolve()
  });
  const vm = Object.assign(component.data(), {
    $refs: { form: { validate: cb => cb(true) } },
    $modal: { msgSuccess: () => {}, confirm: () => Promise.resolve() },
    getList: () => {}
  });

  component.methods.openHandle.call(vm, { id: 77 });
  await Promise.resolve();
  await Promise.resolve();
  component.methods.cancelHandle.call(vm);
  await Promise.resolve();
  await Promise.resolve();

  assert.deepEqual(releases, [{ refundId: 77, leaseToken: 'lease-xyz' }]);
  assert.equal(vm.open, false);
  assert.equal(vm.form.leaseToken, null, '释放后表单不能还留着作废的 token');
});

test('过期租约走显式接管入口，拿到新 leaseToken 才允许登记', async () => {
  const takeovers = [];
  const component = loadManualPayoutQueue({
    listManualPayout: () => Promise.resolve({ rows: [], total: 0 }),
    claimManualPayout: () => Promise.resolve({ data: null }),
    releaseManualPayout: () => Promise.resolve(),
    takeoverManualPayout: payload => { takeovers.push(payload); return Promise.resolve({ data: 'lease-taken' }); },
    handleManualPayout: () => Promise.resolve(),
    verifyManualPayout: () => Promise.resolve(),
    refreshPayout: () => Promise.resolve()
  });
  const vm = Object.assign(component.data(), {
    $refs: { form: { validate: cb => cb(true) } },
    $modal: { msgSuccess: () => {}, confirm: () => Promise.resolve() },
    getList: () => {}
  });

  await component.methods.handleTakeover.call(vm, { id: 88, payoutLeaseOwner: 100 });

  assert.deepEqual(takeovers, [{ refundId: 88 }]);
  assert.equal(vm.form.leaseToken, 'lease-taken');
  assert.equal(vm.form.refundId, 88);
  assert.equal(vm.open, true);
});

test('canonical 退款列表兼容旧筛选分页 query，并拒绝用错误权限加载另一条队列', async () => {
  const requests = [];
  const component = loadRefundWorkbench({
    listRefund: query => new Promise(resolve => requests.push({ query, resolve })),
    addRefund: () => Promise.resolve(),
    reviewRefund: () => Promise.resolve(),
    auth: { hasPermi: permission => permission === 'business:refund:list' }
  });
  const replaceTargets = [];
  const vm = Object.assign(component.data(), {
    $router: { replace: target => replaceTargets.push(target) },
    $route: { query: {} },
    $modal: { msgSuccess: () => {} }
  });
  vm.getList = component.methods.getList.bind(vm);

  component.methods.loadRoute.call(vm, {
    pageNum: '3',
    pageSize: '25',
    merchantId: 'm-100',
    memberId: 'u-200',
    status: '1'
  });
  assert.deepEqual(requests[0].query, {
    pageNum: 3,
    pageSize: 25,
    merchantId: 'm-100',
    memberId: 'u-200',
    status: 1,
    // 拍板 #12:退款台新增「已转平台」筛选与按转平台时间排序;null = 全部/默认排序
    platformTakeover: null,
    takeoverOrder: null
  });

  requests[0].resolve({ rows: [{ id: 1 }], total: 1 });
  await Promise.resolve();
  await Promise.resolve();
  assert.deepEqual(vm.refundList, [{ id: 1 }]);

  component.methods.loadRoute.call(vm, { queue: 'manual' });
  assert.deepEqual(replaceTargets, ['/401']);
  assert.equal(requests.length, 1);
  assert.equal(vm.isManualQueue, false, '无人工复核权限时不应短暂挂载人工子队列');
});

test('只有人工复核权限的账号不能借 canonical 路由读取普通退款清单', () => {
  const requests = [];
  const component = loadRefundWorkbench({
    listRefund: query => new Promise(resolve => requests.push({ query, resolve })),
    addRefund: () => Promise.resolve(),
    reviewRefund: () => Promise.resolve(),
    auth: { hasPermi: permission => permission === 'business:refund:review' }
  });
  const replaceTargets = [];
  const vm = Object.assign(component.data(), {
    $router: { replace: target => replaceTargets.push(target) },
    $route: { query: {} }
  });
  vm.getList = component.methods.getList.bind(vm);

  component.methods.loadRoute.call(vm, { merchantId: 'm-100' });

  assert.deepEqual(replaceTargets, ['/401']);
  assert.equal(requests.length, 0);
  assert.equal(vm.isManualQueue, false);
});

test('人工子队列接住旧入口保留的分页 query，不静默回到第一页', () => {
  const requests = [];
  const component = loadManualPayoutQueue({
    listManualPayout: query => new Promise(resolve => requests.push({ query, resolve })),
    handleManualPayout: () => Promise.resolve(),
    verifyManualPayout: () => Promise.resolve(),
    refreshPayout: () => Promise.resolve()
  });
  const vm = Object.assign(component.data(), {
    routeQuery: { pageNum: '3', pageSize: '25', queue: 'manual' }
  });
  vm.getList = component.methods.getList.bind(vm);

  component.watch.routeQuery.handler.call(vm, vm.routeQuery);

  assert.deepEqual(requests[0].query, { pageNum: 3, pageSize: 25 });
});
