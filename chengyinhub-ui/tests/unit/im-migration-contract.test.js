const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const VueRouter = require('vue-router');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadImWorkbench(overrides = {}) {
  const intentSource = read('src/utils/im-admin-send-intent.js').replace(/export function /g, 'function ');
  const intent = new Function(intentSource + '\nreturn { adminSendIntent, clearAdminSendIntent };')();
  const values = new Map();
  const browser = { sessionStorage: {
    getItem: key => values.get(key) || null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  }, crypto: { randomUUID: require('node:crypto').randomUUID } };
  const source = read('src/views/business/im/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ adminSendIntent, clearAdminSendIntent \} from .+$/m, 'const { adminSendIntent, clearAdminSendIntent } = intent;')
    .replace(/^import \{ getToken \} from .+$/m, 'const getToken = () => "fixture-session";')
    .replace(/^import \{ [^}]+\} from '@\/api\/business\/im'$/m, 'const { listImConversations, listImMessages, sendImMessage, getImSendReceipt } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('intent', 'window', 'dependencies', script.replace(/export\s+default\s*/, 'return '))(intent, browser, Object.assign({
    listImConversations: () => Promise.resolve({ data: [] }),
    listImMessages: () => Promise.resolve({ data: { list: [] } }),
    sendImMessage: () => Promise.resolve({ code: 200 })
  }, overrides));
}

function attachMethods(component, vm) {
  vm.$store = vm.$store || { state: { user: { id: 7 } } };
  Object.keys(component.methods).forEach(name => {
    vm[name] = (...args) => component.methods[name].apply(vm, args);
  });
  return vm;
}

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve));
}

test('客服会话以原列表权限注册唯一的 customer/im 入口', () => {
  const router = read('src/router/index.js');

  assert.match(router, /path:\s*['"]\/customer\/im['"][\s\S]*?permissions:\s*\[['"]business:im:list['"]\][\s\S]*?name:\s*['"]customerIm['"]/);
  assert.match(router, /component:\s*\(\)\s*=>\s*import\(['"]@\/views\/business\/im\/index['"]\)/);
  assert.match(router, /path:\s*['"]\/business\/im['"][\s\S]*?redirect:\s*to\s*=>\s*\(\{\s*name:\s*['"]customerIm['"]/);
  assert.ok(router.indexOf("path: '/customer/im'") < router.indexOf("path: '/business/im'"));
});

test('旧客服会话地址在后台同 path 菜单注册后仍路由级跳到规范入口', () => {
  const router = new VueRouter({ mode: 'abstract', routes: [] });
  router.addRoutes([
    { path: '/customer/im', name: 'customerIm' },
    { path: '/business/im', redirect: to => ({ name: 'customerIm', query: Object.assign({}, to.query) }) }
  ]);
  router.addRoutes([{ path: '/business/im', name: 'serverLegacyMenu' }]);

  const resolved = router.match('/business/im?memberId=19');
  assert.equal(resolved.name, 'customerIm');
  assert.equal(resolved.path, '/customer/im');
  assert.deepEqual(resolved.query, { memberId: '19' });
});

test('旧客服入口只兼容转发，不在旧地址读取会话', () => {
  const component = loadImWorkbench();
  const targets = [];
  let reads = 0;
  const vm = attachMethods(component, Object.assign(component.data(), {
    $route: { path: '/business/im', query: { memberId: '19' } },
    $router: { replace: target => targets.push(target) }
  }));
  vm.loadConvs = () => { reads += 1; };

  component.methods.ensureCanonicalRoute.call(vm);

  assert.deepEqual(targets, [{ name: 'customerIm', query: { memberId: '19' } }]);
  assert.equal(reads, 0);
});

test('切换客服会话时，较早消息回包不会覆盖新会话，失败不伪装成空消息', async () => {
  const pending = {};
  const component = loadImWorkbench({
    listImMessages: conversationId => new Promise((resolve, reject) => {
      pending[conversationId.conversationId] = { resolve, reject };
    })
  });
  const vm = attachMethods(component, Object.assign(component.data(), {
    $nextTick: callback => callback(),
    $refs: { msgs: { scrollTop: 0, scrollHeight: 100 } }
  }));

  component.methods.selectConv.call(vm, { conversationId: 1, memberId: 11 });
  component.methods.selectConv.call(vm, { conversationId: 2, memberId: 22 });
  pending[2].resolve({ data: { list: [{ id: 2, content: '新会话' }] } });
  await flushPromises();
  pending[1].resolve({ data: { list: [{ id: 1, content: '旧会话' }] } });
  await flushPromises();

  assert.equal(vm.current.conversationId, 2);
  assert.deepEqual(vm.msgs.map(message => message.id), [2]);

  component.methods.selectConv.call(vm, { conversationId: 3, memberId: 33 });
  pending[3].reject(new Error('offline'));
  await flushPromises();

  assert.deepEqual(vm.msgs, []);
  assert.equal(vm.messagesError, '会话消息读取失败，请重新读取。');
  const source = read('src/views/business/im/index.vue');
  assert.match(source, /<cy-resource-state[\s\S]*?v-if="messagesError"[\s\S]*?@retry="loadMsgs"/);
});

test('客服会话不直接执行全局封禁，发送失败可见且旧会话回包不污染新草稿', async () => {
  const sends = [];
  const component = loadImWorkbench({
    sendImMessage: payload => new Promise((resolve, reject) => sends.push({ payload, resolve, reject }))
  });
  const errors = [];
  let listReloads = 0;
  let messageReloads = 0;
  const vm = attachMethods(component, Object.assign(component.data(), {
    current: { conversationId: 1, memberId: 11 },
    sendText: '给 A 的回复',
    $store: { state: { user: { id: 7 } } },
    $modal: { msgError: message => errors.push(message) }
  }));
  vm.loadConvs = () => { listReloads += 1; };
  vm.loadMsgs = () => { messageReloads += 1; };

  const firstSend = component.methods.doSend.call(vm);
  assert.deepEqual(sends[0].payload, { memberId: '11', msgType: 1, content: '给 A 的回复', requestId: vm.pendingSend.requestId });
  assert.match(sends[0].payload.requestId, /^[A-Za-z0-9_-]{8,96}$/);
  vm.current = { conversationId: 2, memberId: 22 };
  vm.sendText = '给 B 的草稿';
  sends[0].resolve({ code: 200 });
  await firstSend;

  assert.equal(vm.sendText, '给 B 的草稿');
  assert.equal(messageReloads, 0);
  assert.equal(listReloads, 1);

  vm.current = { conversationId: 3, memberId: 33 };
  vm.sendText = '失败后保留';
  const failedSend = component.methods.doSend.call(vm);
  sends[1].reject(new Error('offline'));
  await failedSend;
  assert.equal(vm.sendText, '失败后保留');
  assert.deepEqual(errors, []);
  assert.match(vm.sendRecoveryError, /offline.*保留原请求号与内容/);
  assert.equal(vm.pendingSend.requestId, sends[1].payload.requestId);
  assert.equal(vm.pendingSend.content, '失败后保留');
  assert.equal(vm.sending, false);

  const source = read('src/views/business/im/index.vue');
  assert.doesNotMatch(source, /\bblockImUser\b|\bunblockImUser\b|\bbusiness:im:block\b|\bdoBlock\b|\bdoUnblock\b/);
});
