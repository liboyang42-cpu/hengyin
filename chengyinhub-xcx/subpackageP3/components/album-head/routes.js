'use strict'
// 收集册三段 = 三个原路由(album-head 段切换与 album-intro 卡堆共用一份)
const ALBUM_ROUTES = {
  medal: '/subpackageP3/pages/badge-wall/index/index',
  stamp: '/subpackageP3/pages/stamp-album/index/index',
  object: '/subpackageP3/pages/object-cards/index/index',
}
const ALBUM_SEGS = [{ key: 'medal', label: '勋章' }, { key: 'stamp', label: '邮票' }, { key: 'object', label: '藏品' }]
module.exports = { ALBUM_ROUTES, ALBUM_SEGS }
