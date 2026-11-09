const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// 会话列表:邀请行、左滑操作都有读屏标签,唯独会话行本身没有 —— 读屏只念出昵称和预览,
// 不知道这是一个能点开的按钮。
test('会话行带「打开…会话」读屏标签', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../subpackageB/pages/im/list/index.wxml'), 'utf8');
  const row = wxml.match(/<view class="conv \{\{[\s\S]*?>/);
  assert.ok(row, '找不到会话行');
  assert.match(row[0], /bindtap="openChat"/);
  assert.match(row[0], /aria-role="button"/);
  assert.match(row[0], /aria-label="打开\{\{item\.counterparty\.nickname\}\}的会话"/);
});
