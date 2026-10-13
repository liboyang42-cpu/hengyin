const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = file => fs.readFileSync(path.join(__dirname, '../..', file), 'utf8');

test('F0053 活动核验团码与玩家组队邀请分名', () => {
  assert.match(read('pages/club/group-code/index.wxml'), /title="活动核验团码"/);
  assert.match(read('pages/team/join/index.wxml'), /邀请码不能用于活动签到或商家核销/);
});
