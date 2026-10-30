'use strict'

// dw29 L8(2026-10-29 业主拍板):帖文动作行 = 点赞 / 评论 / 转发 / 分享。
// 覆盖:卡片转发按钮与「某某 转发了」行、动态流转发条目的映射、乐观切换与失败回滚。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const repost = require('../../utils/club-post-repost.js')

function normalize(p) {
  return Object.assign({}, p, {
    memberNickname: p.nickname,
    noticeBadge: Number(p.type) === 2 ? (p.isPinned ? '公告 · 置顶' : '公告') : '',
  })
}

function loadPostCard() {
  let def = null
  vm.runInNewContext(read('components/cy/post-card/index.js'), {
    Component: (d) => { def = d },
    wx: { getStorageSync: () => '' },
    setTimeout, clearTimeout,
  })
  return def
}

function fakePage(listKey, rows) {
  const page = { data: { [listKey]: rows } }
  page.setData = (patch) => { Object.assign(page.data, patch) }
  return page
}

test('卡片:第三个动作是转发(图标 + 计数),收藏不在动作行;分享按钮叫「分享到微信」', () => {
  const wxml = read('components/cy/post-card/index.wxml')
  const actions = wxml.slice(wxml.indexOf('post-card__actions-left'), wxml.indexOf('post-card__share'))
  const like = actions.indexOf('post-card__action--like')
  const comment = actions.indexOf('emitComment')
  const repostAt = actions.indexOf('post-card__action--repost')
  assert.ok(like >= 0 && comment > like && repostAt > comment, '顺序:点赞 → 评论 → 转发')
  // 帖文第三轮:发现页混着社区帖,转发只对俱乐部帖文出
  assert.match(actions, /wx:if="\{\{showRepost && post\.source !== 'community'\}\}"[^>]*catchtap="emitRepost"/)
  assert.match(actions, /post-card__action--repost \{\{post\.viewerReposted \? 'is-on' : ''\}\}/)
  assert.match(actions, /<cy-icon class="post-card__repost-icon" name="repost" size="44" \/><text class="post-card__action-text">\{\{post\.repostCount \|\| ''\}\}<\/text>/)
  assert.doesNotMatch(wxml, /emitFavorite|isBookmarked|star-filled/)
  assert.match(wxml, /open-type="share"[^>]*aria-label="分享到微信"/)
  assert.match(read('components/cy/icon/icons.wxss'), /\.cyi--repost \{/)
})

test('卡片:转发条目在头像上方一行「某某 转发了」,同一张卡,头像随之下移', () => {
  const wxml = read('components/cy/post-card/index.wxml')
  const line = wxml.indexOf('post-card__repost-by')
  assert.ok(line > 0 && line < wxml.indexOf('<view class="post-card__avatar">'))
  assert.match(wxml, /wx:if="\{\{post\.repostBy\}\}">\{\{post\.repostBy\.nick \|\| '城瘾玩家'\}\} 转发了<\/view>/)
  assert.match(wxml, /\{\{post\.repostBy \? 'post-card--repost' : ''\}\}/)
  const wxss = read('components/cy/post-card/index.wxss')
  assert.match(wxss, /\.post-card--repost \.post-card__avatar \{ top: calc\(var\(--cy-space-3\) \+ var\(--cy-space-4\) \+ var\(--cy-space-2\)\); \}/)
})

test('卡片:emitRepost 抛 repost 事件,带原帖 id / 俱乐部;showFavorite 属性已退场', () => {
  const def = loadPostCard()
  assert.ok(def.properties.showRepost)
  assert.equal(def.properties.showFavorite, undefined)
  const events = []
  const ctx = {
    data: { index: 3, post: { id: '9007199254740993', memberId: 7, clubId: 5 } },
    triggerEvent: (name, detail) => events.push([name, detail]),
  }
  ctx.eventDetail = def.methods.eventDetail.bind(ctx)
  def.methods.emitRepost.call(ctx)
  // 组件对象出自 vm 沙箱(别的 realm),按 JSON 比
  assert.equal(JSON.stringify(events), JSON.stringify([['repost', { index: 3, postId: '9007199254740993', memberId: 7, clubId: 5 }]]))
})

test('映射:普通帖文 feedKey=id;转发条目摊平成原帖 + repostBy,置顶不跟过来;残行丢掉', () => {
  const rows = [
    { id: 11, nickname: 'a', type: 0, createTime: '2026-10-29 10:00:00' },
    { itemType: 'repost', repostId: 5, reposterMemberId: 99, reposterNick: '小明', repostTime: '2026-10-29 10:30:00',
      post: { id: 11, nickname: 'a', type: 2, isPinned: 1, clubId: 3, repostCount: 2, viewerReposted: true } },
    { itemType: 'repost', repostId: 6 },
    null,
  ]
  const out = repost.mapClubFeedRows(rows, normalize)
  assert.equal(out.length, 2)
  assert.equal(out[0].feedKey, '11')
  assert.equal(out[0].isRepostItem, undefined)
  assert.equal(out[1].feedKey, 'r5')
  assert.equal(out[1].id, 11, '动作作用在原帖 id 上')
  assert.equal(out[1].isRepostItem, true)
  assert.equal(out[1].isPinned, false)
  assert.equal(out[1].noticeBadge, '公告')
  assert.deepEqual(out[1].repostBy, { memberId: 99, nick: '小明', time: '2026-10-29 10:30:00' })
  assert.equal(out[1].memberNickname, 'a', '原帖仍按页面自己的 normalize 映射')
  assert.notEqual(out[0].feedKey, out[1].feedKey, '同一帖与它的转发条目 key 不能撞')
})

test('applyRepostState:原帖与它的转发条目一起改,别的帖不动', () => {
  const list = [{ id: 1, feedKey: '1' }, { id: 1, feedKey: 'r9' }, { id: 2, feedKey: '2' }]
  const out = repost.applyRepostState(list, '1', true, 4)
  assert.deepEqual(out.map(r => [r.viewerReposted, r.repostCount]), [[true, 4], [true, 4], [undefined, undefined]])
  assert.equal(out[2], list[2])
})

test('切换:先乐观改卡面,成功按服务端回读落定;请求形状是 JSON + 不自动弹错', () => {
  const page = fakePage('posts', [{ id: 1, feedKey: '1', repostCount: 2, viewerReposted: false }, { id: 1, feedKey: 'r3', repostCount: 2, viewerReposted: false }])
  const sent = []
  assert.equal(repost.toggleRepost({ page, listKey: 'posts', postId: 1, app: { sendRequest: (o) => sent.push(o) }, toast: () => assert.fail('no toast') }), true)
  assert.deepEqual(page.data.posts.map(r => [r.viewerReposted, r.repostCount]), [[true, 3], [true, 3]])
  assert.equal(sent.length, 1)
  assert.equal(sent[0].url, '/api/club/post/repost')
  assert.equal(sent[0].method, 'POST')
  assert.equal(sent[0].autoErrorToast, false)
  assert.deepEqual(JSON.parse(sent[0].data), { postId: 1 })
  assert.equal(repost.toggleRepost({ page, listKey: 'posts', postId: 1, app: { sendRequest: (o) => sent.push(o) }, toast: () => {} }), false, '在途不发第二个')
  sent[0].success({ code: 200, data: { reposted: true, repostCount: 7 } })
  assert.deepEqual(page.data.posts.map(r => [r.viewerReposted, r.repostCount]), [[true, 7], [true, 7]])

  repost.toggleRepost({ page, listKey: 'posts', postId: 1, app: { sendRequest: (o) => sent.push(o) }, toast: () => {} })
  assert.equal(sent[1].url, '/api/club/post/repost/undo')
  assert.equal(page.data.posts[0].repostCount, 6)
})

test('切换:业务失败回滚并原样显示后端提示;网络失败回滚;没有 msg 才用兜底文案', () => {
  const page = fakePage('feed', [{ id: 8, repostCount: 1, viewerReposted: true }])
  const toasts = []
  const sent = []
  const go = () => repost.toggleRepost({ page, listKey: 'feed', postId: 8, app: { sendRequest: (o) => sent.push(o) }, toast: (m) => toasts.push(m) })

  go()
  assert.deepEqual([page.data.feed[0].viewerReposted, page.data.feed[0].repostCount], [false, 0])
  sent[0].success({ code: 500, msg: '这个俱乐部只对成员开放' })
  assert.deepEqual([page.data.feed[0].viewerReposted, page.data.feed[0].repostCount], [true, 1])
  assert.equal(toasts.pop(), '这个俱乐部只对成员开放')

  go()
  sent[1].fail()
  assert.deepEqual([page.data.feed[0].viewerReposted, page.data.feed[0].repostCount], [true, 1])
  assert.equal(toasts.pop(), '网络异常，请重试')

  go()
  sent[2].success({ code: 200, data: {} })
  assert.equal(page.data.feed[0].viewerReposted, true)
  assert.equal(toasts.pop(), '转发没成功')
  assert.equal(repost.toggleRepost({ page, listKey: 'feed', postId: 404, app: { sendRequest: () => assert.fail() }, toast: () => {} }), false)
})

test('接入:俱乐部详情 / 俱乐部 tab 能转发,商家合作页只显示转发条目;列表 key 用 feedKey', () => {
  const detail = read('pages/club/detail/index.wxml')
  assert.match(detail, /wx:for="\{\{posts\}\}" wx:key="feedKey"/)
  assert.match(detail, /<cy-post-card[^>]*show-repost[^>]*bind:repost="onRepostPost"/)
  assert.match(detail, /slot="moderation" class="post-moderation" wx:if="\{\{!item\.isRepostItem\}\}"/)
  const detailJs = read('pages/club/detail/index.js')
  assert.match(detailJs, /onRepostPost\(e\)[\s\S]*clubPostRepost\.toggleRepost\(\{ page: this, listKey: 'posts', postId: postId, app: app/)
  assert.equal((detailJs.match(/clubPostRepost\.mapClubFeedRows\(/g) || []).length, 3, '首屏 / 演示 / 续页三处同一映射')

  const talent = read('pages/talent/list/index.wxml')
  assert.match(talent, /<cy-post-card[^>]*wx:key="feedKey"[^>]*show-repost[^>]*bind:repost="onRepostPost"/)
  // 2026-10-29 L2:发现页改全站流(utils/discover-feed.js),只出原帖不混转发条目;转发动作照常可点
  assert.match(read('pages/talent/list/index.js'), /clubPostRepost\.toggleRepost\(\{ page: this, listKey: 'feed'/)

  const relation = read('pages/merchant/relation/index.wxml')
  assert.match(relation, /<cy-post-card[^>]*wx:key="feedKey"/)
  assert.doesNotMatch(relation, /show-repost/)
  assert.match(read('pages/merchant/relation/index.js'), /clubPostRepost\.mapClubFeedRows\(rows,/)
})

test('俱乐部详情分页:转发条目不算页深也不进游标(它的 id 是原帖的)', () => {
  const src = read('pages/club/detail/index.js')
  const start = src.indexOf('function unpinnedClubPosts')
  const end = src.indexOf('function normalizeClubPost')
  const sandbox = {}
  vm.runInNewContext(src.slice(start, end).replace(/^const /gm, 'var ') + '\nthis.unpinnedClubPosts = unpinnedClubPosts; this.clubPostCursor = clubPostCursor;', sandbox)
  const rows = [
    { id: 3, isPinned: 1 },
    { id: 9 },
    { itemType: 'repost', repostId: 1, post: { id: 1 } },
    { id: 1, isRepostItem: true, feedKey: 'r1' },
    { id: 7 },
  ]
  assert.deepEqual(sandbox.unpinnedClubPosts(rows).map(r => r.id), [9, 7])
  assert.equal(sandbox.clubPostCursor(rows), 7)
})
