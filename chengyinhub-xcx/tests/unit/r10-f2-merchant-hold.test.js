const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// 第十轮 f2 第 5 项 + j2 F-07 / F-10:
// - 商家随时能从工作台进本站页接受与准备(不经过会关掉的报名窗口);
// - 主理人看得到是哪一站卡在商家,并能去联系;开始活动被拒时说出原因;
// - 招商层「确认」之后直接进带条款的邀约页。

const ROOT = path.join(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const { attachMerchantGameEntries } = require('../../pages/merchant/utils/game-session-merchant.js');
const { DIRECTOR_DATA, DIRECTOR_METHODS } = require('../../pages/club/topic-detail/director.js');

// 第十一轮 h4 L2:同主题已有项目卡(承接)时,场次站点卡原来不出(项目卡又不渲染场次入口)。
test('工作台:同主题已有项目卡时,场次站点卡照样出,一场一张;同场次活动卡在时不重复', () => {
  const { attachMerchantGameEntries } = require('../../pages/merchant/utils/game-session-merchant.js');
  const entry = { activityId: 9, topicId: 990015, activityName: '人民广场 · 喷泉报时', stationCount: 1 };
  const withProject = attachMerchantGameEntries([{ key: 'join-1', id: 1, role: 'join', ownerType: 1, ownerId: 990015, title: '喷泉报时' }], [entry], {});
  assert.equal(withProject.length, 2);
  assert.equal(withProject[1].gameStationCard, true);
  assert.equal(withProject[1].id, 9);
  const withSameActivity = attachMerchantGameEntries([{ key: 'act-9', id: 9, role: 'host', bizType: 'activity', ownerType: 2, ownerId: 9, title: '场次' }], [entry], {});
  assert.equal(withSameActivity.length, 1, '同一场次已有活动卡就不再补卡');
});

test('工作台:场次里有本店站点、又不挂在任何项目卡上时,补出一张直达本站页的卡', () => {
  const cards = attachMerchantGameEntries([], [{ activityId: 9, topicId: 990015, activityName: '人民广场 · 喷泉报时', stationCount: 1, startAt: '2026-11-05 00:30' }], {});
  assert.equal(cards.length, 1);
  assert.equal(cards[0].gameStationCard, true);
  assert.equal(cards[0].id, 9);
  const wxml = read('pages/merchant/index/index.wxml');
  assert.match(wxml, /data-gameactivityid="\{\{item\.gameStationCard \? item\.id : ''\}\}"/);
  const js = read('pages/merchant/index/index.js');
  assert.match(js, /if \(ds\.gameactivityid\) \{[\s\S]{0,200}\/pages\/merchant\/game-node\/index\?activityId=' \+ ds\.gameactivityid/);
});

function director(projection) {
  const page = Object.assign({ data: JSON.parse(JSON.stringify(DIRECTOR_DATA)), _topicId: 990015 }, DIRECTOR_METHODS);
  page.setData = function (patch) { Object.assign(this.data, patch); };
  page.applyProjection(projection);
  return page;
}

test('导演台:卡在商家(待商家接受 / 没有合作单)时露出「联系商家」,点了进本主题的合作列表', () => {
  const page = director({ status: 'READY', readiness: { blockers: ['人民广场音乐喷泉（待商家接受）'] }, stations: [], teams: [] });
  assert.equal(page.data.merchantHoldBlocked, true);
  assert.match(page.data.blockerText, /人民广场音乐喷泉（待商家接受）/);
  const urls = [];
  global.wx = { navigateTo(o) { urls.push(o.url); o.complete && o.complete(); } };
  page.goStationMerchants();
  assert.match(urls[0], /^\/pages\/coop\/list\/index\?tab=sent&topicId=990015/);
  const other = director({ status: 'READY', readiness: { blockers: ['尚无有效报名队伍'] }, stations: [], teams: [] });
  assert.equal(other.data.merchantHoldBlocked, false);
});

test('开始活动被服务端拒:原因贴在底栏开始按钮上方(第十二轮 k4,j2 N-04),不再无声', () => {
  const js = read('pages/club/topic-detail/director.js');
  assert.match(js, /if \(action === 'START' && reason\) return reason/);
  assert.match(js, /if \(command\.action === 'START'\) that\.setData\(\{ startFailText: rejectedText \? '没能开始：' \+ rejectedText/);
  const wxml = read('pages/club/topic-detail/index.wxml');
  assert.match(wxml, /<text class="ctd-footbar__err" wx:if="\{\{startFailText && statusKey === 'preparing'\}\}">\{\{startFailText\}\}<\/text>\s*<view\s+wx:if="\{\{primary\.text\}\}"/);
  assert.match(wxml, /caption="\{\{writeMessage \|\| blockerText\}\}"/, '准备总览不再重复同一句');
  const page = director({ status: 'READY', readiness: { blockers: [] }, stations: [], teams: [] });
  page.data.startFailText = '没能开始：当前不在活动服务时段';
  page.applyProjection({ status: 'READY', readiness: { blockers: [] }, stations: [], teams: [] });
  assert.equal(page.data.startFailText, '没能开始：当前不在活动服务时段', '刷新投影不吞掉原因');
  page.applyProjection({ status: 'RUNNING', readiness: { blockers: [] }, stations: [], teams: [] });
  assert.equal(page.data.startFailText, '', '开局成功后清掉');
});

test('招商层确认候选后直接进带条款的邀约页(F-10)', () => {
  const js = read('pages/topic/merchantinfo/merchantinfo.js');
  assert.match(js, /that\.goTermsInvite\(ds\.id, ds\.name\);/);
  assert.match(js, /'\/pages\/coop\/invite\/index\?type=0&toId=' \+ toId/);
  assert.match(js, /return \{ id: r\.id, memberId: r\.memberId,/);
});
