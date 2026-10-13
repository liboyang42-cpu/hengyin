const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadFilterBar() {
  const source = read('src/components/CyFilterBar/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script.replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('CyFilterBar 将查询、重置与已选条件移除交还给页面', () => {
  const component = loadFilterBar();
  const emitted = [];
  const vm = { $emit: (...args) => emitted.push(args) };

  component.methods.submit.call(vm);
  component.methods.reset.call(vm);
  component.methods.removeFilter.call(vm, 'status');

  assert.deepEqual(emitted, [
    ['submit'],
    ['reset'],
    ['remove-filter', 'status']
  ]);
});

test('CyFilterBar 的接口只要求已选条件描述，复杂字段保留在页面 slot', () => {
  const source = read('src/components/CyFilterBar/index.vue');

  assert.match(source, /activeFilters:\s*\{\s*type:\s*Array/);
  assert.match(source, /<slot\s*\/>/);
  assert.match(source, /<slot\s+name="more"\s*\/>/);
  assert.ok(source.includes('@click="removeFilter(filter.key)"'));
});

test('CyFilterBar 只在页面提供更多筛选字段时展开该区域', () => {
  const component = loadFilterBar();
  const vm = { ...component.data(), $slots: { more: [{}] } };

  assert.equal(component.computed.hasMoreFilters.call(vm), true);
  assert.equal(vm.moreOpen, false);
  component.methods.toggleMore.call(vm);
  assert.equal(vm.moreOpen, true);
  component.methods.toggleMore.call(vm);
  assert.equal(vm.moreOpen, false);
});

test('审核台和退款台以各自查询状态接入 CyFilterBar，而非共享 queryParams', () => {
  for (const relativePath of [
    'src/views/business/audit/index.vue',
    'src/views/business/finance/refund/index.vue'
  ]) {
    const source = read(relativePath);
    assert.ok(source.includes("import CyFilterBar from '@/components/CyFilterBar'"));
    assert.ok(source.includes('<cy-filter-bar'));
    assert.ok(source.includes(':active-filters="activeFilters"'));
    assert.ok(source.includes('@remove-filter="clearFilter"'));
  }
});
