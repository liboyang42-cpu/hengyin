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

// U2 第二批(1120 复核 master 残留):这些缺参态还在用「缺少…信息 / …链接不完整」和「返回上一页 / 返回活动列表」。
const U2_BATCH2 = [
  'pages/merchant/ledger/order-detail/index.wxml',
  'pages/merchant/ledger/batch-detail/index.wxml',
  'pages/square/detail/index.wxml',
  'pages/topic/merchantinfo/merchantinfo.wxml',
  'pages/club/group-code/index.wxml',
  'pages/club/payout-receiver/index.wxml',
  'subpackageMember/components/cy/scene-roam-poi-detail/index.wxml',
];

test('U2 第二批 wxml:缺参态默认文案 + 唯一「返回」', () => {
  for (const rel of U2_BATCH2) {
    const tags = read(rel).match(/<cy-empty\b[^>]*kind="missing-param"[^>]*>/g) || [];
    assert.ok(tags.length, rel + ' 必须有 missing-param 缺参态');
    for (const tag of tags) {
      assert.match(tag, /cta="返回"/, rel);
      const title = tag.match(/\btitle="([^"]*)"/);
      assert.ok(!title || title[1] === '链接已失效', rel + ' 缺参态只用「链接已失效」');
      assert.doesNotMatch(tag, /\bsub="/, rel + ' 缺参态不加副文案');
    }
  }
  const partner = read('pages/topic/pricing/partner/index.wxml').match(/<cy-empty wx:if="\{\{invalidLink\}\}"[^>]*>/);
  assert.ok(partner, '合作页缺参态');
  assert.match(partner[0], /kind="missing-param"[\s\S]*cta="返回"/);
  assert.doesNotMatch(partner[0], /\btitle="/);
  assert.doesNotMatch(read('pages/crop/index.wxml'), /没有可裁剪的图片/);
  // 俱乐部团码缺参不再是 cy-error + 第二个「进入俱乐部管理」出口
  assert.doesNotMatch(read('pages/club/group-code/index.wxml'), /缺少路线或场次信息/);
  assert.match(read('pages/club/group-code/index.js'), /goBack\(\) \{\s*if \(getCurrentPages\(\)\.length > 1\)/);
  // 个人收款缺俱乐部参数不再给假「重新加载」
  assert.match(read('pages/club/payout-receiver/index.js'), /if \(!this\._mine\) \{ done\(\); this\.setData\(\{ state: 'missing-param'/);
});

test('U2 第二批 js:缺参标题统一「链接已失效」,动作统一「返回」', () => {
  const team = read('pages/team/detail/index.js');
  assert.match(team, /error: '链接已失效',\s*errorKind: 'missing-param',\s*errorSub: '',\s*errorAction: '返回'/);
  assert.match(team, /errorAction: refused \? '返回' : '重新加载'/);
  const join = read('pages/team/join/index.js');
  assert.match(join, /'邀请链接已升级' : '链接已失效'/);
  assert.doesNotMatch(join, /邀请链接不完整|请从队长分享的邀请重新进入|返回上一页/);
  const circle = read('pages/play/circle/index.js');
  assert.match(circle, /errorText: '链接已失效',\s*errorKind: 'missing-param',\s*errorSub: '',\s*errorAction: '返回'/);
  assert.match(read('pages/activity/official-detail/index.js'), /loadErrTitle: '链接已失效', loadErrSub: '',\s*loadErrAction: '返回' \}\)/);
  const part = read('subpackageMember/components/scene-member-participation-detail/index.js');
  assert.match(part, /missingTitle: '链接已失效'/);
  assert.match(part, /loadState: 'missing-param',\s*loadErrorText: '',\s*errorActionText: '返回',/);
});
