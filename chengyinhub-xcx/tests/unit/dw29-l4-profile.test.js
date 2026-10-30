'use strict'
// 2026-10-29 真机走查 lane l4-profile:V-15 头像即时显示 + 全站同步、V-18 藏品卡照片、V-21 个人介绍入口
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const OSS_HTTP = 'http://chengyin-oss.oss-cn-shanghai.aliyuncs.com/u/a.jpg?Signature=x'
const OSS_HTTPS = 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/u/a.jpg?Signature=x'

// 与 app.getImgUrl 同口径的桩:http→https,相对路径补域名,以 / 开头的本地资源原样
function getImgUrl(p) {
  if (!p) return ''
  if (p.indexOf('/') === 0) return p
  if (/^https?:\/\//i.test(p)) return p.replace(/^http:\/\//i, 'https://')
  return 'https://www.chengyinhub.com/prod-api/profile/' + p
}

function fresh(rel) {
  delete require.cache[require.resolve(path.join(ROOT, rel))]
  return require(path.join(ROOT, rel))
}

test('V-15 identitySync.avatarSrc:服务端头像过 getImgUrl;本机临时文件原样;空值给空串', () => {
  const { avatarSrc, isLocalFile } = fresh('utils/identity-sync.js')
  assert.equal(avatarSrc(OSS_HTTP, getImgUrl), OSS_HTTPS)
  assert.equal(avatarSrc('upload/2026/a.jpg', getImgUrl), 'https://www.chengyinhub.com/prod-api/profile/upload/2026/a.jpg')
  assert.equal(avatarSrc('wxfile://tmp_abc.jpg', getImgUrl), 'wxfile://tmp_abc.jpg')
  assert.equal(avatarSrc('http://tmp/abc.jpg', getImgUrl), 'http://tmp/abc.jpg', '开发者工具临时文件不能被改成 https')
  assert.equal(avatarSrc('/images/d_profile.png', getImgUrl), '/images/d_profile.png')
  assert.equal(avatarSrc('', getImgUrl), '')
  assert.equal(avatarSrc(null, getImgUrl), '')
  assert.equal(isLocalFile('https://www.chengyinhub.com/a.jpg'), false)
})

function applyPatch(target, patch) {
  Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.')
    let cursor = target
    for (let i = 0; i < parts.length - 1; i += 1) cursor = cursor[parts[i]]
    cursor[parts.at(-1)] = value
  })
}

function loadProfileEdit(appOverrides) {
  let config
  const requests = []
  const app = Object.assign({
    globalData: {}, getUserID: () => 1, tips() {}, getImgUrl,
    sendRequest(req) { requests.push(req) },
  }, appOverrides || {})
  global.getApp = () => app
  global.Page = (value) => { config = value }
  global.wx = { showToast() {}, showLoading() {}, hideLoading() {}, navigateBack() {} }
  delete require.cache[require.resolve('../../pages/gerenziliao/gerenziliao.js')]
  require('../../pages/gerenziliao/gerenziliao.js')
  const page = Object.assign({}, config)
  page.data = JSON.parse(JSON.stringify(config.data))
  page.setData = (patch, cb) => { applyPatch(page.data, patch); if (cb) cb() }
  return { page, requests, app }
}

function answerUserInfo(requests, data) {
  const req = requests.find((r) => r.url === '/api/user/info')
  req.success({ code: '200', data })
}

test('V-15 资料页:读到的头像走 getImgUrl,不再把 http 签名链接直接塞给 <image>', () => {
  const { page, requests } = loadProfileEdit()
  page.onLoad({})
  answerUserInfo(requests, { name: '小林', avatar: OSS_HTTP })
  assert.equal(page.data.avatarSrc, OSS_HTTPS)
  assert.equal(page.data.userInfo.avatar, OSS_HTTP, '提交给后端的仍是原值')
  assert.match(read('pages/gerenziliao/gerenziliao.wxml'), /<cy-avatar src="\{\{avatarSrc \|\| '\/images\/d_profile\.png'\}\}" size="170" \/>/)
})

