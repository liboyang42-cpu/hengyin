// 在已启动的专用 DevTools 自动化实例上验证；不发布内容。
// NODE_PATH=<含 miniprogram-automator 的 node_modules> WX_AUTO_PORT=9546 node scripts/_verify_publish_center_motion.js
// 能力快照为受控测试数据，不代表登录态或线上发布验证。
const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
const automator = require('miniprogram-automator');
require('miniprogram-automator/out/MiniProgram').default.prototype.checkVersion = async () => {};
const out = process.env.SHOT_DIR || '/tmp/publish-center-motion';
(async () => {
  fs.mkdirSync(out, { recursive: true });
  const mp = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${process.env.WX_AUTO_PORT || 9546}` });
  try {
    const page = await mp.reLaunch('/pages/template/index');
    await page.waitFor(1800);
    const bar = await page.$('tabBar');
    assert.ok(bar);
    await mp.screenshot({ path: path.join(out, 'closed.png') });
    await bar.callMethod('openPublishSheet');
    assert.equal((await mp.currentPage()).path, page.path, '发布必须原地展开');
    await page.waitFor(3200); // 等本机未登录时的错误提示结束，保留失败事实。
    const sheet = await bar.$('cy-publish-sheet');
    assert.equal(await sheet.data('_render'), true);
    const actions = await sheet.$$('.ps__quick-action');
    assert.equal(actions.length, 4);
    assert.deepEqual(await Promise.all(actions.map(action => action.text())), ['自由探索', '城市定向', '互动', '返回']);
    await mp.screenshot({ path: path.join(out, 'open.png') });
    await sheet.setData({ pub: { role: 'player', permission: { canSimplePublish: true, canProPublish: true } } });
    for (const mode of [1, 2]) {
      await sheet.callMethod('chooseTopicMode', { currentTarget: { dataset: { mode } } });
      assert.equal(await sheet.data('selectedMode'), mode);
      assert.equal(await sheet.data('level'), 'topicAbility');
      await page.waitFor(600);
      assert.equal(await sheet.$('.ps__panel'), null);
      assert.equal((await sheet.$$('.ps__quick-action')).length, 3);
      await mp.screenshot({ path: path.join(out, `mode-${mode}.png`) });
      await sheet.callMethod('backToCards');
      await page.waitFor(400);
    }
    await (await sheet.$('.ps__quick-action--return')).tap();
    await page.waitFor(300);
    assert.equal(await sheet.data('_render'), false);
    // 重新打开仍然出现三项；检查减动效直接退场。
    await bar.callMethod('openPublishSheet');
    await page.waitFor(400);
    assert.equal(await sheet.data('quickOpen'), true);
    await sheet.setData({ reducedMotion: true });
    await bar.callMethod('closePublishSheet');
    assert.equal(await sheet.data('_render'), false);
    // 实际商家宿主传入白色主题；只注入 UI 身份和能力，不调用发布接口。
    await page.setData({ isMerchant: true, operationScope: 'MERCHANT', canManageMerchantProjects: true, pub: { role: 'merchant', permission: {} }, publishSheetShow: true });
    await page.waitFor(3200); // 等未登录环境的提示自然结束。
    const merchantSheet = await page.$('#template-publish-menu');
    assert.equal(await merchantSheet.data('merchant'), true);
    const shell = await merchantSheet.$('.ps');
    assert.equal(await shell.style('background-color'), 'rgba(0, 0, 0, 0)', '主题不能在遮罩后面铺不透明底色');
    const mask = await merchantSheet.$('.ps__mask');
    assert.equal(await mask.style('background-color'), 'rgba(255, 255, 255, 0.58)');
    assert.equal((await merchantSheet.$$('.ps__quick-action')).length, 4);
    await mp.screenshot({ path: path.join(out, 'merchant-white.png') });
    await mask.tap();
    await page.waitFor(300);
    assert.equal(await merchantSheet.data('_render'), false);
    console.log('PASS: 四项顺序、返回点击、原地展开、两种主题模式、返回、关闭、重开、减动效；受控能力数据。截图：' + out);
  } finally { await mp.disconnect(); }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
