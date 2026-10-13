/**
 * 定价页 UI 预览：reLaunch 到 pricing 页 + 注入 mock 数据 + 截图
 * 用法：node scripts/preview-pricing.js [topicId]
 */
const automator = require('miniprogram-automator');
const path = require('path');

const PROJECT = path.resolve(__dirname, '..');
const CLI = '/Applications/wechatwebdevtools.app/Contents/MacOS/cli';
const topicId = process.argv[2] || '8001';
const OUT = path.join(PROJECT, 'scripts', '_preview_pricing.png');

const mockPreview = {
  lineup: [
    { toType: 'club', toId: 8001, shareMode: 1, shareRate: 30 },
    { toType: 'merchant', toId: 1203, shareMode: 2, fixedFee: 15 },
  ],
  platformRate: 0.1,
  sumShareRate: 0.3,
  sumFixedFee: 15,
  leadCostPerHead: 0,
  costC: 25.5,
  priceMin: '28.00',
};

(async () => {
  const miniProgram = await automator.launch({
    projectPath: PROJECT,
    cliPath: CLI,
  });

  await miniProgram.evaluate(() => {
    wx.setStorageSync('authorization', 'dev-token');
    wx.setStorageSync('open_id', 'dev-openid');
    const app = getApp();
    if (app && app.globalData) {
      app.globalData.authorization = 'dev-token';
      app.globalData.open_id = 'dev-openid';
    }
  });

  const url = `/pages/topic/pricing/index?topicId=${topicId}`;
  console.log('→ reLaunch', url);
  await miniProgram.reLaunch(url);
  await new Promise((r) => setTimeout(r, 1200));

  const page = await miniProgram.currentPage();
  const route = page.path || '(unknown)';
  console.log('→ current page:', route);

  await page.setData({
    preview: mockPreview,
    guidedFloor: '28.00',
    selfFloor: '18.00',
    leadCost: '500',
    teamSize: '10',
    guidedInventory: '100',
  });
  await new Promise((r) => setTimeout(r, 800));

  await miniProgram.screenshot({ path: OUT });
  console.log('✓ screenshot →', OUT);
  console.log('\n若模拟器仍显示旧样式，请在开发者工具：');
  console.log('  1. 编译模式 → 添加 → 启动页面选 pages/topic/pricing/index');
  console.log('  2. 启动参数填 topicId=' + topicId);
  console.log('  3. 点「编译」或 Cmd+B 强制刷新');

  await miniProgram.close();
})().catch((err) => {
  console.error('FAILED:', err.message);
  console.error('\n常见原因：');
  console.error('  · 微信开发者工具未开「设置 → 安全 → 服务端口」');
  console.error('  · 项目未在开发者工具中打开');
  process.exit(1);
});
