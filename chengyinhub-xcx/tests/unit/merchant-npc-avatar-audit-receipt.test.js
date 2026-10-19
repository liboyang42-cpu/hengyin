'use strict'

/**
 * F0225 · 门店 AI 角色头像:保存成功 ≠ 已审核可见。
 * 回执以服务端为准(新形象一律回到待审),审核中/驳回要写清玩家侧此刻看不到。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const JS_PATH = 'pages/merchant/decor/ai-npc/index.js';
const PAGE_JS = fs.readFileSync(path.join(ROOT, JS_PATH), 'utf8');
const WXML = fs.readFileSync(path.join(ROOT, 'pages/merchant/decor/ai-npc/index.wxml'), 'utf8');
const flush = () => new Promise((resolve) => setImmediate(resolve));

function loadPage() {
  const requests = [];
  const toasts = [];
  let definition = null;
  const noop = () => {};
  const app = {
    globalData: {},
    sendRequest: (options) => { requests.push(options); return { abort: noop }; },
    getUploadClient: () => ({ uploadAll: noop }),
  };
  const file = path.join(ROOT, JS_PATH);
  vm.runInNewContext(PAGE_JS, {
    Page: (value) => { definition = value; },
    getApp: () => app,
    getCurrentPages: () => [{ route: 'pages/merchant/decor/ai-npc/index' }],
    wx: { navigateBack: noop, redirectTo: noop },
    console,
    setTimeout: noop,
    clearTimeout: noop,
    require(id) {
      if (id.includes('/toast')) return Object.assign((m) => toasts.push(m), { success: (m) => toasts.push(m) });
      if (id.includes('/merchant-theme')) return { merchantPageShow: noop, merchantPageRestore: noop };
      return require(path.resolve(path.dirname(file), id));
    },
  });
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, callback) {
      Object.keys(patch).forEach((key) => { this.data[key] = patch[key]; });
      if (typeof callback === 'function') callback();
    },
    _drawPreview: noop,
  });
  page._saved = { avatar: 'px1:p01', name: '小店长', greeting: '', persona: '', knowledge: '' };
  page.data.loading = false;
  page.data.hasProfile = true;
  page.data.auditStatus = 1; // 之前那一版已上线
  return { page, requests, toasts };
}

test('重新保存后回到审核中,不沿用上一版「已上线」,提示不说「已更新」', async () => {
  const { page, requests, toasts } = loadPage();
  page.persistProfile({ name: '小店长', avatar: 'px1:p02', greeting: '' });
  await flush();
  assert.equal(requests.length, 1);
  requests[0].success({ code: 200, msg: '已保存，审核通过后玩家可见', data: { auditStatus: 0, mediaCheck: 'PRESET' } });
  await flush();
  assert.equal(page.data.auditStatus, 0, '保存后必须回到待审,不能继续显示已上线');
  assert.ok(toasts.some((m) => /审核/.test(String(m))), '回执要说明需要审核');
  assert.ok(!toasts.some((m) => /角色已更新/.test(String(m))));
});

test('非已上线状态写明玩家侧暂时看不到', () => {
  assert.match(WXML, /wx:elif="\{\{auditStatus !== 1\}\}">审核通过前，玩家暂时看不到这个角色/);
});
