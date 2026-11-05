'use strict';
// 第十二轮 k2(j1 N1):没有游戏的场次票只出入场码,订单页与票夹都不出「开始游戏」。
const assert = require('assert');
const test = require('node:test');
const fs = require('fs');
const path = require('path');
const { activityHasGame, ticketHasGame } = require('../../utils/ticket-game.js');
const read = p => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8');

test('k2 j1 N1:有来源主题或玩法模板才算有游戏;读不到活动按有游戏', () => {
  assert.equal(activityHasGame({ topicId: 0, templateId: 0 }), false);
  assert.equal(activityHasGame({ topicId: null, templateId: null }), false);
  assert.equal(activityHasGame({ topicId: 12 }), true);
  assert.equal(activityHasGame({ templateId: 3 }), true);
  assert.equal(activityHasGame(null), true);
  assert.equal(ticketHasGame({ ownerType: 1 }), true);
  assert.equal(ticketHasGame({ ownerType: 2, cmsActivity: { topicId: 0, templateId: 0 } }), false);
});

test('k2 j1 N1:订单页主按钮与票夹「开始游戏」都按 ticketHasGame 收', () => {
  assert.match(read('subpackageMember/components/cy/scene-member-order-detail/index.js'), /playQuery\(value\) && ticketHasGame\(value\)\) return 'play'/);
  const wxml = read('subpackageMember/signup/index.wxml');
  assert.match(wxml, /class="wl-start[^"]*"[^>]*wx:if="\{\{walletState === 'ready' && ticketList\.length && !ticketList\[currentSwiperTicket\]\.wNoGame\}\}"/);
  const js = read('subpackageMember/signup/index.js');
  assert.match(js, /wNoGame: !activityHasGame\(a\)/);
  assert.match(js, /_showQrIfNoGame\(\) \{[\s\S]{0,200}this\.setData\(\{ flipped: true \}\)/);
});
