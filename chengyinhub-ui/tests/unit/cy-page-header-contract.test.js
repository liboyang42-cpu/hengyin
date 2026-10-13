const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadPageHeader() {
  const source = read('src/components/CyPageHeader/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

test('CyPageHeader 提供页面级标题、描述、状态与更新时间，并保留两个操作区插槽', () => {
  const component = loadPageHeader();
  const source = read('src/components/CyPageHeader/index.vue');

  for (const propName of ['eyebrow', 'title', 'description', 'status', 'updatedAt']) {
    assert.ok(component.props[propName], `缺少 ${propName} 接口`);
  }
  assert.equal(component.props.title.required, true, 'title 应为必填 prop');

  assert.match(source, /<slot\s*\/>/);
  assert.match(source, /<slot name="primary"\s*\/>/);
});

test('CyPageHeader 不拥有列表请求、路由跳转或任何领域动作', () => {
  const component = loadPageHeader();

  assert.equal(component.methods && component.methods.getList, undefined, '组件不应请求页面列表');
  assert.equal(component.methods && component.methods.goAuditWorkbench, undefined, '组件不应拥有路由跳转');
  assert.equal(component.methods && component.methods.getHomeData, undefined, '组件不应拥有首页数据请求');
  assert.equal(component.methods && component.methods.openDetail, undefined, '组件不应拥有详情打开');
});

test('审核台与提现回补队列以 CyPageHeader 承载统一标题，并各自保留刷新或人工队列操作', () => {
  const pages = [
    'src/views/business/audit/index.vue',
    'src/views/business/cashwithdraw/index.vue'
  ];

  for (const relativePath of pages) {
    const source = read(relativePath);
    assert.ok(source.includes("import CyPageHeader from '@/components/CyPageHeader'"));
    assert.ok(source.includes('<cy-page-header'));
    assert.ok(source.includes('title="'));
  }

  const audit = read('src/views/business/audit/index.vue');
  assert.match(audit, /title="统一审核台"/);
  assert.match(audit, /eyebrow="审核与风控"/);
  assert.match(audit, /description="/);

  const cashwithdraw = read('src/views/business/cashwithdraw/index.vue');
  assert.match(cashwithdraw, /title="提现回补队列"/);
  assert.match(cashwithdraw, /eyebrow="资金与售后"/);
  assert.match(cashwithdraw, /slot="primary"/);
});

test('首页同时使用 default 与 primary 插槽，并通过 status 和 updatedAt 展示实时状态', () => {
  const source = read('src/views/index.vue');

  assert.ok(source.includes("import CyPageHeader from '@/components/CyPageHeader'"));
  assert.match(source, /:status="loading \? '同步中' : '实时队列'"/);
  assert.match(source, /:updated-at="updatedAt"/);
  assert.match(source, /slot="primary"/);
  assert.match(source, /el-button icon="el-icon-refresh"/);
});
