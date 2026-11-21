// ui-sweep-1119 U1/U2:无参数打开的页面统一落在「链接已失效 · 返回」缺参态,不再是只剩标题的黑屏,
// 也不再各写一套「活动链接无效 / 无法打开报名页 / 没有指定俱乐部 …」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

const STANDARD = /<cy-empty\b[^>]*kind="missing-param"[^>]*cta="返回"[^>]*bind:cta="[A-Za-z]+"/;

// U1:原来这几页缺参时只走 load-failure(空白占位),整屏只剩标题。
const U1_PAGES = [
  ['pages/club/customers/index', "state === 'missing-param'"],
  ['pages/club/event-ops/index', "state === 'missing-param'"],
  ['pages/club/governance/index', "state === 'missing-param'"],
  ['pages/club/notify/index', "state === 'missing-param'"],
  ['pages/club/settlement/index', "state === 'missing-param'"],
  ['subpackageMember/invoice-detail/index', 'missing'],
];

for (const [page, cond] of U1_PAGES) {
  test(`U1 ${page}:缺参渲染标准缺参态(链接已失效 + 返回)`, () => {
    const wxml = read(page + '.wxml');
    const line = wxml.split('\n').find((l) => l.includes('kind="missing-param"'));
    assert.ok(line, '必须有 missing-param 缺参态');
    assert.ok(line.includes(cond), '缺参态必须挂在缺参分支上');
    assert.match(line, STANDARD);
    assert.doesNotMatch(line, /\btitle=|\bsub=/, '缺参态用组件默认文案「链接已失效」,不加副文案');
    const json = JSON.parse(read(page + '.json'));
    assert.ok(json.usingComponents['cy-empty'], 'cy-empty 必须注册');
  });
}

test('U1 俱乐部四页的 js 缺参不再落到 error(load-failure 空白)', () => {
  for (const page of ['customers', 'event-ops', 'governance', 'notify', 'settlement']) {
    const js = read(`pages/club/${page}/index.js`);
    assert.match(js, /state: 'missing-param'/, page);
    assert.doesNotMatch(js, /errorText: '缺少俱乐部(信息|ID)/, page);
  }
});

test('U1 invoice-detail:没有 id 不请求、进入 missing 态', () => {
  const js = read('subpackageMember/invoice-detail/index.js');
  assert.match(js, /if \(!id\) \{ this\.setData\(\{ missing: true \}\); return; \}/);
});

// U2:缺参文案只剩一种(组件默认「链接已失效」),动作只剩「返回」
const U2_FILES = [
  'pages/activity/baoming/baoming.wxml',
  'pages/club/components/cy/scene-club-edit/index.wxml',
  'pages/club/join-requests/index.wxml',
  'pages/coop/settlement-detail/index.wxml',
  'pages/coop/nearby/index.wxml',
  'pages/topic/pricing/index.wxml',
  'pages/userinfo/userinfo.wxml',
  'subpackageRoam/citynode-code/index.wxml',
  'components/cy/scene-qr-group-code/index.wxml',
  'subpackageMember/components/cy/scene-play-activity-detail/index.wxml',
  'subpackageMember/components/cy/scene-route-content/index.wxml',
  'subpackageB/pages/im/chat/index.wxml',
  'pages/coop/invite-detail/index.wxml',
  'pages/topic/index/index.wxml',
];
const OLD_COPY = /活动链接无效|无法打开报名页|没有指定俱乐部|无法打开入会申请|缺少结算记录信息|主题信息无效|这个主页链接不完整|这个核销码无法打开|团核销码无法打开|这个会话无法打开|无法打开这条邀约|无法打开主题|缺少主题信息"|缺少券信息/;

test('U2 缺参态只用「链接已失效」一种说法 + 「返回」一个动作', () => {
  for (const rel of U2_FILES) {
    const wxml = read(rel);
    const tags = wxml.match(/<cy-empty\b[^>]*kind="missing-param"[^>]*>/g) || [];
    assert.ok(tags.length, rel + ' 必须有 missing-param 缺参态');
    for (const tag of tags) {
      assert.match(tag, /cta="返回"/, rel);
      assert.doesNotMatch(tag, OLD_COPY, rel);
      assert.doesNotMatch(tag, /\btitle="/, rel + ' 缺参态用组件默认文案');
      assert.doesNotMatch(tag, /\bsub="/, rel + ' 缺参态不加副文案');
    }
  }
  assert.doesNotMatch(read('subpackageMember/coupon-qr/index.wxml'), /缺少券信息/);
  assert.doesNotMatch(read('subpackageMember/components/cy/order-center-entry/index.js'), /订单链接无效/);
  // 缺参不再自动退页:原地给出「返回」
  assert.match(read('pages/club/badges/index.wxml'), /kind="missing-param"[^>]*primary="返回"[^>]*bind:primary="goBack"/);
  assert.doesNotMatch(read('pages/club/badges/index.wxml'), /kind="missing-param"[^>]*auto-back/);
});

test('U2 journey 四页缺参态都有「返回」出口', () => {
  for (const page of ['seats', 'landing', 'settle', 'report']) {
    const js = read(`subpackageJourney/pages/${page}/index.js`);
    assert.match(js, /state: 'missing'[^}]*stateTitle: '链接已失效'[^}]*stateCta: '返回'/, page);
    assert.match(js, /onStateCta\(\) \{\s*if \(this\.data\.state (===|!==) 'missing'\)/, page);
  }
  assert.match(read('subpackageJourney/pages/seats/index.wxml'), /bind:cta="onStateCta"/);
});

test('领取礼品 / 入场码 / 参与详情:缺参同样落在「链接已失效 · 返回」', () => {
  const gift = read('subpackageMember/market/gift/index.wxml');
  assert.match(gift, /<cy-empty wx:if="\{\{state === 'missing'\}\}"[^>]*kind="missing-param"[^>]*cta="返回"[^>]*bind:cta="goBack"/);
  assert.doesNotMatch(read('subpackageMember/market/gift/index.js'), /TOKEN_REQUIRED'\)/);
  const ticket = read('pages/play/components/scene-qr-ticket/index.wxml');
  assert.match(ticket, /<cy-empty wx:if="\{\{state === 'missing'\}\}"[^>]*kind="missing-param"[^>]*cta="返回"[^>]*bind:cta="close"/);
  const ticketJs = read('pages/play/components/scene-qr-ticket/index.js');
  assert.doesNotMatch(ticketJs, /缺少报名记录/);
  assert.equal((ticketJs.match(/state: 'missing'/g) || []).length, 3);
  const part = read('subpackageMember/components/scene-member-participation-detail/index.js');
  assert.doesNotMatch(part, /missingTitle: '缺少参与记录'|loadErrorText: '请返回列表重新进入'|errorActionText: '返回参与列表'/);
});
