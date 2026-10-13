/**
 * 商户首页 UI 预览脚本：
 * 1. 注入 user_type=2 避开身份分流守卫
 * 2. reLaunch 到 /pages/merchant/index/index
 * 3. 依次注入 status=0/1/2 三种 mock 数据并截图
 *
 * 用法：node scripts/preview-merchant-home.js
 */

const automator = require('miniprogram-automator');
const path = require('path');

const PROJECT = path.resolve(__dirname, '..');
const CLI = '/Applications/wechatwebdevtools.app/Contents/MacOS/cli';
const OUT = '/tmp';

const mockMerchant = (status, extra = {}) => ({
  id: 9001,
  name: 'LULU 城市探索',
  logo: '',
  status,
  topicNum: 12,
  likeNum: 86,
  fansNum: 245,
  auditRemark: '营业执照图片不清晰，请重新上传',
  ...extra,
});

const mockDashboard = {
  revenue: '1,256.00',
  pendingOrders: 3,
};

const mockJoinList = [
  {
    id: 1,
    topicName: '街角密码',
    picUrl: '/images/route_city_cover.png',
    startDateStr: '周三 5月28日',
    searchStatus: 1,
    mode: 1,
    topicId: 9001,
  },
];

const mockRecruit = [
  {
    id: 1,
    name: '时间邮局',
    imgUrl: '/images/route_free_cover.png',
    startDateStr: '2026.6.1',
    addressName: '上海',
    categoryNames: ['文化叙事'],
  },
];

(async () => {
  const miniProgram = await automator.launch({
    projectPath: PROJECT,
    cliPath: CLI,
  });

  // 1. 注入身份缓存 + globalData，避开商户首页 onLoad 的 redirect 守卫
  await miniProgram.evaluate(() => {
    wx.setStorageSync('user_type', 2);
    wx.setStorageSync('user_id', 1);
    wx.setStorageSync('authorization', 'dev-token');
    wx.setStorageSync('open_id', 'dev-openid');
    const app = getApp();
    if (app && app.globalData) {
      app.globalData.user_type = 2;
      app.globalData.user_id = 1;
      app.globalData.authorization = 'dev-token';
      app.globalData.open_id = 'dev-openid';
    }
  });

  // 2. 跳到商户首页
  await miniProgram.reLaunch('/pages/merchant/index/index');
  await new Promise(r => setTimeout(r, 1000));
  const page = await miniProgram.currentPage();

  // 3. 三种状态：审核中 / 已认证 / 审核未通过
  const states = [
    { status: 0, label: 'pending' },
    { status: 1, label: 'approved' },
    { status: 2, label: 'failed' },
  ];

  for (const s of states) {
    await page.setData({
      merchantInfo: mockMerchant(s.status),
      dashboardData: mockDashboard,
      joinList: mockJoinList,
      recruitList: mockRecruit,
    });
    await new Promise(r => setTimeout(r, 600));
    const out = path.join(OUT, `merchant-home-${s.label}.png`);
    await miniProgram.screenshot({ path: out });
    console.log(`✓ ${s.label} (status=${s.status}) → ${out}`);
  }

  await miniProgram.close();
  console.log('Done.');
})().catch(err => {
  console.error('FAILED:', err.message);
  process.exit(1);
});
