// 三段动效 GIF 共 1.42MB —— 留在包里会把主包顶穿微信 2MB 硬限。
// motionSrc 走服务端静态目录(母版在 assets/covers/home-banners/),
// poster 仍是包内 jpg:首屏立刻有图,GIF 慢慢流进来,不会白屏。
const EXPECTED_ROUTES = {
  'home-action-club': '/pages/club/apply/index',
  'home-action-merchant': '/pages/merchant/apply/index',
  'home-action-activity': '/pages/activity/list/index',
};

const HOME_ACTION_BANNERS = [
  {
    id: 'home-action-club',
    title: '成为俱乐部',
    subtitle: '聚集同好，发起属于你们的城市活动',
    route: EXPECTED_ROUTES['home-action-club'],
    motionSrc: 'https://www.chengyinhub.com/prod-api/profile/home-banners/home-banner-club.gif',
    poster: '/images/home-banner-club.jpg',
  },
  {
    id: 'home-action-merchant',
    title: '成为商家',
    subtitle: '把空间、服务和好内容带给更多玩家',
    route: EXPECTED_ROUTES['home-action-merchant'],
    motionSrc: 'https://www.chengyinhub.com/prod-api/profile/home-banners/home-banner-merchant.gif',
    poster: '/images/home-banner-merchant.jpg',
  },
  {
    id: 'home-action-activity',
    title: '和朋友报名活动',
    subtitle: '和朋友一起，发现附近正在发生的有趣体验',
    route: EXPECTED_ROUTES['home-action-activity'],
    motionSrc: 'https://www.chengyinhub.com/prod-api/profile/home-banners/home-banner-friends.gif',
    poster: '/images/home-banner-friends.jpg',
  },
].map(function (item) {
  return Object.assign({}, item, {
    name: item.title,
    _type: 'action',
  });
});

function validateHomeActionBanners(list) {
  if (!Array.isArray(list) || list.length !== HOME_ACTION_BANNERS.length) {
    throw new Error('home-action-banner-count');
  }
  list.forEach(function (item) {
    if (!item || EXPECTED_ROUTES[item.id] !== item.route) {
      throw new Error(item && item.id ? item.id : 'home-action-banner-id');
    }
  });
  return true;
}

// HOME_ACTION_BANNERS 与 EXPECTED_ROUTES 都是本文件里的冻结字面量,拿一个校验另一个
// 在生产路径上永远不可能失败 —— 它只是把测试该做的事搬到了每次调用上。
// 校验保留为导出,由 home-action-banner-contract.test.js 调用;运行时不再跑。
function buildHomeActionBanners() {
  return HOME_ACTION_BANNERS.map(function (item) { return Object.assign({}, item); });
}

function openHomeActionBanner(wxApi, item) {
  // 没有 route 的 Banner 必须在这里就掉头:少了 !item.route 这半句,
  // undefined === undefined 会让它一路走到 navigateTo({ url: undefined })。
  if (!item || !item.route || EXPECTED_ROUTES[item.id] !== item.route) return false;
  if (!wxApi || typeof wxApi.navigateTo !== 'function') return false;
  wxApi.navigateTo({ url: item.route });
  return true;
}

module.exports = {
  buildHomeActionBanners,
  openHomeActionBanner,
  validateHomeActionBanners,
};
