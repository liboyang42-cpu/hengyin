'use strict';
// P-17:首页没有封面的活动卡不再显示设计稿里的人物头像图与红色 LIVE。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const wxml = fs.readFileSync(path.join(__dirname, '../../pages/index/index.wxml'), 'utf8');
const wxss = fs.readFileSync(path.join(__dirname, '../../pages/index/index.wxss'), 'utf8');

test('活动卡缺封面回落到中性的城市路线封面', () => {
  assert.doesNotMatch(wxml, /index-figma\/nearby-card\.jpg/);
  assert.match(wxml, /item\.cover \|\| item\.imgUrl \|\| '\/images\/route_city_cover\.png'/);
  assert.ok(fs.existsSync(path.join(__dirname, '../../images/route_city_cover.png')));
});

test('进行中的活动用中文标签,不用红色 LIVE', () => {
  assert.doesNotMatch(wxml, />LIVE</);
  assert.match(wxml, /class="v3-nearby-live" wx:if="\{\{item\.started\}\}">进行中</);
  const rule = wxss.slice(wxss.indexOf('.v3-nearby-live {'), wxss.indexOf('}', wxss.indexOf('.v3-nearby-live {')));
  assert.doesNotMatch(rule, /danger/);
});
