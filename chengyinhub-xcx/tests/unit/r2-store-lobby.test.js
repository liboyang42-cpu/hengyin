'use strict';
// r2-store 开局大厅:席位圆、N/M、邀请与去起点只来自服务端席位事实。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const view = require('../../subpackageJourney/utils/journey-view.js');
const { lobbyView, playUrl } = require('../../subpackageJourney/utils/lobby-view.js');

const raw = { registrationId: 901, ownerType: 2, ownerId: 9, topicId: 990014, seatCount: 4, freeSeats: 2, buyerIsCaller: true, readOnly: false,
  seatCancelSupported: true, seats: [{ seatNo: 1, state: 'BUYER' }, { seatNo: 2, state: 'INVITED', invitationId: 7 }, { seatNo: 3, state: 'CLAIMED' }, { seatNo: 4, state: 'AVAILABLE' }] };

test('N/M 只数买家+已领;空位可点只在服务端允许邀请时', () => {
  const l = lobbyView(view.seatsView(raw), raw, { avatar: 'a.jpg', initial: '小' });
  assert.equal(l.countText, '2/4');
  assert.deepEqual(l.circles.map((c) => c.kind), ['me', 'invited', 'taken', 'open']);
  assert.equal(l.circles[0].avatar, 'a.jpg');
  assert.equal(l.circles[2].avatar, '', '服务端不给被邀请人头像,不编');
  assert.equal(l.circles[3].tappable, true);
  assert.deepEqual(l.invited.map((s) => s.no), [2, 3]);
  const ro = Object.assign({}, raw, { readOnly: true });
  assert.equal(lobbyView(view.seatsView(ro), ro, {}).circles[3].tappable, false);
});

test('一人票不出同行卡;读不到 seatCount 不写 N/M', () => {
  const one = Object.assign({}, raw, { seatCount: 1, freeSeats: 0, seats: [{ seatNo: 1, state: 'BUYER' }] });
  assert.equal(lobbyView(view.seatsView(one), one, {}).showParty, false);
  const unk = Object.assign({}, raw, { seatCount: null });
  assert.equal(lobbyView(view.seatsView(unk), unk, {}).countText, '');
});

test('去起点带原活动/主题与报名单', () => {
  assert.equal(playUrl(view.seatsView(raw)), '/pages/play/index?activityId=9&topicId=990014&registrationId=901');
  const t = Object.assign({}, raw, { ownerType: 1, ownerId: 990014 });
  assert.equal(playUrl(view.seatsView(t)), '/pages/play/index?topicId=990014&registrationId=901&entry=ticket');
});

test('大厅页:无二维码/加入码/释放;关闭在半屏标题栏;不新增 keyframes', () => {
  const dir = path.join(__dirname, '../../subpackageJourney/pages/seats');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  assert.ok(!/二维码|加入码|释放/.test(wxml.replace(/<!--[\s\S]*?-->/g, '')));
  // 2026-11-10 qx-final:l-after 把所有邀请统一成 Spotify 式邀请页(业主批准 proto sh-invite),
  // 「邀请同行」半屏换成 cy-share-card;「发给微信好友」的 open-type="share" 在组件里。
  assert.match(wxml, /<cy-share-card show="\{\{inviteShow\}\}" card="\{\{inviteCard\}\}"/);
  assert.match(fs.readFileSync(path.join(__dirname, '../../components/cy/share-card/index.wxml'), 'utf8'), /open-type="share"/);
  const wxss = fs.readFileSync(path.join(dir, 'index.wxss'), 'utf8');
  assert.ok(!/@keyframes/.test(wxss));
});

test('受邀落地:领取被拒(已满/过期/撤回,服务端原话)时给「自己购买」回原活动或主题', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../subpackageJourney/pages/landing/index.js'), 'utf8');
  assert.match(js, /primary: this\._buyOwn \? '自己购买' : '', actionError: res\.msg \|\| '领取失败'/);
  assert.match(js, /if \(this\._buyOwn\) \{ this\._goOwn\(\); return; \}/);
  assert.match(js, /this\._buyOwn = false;/);
});
