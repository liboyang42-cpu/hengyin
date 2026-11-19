'use strict'

// 已知仍由路由页与场景组件各自实现的孪生内容。
// 新条目必须说明为什么暂时保留，以及如何退出白名单。
const KNOWN_SCENE_TWINS = {
  'roam-task-list': {
    reason: '活动列表路由页仍自取数自绘，scene-roam-task-list 另有一份嵌入实现。',
    exitIntent: '路由页改为渲染 cy-scene-roam-task-list 后删除此项。',
  },
  // 2026-11-14 roam-session 退出:分享足迹卡改为真实 map 快照,页面不再有轨迹 wx:for,孪生门禁自然通过。
  // 2026-11-19 PG-033:账户收益页删掉 tx* 资金表单与账户读取后成为薄壳,asset-earnings 退出白名单。
  'qr-ticket': {
    // 2026-09-16:同上,组件从 pages/play 搬进主包后进入门禁视野。
    // 宿主是玩法主页面 pages/play,整页自取数 + 38 处 wx:for,薄壳判据天然不成立,
    // 入场码正文本身已经只有组件一份。
    reason: '正文已收进 cy-scene-qr-ticket 单一真源;宿主 pages/play 是玩法主页,自取数且大量 wx:for,不满足薄壳判据。',
    exitIntent: '薄壳判据能按场景正文判定(而非整页)后,删除此项。',
  },
  'qr-coupon': {
    reason: '优惠券核销码路由页仍自出码自绘，scene-qr-coupon 另有一份嵌入实现。',
    exitIntent: '路由页改为渲染 cy-scene-qr-coupon 后删除此项。',
  },
  'qr-group-code': {
    reason: '团核销码路由页仍自出码自绘，scene-qr-group-code 另有一份嵌入实现。',
    exitIntent: '路由页改为渲染 cy-scene-qr-group-code 后删除此项。',
  },
  // 2026-11-19 PG-091:据点核销码页改为 cy-scene-qr-citynode 薄壳,qr-citynode 退出白名单。
  'roam-stamp-album': {
    reason: '集邮册路由页仍自取数自绘，scene-roam-stamp-album 另有一份嵌入实现。',
    exitIntent: '路由页改为渲染 cy-scene-roam-stamp-album 后删除此项。',
  },
}

module.exports = { KNOWN_SCENE_TWINS }
