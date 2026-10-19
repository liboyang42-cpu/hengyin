const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
global.wx = { showToast() {}, getStorageSync: () => '', setStorageSync() {} };
global.getApp = () => ({ globalData: {} });
const backpack = require('../../pages/play/utils/theme-backpack.js');

function host() {
  const instance = { data: JSON.parse(JSON.stringify(backpack.data)), _themeHidden: true, setData(patch) { Object.assign(this.data, patch); } };
  Object.assign(instance, backpack.methods(() => Promise.resolve({ code: 200 })));
  return instance;
}

test('X012 团队归零面板说明全队共用一次恢复，个人局不显示', () => {
  const zeroHp = { phase: 'RECOVERING', policy: 'CHALLENGE', effectivePolicy: 'CHALLENGE', progress: 1, required: 3, restoreTo: 4, used: 0, limit: 2, allowedActions: [] };
  const team = host(); team._applyZeroHpView({ scope: 'TEAM', sessionId: 7, zeroHp }); team._stopZeroHpTimer();
  assert.equal(team.data.zeroHpView.sharedText, '全队共用这次归零：任一队员完成恢复，全队同时恢复，只结算一次');
  assert.equal(team.data.zeroHpView.progressText, '1 / 3');
  const personal = host(); personal._applyZeroHpView({ scope: 'PLAYER', sessionId: 8, zeroHp }); personal._stopZeroHpTimer();
  assert.equal(personal.data.zeroHpView.sharedText, '');
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /zeroHpView\.sharedText/);
});
