const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadDataTable() {
  const source = read('src/components/CyDataTable/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script.replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('CyDataTable 把数据请求和业务动作留在页面，只接收列表呈现状态', () => {
  const component = loadDataTable();

  for (const prop of ['data', 'columns', 'loading', 'total', 'page', 'limit']) {
    assert.ok(component.props[prop], `缺少 ${prop} 呈现状态`);
  }
  assert.equal(component.methods.getList, undefined, '组件不应拥有页面请求');
  assert.equal(component.methods.openDetail, undefined, '组件不应拥有业务动作');
});

test('CyDataTable 只渲染可见列，并在页码变化时把状态交还给页面', () => {
  const component = loadDataTable();
  const vm = {
    columns: [
      { key: 'subject', visible: true },
      { key: 'risk', visible: false },
      { key: 'actions', visible: true }
    ],
    ...component.data(),
    $emit: (...args) => vm.emitted.push(args),
    emitted: []
  };

  assert.deepEqual(component.computed.visibleColumns.call(vm).map((column) => column.key), ['subject', 'actions']);
  component.methods.handlePagination.call(vm, { page: 3, limit: 40 });
  assert.deepEqual(vm.emitted, [
    ['update:page', 3],
    ['update:limit', 40],
    ['pagination', { page: 3, limit: 40 }]
  ]);
});

test('CyDataTable 的密度与列设置留在表格模块内，不要求页面维护 UI 状态', () => {
  const component = loadDataTable();
  const vm = { ...component.data.call({ defaultDensity: 'compact' }) };

  assert.equal(vm.density, 'compact');
  component.methods.setDensity.call(vm, 'comfortable');
  assert.equal(vm.density, 'comfortable');
});

test('CyDataTable 不允许列设置隐藏最后一列业务列或固定操作列', () => {
  const component = loadDataTable();
  const vm = {
    columnStates: [
      { key: 'subject', visible: true },
      { key: 'actions', visible: true, locked: true }
    ]
  };

  component.methods.setColumnVisibility.call(vm, 'actions', false);
  assert.equal(vm.columnStates[1].visible, true);
  component.methods.setColumnVisibility.call(vm, 'subject', false);
  assert.equal(vm.columnStates[0].visible, true);
  assert.equal(vm.columnStates[1].visible, true);
});

test('CyDataTable 仅在页面明确启用时提供选择列，并把所选行交回页面', () => {
  const component = loadDataTable();
  const vm = {
    ...component.data.call({ defaultDensity: 'compact' }),
    selectable: true,
    $emit: (...args) => vm.emitted.push(args),
    emitted: []
  };
  const rows = [{ id: 12 }, { id: 18 }];

  assert.ok(component.props.selectable, '页面应显式决定是否需要批量选择');
  assert.equal(component.methods.handleExport, undefined, '组件不应拥有导出请求');
  component.methods.handleSelectionChange.call(vm, rows);

  assert.deepEqual(vm.selectedRows, rows);
  assert.deepEqual(vm.emitted, [['selection-change', rows]]);
});

test('CyDataTable 将作用域工具栏插槽作为真实页面操作区渲染', () => {
  const source = read('src/components/CyDataTable/index.vue');

  assert.match(source, /\$scopedSlots\['toolbar-actions'\]/);
  assert.doesNotMatch(source, /v-if="\$slots\['toolbar-actions'\]"/);
});

test('优惠券目录把读取与导出接入 CyDataTable，物理删除不再作为批量动作提供', () => {
  const source = read('src/views/business/coupon/index.vue');

  assert.ok(source.includes("import CyDataTable from '@/components/CyDataTable'"));
  assert.match(source, /<cy-data-table[\s\S]*?@pagination="handlePagination"[\s\S]*?@refresh="getList"/);
  assert.match(source, /@refresh="getList"/);
  assert.match(source, /<template slot="toolbar-actions">/);
  assert.match(source, /@click="handleExport"/);
  assert.match(source, /@click="deactivateCoupon\(scope\.row\)"/);
  assert.doesNotMatch(source, /:selectable="true"|@selection-change="handleSelectionChange"/);
  assert.doesNotMatch(source, /\bdelCoupon\b/);
  assert.doesNotMatch(source, /<el-table\b/, '页面不应保留手写选择表格壳');
});

test('票务定位器复用数据表，但不保留全局选择或写操作入口', () => {
  const source = read('src/views/business/ticket/index.vue');

  assert.ok(source.includes("import CyDataTable from '@/components/CyDataTable'"));
  assert.match(source, /<cy-data-table[\s\S]*?@pagination="handlePagination"/);
  assert.match(source, /@refresh="getList"/);
  assert.match(source, /<cy-entity-link/);
  assert.match(source, /@open="openOwner\(row\)"/);
  assert.doesNotMatch(source, /:selectable="true"|@selection-change="handleSelectionChange"|<template #toolbar-actions>|handleAdd|handleUpdate|handleDelete|handleExport/);
  assert.doesNotMatch(source, /<el-table\b/, '页面不应保留手写选择表格壳');
});

test('审核台和退款台以真实队列列配置接入 CyDataTable，而不是继续各自手写 el-table', () => {
  for (const relativePath of [
    'src/views/business/audit/index.vue',
    'src/views/business/finance/refund/index.vue'
  ]) {
    const source = read(relativePath);
    assert.ok(source.includes("import CyDataTable from '@/components/CyDataTable'"));
    assert.ok(source.includes('<cy-data-table'));
    assert.ok(source.includes(':columns="tableColumns"'));
    assert.doesNotMatch(source, /<el-table\b/, '页面不应保留手写表格壳');
  }
});
