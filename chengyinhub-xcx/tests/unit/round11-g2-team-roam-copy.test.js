'use strict';
// 第十一轮 g2:j4 F9(说明小字、退队确认、加入方式)与 F12(评价读不到时不再同时说「暂无评分」)。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = rel => fs.readFileSync(path.resolve(__dirname, '../..', rel), 'utf8');

test('F9 退队确认:队员不看「你是队长的话」、不写不实的「不可撤销」;队长看移交与不可撤销;标点全角', () => {
  delete require.cache[require.resolve('../../utils/danger-actions.js')];
  const { getDangerAction } = require('../../utils/danger-actions.js');
  const member = getDangerAction('team.quit', {});
  const leader = getDangerAction('team.quit-leader', {});
  const memberText = JSON.stringify(member);
  assert.equal(member.irreversible, false);
  assert.doesNotMatch(memberText, /队长|不可撤销|重新被邀请/);
  assert.equal(leader.irreversible, true);
  assert.match(JSON.stringify(leader.consequences), /移交给最早加入的队员/);
  assert.match(JSON.stringify(leader.consequences), /此操作不可撤销/);
  for (const a of [member, leader]) {
    const texts = [a.title, a.content, a.done.title, a.done.text].concat(a.consequences.map(c => c.text));
    for (const t of texts) assert.doesNotMatch(t, /[,;]/, t);
  }
  const detail = read('pages/team/detail/index.js');
  assert.match(detail, /if \(this\.data\.leader\) dc\.open\('team\.quit-leader', \{\}\);\s*else dc\.open\('team\.quit', \{\}\);/);
});

test('F9 加入方式绑定真实 joinMode;队伍详情对招募中的队长只留开关一处「公开招募」', () => {
  assert.match(read('pages/team/join/index.wxml'), /team\.joinMode == 2 \? '公开招募' : '仅邀请'/);
  const detail = read('pages/team/detail/index.wxml');
  // 招募中的队长已有「公开招募」开关,副标题只给其他人看,不再同屏重复
  assert.match(detail, /<view wx:if="\{\{!\(leader && team\.status == 0\)\}\}" class="team-sub">/);
});

test('F9 漫游入口与城市清单的说明小字', () => {
  const roam = read('pages/roam/index.js'), wxml = read('pages/roam/index.wxml');
  assert.doesNotMatch(roam, /走到哪，雾散到哪' \}|已加入 · 申请中/);
  assert.doesNotMatch(wxml, /CITY ROAM/);
  // 2026-11-09 V3-35:城市清单组件已删,目录地点直接画在地图上
  assert.doesNotMatch(wxml, /cy-roam-city-guide/);
  assert.doesNotMatch(read('subpackageMember/components/cy/scene-roam-poi-detail/index.wxml'), /完成据点挑战后可以评分和评论/);
});

test('F12 评价读不到时只留重试,不再同时写「暂无评分，等你来体验」', () => {
  const poi = read('subpackageMember/components/cy/scene-roam-poi-detail/index.wxml');
  assert.match(poi, /wx:if="\{\{node\.ratingCount \|\| !reviewsError\}\}"[^>]*>\{\{node\.ratingCount \?[^<]*暂无评分，等你来体验/);
});
