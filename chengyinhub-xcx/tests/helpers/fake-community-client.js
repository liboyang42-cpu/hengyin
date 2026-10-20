'use strict'

// 页面回调单测专用:把社区客户端(utils/community-post-api.js)换成同步透传的假客户端。
// 真客户端会异步发请求、把旧 URL 映射到 /api/v1/community/**、并要求帖文已在客户端缓存里;
// 这些映射/缓存/单飞行为由 community-post-cutover / community-action-ledger 合同测试覆盖。
// 这里只验证页面自己的回调(按 id 回写、找不到不动、不原地改旧对象),所以请求原样交给测试里的 app.sendRequest 记录。
const path = require('node:path')

function installFakeCommunityClient(root) {
  const modulePath = require.resolve(path.join(root, 'utils/community-post-api.js'))
  const real = require(modulePath)
  const fake = Object.assign({}, real, {
    createClient(app) {
      return {
        request(options) { return app.sendRequest(options) },
        captureUpload(data) { return data && data.url },
      }
    },
  })
  require.cache[modulePath] = { id: modulePath, filename: modulePath, loaded: true, exports: fake }
}

module.exports = { installFakeCommunityClient }
