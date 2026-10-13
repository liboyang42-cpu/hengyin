const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadStatusTag() {
  const source = read('src/components/CyStatusTag/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

test('CyStatusTag 接收必填 label 与枚举 tone，六个值涵盖 neutral/brand/success/warning/danger/info', () => {
  const component = loadStatusTag();

  assert.ok(component.props.label, '缺少 label prop');
  assert.equal(component.props.label.required, true, 'label 应为必填 prop');
  assert.ok(component.props.tone, '缺少 tone prop');
  assert.equal(component.props.tone.default, 'neutral', '默认 tone 应为 neutral');

  const validator = component.props.tone.validator;
  const validTones = ['neutral', 'brand', 'success', 'warning', 'danger', 'info'];
  for (const tone of validTones) {
    assert.equal(validator(tone), true, `tone="${tone}" 应通过校验`);
  }
  for (const bad of ['', 'primary', 'error', 'gray', undefined, null, 'custom']) {
    assert.equal(validator(bad), false, `tone="${bad}" 应被拒绝`);
  }
  assert.equal(validTones.length, 6, 'tone 枚举应恰好覆盖六个语义值');
});

test('CyStatusTag 不承载数据请求、状态计算或业务权限逻辑', () => {
  const component = loadStatusTag();

  assert.equal(component.methods && component.methods.getList, undefined, '组件不应请求数据');
  assert.equal(component.methods && component.methods.getDetail, undefined, '组件不应请求详情');
  assert.equal(component.computed && component.computed.statusLabel, undefined, '组件不应计算状态文案');
  assert.equal(component.computed && component.computed.statusTone, undefined, '组件不应计算 tone 映射');
});

test('审核台和提现回补队列通过 CyStatusTag 展示状态，tone 由页面映射而非写在标签上', () => {
  const pages = [
    'src/views/business/audit/index.vue',
    'src/views/business/cashwithdraw/index.vue'
  ];

  for (const relativePath of pages) {
    const source = read(relativePath);
    assert.ok(source.includes("import CyStatusTag from '@/components/CyStatusTag'"));
    assert.ok(source.includes('<cy-status-tag'));
    assert.match(source, /:tone="statusTone\(/);
    assert.doesNotMatch(source, /background.*#/);
  }
});
