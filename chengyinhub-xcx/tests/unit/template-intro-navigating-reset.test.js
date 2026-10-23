// 2026-09-23 CU-M-31:模板介绍页「开始创建」成功跳走后 navigating 不复位,
// 从创建页返回再点按钮被 early-return 挡死、按钮永远转圈。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadPage(wx) {
  let config;
  const sandbox = {
    Page(value) { config = value; },
    require: () => ({ isRecordList: Array.isArray }),
    getApp: () => ({ globalData: {}, sendRequest() {} }),
    wx, console,
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../pages/publish/template-intro/index.js'), 'utf8'), sandbox);
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = function (patch) { Object.assign(this.data, patch); };
  return page;
}

// 2026-10-22 owner 定稿去掉了「跳过」,出口只剩「开始创建」:同一按钮返回后再点一次仍要能走。
test('开始创建成功后返回,再点开始创建仍能进入创建页', () => {
  const urls = [];
  const page = loadPage({ navigateTo: (opts) => { urls.push(opts.url); if (opts.success) opts.success(); } });
  page.goCreate();
  page.goCreate();
  assert.equal(urls.length, 2, '第二次必须真的发起跳转');
  assert.equal(page.data.navigating, false, '按钮不得停在加载态');
});

// owner 2026-10-22 复审:圆圈不能点、没有「玩法预览」;去掉起名页,开始创建直接进编辑器新建。
test('开始创建直接进编辑器新建,商家身份带 scope', () => {
  const urls = [];
  const page = loadPage({ navigateTo: (opts) => { urls.push(opts.url); if (opts.success) opts.success(); } });
  page.goCreate();
  page.data.operationScope = 'MERCHANT';
  page.goCreate();
  assert.deepEqual(urls, [
    '/pages/publish/temp/index',
    '/pages/publish/temp/index?scope=MERCHANT',
  ]);
});

test('圆圈不能点:没有预览半屏,也没有点圆圈的入口', () => {
  const dir = path.join(__dirname, '../../pages/publish/template-intro');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');
  assert.doesNotMatch(wxml, /玩法预览|tiSheet|useCurrent/);
  assert.doesNotMatch(js, /openSheet|useCurrent|temp\/index\?id=/);
});

// owner:圆圈在底部按钮下面。画布在开发者工具/部分基础库按原生层压在最上面,z-index 管不住,
// 所以必须在画布上把按钮形状挖掉。
test('圆圈从开始创建按钮后面过:画布挖掉按钮区域', () => {
  const dir = path.join(__dirname, '../../pages/publish/template-intro');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');
  assert.match(wxml, /<view id="tiBtn">\s*<cy-btn[^>]*bindtap="goCreate"/);
  assert.match(js, /select\('#tiBtn'\)/);
  assert.match(js, /globalCompositeOperation = 'destination-out'/);
});

test('引导页没有「跳过」入口', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/template-intro/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /跳过|onSkip/);
});
