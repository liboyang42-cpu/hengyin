// f-play2(2026-11-11):城市定向场次票在走查 rig 里整局走通时发现、并修掉的缺陷。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const WXML = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8');

function toolsMenu() {
  const start = WXML.indexOf('<scroll-view class="tools tools--corner');
  assert.notEqual(start, -1, '找不到游玩工具菜单');
  return WXML.slice(start, WXML.indexOf('</scroll-view>', start));
}

// 菜单 max-height 52vh、不显示滚动条,rig 实测正好切在第 8 行下沿。队长的放行 / 核销 / 结算原来排在第 9 行以后,
// 队员卡在「等待放行」时队长打开菜单看不到「解锁下一章节」,点到的是菜单外的遮罩(r2-L-tools.jpg)。
test('队长的行排在游玩工具菜单最前面:第一个 drow 就是队长行,后面才是所有人都有的行', () => {
  const menu = toolsMenu();
  const rows = [...menu.matchAll(/<view class="drow[^"]*"([^>]*)>/g)].map((m) => m[1]);
  const leaderHandlers = ['leadVerifyMemberTicketFromTools', 'leadShowGroupCodeFromTools', 'leadUnlockFromTools',
    'leadReleaseStation', 'leadSettleFromTools'];
  const firstNonLeader = rows.findIndex((attrs) => !leaderHandlers.some((h) => attrs.includes('bindtap="' + h + '"')));
  assert.equal(firstNonLeader, leaderHandlers.length, '前 5 个 drow 必须正好是队长的 5 种行');
  leaderHandlers.forEach((h, i) => assert.match(rows[i], new RegExp('bindtap="' + h + '"'), `第 ${i + 1} 行应是 ${h}`));
  // 守卫不变:不是队长就不渲染,队员菜单不多出任何一行
  rows.slice(0, leaderHandlers.length).forEach((attrs) => assert.match(attrs, /lead\.isLeader/));
  assert.match(menu, /<view class="drow" wx:if="\{\{lead\.exists && lead\.isLeader && !lead\.directorRun\}\}" bindtap="leadUnlockFromTools"/);
});
