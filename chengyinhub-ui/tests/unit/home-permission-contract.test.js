const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');
const read = relativePath => fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');

function loadIndex() {
  const script = read('src/views/index.vue').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/export\s+default\s*/, 'return ');
  return new Function('getHomeData', 'CyPageHeader', 'CyTaskQueue', 'CyMetricCard', script);
}

function vmFor(permissions, getHomeData) {
  const component = loadIndex()(getHomeData, {}, {}, {});
  const vm = { ...component.data(), $store: { state: { user: { permissions } } }, parseTime: () => 'now' };
  for (const [name, fn] of Object.entries(component.methods)) vm[name] = fn.bind(vm);
  Object.defineProperty(vm, 'canReadSummary', { get: () => component.computed.canReadSummary.call(vm) });
  return vm;
}

test('F-W4-02 无 business:analytics:list 不请求首页摘要，提示无权而不是网络问题，且不标「已接通」', async () => {
  let called = 0;
  const vm = vmFor(['business:square:list'], () => { called += 1; return Promise.resolve({ data: {} }); });
  await vm.getHomeData();
  assert.equal(called, 0);
  assert.match(vm.error, /无权查看待审摘要/);
  assert.doesNotMatch(vm.error, /网络/);
  assert.equal(vm.summaryLoaded, false);
});

test('F-W4-02 有权限时请求成功才标「已接通」；失败提示不归因为网络', async () => {
  const ok = vmFor(['business:analytics:list'], () => Promise.resolve({ data: { topicNum: 2 } }));
  await ok.getHomeData();
  assert.equal(ok.summaryLoaded, true);
  assert.equal(ok.error, '');

  const failed = vmFor(['business:analytics:list'], () => Promise.reject('error'));
  await failed.getHomeData();
  assert.equal(failed.summaryLoaded, false);
  assert.doesNotMatch(failed.error, /检查网络/);
  assert.match(failed.error, /权限或服务状态/);
});

test('F-W4-02 模板：审核台按钮、刷新与重试按权限出现，「已接通」绑定请求结果', () => {
  const source = read('src/views/index.vue');
  assert.match(source, /v-if="hasPermission\('business:audit:list'\)"[^>]*@click="goAuditWorkbench"/);
  assert.match(source, /el-button icon="el-icon-refresh" v-if="canReadSummary"[^>]*@click="getHomeData"/);
  assert.match(source, /:retryable="canReadSummary"/);
  assert.match(source, /:connected="summaryLoaded"/);
  assert.doesNotMatch(source, /description="商家、帖文、主题、活动和模板待审计数。"\s*connected/);

  const queue = read('src/components/CyTaskQueue/index.vue');
  assert.match(queue, /<button v-if="retryable" type="button" @click="\$emit\('retry'\)">重新读取<\/button>/);
  assert.match(queue, /retryable: \{ type: Boolean, default: true \}/);
});

test('F-W4-02 无审核权限时 goAuditWorkbench 不跳转（避免白屏）', () => {
  const vm = vmFor(['business:analytics:list'], () => Promise.resolve({ data: {} }));
  let pushed = 0;
  vm.$router = { push: () => { pushed += 1; } };
  vm.goAuditWorkbench();
  assert.equal(pushed, 0);
});
