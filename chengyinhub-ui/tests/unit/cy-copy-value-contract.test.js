const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadCopyValue() {
  const source = read('src/components/CyCopyValue/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script.replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('CyCopyValue 只负责展示与复制反馈，不接管页面数据或领域动作', () => {
  const component = loadCopyValue();
  const vm = { value: 0 };

  assert.ok(component.props.value, '复制值必须由页面提供');
  assert.equal(component.methods.getList, undefined, '组件不应请求页面数据');
  assert.equal(component.methods.openDetail, undefined, '组件不应拥有领域动作');
  assert.equal(component.computed.hasValue.call(vm), true, '数字 0 也是可复制的值');
  assert.equal(component.computed.displayValue.call({ value: 0, hasValue: true }), '0');
  assert.equal(component.computed.hasValue.call({ value: '' }), false, '空值不可触发复制');
  assert.equal(component.computed.displayValue.call({ value: null, hasValue: false }), '-');
});

test('CyCopyValue 复用现有 clipboard 指令，并提供可访问的复制按钮与统一反馈', () => {
  const source = read('src/components/CyCopyValue/index.vue');

  assert.match(source, /v-clipboard:copy="value"/);
  assert.match(source, /v-clipboard:success="handleSuccess"/);
  assert.match(source, /v-clipboard:error="handleError"/);
  assert.match(source, /:disabled="!hasValue"/);
  assert.match(source, /:aria-label="`复制\$\{label\}`"/);
  assert.match(source, /@click\.stop/);
  assert.match(source, /this\.\$modal\.msgSuccess\(`/);
  assert.match(source, /this\.\$modal\.msgError\(/);
});

test('订单、商品与 AI 调用日志以同一组件复制编号或 traceId，不再手写 execCommand', () => {
  for (const relativePath of [
    'src/views/business/config/points-product/index.vue',
    'src/views/business/finance/order/index.vue',
    'src/views/business/ailog/index.vue'
  ]) {
    const source = read(relativePath);
    assert.ok(source.includes("import CyCopyValue from '@/components/CyCopyValue'"));
    assert.match(source, /<cy-copy-value/);
    assert.doesNotMatch(source, /execCommand\(/, '页面不应保留手写复制实现');
  }
});
