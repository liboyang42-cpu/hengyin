const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = file => fs.readFileSync(path.join(__dirname, '../..', file), 'utf8');

test('F0463 自助体验记录不宣称商家确认或消费报名权益', () => {
  const wxml = read('pages/play/circle/index.wxml');
  assert.match(wxml, /自助体验记录/);
  assert.match(wxml, /不扣报名票权益，不代表商家确认/);
});
