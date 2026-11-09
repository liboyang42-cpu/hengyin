'use strict';
// 第十二轮 k8 N12:参与人信息不再让玩家另填一个建单时会被忽略的手机号。
// 绑过微信手机号 → 只读显示那一个,保存时随参与人存下;没绑过才保留手填。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

function applyPatch(target, patch) {
  Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.');
    let cursor = target;
    for (let i = 0; i < parts.length - 1; i += 1) cursor = cursor[parts[i]];
    cursor[parts.at(-1)] = value;
  });
}

function loadPage() {
  let definition;
  const requests = [];
  const app = {
    globalData: {}, getUserID: () => '9004',
    getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest(options) { requests.push(options); return { abort() {} }; },
  };
  vm.runInNewContext(read('pages/addressinfo/addressinfo.js'), {
    getApp: () => app,
    Page(value) { definition = value; },
    require(request) {
      if (request === '../../utils/form-state.js') return require('../../utils/form-state.js');
      throw new Error('unexpected require: ' + request);
    },
    wx: { showToast() {}, navigateBack() {}, redirectTo() {}, reLaunch() {} },
    Date, Math, setTimeout() { return 1; }, clearTimeout() {}, console,
  });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = function (patch, cb) { applyPatch(this.data, patch); if (cb) cb(); };
  return { page, requests };
}

const userInfo = (h) => h.requests.filter((r) => r.url === '/api/user/info');
const addrInfo = (h) => h.requests.filter((r) => r.url === '/api/user/address/info');

test('N12:绑了微信手机号就只读显示,保存的是这个号', () => {
  const h = loadPage();
  h.page.onLoad({});
  assert.equal(userInfo(h).length, 1);
  userInfo(h)[0].success({ code: 200, data: { phone: '13900000004' } });
  assert.equal(h.page.data.boundPhone, '13900000004');
  h.page.onInputChange({ currentTarget: { dataset: { field: 'fullName' } }, detail: { value: '林夏' } });
  assert.equal(h.page.data.canSubmit, true);
  h.page.saveAddress();
  const save = h.requests.filter((r) => r.url === '/api/user/address/action')[0];
  assert.equal(save.data.mobilePhone, '13900000004');
});

test('N12:编辑旧参与人时也显示绑定号,不回填旧的另一个号', () => {
  const h = loadPage();
  h.page.onLoad({ id: '11' });
  userInfo(h)[0].success({ code: 200, data: { phone: '13900000004' } });
  addrInfo(h)[0].success({ code: 200, data: { fullName: '林夏', mobilePhone: '13800000004', province: '', detailAddress: '' } });
  assert.equal(h.page.data.form.mobilePhone, '13900000004');
});

test('N12:没绑过手机号仍可手填(报名页还读参与人手机号)', () => {
  const h = loadPage();
  h.page.onLoad({});
  userInfo(h)[0].success({ code: 200, data: { phone: '' } });
  assert.equal(h.page.data.boundPhone, '');
});

test('N12:WXML 绑定号只读,手填框只在没绑时出现', () => {
  const wxml = read('pages/addressinfo/addressinfo.wxml');
  assert.match(wxml, /<input wx:if="\{\{boundPhone\}\}" type="number" disabled value="\{\{boundPhone\}\}" \/>/);
  assert.match(wxml, /<input wx:else type="number" placeholder="输入手机号"/);
});
