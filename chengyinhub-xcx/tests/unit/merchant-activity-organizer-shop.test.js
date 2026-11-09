// check-1104 j1 F14(第十轮 f1 第 10 项):商家自己发的活动,主办方是店铺,不是发起人昵称「城*」/「城瘾用户 · 主理人」。
// 详情页用服务端 organizerShopName/organizerShopLogo;发布表单合作者首行在商家主办时署店铺名、标「商家」。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

test('活动详情:有店铺名就以店铺作主办方,标题「主办方」、角色「商家」;否则沿用发起人', () => {
  const js = read('subpackageMember/components/cy/scene-play-activity-detail/index.js');
  const wxml = read('subpackageMember/components/cy/scene-play-activity-detail/index.wxml');
  assert.match(js, /hostName: nonEmptyString\(info\.organizerShopName\) \|\| nonEmptyString\(host\.memberRealName\)/);
  assert.match(js, /hostAvatar: nonEmptyString\(info\.organizerShopName\) \? nonEmptyString\(info\.organizerShopLogo\) : nonEmptyString\(host\.memberAvatar\)/);
  assert.match(js, /hostIsShop: !!nonEmptyString\(info\.organizerShopName\)/);
  assert.match(wxml, /\{\{hostIsShop \? '主办方' : '主理人'\}\}/);
  assert.match(wxml, /\{\{hostIsShop \? '商家' : '发起人 · 主理人'\}\}/);
});

test('发布活动(商家主办):合作者首行署店铺名与店铺头像,标签「商家」', () => {
  const js = read('pages/publish/activity/index.js');
  const wxml = read('pages/publish/activity/index.wxml');
  assert.match(js, /this\._hostShop = shop \? \{ name: shop, logo: res\.data\.merchant\.logo \|\| '' \} : null/);
  assert.match(js, /const shop = that\.merchantHost \? that\._hostShop : null/);
  assert.match(js, /nickname: shop \? shop\.name : res\.data\.nickname/);
  assert.match(js, /ownerLabel: shop \? '商家' : ''/);
  assert.match(wxml, /\{\{item\.ownerLabel \|\| '主理人'\}\}/);
});
