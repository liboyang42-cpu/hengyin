const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadResourceState() {
  const source = read('src/components/CyResourceState/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

function loadClub() {
  const source = read('src/views/business/club/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script
    .replace(/import\s+\{[\s\S]*?\}\s+from\s+['"][^'"]+['"]\n/g, '')
    .replace(/^import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('CyResourceState 只呈现已判定的不可用状态，不请求资源或判断权限', () => {
  const component = loadResourceState();

  for (const propName of ['kind', 'title', 'description', 'retryText', 'retrying']) {
    assert.ok(component.props[propName], `缺少 ${propName} 接口`);
  }
  assert.equal(component.methods && component.methods.loadWorkspace, undefined);
  assert.equal(component.methods && component.methods.checkPermi, undefined);
  assert.match(read('src/components/CyResourceState/index.vue'), /\['forbidden', 'error'\]/);
});

test('CyResourceState 只在页面提供重试动作时回传 retry，并在重试中禁用按钮', () => {
  const component = loadResourceState();
  const emitted = [];
  component.methods.retry.call({ $emit: (...args) => emitted.push(args) });
  assert.deepEqual(emitted, [['retry']]);

  component.methods.retry.call({ retrying: true, $emit: (...args) => emitted.push(args) });
  assert.deepEqual(emitted, [['retry']], '重试中不得再次 emit');

  const source = read('src/components/CyResourceState/index.vue');
  assert.match(source, /v-if="retryText"/);
  assert.match(source, /:loading="retrying"/);
  assert.match(source, /:disabled="retrying"/);
});

test('主题工作台与俱乐部工作区接入同一状态组件，并由页面提供重试动作', () => {
  const topic = read('src/views/business/topic/workspace.vue');
  const club = read('src/views/business/club/index.vue');

  for (const source of [topic, club]) {
    assert.ok(source.includes("import CyResourceState from '@/components/CyResourceState'"));
    assert.ok(source.includes('<cy-resource-state'));
  }
  assert.match(topic, /kind="error"/);
  assert.match(topic, /@retry="loadWorkspace"/);
  assert.match(topic, /checkPermi\(\['business:topic:edit'\]\)/);
  assert.match(club, /@retry="retryWorkspace"/);
  assert.match(club, /retryWorkspace\(\)/);
  assert.match(club, /if \(this\.workspaceLoading \|\| !this\.current\.id\) return/);
});

test('俱乐部工作区重试会在加载中或缺少对象时早退，空闲时才重新读取', () => {
  const club = loadClub();
  const calls = [];
  const vm = {
    workspaceLoading: true,
    current: { id: 7 },
    openWorkspace: row => calls.push(row)
  };

  club.methods.retryWorkspace.call(vm);
  vm.workspaceLoading = false;
  vm.current = {};
  club.methods.retryWorkspace.call(vm);
  vm.current = { id: 7 };
  club.methods.retryWorkspace.call(vm);

  assert.deepEqual(calls, [{ id: 7 }]);
});
