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

// 预览里选了玩法(用它开始)后,开始创建直接用它开编辑器;没选照旧去命名页新建。
test('选了玩法直接开编辑器,没选去命名页', () => {
  const urls = [];
  const page = loadPage({ navigateTo: (opts) => { urls.push(opts.url); if (opts.success) opts.success(); } });
  page.goCreate();
  page.data.pick = { id: 42, title: '街角谜题', imgUrl: '' };
  page.goCreate();
  page.data.operationScope = 'MERCHANT';
  page.goCreate();
  assert.deepEqual(urls, [
    '/pages/publish/templateadd/templateadd',
    '/pages/publish/temp/index?id=42',
    '/pages/publish/temp/index?id=42&scope=MERCHANT',
  ]);
});

test('引导页没有「跳过」入口', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/template-intro/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /跳过|onSkip/);
});
