const { test } = require('node:test');
const assert = require('node:assert/strict');

test('不注册占位探索页', () => {
  const appConfig = require('../../app.json');

  assert.equal(appConfig.pages.includes('pages/explore/index'), false);
});

// 2026-08-19 产品决策变更(用户拍板):启用后台定位。
// 原来这条断言写死「不得声明后台定位」,那是「我们不做后台采集」这个决策的留痕。
// 变更理由:在架路线是 2–4.5 小时、7.8–10.5 公里的步行/骑行(cms_topic 真实数据),
// 玩家必然锁屏塞兜,前台定位一断就丢轨迹段、走到点位也判不出到达。
// 微信侧 wx.startLocationUpdateBackground 权限已开通。
// ⚠️ 断言方向反过来了,但**守的东西没有变少**:仍然钉死必须声明 requiredBackgroundModes,
//    否则声明了私有接口却跑不起来 —— 那是「权限拿了、功能是空的」的静默失效。
//    真正的合规防线在 utils/location/bg-tracker.js 的引用计数 + 其单测负控。
test('启用微信隐私检查,并按后台定位形态完整声明', () => {
  const appConfig = require('../../app.json');

  assert.equal(appConfig.__usePrivacyCheck__, true);
  assert.deepEqual(appConfig.requiredBackgroundModes, ['location'],
    '声明了后台定位私有接口就必须同时声明 requiredBackgroundModes,否则接口调不起来');
  assert.equal(appConfig.requiredPrivateInfos.includes('startLocationUpdateBackground'), true);
  assert.equal(appConfig.requiredPrivateInfos.includes('getLocation'), true);
  assert.equal(appConfig.requiredPrivateInfos.includes('getFuzzyLocation'), false);
  assert.equal(appConfig.permission['scope.userFuzzyLocation'], undefined);
  assert.equal(appConfig.requiredPrivateInfos.includes('startLocationUpdate'), true);
  assert.equal(appConfig.requiredPrivateInfos.includes('onLocationChange'), true);
  // 第七轮主包瘦身:隐私政策 / 注销页随各自分包下发,路由不变,查「仍注册」。
  const routes = appConfig.pages.concat((appConfig.subPackages || []).flatMap((pkg) => pkg.pages.map((page) => pkg.root + '/' + page)));
  assert.equal(routes.includes('pages/privacy/index'), true);
  assert.equal(routes.includes('pages/deregister/index'), true);
});

// 第七轮 C 第 8 项:requiredPrivateInfos 只声明源码真实调用的定位接口。choosePoi 自 2026-08-20
// location-manager 换成 chooseLocation 后再无调用,却仍出现在审核的隐私声明里。
test('requiredPrivateInfos declares exactly the location APIs the shipped source calls', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const { collectUsedLocationApis, LOCATION_APIS } = require('../../scripts/wechat-release-contract.js');
  const root = path.resolve(__dirname, '../..');
  const appConfig = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
  const files = [];
  (function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (['node_modules', 'tests', 'scripts', 'docs', 'miniprogram_npm', '.git'].includes(entry.name)) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (/\.(js|wxml)$/.test(entry.name)) files.push({ path: full, content: fs.readFileSync(full, 'utf8') });
    }
  })(root);
  const used = collectUsedLocationApis(files).slice().sort();
  const declared = appConfig.requiredPrivateInfos.filter((api) => LOCATION_APIS.includes(api)).slice().sort();
  assert.equal(declared.includes('choosePoi'), false);
  assert.deepEqual(declared, used);
});
