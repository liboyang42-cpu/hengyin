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

  assert.match(componentJs, /showFavorite[\s\S]*emitFavorite/)
  assert.match(componentWxml, /isBookmarked[\s\S]*emitFavorite[\s\S]*star-filled/)
  assert.match(list, /show-favorite[\s\S]*bind:favorite="favoriteClick"/)
  const listJs = read('pages/square/list/index.js')
  const api = read('utils/community-post-api.js')
  assert.match(listJs, /favoriteClick[\s\S]*changePostAction\(item, 'BOOKMARK', item\.isBookmarked\)/)
  assert.match(detailJs, /favoriteClick\(\)[\s\S]*changePostAction\('BOOKMARK', info\.isBookmarked\)/)
  for (const source of [listJs, detailJs]) {
    assert.match(source, /communityClient\(\)\.removePostAction\(options\)/)
    assert.match(source, /communityClient\(\)\.addPostAction\(options\)/)
  }
  assert.match(profileJs, /favoriteClick[\s\S]*performPostAction\(item, 'BOOKMARK'\)/)
  assert.match(profileJs, /communityClient\(\)\.removePostAction\(options\)/)
  assert.match(profileJs, /communityClient\(\)\.addPostAction\(options\)/)
  assert.match(mylikeJs, /communityClient\(\)\.bookmarks\(/)
  assert.match(mylikeJs, /communityClient\(\)\.removePostAction\(\{ postId: item\.id, actionType: 'BOOKMARK'/)
  const add = api.slice(api.indexOf('function addPostAction('), api.indexOf('function removePostAction('))
  const remove = api.slice(api.indexOf('function removePostAction('), api.indexOf('function addComment('))
  assert.match(add, /url: '\/api\/v1\/community\/posts\/'[\s\S]*'\/actions'[\s\S]*method: 'POST'/)
  assert.match(remove, /'\/actions\/'[\s\S]*method: 'DELETE'/)
  assert.match(remove, /actionResult\(result, options\.postId, options\.actionType\)/)
  const resultCheck = api.slice(api.indexOf('function actionResult('), api.indexOf('function addPostAction('))
  assert.match(resultCheck, /view\.validateBundle\(result, postId\)/)
  assert.match(resultCheck, /viewerBookmarked/)
  assert.match(resultCheck, /bookmarkCount/)
  assert.match(resultCheck, /Number\.isSafeInteger/)
  for (const source of [listJs, detailJs, profileJs, mylikeJs]) assert.doesNotMatch(source, /\/api\/creativesquare\/bookmark|favorite_only/)

})

test('转发使用产品箭头且点赞不再播放扩散圆环', () => {
  const componentWxml = read('components/cy/post-card/index.wxml')
  const detailWxml = read('pages/square/detail/index.wxml')
  const detailWxss = read('pages/square/detail/index.wxss')

  assert.match(componentWxml, /aria-label="转发帖文"[\s\S]*icon_share\.png/)
  assert.match(detailWxml, /aria-label="转发帖文"[\s\S]*icon_share\.png/)
  assert.doesNotMatch(detailWxss, /cy-like-ring|is-bursting::after/)
})
