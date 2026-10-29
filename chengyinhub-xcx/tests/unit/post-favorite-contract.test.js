'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')

test('帖文收藏在列表、详情、个人主页和我的收藏中共用同一接口', () => {
  const componentJs = read('components/cy/post-card/index.js')
  const componentWxml = read('components/cy/post-card/index.wxml')
  const list = read('pages/square/list/index.wxml')
  const detailJs = read('pages/square/detail/index.js')
  const profileJs = read('subpackageMember/components/cy/profile/index.js')
  const mylikeJs = read('pages/mylike/mylike.js')

  // 2026-10-29 业主:动作行第三位换成转发,收藏挪进「···」菜单(行为与接口不变)
  assert.doesNotMatch(componentJs, /showFavorite|emitFavorite/)
  assert.doesNotMatch(componentWxml, /emitFavorite|isBookmarked/)
  assert.doesNotMatch(list, /show-favorite|bind:favorite=/)
  assert.match(list, /<cy-post-actions[^>]*\sbookmarkable\s[^>]*bookmarked="\{\{currentActionBookmarked\}\}"/)
  assert.match(read('pages/square/list/index.js'), /detail\.action === 'bookmark'[\s\S]*this\.favoriteClick\(/)
  assert.match(read('pages/mylike/mylike.wxml'), /show-more[^>]*bind:more="onPostMore"/)
  assert.match(detailJs, /favoriteClick\(\)[\s\S]*\/api\/creativesquare\/bookmark/)
  assert.match(profileJs, /favoriteClick[\s\S]*\/api\/creativesquare\/bookmark/)
  assert.match(mylikeJs, /favorite_only:\s*1[\s\S]*\/api\/creativesquare\/bookmark/)
})

test('分享使用产品箭头且点赞不再播放扩散圆环', () => {
  const componentWxml = read('components/cy/post-card/index.wxml')
  const detailWxml = read('pages/square/detail/index.wxml')
  const detailWxss = read('pages/square/detail/index.wxss')

  // 卡片里「转发」现在是站内转发;分享到微信的按钮改叫「分享到微信」,图标不变
  assert.match(componentWxml, /aria-label="分享到微信"[\s\S]*icon_share\.png/)
  assert.match(detailWxml, /aria-label="转发帖文"[\s\S]*icon_share\.png/)
  assert.doesNotMatch(detailWxss, /cy-like-ring|is-bursting::after/)
})
