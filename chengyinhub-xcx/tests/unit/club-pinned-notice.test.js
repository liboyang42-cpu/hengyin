const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const n = require('../../pages/club/utils/club-pinned-notice.js')

const mem = () => { const m = {}; return { getStorageSync: (k) => m[k], setStorageSync: (k, v) => { m[k] = v } } }
const post = (id, type, pinned, t) => ({ id, type, isPinned: pinned, createTime: t, contents: '内容' + id })

test('只挑置顶公告,按时间倒序,最多 2 条', () => {
  const posts = [
    post(1, 2, 1, '2026-10-01 10:00:00'), post(2, 2, 1, '2026-10-03 10:00:00'),
    post(3, 2, 1, '2026-10-02 10:00:00'), post(4, 0, 1, '2026-10-09 10:00:00'), post(5, 2, 0, '2026-10-09 10:00:00'),
  ]
  assert.deepEqual(n.pick(posts, 'c1', mem()).map((x) => x.id), [2, 3])
  assert.deepEqual(n.pick(null, 'c1', mem()), [])
})

test('已读按俱乐部本地保存,pick 反映 unread', () => {
  const env = mem()
  const posts = [post(7, 2, true, '2026-10-01 10:00:00')]
  assert.equal(n.pick(posts, 'c1', env)[0].unread, true)
  assert.equal(n.markRead('c1', 7, env), true)
  assert.equal(n.pick(posts, 'c1', env)[0].unread, false)
  assert.equal(n.pick(posts, 'c2', env)[0].unread, true)
})

test('markRead 只留最近 50 个;storage 抛错不崩', () => {
  const env = mem()
  for (let i = 0; i < 60; i++) n.markRead('c', i, env)
  assert.equal(n.loadRead('c', env).length, 50)
  const bad = { getStorageSync() { throw new Error('x') }, setStorageSync() { throw new Error('x') } }
  assert.deepEqual(n.loadRead('c', bad), [])
  assert.equal(n.markRead('c', 1, bad), false)
})

test('页面接入:帖子 tab 顶部挂公告块,loadPosts(demo+接口)与已读后刷新', () => {
  const root = path.join(__dirname, '../..')
  const wxml = fs.readFileSync(path.join(root, 'pages/club/detail/index.wxml'), 'utf8')
  assert.ok(wxml.indexOf("activeTab === 'posts'") < wxml.indexOf('cy-club-pinned-notice'))
  assert.ok(wxml.indexOf('cy-club-pinned-notice') < wxml.indexOf('post-composer-collapsed'))
  const js = fs.readFileSync(path.join(root, 'pages/club/detail/index.js'), 'utf8')
  assert.equal((js.match(/pinnedNotices: clubPinnedNotice\.pick/g) || []).length, 3) // demo + 接口 + 已读后刷新
})
