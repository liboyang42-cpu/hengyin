const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function loadTimeline() {
  const source = fs.readFileSync(path.join(uiRoot, 'src/components/CyTimeline/index.vue'), 'utf8');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

test('CyTimeline 只消费页面已归一化的轨迹项，不请求或翻译领域记录', () => {
  const component = loadTimeline();
  const item = {
    id: 'record-7',
    title: '审核通过',
    summary: '人工核验内容符合规范',
    actor: '操作人 #18',
    occurredAt: '2026-07-22 09:30',
    tone: 'success',
    evidence: ['内容快照已核验']
  };

  assert.ok(component.props.items);
  assert.ok(component.props.emptyText);
  assert.deepEqual(component.computed.visibleItems.call({ items: [item, { id: 'missing-title' }, { title: '缺少稳定标识' }] }), [item]);
  assert.equal(component.methods && component.methods.getDetail, undefined);
  assert.equal(component.methods && component.methods.actionLabel, undefined);
  const source = fs.readFileSync(path.join(uiRoot, 'src/components/CyTimeline/index.vue'), 'utf8');
  assert.doesNotMatch(source, /item\.changes|displayValue/, '没有真实消费者前，不预留前后值字段');
});

function loadAuditWorkbench() {
  const source = fs.readFileSync(path.join(uiRoot, 'src/views/business/audit/index.vue'), 'utf8');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('审核台把后端处置记录归一化后交给 CyTimeline，保留页面的动作语义', () => {
  const component = loadAuditWorkbench();
  const vm = {
    detailRecords: [{
      id: 7,
      action: 'APPROVE',
      reasonText: '人工核验内容符合规范',
      operatorId: 18,
      snapshotJson: '{"id": 1}',
      createTime: '2026-07-22 09:30:00'
    }],
    ...component.methods,
    parseTime: value => value
  };

  assert.deepEqual(component.computed.auditTimelineItems.call(vm), [{
    id: 7,
    title: '审核通过',
    summary: '人工核验内容符合规范',
    actor: '操作人 #18',
    occurredAt: '2026-07-22 09:30:00',
    tone: 'success',
    evidence: ['附有当时内容快照']
  }]);

  const source = fs.readFileSync(path.join(uiRoot, 'src/views/business/audit/index.vue'), 'utf8');
  assert.match(source, /import CyTimeline from '@\/components\/CyTimeline'/);
  assert.match(source, /<cy-timeline[\s\S]*:items="auditTimelineItems"/);
  assert.doesNotMatch(source, /class="evidence-timeline"/, '审核页不保留手写时间线');
});
