'use strict'

/**
 * 漫游页右上角转发:不给 imageUrl 时微信截当前页当封面,漫游地图上就是玩家实时位置。
 * 固定用仓内不含任何位置的插画;足迹卡仍走分享面板(隐私裁剪后的导出图)。
 */
const ROAM_SHARE_COVER = '/images/route_city_cover.png'

function roamMenuShare() {
  return { title: '来城瘾,一起点亮城市', path: '/pages/roam/index', imageUrl: ROAM_SHARE_COVER }
}

module.exports = { ROAM_SHARE_COVER, roamMenuShare }
