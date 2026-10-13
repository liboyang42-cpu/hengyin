const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadEntityHeader() {
  const source = read('src/components/CyEntityHeader/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script
    .replace(/import[^\n]+\n/, 'const CyStatusTag = {};\n')
    .replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('CyEntityHeader 只接收已格式化的对象摘要，状态复用 CyStatusTag', () => {
  const component = loadEntityHeader();
  const source = read('src/components/CyEntityHeader/index.vue');

  assert.ok(component.props.title);
  assert.ok(component.props.eyebrow);
  assert.ok(component.props.status);
  assert.ok(component.props.facts);
  assert.ok(component.components.CyStatusTag);
  assert.match(source, /<slot name="actions"/);
  assert.equal(component.methods && component.methods.getDetail, undefined, '组件不应拥有详情请求');
  assert.equal(component.methods && component.methods.submitReview, undefined, '组件不应拥有领域动作');
});

test('CyEntityHeader 把首屏事实限制为四条，避免详情抽屉重新堆成表单', () => {
  const component = loadEntityHeader();
  const facts = [
    { label: '来源', value: '报名订单 #1' },
    { label: '申请人', value: '#2' },
    { label: '审核人', value: '#3' },
    { label: '风险', value: '中' },
    { label: '不应在对象头展示', value: '第五条' }
  ];

  assert.deepEqual(component.computed.visibleFacts.call({ facts }), facts.slice(0, 4));
});

test('对象头不把未分配状态伪装成带 # 的身份 ID', () => {
  const audit = read('src/views/business/audit/index.vue');
  const refund = read('src/views/business/finance/refund/index.vue');

  assert.match(audit, /submitterId \? `#\$\{this\.detailTask\.submitterId\}` : '—'/);
  assert.match(refund, /memberId \? `#\$\{this\.detailRow\.memberId\}` : '—'/);
  assert.match(refund, /reviewerId \? `#\$\{this\.detailRow\.reviewerId\}` : '待分配'/);
  assert.doesNotMatch(refund, /#\$\{this\.detailRow\.memberId \|\| '—'\}/);
  assert.doesNotMatch(refund, /#\$\{this\.detailRow\.reviewerId \|\| '待分配'\}/);
});

test('审核台和退款台把真实详情摘要接入 CyEntityHeader，动作和权限仍由页面处理', () => {
  const pages = [
    ['src/views/business/audit/index.vue', 'auditHeaderFacts'],
    ['src/views/business/finance/refund/index.vue', 'refundHeaderFacts']
  ];

  for (const [relativePath, factsName] of pages) {
    const source = read(relativePath);
    assert.ok(source.includes("import CyEntityHeader from '@/components/CyEntityHeader'"));
    assert.ok(source.includes('<cy-entity-header'));
    assert.ok(source.includes(`:facts="${factsName}"`));
    assert.match(source, /<template slot="actions">/);
  }

  const audit = read('src/views/business/audit/index.vue');
  const refund = read('src/views/business/finance/refund/index.vue');
  assert.doesNotMatch(audit, /class="audit-detail__hero"|class="audit-detail__facts"/);
  assert.doesNotMatch(refund, /class="refund-detail__hero"/);
  assert.match(audit, /v-hasPermi="\['business:audit:review'\]"/);
  assert.match(refund, /v-hasPermi="\['business:refund:review'\]"/);
});
