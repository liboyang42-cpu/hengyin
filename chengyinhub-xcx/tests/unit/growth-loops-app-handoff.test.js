// R11 growth-loops(GRO-05):小程序 → App 入口。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

test('设置里有「使用 App」入口,页面已注册', () => {
  const appConfig = require('../../app.json');
  const setting = appConfig.subPackages.find(s => s.root === 'pages/shezhi');
  assert.ok(setting.pages.includes('app/index'));
  assert.match(read('pages/shezhi/shezhi.wxml'), /id="settings-use-app"[^>]*bind:tap="goApp"/);
  assert.match(read('pages/shezhi/shezhi.js'), /goApp\(\)\s*\{\s*wx\.navigateTo\(\{ url: '\/pages\/shezhi\/app\/index' \}\)/);
});

test('从 App 打开时用 launchApp 回 App,失败降级为复制通用链接', () => {
  const wxml = read('pages/shezhi/app/index.wxml');
  const js = read('pages/shezhi/app/index.js');
  assert.match(wxml, /open-type="launchApp"/);
  assert.match(wxml, /binderror="onLaunchAppError"/);
  assert.match(wxml, /wx:if="\{\{canLaunchApp\}\}"/);
  assert.match(js, /LAUNCH_APP_SCENES = \[1069\]/);
  assert.match(js, /onLaunchAppError[\s\S]*canLaunchApp: false/);
  assert.match(js, /\/api\/growth-loops\/app-handoff/);
  assert.match(js, /https:\/\/www\.chengyinhub\.com\/door/);
});

test('App Store 地址只接受 apps.apple.com,未配置时如实写即将上架', () => {
  const wxml = read('pages/shezhi/app/index.wxml');
  const js = read('pages/shezhi/app/index.js');
  assert.match(js, /indexOf\('https:\/\/apps\.apple\.com\/'\) === 0/);
  assert.match(wxml, /即将上架/);
});
