'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { createOfflineAnchorPreview } = require('../tools/offline-anchor-preview');

test('isolated anchor preview reuses production markup and gating without any network or write capability', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'gg-anchor-preview-check-'));
  try {
    createOfflineAnchorPreview(directory);
    assert.equal(fs.readFileSync(path.join(directory, 'app.js'), 'utf8'), 'App({});');
    const markup = fs.readFileSync(path.join(directory, 'pages/index/index.wxml'), 'utf8');
    assert.ok(markup.includes('隔离渲染，非真实页面端到端'));
    assert.ok(markup.includes('hover-class="cy-pressed" hover-stay-time="80"'));
    let page;
    vm.runInNewContext(fs.readFileSync(path.join(directory, 'pages/index/index.js'), 'utf8'), {
      require: () => require('../../pages/club/utils/generic-director-ops'), Page: value => { page = value; }
    });
    page.setData = update => Object.assign(page.data, update);
    assert.equal(page.data.genericOpsActions.some(row => row.id === 'OFFLINE_ANCHOR'), false);
    page.setAvailable(true);
    assert.equal(page.data.genericOpsActions[0].title, '开始离线记录');
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