test('V-15 资料页:裁完立刻显示本机图,上传成功仍显示本机图并记下服务端地址;失败退回原头像', () => {
  let chooseArgs
  const { page, requests } = loadProfileEdit({ chooseImage(cb, count, options) { chooseArgs = { cb, count, options } } })
  page.onLoad({})
  answerUserInfo(requests, { name: '小林', avatar: OSS_HTTP })
  page.data.avatarReviewText = '头像已更新，图片安全复核中；未通过会被撤下'

  page.uploadPic()
  chooseArgs.options.onUploadStart(['wxfile://tmp_new.jpg'])
  assert.equal(page.data.avatarSrc, 'wxfile://tmp_new.jpg', '上传开始就换上本机图,不留空圈')
  assert.equal(page.data.avatarReviewText, '')
  assert.equal(chooseArgs.options.onUploadDone({ ok: true }), false, '不吞掉 chooseImage 默认处理')
  chooseArgs.cb(['http://chengyin-oss.oss-cn-shanghai.aliyuncs.com/u/new.jpg'])
  assert.equal(page.data.avatarSrc, 'wxfile://tmp_new.jpg')
  assert.equal(page.data.userInfo.avatar, 'http://chengyin-oss.oss-cn-shanghai.aliyuncs.com/u/new.jpg')

  page.uploadPic()
  chooseArgs.options.onUploadStart(['wxfile://tmp_bad.jpg'])
  assert.equal(chooseArgs.options.onUploadDone({ ok: false, failures: [] }), false)
  assert.equal(page.data.avatarSrc, 'wxfile://tmp_new.jpg', '上传失败退回上一张')
})

test('V-15 资料页:保存成功广播新头像(会话单一真源),app.getAvatar 读出来就是 https', () => {
  const js = read('pages/gerenziliao/gerenziliao.js')
  assert.match(js, /identitySync\.publishIdentity\('member', \{ nickname: data\.name, avatar: data\.avatar \}\)/)
  const app = read('app.js')
  assert.match(app, /getAvatar: function \(\) \{ return avatarSrc\(this\.getSession\(\)\.getAvatar\(\), this\.getImgUrl\.bind\(this\)\); \}/)
})

