'use strict';
// 走查 v2 W2-15(业主):店铺参谋「经营优化」—— 四张带图卡,讲怎么用城瘾带客流,每张一个动作。
// 每张卡只在当前岗位能做那个动作时出现(权限字段来自 access/me 归一后的 normalizeMerchantAccess),
// 没有权限的卡整张不出,不留点进去才被拒的假入口。权限名按目标页实际守卫取:
//   合作中心无岗位闸(后端按商家身份判) · 成为节点从店铺装修进(canManageCoop)
//   优惠券(canManageCoupons) · AI 店铺角色(canWriteProfile)
const IMG = '/pages/merchant/images/grow/';

const GROW_TIPS = [
  { key: 'coop', title: '承接一条主题路线', sub: '玩家按路线来店打卡', cover: IMG + 'old-movie.jpg', cta: '去看看', permission: '', url: '/pages/merchant/coop-center/index' },
  { key: 'node', title: '把门店设成打卡点', sub: '漫游地图上出现你的店', cover: IMG + 'flavor-tour.jpg', cta: '去设置', permission: 'canManageCoop', url: '/pages/merchant/citynode/index' },
  { key: 'coupon', title: '发一张到店券', sub: '新客到店核销，带回头客', cover: IMG + 'midlife-day.jpg', cta: '去发券', permission: 'canManageCoupons', url: '/subpackageMember/coupon/coupon?scope=MERCHANT' },
  { key: 'npc', title: '让 AI 店铺角色招呼客人', sub: '玩家路过时打招呼、介绍菜单', cover: IMG + 'treasure-hunter.jpg', cta: '去配置', permission: 'canWriteProfile', url: '/pages/merchant/decor/ai-npc/index' },
];

// access 为空(守卫还没放行 / 读失败)时一张都不出:没确认身份就不画动作入口。
function visibleGrowTips(access) {
  if (!access || access.active !== true) return [];
  return GROW_TIPS.filter((tip) => !tip.permission || access[tip.permission] === true)
    .map((tip) => ({ key: tip.key, title: tip.title, sub: tip.sub, cover: tip.cover, cta: tip.cta }));
}

function growTipUrl(key) {
  const tip = GROW_TIPS.find((t) => t.key === key);
  return tip ? tip.url : '';
}

module.exports = { GROW_TIPS, visibleGrowTips, growTipUrl };
