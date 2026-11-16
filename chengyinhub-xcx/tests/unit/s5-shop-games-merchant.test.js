// S5 店铺小游戏:商家页按小游戏池挑选、受套餐数量限制、平台锁定时只读;打卡后告诉玩家今天的小游戏。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { shapeShopGames, selectedCount, saveBody } = require('../../pages/merchant/shop-games/view-model.js');

const root = path.join(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

function payload(extra) {
  return Object.assign({
    kind: { code: 'CAFE', nameZh: '咖啡茶饮' },
    source: 'DEFAULT',
    allowance: 1,
    editable: true,
    games: [{ key: 'qaPick', templateId: null, ready: false }],
    pool: [
      { key: 'qaPick', nameZh: '快问快答', seconds: 60, enabled: true },
      { key: 'imageGuess', nameZh: '看图猜一猜', seconds: 60, enabled: true },
      { key: 'trueFalse', nameZh: '对还是错', seconds: 45, enabled: false },
    ],
    templates: [{ id: 12, title: '店里的咖啡问答', game: 'qaPick' }, { id: 14, title: '拍照', game: 'qaShot' }],
  }, extra || {});
}

test('回包整理:默认小游戏选中并带上本店对应的内容', () => {
  const v = shapeShopGames(payload());
  assert.equal(v.kindName, '咖啡茶饮');
  assert.equal(v.allowance, 1);
  assert.equal(selectedCount(v.pool), 1);
  const quiz = v.pool.find(g => g.key === 'qaPick');
  assert.deepEqual(quiz.optionTitles, ['店里的咖啡问答']);
  assert.equal(v.pool.find(g => g.key === 'trueFalse').enabled, false);
  assert.deepEqual(saveBody(v.pool), { games: [{ key: 'qaPick', templateId: 12 }] });
  assert.equal(shapeShopGames(null), null);
  assert.equal(shapeShopGames({ pool: 'x' }), null);
});

test('平台指定时页面只读', () => {
  const v = shapeShopGames(payload({ source: 'OPERATOR', editable: false }));
  assert.equal(v.editable, false);
  assert.match(v.sourceText, /平台/);
});

test('页面只调后端已有的商家路由,并已注册与有入口', () => {
  const js = read('pages/merchant/shop-games/index.js');
  assert.ok(js.includes("'/api/merchant/shop-games'"));
  assert.ok(js.includes("'/api/merchant/shop-games/save'"));
  const controller = fs.readFileSync(path.join(root, '..',
    'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMerchantShopGameController.java'), 'utf8');
  assert.ok(controller.includes('"/api/merchant/shop-games"') && controller.includes('"/api/merchant/shop-games/save"'));
  const app = JSON.parse(read('app.json'));
  const merchant = app.subPackages.find(s => s.root === 'pages/merchant');
  assert.ok(merchant.pages.includes('shop-games/index'));
  assert.ok(read('pages/merchant/citynode/index.wxml').includes('bindtap="goShopGames"'));
  assert.ok(read('pages/merchant/citynode/index.js').includes("'/pages/merchant/shop-games/index'"));
  assert.match(js, /当前套餐最多可选/);
});

test('打卡回包里的小游戏会告诉玩家,没配好时如实说明', () => {
  const roam = read('pages/roam/index.js');
  assert.match(roam, /r\.shopGame/);
  assert.match(roam, /今天这家店的小游戏/);
  assert.match(roam, /这家店的小游戏还在准备中/);
});

test('W7 GAME-8:店铺小游戏未开放(NOT_OPEN)时打卡不提示小游戏', () => {
  const roam = read('pages/roam/index.js');
  const notOpen = roam.indexOf("sg.reason === 'NOT_OPEN'");
  const toast = roam.indexOf('今天这家店的小游戏');
  assert.ok(notOpen > 0 && notOpen < toast, 'NOT_OPEN is handled before the game toast');
  assert.match(roam, /else if \(sg\.available && sg\.game && sg\.game\.nameZh\)/);
});