test('V-15 全站同步:我的/主页头、首页问候、俱乐部成员、帖文头像都过 https 口径', () => {
  const profileJs = read('subpackageMember/components/cy/profile/index.js')
  assert.match(profileJs, /id\.avatar = avatarSrc\(id\.avatar, app\.getImgUrl && app\.getImgUrl\.bind\(app\)\);/)
  const profileWxml = read('subpackageMember/components/cy/profile/index.wxml')
  // 帖文第三轮:我的帖文换成同一张 cy-post-card(卡里 img.first(post.memberAvatar)),缺头像时读取处补主页头的 _avatarSrc
  assert.match(profileWxml, /<cy-post-card wx:for="\{\{list\}\}"/)
  assert.match(read('components/cy/post-card/index.wxml'), /src="\{\{img\.first\(post\.memberAvatar\) \|\| '\/images\/d_profile\.png'\}\}"/)
  assert.match(profileJs, /if \(!row\.memberAvatar\) row\.memberAvatar = that\.data\._avatarSrc;/)

  const home = read('pages/index/index.js')
  assert.match(home, /this\._offIdentity = identitySync\.onIdentityChange\(/)
  assert.match(home, /if \(this\._offIdentity\) \{ this\._offIdentity\(\); this\._offIdentity = null; \}/)
  assert.match(home, /avatar: avatarSrc\(res\.data\.avatar, app\.getImgUrl && app\.getImgUrl\.bind\(app\)\)/)

  const club = read('pages/club/detail/index.js')
  assert.match(club, /m && m\.avatar && app\.getImgUrl \? Object\.assign\(\{\}, m, \{ avatar: app\.getImgUrl\(m\.avatar\) \}\) : m/)
  assert.match(club, /memberStrip: buildMemberStrip\(members,/)

  const card = read('components/cy/post-card/index.wxml')
  assert.equal((card.match(/src="\{\{img\.first\(post\.memberAvatar\) \|\| '\/images\/d_profile\.png'\}\}"/g) || []).length, 2)
})

test('V-15 首页:收到会员广播立即换问候区头像(https)与昵称', () => {
  // 只跑监听回调本身:首页 onLoad 依赖太多,这里抽出同一段逻辑的行为由 identity-sync 驱动
  global.getApp = () => ({ globalData: {} })
  const sync = fresh('utils/identity-sync.js')
  sync._resetForTest()
  const { avatarSrc } = sync
  const data = { userInfo: { avatar: 'old', nickname: '旧' } }
  const off = sync.onIdentityChange((evt) => {
    if (evt.scope !== 'member') return
    const patch = {}
    if (evt.patch.avatar) patch['userInfo.avatar'] = avatarSrc(evt.patch.avatar, getImgUrl)
    if (evt.patch.nickname) patch['userInfo.nickname'] = evt.patch.nickname
    applyPatch(data, patch)
  })
  sync.publishIdentity('member', { avatar: OSS_HTTP, nickname: '新' })
  off()
  assert.deepEqual(data.userInfo, { avatar: OSS_HTTPS, nickname: '新' })
})

test('V-18 藏品卡:frames / sourceUrl / cutoutUrl 走 getImgUrl,http 签名链接升 https', () => {
  global.getApp = () => ({ getImgUrl })
  const { toCard } = fresh('utils/object-card.js')
  const card = toCard({ id: 1, title: '实体β', sourceUrl: OSS_HTTP, cutoutUrl: null, frames: [OSS_HTTP] })
  assert.deepEqual(card.frames, [OSS_HTTPS])
  assert.equal(card.sourceUrl, OSS_HTTPS)
  assert.equal(card.cutoutUrl, null)
  const legacy = toCard({ id: 2, title: 'x', sourceUrl: OSS_HTTP })
  assert.deepEqual(legacy.frames, [OSS_HTTPS], '旧后端没带 frames 时退回升过级的原照片')
  const cut = toCard({ id: 3, title: 'y', sourceUrl: OSS_HTTP, cutoutUrl: 'http://www.chengyinhub.com/c.png', frames: [] })
  assert.equal(cut.cutoutUrl, 'https://www.chengyinhub.com/c.png')
  // 列表页 d.list.map(toCard) 会传下标,不能影响结果
  assert.deepEqual([{ id: 4, title: 'z', sourceUrl: OSS_HTTP }].map(toCard)[0].frames, [OSS_HTTPS])
})

test('V-21 个人介绍:资料页有「个人介绍」一行(同一 introduction 字段),「关于」的编辑直达并自动打开', () => {
  const wxml = read('pages/gerenziliao/gerenziliao.wxml')
  assert.match(wxml, /<view class="shzlbox_li_left_name">个人介绍<\/view>/)
  assert.match(wxml, /bindtap="openPop" aria-role="button" aria-label="编辑个人介绍"/)
  assert.match(wxml, /show="\{\{ popDavid \}\}" title="个人介绍"/)
  assert.doesNotMatch(wxml, /城市签名/)

  const profileJs = read('subpackageMember/components/cy/profile/index.js')
  assert.match(profileJs, /goEditIntro: function \(\) \{[^\n]*url: '\/pages\/gerenziliao\/gerenziliao\?field=introduction'/)

  const { page, requests } = loadProfileEdit()
  page.onLoad({ field: 'introduction' })
  assert.equal(page.data.popDavid, false, '资料没读到前不开面板')
  answerUserInfo(requests, { name: '小林', introduction: '周末城市漫游' })
  assert.equal(page.data.popDavid, true)
  assert.equal(page.data.tempIntroduction, '周末城市漫游')
  page.onInputChange({ currentTarget: { dataset: { field: 'introduction' } }, detail: { value: '新的介绍' } })
  page.confirm()
  assert.equal(page.data.userInfo.introduction, '新的介绍')

  const plain = loadProfileEdit()
  plain.page.onLoad({})
  answerUserInfo(plain.requests, { name: '小林' })
  assert.equal(plain.page.data.popDavid, false, '从设置进来不自动弹')
})
