const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadTaskQueue() {
  const source = read('src/components/CyTaskQueue/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

test('CyTaskQueue 接收标题、描述、条目、读取与错误状态，导出 retry 与 select 两个事件', () => {
  const component = loadTaskQueue();
  const source = read('src/components/CyTaskQueue/index.vue');

  for (const propName of ['title', 'description', 'items', 'loading', 'error']) {
    assert.ok(component.props[propName], `缺少 ${propName} 接口`);
  }
  assert.equal(component.props.loading.default, false, 'loading 默认应为 false');
  assert.equal(component.props.error.default, '', 'error 默认应为空字符串');
  assert.deepEqual(component.props.items.default(), [], 'items 默认应为空数组');

  assert.match(source, /\$emit\('retry'\)/);
  assert.match(source, /\$emit\('select', item\)/);
});

test('CyTaskQueue total 计算所有条目的 count 之和，支持数字与字符串', () => {
  const component = loadTaskQueue();

  const vmWithNumbers = { items: [{ count: 3 }, { count: 5 }, { count: 2 }] };
  assert.equal(component.computed.total.call(vmWithNumbers), 10);

  const vmWithStrings = { items: [{ count: '4' }, { count: '1' }] };
  assert.equal(component.computed.total.call(vmWithStrings), 5);

  const vmWithMixed = { items: [{ count: 0 }, { count: 0 }] };
  assert.equal(component.computed.total.call(vmWithMixed), 0);

  const vmEmpty = { items: [] };
  assert.equal(component.computed.total.call(vmEmpty), 0);

  const vmMissingCount = { items: [{ count: 5 }, {}] };
  assert.equal(component.computed.total.call(vmMissingCount), 5);
});

test('CyTaskQueue 模板包含读取中、错误重试和空队列三种状态视图', () => {
  const source = read('src/components/CyTaskQueue/index.vue');

  assert.match(source, /v-if="loading".*正在读取任务队列/);
  assert.match(source, /v-else-if="error"/);
  assert.match(source, /\$emit\('retry'\)/);
  assert.match(source, /重新读取/);
  assert.match(source, /v-else-if="!items\.length"/);
  assert.match(source, /当前没有需要处理的任务/);
});

test('CyTaskQueue 不拥有任务数据请求、路由跳转或统计聚合之外的领域计算', () => {
  const component = loadTaskQueue();

  assert.equal(component.methods && component.methods.getHomeData, undefined, '组件不应请求首页数据');
  assert.equal(component.methods && component.methods.openTask, undefined, '组件不应拥有路由跳转');
  assert.equal(component.methods && component.methods.getList, undefined, '组件不应请求列表');
});

test('首页将真实待审队列传入 CyTaskQueue，并监听 select 进入对应业务页', () => {
  const source = read('src/views/index.vue');

  assert.ok(source.includes("import CyTaskQueue from '@/components/CyTaskQueue'"));
  assert.match(source, /<cy-task-queue/);
  // 传给队列的是按 hasPermission 过滤后的列表（无权卡片点了会被
  // router 的 .catch(err => err) 吞成零反馈，所以不能直接渲染 reviewTasks）
  assert.match(source, /:items="visibleReviewTasks"/);
  assert.match(source, /:loading="loading"/);
  assert.match(source, /:error="error"/);
  assert.match(source, /@select="openTask"/);
  assert.match(source, /@retry="getHomeData"/);

  assert.match(source, /title="城市任务流"/);
  assert.match(source, /description="/);

  assert.doesNotMatch(source, /<cy-task-queue\s[^>]*@fetch/);
});
