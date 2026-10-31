// 勋章列表正式图标契约：有 medalImg 时优先展示图片，缺图时只允许通用 cy-icon。
// JS 仍可保留历史 emoji 字段供其它数据链路使用；页面不能把它当作正式 UI 图标渲染。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// 2026-08-06：主页统一到 cy-profile 共用组件后，member/index 与 userinfo 的 wxml
// 只剩一行 <cy-profile />，本文件的断言原本钉在旧结构的节点上。约束没失效、只是搬进了
// 组件 —— 用 helper 在读文件这一层展开，断言原样保留。

// 2026-08-06 主页统一到 cy-profile 共用组件：member/index 与 userinfo 的 wxml/wxss
// 只剩壳，本文件的断言原本钉在旧结构上。约束没失效、只是搬进了组件 ——
// 在读文件这一层展开，断言原样保留。
const { readResolved } = require('../helpers/resolve-profile');

const XCX = process.env.MEMBER_MEDAL_CONTRACT_ROOT || path.resolve(__dirname, '../..');
const read = (file) => readResolved(file);

// 2026-10-30 业主定稿 P-3:「城市勋章」列表并进收集册,我的里只剩收集册入口那一叠勋章缩略图 ——
// 有 medalImg 先用图片,缺图落通用勋章图(不是 emoji)。
test('收集册入口的勋章缩略图:图片优先，缺图落通用勋章图而不渲染 emoji', () => {
  const wxml = read('pages/member/index/index.wxml');
  assert.match(wxml, /src="\{\{medals\[0\]\.medalImg \|\| '\/subpackageMember\/images\/album\/medal\.png'\}\}"/, 'medalImg 存在时必须优先走图片');
  assert.ok(fs.existsSync(path.join(XCX, 'subpackageMember/images/album/medal.png')), '缺图时的通用勋章图要在包里');
  assert.doesNotMatch(wxml, /item\.emoji/, '正式勋章图标不得消费 emoji 字段');
  assert.doesNotMatch(wxml, /pc-achv-ico--emoji/, '正式勋章图标不得保留 emoji fallback class');
  assert.doesNotMatch(wxml, /pc-achv-list/, '城市勋章列表已并进收集册');
});
