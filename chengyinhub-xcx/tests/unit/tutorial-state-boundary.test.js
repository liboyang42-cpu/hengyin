const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function load(file, component = false) {
  let spec, request;
  const app = { globalData: {}, getPageSize: () => 20, sendRequest: r => { request = r; }, getRequestErrorMessage: (_, fallback) => fallback };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8'), {
    Page: s => { spec = s; }, Component: s => { spec = s; }, getApp: () => app,
    wx: { setNavigationBarTitle() {} }, require: () => ({}),
  });
  const page = { data: { ...spec.data, id: '1' }, ...(component ? spec.methods : spec), setData(p) { Object.assign(this.data, p); } };
  page.data = { ...spec.data, id: '1' };
  return { page, request: () => request };
}
test('教程列表区分成功空列表、加载失败、异常响应，并保留重试入口', () => {
  const s = load('subpackageA/pages/infomation/infomation.js');
  s.page.getList(); assert.equal(s.page.data.docsState, 'loading');
  s.request().success({ code: 200, data: [] }); assert.equal(s.page.data.docsState, 'empty');
  s.page.getList(); s.request().fail({}); assert.equal(s.page.data.docsState, 'error');
  assert.equal(s.page.data.nodata, false);
  s.page.getList(); s.request().successStatusAbnormal(); assert.equal(s.page.data.docsState, 'error');
});
for (const [file, component] of [['subpackageA/pages/infomationdetail/infomationdetail.js', false], ['subpackageA/components/scene-how-to-play-detail/index.js', true]]) {
  test(file + '：异常响应终止加载；空结果不展示假详情', () => {
    const s = load(file, component); s.page.getDetail(); s.request().successStatusAbnormal();
    assert.equal(s.page.data.loading, false); assert.equal(s.page.data.error, true);
    s.page.getDetail(); s.request().success({ code: 200, data: null });
    assert.equal(s.page.data.loading, false); assert.ok(s.page.data.empty || s.page.data.notfound);
  });
}
test('教程明确适用版本未知边界', () => {
  for (const file of ['subpackageA/pages/infomation/infomation.wxml', 'subpackageA/pages/infomationdetail/infomationdetail.wxml', 'subpackageA/components/scene-how-to-play-detail/index.wxml']) {
    assert.match(fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8'), /版本暂未标注/);
  }
});
