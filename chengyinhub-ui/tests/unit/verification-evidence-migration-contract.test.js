const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const VueRouter = require('vue-router');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadVerificationEvidence(overrides = {}) {
  const source = read('src/views/business/verifyevidence/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ scanList, groupVerifyList \} from ['"]@\/api\/business\/verifyEvidence['"];?$/m, 'const { scanList, groupVerifyList } = dependencies;')
    .replace(/^import (Cy\w+) from .+;?$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(Object.assign({
    scanList: () => Promise.resolve({ data: [] }),
    groupVerifyList: () => Promise.resolve({ data: [] })
  }, overrides));
}

function attachMethods(component, vm) {
  Object.keys(component.methods).forEach(name => {
    vm[name] = (...args) => component.methods[name].apply(vm, args);
  });
  return vm;
}

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve));
}

test('核销证据以原列表权限注册唯一的 risk/verification-evidence 入口', () => {
  const router = read('src/router/index.js');

  assert.match(router, /path:\s*['"]\/risk\/verification-evidence['"][\s\S]*?permissions:\s*\[['"]business:verifyEvidence:list['"]\][\s\S]*?name:\s*['"]riskVerificationEvidence['"]/);
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/verifyevidence\/index['"]\)/);
  assert.match(router, /path:\s*['"]\/business\/verifyevidence['"][\s\S]*?redirect:\s*to\s*=>\s*\(\{\s*name:\s*['"]riskVerificationEvidence['"]/);
  assert.ok(router.indexOf("path: '/risk/verification-evidence'") < router.indexOf("path: '/business/verifyevidence'"));
});

test('旧核销证据地址在后台同 path 菜单注册后仍路由级跳到规范入口', () => {
  const router = new VueRouter({ mode: 'abstract', routes: [] });
  router.addRoutes([
    { path: '/risk/verification-evidence', name: 'riskVerificationEvidence' },
    { path: '/business/verifyevidence', redirect: to => ({ name: 'riskVerificationEvidence', query: Object.assign({}, to.query) }) }
  ]);
  router.addRoutes([{ path: '/business/verifyevidence', name: 'serverLegacyMenu' }]);

  const resolved = router.match('/business/verifyevidence?tab=groups&topicId=9');
  assert.equal(resolved.name, 'riskVerificationEvidence');
  assert.equal(resolved.path, '/risk/verification-evidence');
  assert.deepEqual(resolved.query, { tab: 'groups', topicId: '9' });
});

test('旧入口只兼容转发，不在旧地址读取核销证据', () => {
  const component = loadVerificationEvidence();
  const targets = [];
  let reads = 0;
  const vm = attachMethods(component, Object.assign(component.data(), {
    $route: { path: '/business/verifyevidence', query: { tab: 'groups', topicId: '9' } },
    $router: { replace: target => targets.push(target) }
  }));
  vm.reloadEvidence = () => { reads += 1; };

  const redirected = component.methods.ensureCanonicalRoute.call(vm);

  assert.equal(redirected, true);
  assert.deepEqual(targets, [{ name: 'riskVerificationEvidence', query: { tab: 'groups', topicId: '9' } }]);
  assert.equal(reads, 0);
});

test('扫码与团核销读取各自防旧回包，失败不能伪装为空记录', async () => {
  const scanPending = [];
  const groupPending = [];
  const component = loadVerificationEvidence({
    scanList: params => new Promise((resolve, reject) => scanPending.push({ params, resolve, reject })),
    groupVerifyList: params => new Promise((resolve, reject) => groupPending.push({ params, resolve, reject }))
  });
  const vm = attachMethods(component, component.data());

  vm.scanQuery.memberId = '11';
  vm.loadScans();
  vm.scanQuery.memberId = '22';
  vm.loadScans();
  scanPending[1].resolve({ data: [{ id: 2, memberId: 22 }] });
  await flushPromises();
  scanPending[0].resolve({ data: [{ id: 1, memberId: 11 }] });
  await flushPromises();

  assert.deepEqual(vm.scans, [{ id: 2, memberId: 22 }]);
  assert.deepEqual(scanPending[1].params, { memberId: 22, registrationId: undefined, registrationType: 1 });

  vm.groupQuery.topicId = '33';
  vm.loadGroups();
  groupPending[0].reject(new Error('offline'));
  await flushPromises();

  assert.deepEqual(vm.groups, []);
  assert.equal(vm.groupError, '团核销证据读取失败，请重新读取。');
  assert.equal(vm.groupLoading, false);
  const source = read('src/views/business/verifyevidence/index.vue');
  assert.match(source, /<cy-resource-state[\s\S]*?v-if="scanError"[\s\S]*?@retry="loadScans"/);
  assert.match(source, /<cy-resource-state[\s\S]*?v-if="groupError"[\s\S]*?@retry="loadGroups"/);
});

test('核销证据 API 保持两条 GET 只读合同，不引入修改入口', () => {
  const api = read('src/api/business/verifyEvidence.js');
  const source = read('src/views/business/verifyevidence/index.vue');

  assert.equal((api.match(/method:\s*['"]get['"]/g) || []).length, 2);
  assert.doesNotMatch(api, /method:\s*['"](?:post|put|delete|patch)['"]/);
  assert.doesNotMatch(source, /business:verifyEvidence:(?:add|edit|remove)/);
});
