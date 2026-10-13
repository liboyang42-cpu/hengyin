const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function loadEntityLink() {
  const source = fs.readFileSync(path.join(uiRoot, 'src/components/CyEntityLink/index.vue'), 'utf8');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

test('CyEntityLink 展示可追踪对象身份，并把打开动作交还给页面', () => {
  const component = loadEntityLink();
  const emitted = [];
  const vm = {
    entityType: 'TOPIC',
    typeLabel: '审核对象',
    entityId: 42,
    $emit: (...args) => emitted.push(args)
  };

  assert.ok(component.props.name);
  assert.ok(component.props.entityType);
  assert.ok(component.props.typeLabel);
  assert.ok(component.props.entityId);
  component.methods.open.call(vm);
  assert.deepEqual(emitted, [['open', { type: 'TOPIC', id: 42 }]]);
});

test('审核台和退款台用各自领域身份接入 CyEntityLink，抽屉动作仍归页面', () => {
  for (const relativePath of [
    'src/views/business/audit/index.vue',
    'src/views/business/finance/refund/index.vue'
  ]) {
    const source = fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
    assert.ok(source.includes("import CyEntityLink from '@/components/CyEntityLink'"));
    assert.ok(source.includes('<cy-entity-link'));
    assert.ok(source.includes('@open="openDetail(scope.row)"'));
    assert.doesNotMatch(source, /class="entity-link"/, '页面不应保留手写对象链接壳');
  }
  const audit = fs.readFileSync(path.join(uiRoot, 'src/views/business/audit/index.vue'), 'utf8');
  assert.ok(audit.includes(':entity-type="scope.row.bizType"'));
});
