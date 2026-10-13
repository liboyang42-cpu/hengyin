const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const compiler = require('vue-template-compiler');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadDetailGrid() {
  const source = read('src/components/CyDetailGrid/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

test('CyDetailGrid 只接收页面已格式化的只读字段，不请求详情或判断权限', () => {
  const component = loadDetailGrid();
  const source = read('src/components/CyDetailGrid/index.vue');

  for (const propName of ['title', 'items', 'columns', 'emptyText', 'emptyValue']) {
    assert.ok(component.props[propName], `缺少 ${propName} 接口`);
  }
  assert.equal(component.methods.getDetail, undefined, '组件不应拥有详情请求');
  assert.equal(component.methods.checkPermi, undefined, '组件不应判断页面权限');
  assert.match(source, /<slot\s+:name="item\.key"/);
  assert.match(source, /<el-empty v-else/);
});

test('CyDetailGrid 保留 0 等有效值，隐藏无效字段，并将跨度限制在列数内', () => {
  const component = loadDetailGrid();
  const items = [
    { key: 'amount', label: '金额', value: 0 },
    { key: 'hidden', label: '隐藏字段', value: 'x', hidden: true },
    { key: '', label: '无稳定键', value: 'x' },
    { key: 'noLabel', label: '', value: 'x' }
  ];

  assert.deepEqual(component.computed.visibleItems.call({ items }), [items[0]]);
  assert.equal(component.computed.safeColumns.call({ columns: 7 }), 2);
  assert.equal(component.methods.displayValue.call({ emptyValue: '—' }, 0), 0);
  assert.equal(component.methods.displayValue.call({ emptyValue: '—' }, ''), '—');
  assert.equal(component.methods.itemSpan.call({ safeColumns: 2 }, { span: 4 }), 2);
  assert.equal(component.methods.itemSpan.call({ safeColumns: 2 }, { span: 1.5 }), 1);
  assert.equal(component.methods.itemSpan.call({ safeColumns: 2 }, { span: 0 }), 1);
});

test('规范订单详情和提现收款资料以各自可行动字段接入 CyDetailGrid', () => {
  const order = read('src/views/business/finance/order/detail.vue');
  const payout = read('src/views/business/memberwithdrawal/index.vue');

  assert.ok(order.includes("import CyDetailGrid from '@/components/CyDetailGrid'"));
  assert.match(order, /<cy-detail-grid title="订单事实" :items="orderItems" :columns="3">/);
  assert.match(order, /orderTimelineItems\(\)/);
  assert.match(order, /statusAndNoteItems\(\)/);
  assert.match(order, /refundItems\(\)/);
  assert.doesNotMatch(order, /shippingItems|receiverDetailAddress|deliveryCompany|deliverySn/);
  assert.doesNotMatch(order, /<el-descriptions(?=[\s>])/);
  const orderTemplate = compiler.parseComponent(order).template.content;
  const orderRender = compiler.compile(orderTemplate).render;
  assert.match(orderRender, /"items":orderItems/);
  assert.match(orderRender, /"items":orderTimelineItems/);
  assert.match(orderRender, /"items":statusAndNoteItems/);
  assert.match(orderRender, /"items":refundItems/);

  assert.ok(payout.includes("import CyDetailGrid from '@/components/CyDetailGrid'"));
  assert.match(payout, /<cy-detail-grid v-loading="payout\.loading" :items="payoutDetailItems" :columns="1"/);
  assert.match(payout, /payoutDetailItems\(\)/);
  assert.match(payout, /v-hasPermi="\['business:memberwithdrawal:viewpii'\]"/);
  assert.match(payout, /getMemberwithdrawalPayout\(row\.id\)/);
  assert.doesNotMatch(payout, /<el-descriptions(?=[\s>])/);
});
