'use strict';
// 第十二轮 k9-pages:p1 / p2 / p3 / j5 / j6 第二轮复查里剩下的页面问题。每条都在旧代码上判红。
const assert = require('assert');
const test = require('node:test');
const fs = require('fs');
const path = require('path');
const read = p => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8');

const PRODUCT_DIRS = ['pages', 'components', 'subpackageMember', 'subpackageB', 'subpackageP3', 'subpackageRoam', 'subpackageTools', 'subpackageA'];
function productFiles(dir, out) {
  const abs = path.join(__dirname, '../..', dir);
  if (!fs.existsSync(abs)) return out;
  for (const name of fs.readdirSync(abs)) {
    const rel = path.join(dir, name);
    const st = fs.statSync(path.join(abs, name));
    if (st.isDirectory()) productFiles(rel, out);
    else if (/\.(wxml|js)$/.test(name)) out.push(rel);
  }
  return out;
}

test('p1 N-6:产品代码里不再有「请返回重新进入」(标题 + 返回按钮就够了)', () => {
  const hits = [];
  for (const dir of PRODUCT_DIRS) {
    for (const f of productFiles(dir, [])) {
      const src = read(f);
      // cy-empty 里那条是解释「为什么不再默认写这句」的注释
      if (/请返回重新进入/.test(src) && f !== path.join('components', 'cy', 'empty', 'index.js')) hits.push(f);
    }
  }
  assert.deepEqual(hits, []);
});

test('p1 N-6:会话缺参态有「返回消息列表」;底栏解释为空时整行不出', () => {
  const wxml = read('subpackageB/pages/im/chat/index.wxml');
  assert.match(wxml, /title="这个会话无法打开" cta="返回消息列表" bind:cta="returnToMessageList"/);
  assert.match(wxml, /wx:if="\{\{disabled \|\| \(loadState !== 'ready' && sendBlockedMap\[loadState\]\)\}\}" class="disabled-tip"/);
  assert.match(read('subpackageB/pages/im/chat/index.js'), /      missing: '',/);
});

