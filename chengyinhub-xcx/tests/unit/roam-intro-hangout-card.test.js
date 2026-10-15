'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.join(__dirname, '../..');
const src = fs.readFileSync(path.join(ROOT, 'pages/roam/index.js'), 'utf8');
const nearby = fs.readFileSync(path.join(ROOT, 'subpackageRoam/nearby/index.js'), 'utf8');

test('首页固定三个入口，不再被报名章节覆盖', () => {
  const cards = src.slice(src.indexOf('introCards: ['), src.indexOf('boundEvent:'));
  assert.deepEqual([...cards.matchAll(/key: '([^']+)'/g)].map(match => match[1]), ['start', 'map', 'my-teams']);
  assert.doesNotMatch(src, /_loadIntroTopics|cards\.concat/);
});

test('地图与我的队伍复用已存在的附近队伍页', () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const roam = (app.subPackages || app.subpackages).find(pkg => pkg.root === 'subpackageRoam');
  assert.ok(roam.pages.includes('nearby/index'));
  assert.match(src, /\/subpackageRoam\/nearby\/index\?sheet=mine/);
  assert.match(nearby, /options\.sheet === 'mine'/);
  assert.match(nearby, /if \(this\._openMineOnShow\) \{ this\._openMineOnShow = false; this\.openMyTeams\(\); \}/);
});

test('开始漫游不随横滑卡片索引改变目标，仍走原定位链', () => {
  const start = src.slice(src.indexOf('  goStart() {'), src.indexOf('  _openRoamMap('));
  assert.doesNotMatch(start, /introCardIdx|card\.nav|wx\.navigateTo/);
  assert.match(start, /wx\.getLocation/);
  assert.match(start, /_openRoamMap\(/);
});
