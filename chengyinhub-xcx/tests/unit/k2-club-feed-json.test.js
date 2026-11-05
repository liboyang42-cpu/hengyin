'use strict';
// 第十二轮 k2(j5 N2):/api/club/post/feed 后端是 @RequestBody,我的 / 主页「帖文」页签必须发 JSON。
// 同一类错已出现两次;全量排查(后端 506 个 @RequestBody 端点 × 小程序调用点)只此一处,见 ROUND12-K2-RESULT.md。
const assert = require('assert');
const test = require('node:test');
const fs = require('fs');
const path = require('path');

test('k2 j5 N2:个人主页俱乐部帖文流带 JSON 请求头', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../subpackageMember/components/cy/profile/index.js'), 'utf8');
  assert.match(src, /function req\(url, data, header\) \{[\s\S]{0,200}method: 'POST', header: header,/);
  assert.match(src, /req\('\/api\/club\/post\/feed', \{[^}]*\}, \{ 'content-type': 'application\/json' \}\)/);
});