test('p1 N-4:别人的参与记录 / 已不存在的记录 → 「链接已失效 · 返回」,不是空白的加载失败', () => {
  const js = read('subpackageMember/components/scene-member-participation-detail/index.js');
  const m = js.match(/const GONE_RECORD_MSG = (\/.*\/);/);
  assert.ok(m);
  const re = eval(m[1]);
  assert.ok(re.test('无权查看该报名信息'));
  assert.ok(re.test('报名记录不存在'));
  assert.ok(!re.test('网络异常，请重试'));
  assert.match(js, /that\.setData\(\{ loadState: 'missing-param', missingTitle: '链接已失效', errorActionText: '返回' \}\)/);
  assert.match(js, /if \(!this\.data\.id \|\| this\.data\.loadState === 'missing-param'\) \{\n        this\.backToList\(\);/);
  const wxml = read('subpackageMember/components/scene-member-participation-detail/index.wxml');
  assert.match(wxml, /wx:elif="\{\{loadState === 'missing-param'\}\}" kind="missing-param" title="\{\{missingTitle\}\}" cta="\{\{errorActionText\}\}" bind:cta="onErrorAction"/);
});

test('p1 N-5 剩余:排行榜游客只说一遍「登录后查看排行榜」;队伍详情游客不写「请重新登录后再打开这支队伍」', () => {
  const lb = read('subpackageP3/pages/growthcenter/leaderboard/index.wxml');
  assert.match(lb, /sub="\{\{boardAuthRequired \|\| boardAccessDenied \? '' : errorMsg\}\}"/);
  const team = read('pages/team/detail/index.js');
  assert.doesNotMatch(team, /请重新登录后再打开这支队伍/);
  assert.match(team, /errorSub: authDenied \? '' : refused/);
});

test('p1 N-7:新玩家足迹四行全 0 时不画「我的足迹」', () => {
  const js = read('subpackageP3/pages/growthcenter/index/index.js');
  assert.match(js, /footprintEmpty: allSuccess && \(overview\.stats \|\| \[\]\)\.every\(\(item\) => !Number\(item && item\.value\)\)/);
  assert.match(read('subpackageP3/pages/growthcenter/index/index.wxml'), /<view class="gc-section" wx:if="\{\{!footprintEmpty\}\}">\s*<view class="gc-section-head"><text class="gc-section-title">我的足迹/);
});

test('p1 N-8:角色卡没有可见属性 → 「链接已失效 · 返回」,四行解释不再叠', () => {
  const wxml = read('subpackageMember/character/index.wxml');
  assert.match(wxml, /<cy-empty wx:if="\{\{\(state === 'ready' \|\| state === 'template'\) && !card\.attributes\.length && !card\.rpg\}\}"[^>]*title="链接已失效" cta="返回" bind:cta="backToParticipation" \/>/);
  assert.doesNotMatch(wxml, /该主题尚未配置可见属性/);
});

test('p1 N-9:票夹队伍胶囊按内容宽;个人介绍空时一行「未填写」', () => {
  const wxss = read('subpackageMember/signup/index.wxss');
  assert.match(wxss, /\.signup \.team-entry \{ width: max-content; max-width: calc\(100vw - 2 \* var\(--cy-page-x\)\); box-sizing: border-box; \}/);
  assert.match(wxss, /\.signup \.team-entry__k, \.signup \.team-entry__n \{ flex-shrink: 0; white-space: nowrap; \}/);
  const wxml = read('pages/gerenziliao/gerenziliao.wxml');
  assert.doesNotMatch(wxml, /\{\{userInfo\.introduction \|\| '介绍一下你自己'\}\}/);
  assert.match(wxml, /<text class="rv-row__k">个人介绍<\/text><text class="rv-row__v is-empty">未填写<\/text>/);
  assert.match(wxml, /placeholder="介绍一下你自己"/, '占位留在编辑框里');
});

test('p1 N-10:会话链接没带名字时从会话列表取对方昵称;徽章没名字不在币下再写一遍「徽章」', () => {
  const js = read('subpackageB/pages/im/chat/index.js');
  assert.match(js, /if \(!that\.data\.name && Number\(that\.data\.type\) === 1\) that\._fillPeerName\(\);/);
  assert.match(js, /_fillPeerName\(\) \{[\s\S]*?url: '\/api\/im\/conversations'[\s\S]*?this\.setData\(\{ name: nickname, heroName: nickname \}\)/);
  assert.match(read('subpackageP3/pages/badge-3d/index.wxml'), /<view class="b3__name" wx:if="\{\{name !== '徽章'\}\}">\{\{name\}\}<\/view>/);
});

test('p1 N-11:结算的「订单金额」只在有减项时出现', () => {
  const wxml = read('pages/activity/baoming/baoming.wxml');
  assert.match(wxml, /<view wx:if="\{\{clubMemberDiscountAmount > 0 \|\| \(pointsUsable !== false && selectedTicket\.price > 0 && userInfo\.point != 0\)\}\}" class="setly_li">\s*<view class="setly_li_left">订单金额<\/view>/);
});

test('p1 N-12:申请开票的登录 / 未开放门态用大标题', () => {
  const wxml = read('subpackageMember/invoice-apply/index.wxml');
  assert.match(wxml, /<cy-nav-bar title="\{\{login \|\| unavailable \? '' : '申请开票'\}\}"/);
  assert.match(wxml, /<cy-page-title wx:if="\{\{login \|\| unavailable\}\}" title="申请开票" \/>/);
  assert.equal(JSON.parse(read('subpackageMember/invoice-apply/index.json')).usingComponents['cy-page-title'], '/components/cy/page-title/index');
});

test('p1 N-13:别人的券 → 「这张券不在你的券包里 · 返回」,不是「稍后重试」', () => {
  const js = read('subpackageMember/components/cy/scene-qr-coupon/index.js');
  assert.match(js, /\/无权查看该券\/\.test\(String\(res\.msg \|\| ''\)\)\) \{ this\._qrOwner = undefined; this\.clearTimers\(\); this\.setData\(\{ state: 'notmine'/);
  assert.match(read('subpackageMember/components/cy/scene-qr-coupon/index.wxml'), /wx:elif="\{\{state === 'notmine'\}\}" fill size="lg" title="这张券不在你的券包里" cta="返回" bind:cta="close"/);
});

test('p1 N-14:没有这一场的票被拒 → 按钮变「去买这一场的票」,去结算', () => {
  const js = read('pages/team/join/index.js');
  assert.match(js, /ticketRequired: !value\.busy && !!rejected && value\.outcome\.errorCode === 'TICKET_REQUIRED'/);
  assert.match(js, /wx\.navigateTo\(\{ url: '\/pages\/activity\/baoming\/baoming\?activityId=' \+ activityId \}\)/);
  assert.match(read('pages/team/join/index.wxml'), /wx:elif="\{\{team\.status == 0 && ticketRequired && team\.ownerType == 2\}\}" class="team-action primary" bindtap="goBuyTicket"[\s\S]*?>去买这一场的票<\/view>/);
});

test('p1 N-16:参与详情与订单详情、票夹同叫「开始游戏」', () => {
  const wxml = read('subpackageMember/components/scene-member-participation-detail/index.wxml');
  assert.match(wxml, /bindtap="goPlay"[^>]*>开始游戏<\/view>/);
  assert.doesNotMatch(wxml, />开始玩</);
});

test('p2 N-9:登录门从导航栏下面开始盖,返回键露出来;客户、账本对游客给登录门', () => {
  assert.match(read('components/cy/access-gate/index.wxml'), /<view wx:if="\{\{state === 'login'\}\}" class="gate" style="top: \{\{top\}\}px;"/);
  assert.match(read('pages/merchant/customer/index.wxml'), /<cy-access-gate kind="login" \/>\s*<cy-nav-bar \/>/);
  assert.match(read('pages/merchant/ledger/index.wxml'), /<cy-access-gate kind="login" \/>\s*<cy-nav-bar custom-back/);
  for (const p of ['pages/merchant/customer/index.json', 'pages/merchant/ledger/index.json']) {
    assert.equal(JSON.parse(read(p)).usingComponents['cy-access-gate'], '/components/cy/access-gate/index', p);
  }
});

test('p2 N-9:玩家开客户页不再被叫去「联系店主开通」', () => {
  for (const base of ['pages/merchant/customer/index', 'pages/merchant/customer/detail/index']) {
    assert.match(read(base + '.js'), /notMerchant: !access\.active/, base);
    const wxml = read(base + '.wxml');
    assert.match(wxml, /title="\{\{notMerchant \? '当前账号不能查看客户' : '当前岗位没有客户查看权限'\}\}" sub="\{\{notMerchant \? '' : '请联系店主开通'\}\}"/, base);
  }
});

test('p2 N-9:不相干的主题链接 → 「链接已失效」,不是空白页', () => {
  assert.match(read('pages/topic/merchantinfo/merchantinfo.js'), /if \(res && res\.code != '200' && \/既不是该主题的发布者\/\.test\(String\(res\.msg \|\| ''\)\)\) \{ that\.setData\(\{ missingParam: true \}\); return; \}/);
});

test('p3 N6:同一页面再点「应用」回到已复制的那份草稿,不再多复制一份', () => {
  const js = read('pages/templatedetail/topic-detail/index.js');
  assert.match(js, /if \(this\._copiedId\) \{ this\._openCopiedDraft\(this\._copiedId\); return; \}/);
  assert.match(js, /that\._copiedId = copiedId;/);
});

test('j6 N4:角色页认 id;缺俱乐部 = 「链接已失效 · 返回」', () => {
  const js = read('pages/club/roles/index.js');
  assert.match(js, /positiveId\(options && \(options\.clubId \|\| options\.id\)\)/);
  assert.match(js, /this\.setData\(\{ state: 'missing', errorText: '' \}\)/);
  assert.match(read('pages/club/roles/index.wxml'), /wx:elif="\{\{state === 'missing'\}\}" fill size="lg" kind="missing-param" title="链接已失效" cta="返回" bind:cta="goBack"/);
});

test('j5 N8:帖文时间不再读成「刚刚前」', () => {
  const js = read('pages/square/detail/index.js');
  const start = js.indexOf('formatTimeDifference: function');
  const body = js.slice(start, js.indexOf('\n  },', start) + 4);
  const fn = eval('({' + body + '})').formatTimeDifference;
  const now = Date.now();
  global.toTimestamp = (v) => v;
  const iso = (ms) => new Date(ms).toISOString();
  // 本页 toTimestamp 是模块内函数;这里只验 fn 自身的拼接
  assert.equal(fn.call({}, iso(now - 10 * 1000)), '刚刚');
  assert.equal(fn.call({}, iso(now - 5 * 60 * 1000)), '5分前');
  delete global.toTimestamp;
});

test('j5 N10:作者看到的「审核中」不折行', () => {
  assert.match(read('pages/topic/index/index.wxss'), /\.topic \.hero-type--held \{ color: var\(--cy-color-status-warning\); white-space: nowrap; flex-shrink: 0; \}/);
});

test('p2 N-15:AI 形象保存后的状态签跟回执走', () => {
  assert.match(read('pages/merchant/decor/ai-npc/index.js'), /auditStatus: receipt && receipt\.review === 'APPROVED' \? 1 : \(receipt && receipt\.review === 'REJECTED' \? 2 : 0\)/);
});

test('p2 N-13:投放数不预填假 0', () => {
  assert.match(read('subpackageMember/couponInfo/couponInfo.wxml'), /placeholder="请输入投放数量" value="\{\{formData\.publishCount \|\| ''\}\}"/);
});

test('j1:结算到账提醒没问过授权时不写说明行;到上限的标题不怪身份;开场标题与按钮留间距', () => {
  const js = read('pages/merchant/ledger/index.js');
  assert.doesNotMatch(js, /授权状态待确认，可点击申请/);
  assert.match(read('pages/merchant/ledger/index.wxml'), /<text wx:if="\{\{settleAlertsState === 'allowed' \|\| settleAlertsHint\}\}" class="fin-alert-sub"/);
  const act = read('pages/publish/activity/index.wxml');
  assert.match(act, /title="暂时不能发布活动" sub="\{\{deniedReason\}\}"/);
  assert.doesNotMatch(act, /当前身份不能发布活动/);
  assert.match(read('pages/merchant/apply/index.wxss'), /\.intro__title \+ \.intro-cta \{ margin-top: var\(--cy-space-6\); \}/);
});

test('j5 N6 / N7:编辑在售活动被拒不说「活动尚未发布」;「保存并绑定」被拒说没保存成功', () => {
  assert.match(read('pages/publish/activity/index.wxml'), /title="\{\{editingActivityId \? '修改没有保存' : '活动尚未发布'\}\}"/);
  assert.match(read('pages/publish/temp/index.wxml'), /title="\{\{from === 'citynode' \|\| from === 'fabu' \? '玩法没有保存成功'/);
});

test('j6 F9:没付的单不写「微信实付」;取消待支付订单用中文标点', () => {
  assert.match(read('subpackageMember/components/cy/scene-member-order-detail/index.wxml'), /\? '微信实付' : '微信待付'\}\}<\/text>/);
  const da = read('utils/danger-actions.js');
  assert.match(da, /'订单关闭，不再保留名额'/);
  assert.match(da, /'此操作不可撤销，需要时请重新下单'/);
});
