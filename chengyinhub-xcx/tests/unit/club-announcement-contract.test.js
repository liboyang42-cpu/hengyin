const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const script = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.js'), 'utf8')
const view = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.wxml'), 'utf8')
const postCard = fs.readFileSync(path.join(ROOT, 'components/cy/post-card/index.wxml'), 'utf8')

// 2026-10-27 P-20 owner:俱乐部发帖改走全站统一的全屏发帖弹窗(cy-post-compose 俱乐部模式),
// 公告开关与创建请求随之搬进组件;判据不变 —— 只有内容管理员能开,普通成员恒为普通动态。
const compose = fs.readFileSync(path.join(ROOT, 'pages/square/components/cy/post-compose/index.js'), 'utf8')
const composeView = fs.readFileSync(path.join(ROOT, 'pages/square/components/cy/post-compose/index.wxml'), 'utf8')

test('内容管理员可发布公告，普通成员的创建请求仍固定为普通动态', () => {
  assert.match(view, /<cy-post-compose[^>]*club-id="\{\{club\.id\}\}"[^>]*can-announce="\{\{canModerateContent\}\}"/)
  assert.match(composeView, /wx:if="\{\{clubId && canAnnounce && !editMode\}\}"[^>]*bindtap="onToggleAnnouncement"/)
  assert.match(compose, /type:\s*that\.data\.canAnnounce && that\.data\.asAnnouncement\s*\?\s*2\s*:\s*0/)
  assert.match(compose, /asAnnouncement:\s*false/)
})

test('帖子流原位展示置顶公告并提供编辑、置顶和历史入口', () => {
  assert.match(script, /noticeBadge:\s*Number\(p\.type\)\s*===\s*2\s*\?\s*\(isPinned\s*\?\s*'公告 · 置顶'\s*:\s*'公告'\)/)
  assert.match(postCard, /post\.noticeBadge[^>]*>\{\{post\.noticeBadge\}\}/)
  assert.match(view, /catchtap="onEditPost"/)
  assert.match(view, /catchtap="onTogglePin"/)
  assert.match(view, /catchtap="onViewPostHistory"/)
})

test('编辑、置顶和历史都调用独立真接口并在写后回读帖子列表', () => {
  assert.match(script, /url:\s*'\/api\/club\/post\/update'/)
  assert.match(script, /url:\s*'\/api\/club\/post\/pin'/)
  assert.match(script, /url:\s*'\/api\/club\/post\/history'/)
  assert.match(script, /onEditPost[\s\S]*?that\.loadPosts\(\)/)
  assert.match(script, /onTogglePin[\s\S]*?that\.loadPosts\(\)/)
})
