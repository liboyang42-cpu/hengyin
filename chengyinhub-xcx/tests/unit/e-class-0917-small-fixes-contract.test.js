'use strict';

// E 类 0917 小程序小修批的两条静态契约:
//   A-14-3 搜索入口页 loading 绑死字段 searchLoading —— 删绑定(页面不在本页做搜索,不需要转圈态),
//          顺带钉住「不会再凭空加一个死字段回来」。
//   COUPON-RPT-10 截图桩 shot-matrix E29 的券类型 fixture 必须与 utils/coupon-form.js 真源同源
//          (含体验卡),否则截图证据会展示一个已经不存在的选项列表。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const { COUPON_TYPE_LABELS } = require(path.join(ROOT, 'utils/coupon-form.js'));

test('A-14-3:搜索入口页不再绑死字段 searchLoading', () => {
  const wxml = read('pages/search2/index.wxml');
  const js = read('pages/search2/index.js');
  assert.doesNotMatch(wxml, /loading="\{\{searchLoading\}\}"/, '入口页没有在页内加载，不许挂永远不转的转圈态');
  assert.doesNotMatch(js, /searchLoading/, '入口页 js 也不该再声明这个死字段');

  // 负控:把绑定加回去,判据必须能抓住
  const broken = wxml.replace(
    'placeholder="搜索主题、活动、俱乐部、商家" bind:input="onKeywordInput"',
    'placeholder="搜索主题、活动、俱乐部、商家" loading="{{searchLoading}}" bind:input="onKeywordInput"',
  );
  assert.notEqual(broken, wxml, '负控锚点失效:搜索入口绑定未命中');
  assert.match(broken, /loading="\{\{searchLoading\}\}"/, '负控必须复现死绑定');
});

// PG-040:建券页 couponInfo 下线,E29 截图行随页删除。
