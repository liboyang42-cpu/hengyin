'use strict';

// PRIVATE-GROUP-J-privacy-purpose-and-play-cache / PC-168 + PC-392
// - 隐私页按用途显示本机漫游采集状态:撤回只停漫游这一用途;其他独立获准的持有者仍在定位时如实说(不恢复 stopAllHolders)。
// - 漫游「清空本机缓存」确认文案与 clearLocal 实际清除项逐条对齐,并说明服务端记录不受影响。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const BG = path.join(ROOT, 'utils/location/bg-tracker.js');
const PRIVACY = path.join(ROOT, 'pages/privacy/index.js');

function mount() {
  const h = { starts: [], stops: 0 };
  global.getApp = () => ({ globalData: {}, getUserID: () => '9', hasPendingPrivacyAuthorization: () => true,
    recordConsent: () => new Promise(() => {}), sendRequest() {} });
  global.wx = {
    getStorageSync: () => '', setStorageSync() {}, showToast() {}, showModal() {},
    startLocationUpdateBackground(options) { h.starts.push(options); },
    stopLocationUpdate(options) { h.stops++; if (options && options.success) options.success(); },
    onLocationChange() {}, offLocationChange() {},
  };
  delete require.cache[BG]; delete require.cache[PRIVACY];
  let definition;
  global.Page = (value) => { definition = value; };
  h.tracker = require(BG);
  require(PRIVACY);
  h.page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb(); } };
  h.acquire = (key) => { h.tracker.acquire(key, {}); if (h.starts.length) h.starts.at(-1).success(); };
  return h;
}

test('隐私页按用途显示漫游采集状态,撤回后立即更新;独立用途仍在定位时如实说明', () => {
  const h = mount();
  try {
    h.page.syncRoamCollection();
    assert.equal(h.page.data.roamCollectionText, '本机未在为漫游采集位置');

    h.acquire('roam');
    h.acquire('independent-purpose');
    h.page.onShow();
    assert.equal(h.page.data.roamCollectionText, '本机正在为漫游采集位置');

    h.page.withdrawRoamLocationConsent();
    assert.equal(h.tracker.hasOwner('roam'), false, '撤回核心(#439)不变:先停漫游用途');
    assert.equal(h.tracker.hasOwner('independent-purpose'), true, '不恢复 stopAllHolders:独立用途继续');
    assert.equal(h.page.data.roamCollectionText, '漫游已停止采集；其他已开启的功能仍在使用定位');

    h.tracker.release('independent-purpose');
    h.page.syncRoamCollection();
    assert.equal(h.page.data.roamCollectionText, '本机未在为漫游采集位置');
  } finally { h.tracker.release('independent-purpose', true); }
});

test('隐私页漫游行渲染采集状态', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/privacy/index.wxml'), 'utf8');
  const row = wxml.slice(wxml.indexOf('<text class="privacy-row__title">漫游定位</text>') - 200, wxml.indexOf('bindtap="withdrawRoamLocationConsent"'));
  assert.match(row, /<text class="privacy-row__sub" aria-role="status">\{\{roamCollectionText\}\}<\/text>/);
  assert.doesNotMatch(fs.readFileSync(PRIVACY, 'utf8'), /stopAllHolders/);
});

test('清空本机缓存确认文案覆盖 clearLocal 的四样,且不用术语', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/roam/index.wxml'), 'utf8');
  const sheet = wxml.slice(wxml.indexOf('<block wx:if="{{clearAsk}}">'), wxml.indexOf('</block>', wxml.indexOf('<block wx:if="{{clearAsk}}">')));
  const lede = /<text class="roam-rules__lede">([^<]+)<\/text>/.exec(sheet)[1];
  for (const item of ['迷雾', '足迹', '漫游记录', '找回记录', '足迹链接也会失效', '已上传到城瘾的记录不受影响']) assert.ok(lede.includes(item), item);
  assert.doesNotMatch(lede, /tile|reveal|session|recovery|缓存桶|epoch/i);
  const memory = fs.readFileSync(path.join(ROOT, 'utils/roam-player-memory.js'), 'utf8');
  assert.match(memory, /this\.writeTiles\(\{\}, 0\), this\.writeReveals\(\[\]\), this\.writeSessions\(\[\]\),\s*this\.writeRecovery\(null\)/);
});
