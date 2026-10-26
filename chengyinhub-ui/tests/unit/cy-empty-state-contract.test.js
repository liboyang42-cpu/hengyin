const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadEmptyState() {
  const source = read('src/components/CyEmptyState/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

function loadImWorkbench(listImConversations) {
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
    .replace(/^import \{[^}]+\} from '@\/api\/business\/im'$/m, 'const { listImConversations } = dependencies;')
    .replace(/^import CyEmptyState.+$/m, 'const CyEmptyState = {};')
    .replace(/^import CyPageHeader.+$/m, 'const CyPageHeader = {};')
    .replace(/^import CyResourceState.+$/m, 'const CyResourceState = {};');
  return new Function('intent', 'window', 'dependencies', script.replace(/export\s+default\s*/, 'return '))(intent, browser, { listImConversations });
}

test('CyEmptyState 只呈现页面已判断的空态，并在页面启用时回传下一步动作', () => {
  const component = loadEmptyState();
  const emitted = [];

  for (const propName of ['title', 'description', 'actionText', 'actionLoading']) {
    assert.ok(component.props[propName], `缺少 ${propName} 接口`);
  }
  assert.equal(component.methods && component.methods.getList, undefined);
  assert.equal(component.methods && component.methods.loadWorkspace, undefined);

  component.methods.triggerAction.call({ actionLoading: false, $emit: (...args) => emitted.push(args) });
  component.methods.triggerAction.call({ actionLoading: true, $emit: (...args) => emitted.push(args) });
  assert.deepEqual(emitted, [['action']]);

  const source = read('src/components/CyEmptyState/index.vue');
  assert.match(source, /role="status"/);
  assert.match(source, /v-if="actionText"/);
  assert.match(source, /:loading="actionLoading"/);
  assert.match(source, /:disabled="actionLoading"/);
});

test('客服会话只在成功读取后显示空态，失败与刷新状态仍由页面管理', () => {
  const source = read('src/views/business/im/index.vue');

  assert.ok(source.includes("import CyEmptyState from '@/components/CyEmptyState'"));
  assert.ok(source.includes("import CyResourceState from '@/components/CyResourceState'"));
  assert.ok(source.includes("import CyPageHeader from '@/components/CyPageHeader'"));
  assert.match(source, /<cy-page-header[\s\S]*?title="客服会话"[\s\S]*?:updated-at="convsUpdatedAt"[\s\S]*?@click="loadConvs"/);
  assert.match(source, /<el-button v-if="!convsError && convsLoaded && convs\.length"[\s\S]*?@click="loadConvs">刷新会话<\/el-button>/);
  assert.match(source, /<cy-resource-state[\s\S]*?v-if="convsError"[\s\S]*?@retry="loadConvs"/);
  assert.match(source, /<cy-empty-state[\s\S]*?v-else-if="convsLoaded && !convs\.length"[\s\S]*?title="暂无客服会话"[\s\S]*?:action-loading="convsLoading"[\s\S]*?@action="loadConvs"/);
  assert.match(source, /convsLoading: false/);
  assert.match(source, /convsLoaded: false/);
  assert.match(source, /convsUpdatedAt: ''/);
  assert.match(source, /if \(this\.convsLoading\) return/);
  assert.match(source, /客服会话读取失败，请检查网络后重试。/);
  assert.doesNotMatch(source, /<el-empty v-if="convs\.length === 0"/);
});

test('客服会话请求成功、失败和重入时均保留正确的页面状态', async () => {
  const pending = [];
  const workbench = loadImWorkbench(() => new Promise((resolve, reject) => pending.push({ resolve, reject })));
  const vm = workbench.data();
  vm.$store = { state: { user: { id: 7 } } };
  for (const [name, method] of Object.entries(workbench.methods)) vm[name] = method.bind(vm);
  vm.parseTime = () => '2026-07-22 02:10';

  const firstRequest = workbench.methods.loadConvs.call(vm);
  assert.equal(vm.convsLoading, true);
  assert.equal(workbench.methods.loadConvs.call(vm), undefined);
  assert.equal(pending.length, 1);

  pending[0].resolve({ data: [] });
  await firstRequest;
  assert.equal(vm.convsLoading, false);
  assert.equal(vm.convsLoaded, true);
  assert.equal(vm.convsError, '');
  assert.equal(vm.convsUpdatedAt, '2026-07-22 02:10');

  const failedWorkbench = loadImWorkbench(() => Promise.reject(new Error('network')));
  const failedVm = failedWorkbench.data();
  failedVm.$store = { state: { user: { id: 7 } } };
  for (const [name, method] of Object.entries(failedWorkbench.methods)) failedVm[name] = method.bind(failedVm);
  await failedWorkbench.methods.loadConvs.call(failedVm);
  assert.equal(failedVm.convsLoading, false);
  assert.equal(failedVm.convsLoaded, false);
  assert.equal(failedVm.convsError, '客服会话读取失败，请检查网络后重试。');
});

test('主题工作台只在成功读取后复用无动作空态，不为章节配置虚构领域按钮', () => {
  const source = read('src/views/business/topic/workspace.vue');

  assert.ok(source.includes("import CyEmptyState from '@/components/CyEmptyState'"));
  assert.match(source, /<cy-empty-state[\s\S]*?v-if="!isAxisAvailable\('structure'\)"/);
  assert.match(source, /<cy-empty-state[\s\S]*?v-else-if="!loading && !error && !chapters\.length"[\s\S]*?title="尚未配置章节"/);
  assert.doesNotMatch(source, /<cy-empty-state[\s\S]*?v-else-if="!loading && !error && !chapters\.length"[\s\S]*?action-text=/);
  assert.doesNotMatch(source, /<el-empty v-if="!chapters\.length"/);
});
