/**
 * 定价页截图 → 桌面
 * 用法：node scripts/preview-pricing-flow.js
 * 2026-09-17:合作方详情页(pages/topic/pricing/partner)按 B-06 退役,本脚本的合作俱乐部/承接商家两段一并删除。
 */
const automator = require('miniprogram-automator');
const path = require('path');
const os = require('os');

const PROJECT = path.resolve(__dirname, '..');
const CLI = '/Applications/wechatwebdevtools.app/Contents/MacOS/cli';
const DESKTOP = path.join(os.homedir(), 'Desktop');

const mockPreviewMulti = {
  lineup: [
    { toType: 'club', toId: 8001, shareMode: 1, shareRate: 30 },
    { toType: 'merchant', toId: 1203, shareMode: 2, fixedFee: 15 },
    { toType: 'merchant', toId: 1208, shareMode: 2, fixedFee: 20 },
    { toType: 'merchant', toId: 1215, shareMode: 0 },
  ],
  platformRate: 0.1,
  sumShareRate: 0.3,
  sumFixedFee: 35,
  leadCostPerHead: 50,
  costC: 85.5,
  priceMin: '98.00',
};

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

const mockMerchantProfile = {
  m: {
    name: 'LULU 咖啡 · 外滩店',
    catNames: '餐饮 · 咖啡',
    chargeText: '收费承接',
    capacity: 40,
    suitActivityTypes: '文化叙事 · 城市漫步',
    availableTime: '周一至周日 10:00-22:00',
    demand: '欢迎俱乐部带团到店体验',
    businessTime: '10:00 - 22:00',
    address: '上海市黄浦区中山东一路 1 号',
    description: '适合文化叙事与城市漫步路线的承接商家，可提供场地与轻食套餐。',
  },
  loaded: true,
};

async function shot(mp, file) {
  const out = path.join(DESKTOP, file);
  await mp.screenshot({ path: out });
  console.log('✓', out);
}

(async () => {
  const mp = await automator.launch({ projectPath: PROJECT, cliPath: CLI });

  await mp.evaluate(() => {
    wx.setStorageSync('authorization', 'dev-token');
    wx.setStorageSync('open_id', 'dev-openid');
  });

  // 1. 定价页
  await mp.reLaunch('/pages/topic/pricing/index?topicId=8001');
  await new Promise((r) => setTimeout(r, 1200));
  let page = await mp.currentPage();
  await page.setData({
    preview: mockPreview,
    guidedFloor: '28.00',
    selfFloor: '18.00',
    leadCost: '500',
    teamSize: '10',
    guidedInventory: '100',
  });
  await new Promise((r) => setTimeout(r, 600));
  await shot(mp, '城瘾-定价开售.png');

  // 1b. 多商家阵容
  await page.setData({
    preview: mockPreviewMulti,
    guidedFloor: '98.00',
    selfFloor: '48.00',
    leadCost: '500',
    teamSize: '10',
    guidedInventory: '100',
  });
  await new Promise((r) => setTimeout(r, 600));
  await shot(mp, '城瘾-定价-多商家阵容.png');

  // 2. 商家完整主页(从「查看完整主页」进入)
  await mp.reLaunch('/pages/merchant/profile/index?id=1203');
  await new Promise((r) => setTimeout(r, 1000));
  page = await mp.currentPage();
  await page.setData(mockMerchantProfile);
  await new Promise((r) => setTimeout(r, 400));
  await shot(mp, '城瘾-商家主页.png');

  await mp.close();
  console.log('\n截图已保存到桌面：');
  console.log('  · 城瘾-定价开售.png');
  console.log('  · 城瘾-定价-多商家阵容.png');
  console.log('  · 城瘾-商家主页.png');
})().catch((err) => {
  console.error('FAILED:', err.message);
  process.exit(1);
});
