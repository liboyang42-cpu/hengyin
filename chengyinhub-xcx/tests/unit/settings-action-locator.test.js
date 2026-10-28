'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/shezhi/shezhi.wxml'), 'utf8');

test('设置页每个 cy-cell 动作都有稳定且唯一的 id 定位器', () => {
  const cells = wxml.match(/<cy-cell\b[^>]*\bbind:tap="[^"]+"[^>]*\/>/g) || [];
  assert.equal(cells.length, 13, '设置菜单动作数量变化时必须同步定位器合同');
  const ids = cells.map((tag) => {
    const match = /\bid="([^"]+)"/.exec(tag);
    assert.ok(match, `缺少 id: ${tag}`);
    return match[1];
  });
  assert.equal(new Set(ids).size, ids.length, '设置菜单 id 必须逐动作唯一');
  assert.ok(ids.includes('settings-marketing-consent'), '营销同意入口必须有稳定定位器');
  assert.ok(ids.includes('settings-invoice'), 'invoice entry needs a stable locator');
  assert.ok(ids.includes('settings-appeals'), '我的申诉入口必须有稳定定位器');
  // X6：我的申诉用 icon_privacy，排在发票前
  assert.ok(ids.indexOf('settings-appeals') === ids.indexOf('settings-invoice') - 1);
  assert.ok(ids.includes('settings-post-governance'), '帖子处置与申诉入口必须有稳定定位器');
});


test('invoice settings action opens the page registered in the existing member package', () => {
  const vm = require('node:vm');
  const calls = [];
  let page;
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../pages/shezhi/shezhi.js'), 'utf8'), {
    require: () => ({}), getApp: () => ({ globalData: {} }), Page: value => { page = value; },
    wx: { navigateTo: options => calls.push(options.url) },
  });
  page.goInvoice();
  assert.deepEqual(calls, ['/subpackageMember/invoice/index']);
  const app = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../app.json'), 'utf8'));
  const member = app.subPackages.find(item => item.root === 'subpackageMember');
  assert.equal(member.pages.filter(item => item === 'invoice/index').length, 1);
  assert.match(wxml, /id="settings-invoice"[^>]*bind:tap="goInvoice"/);
});
