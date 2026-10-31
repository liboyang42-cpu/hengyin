'use strict'
// 2026-10-30 走查:同一张帖文卡在发现 / 俱乐部 / 我的三处时间写法不一样,统一由卡片按 createTime 算
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const src = fs.readFileSync(path.join(__dirname, '../../components/cy/post-card/index.js'), 'utf8')
const postTimeLabel = new Function(src.slice(src.indexOf('function postTimeLabel'), src.indexOf('Component({')) + 'return postTimeLabel')()

test('帖文卡时间:刚刚 / 分钟 / 小时 / 天 / 今年月日 / 往年带年份', () => {
  const now = new Date(2026, 9, 30, 18, 0, 0).getTime()
  assert.equal(postTimeLabel('2026-10-30 17:59:30', now), '刚刚')
  assert.equal(postTimeLabel('2026-10-30 17:15:00', now), '45 分钟前')
  assert.equal(postTimeLabel('2026-10-30 08:00:00', now), '10 小时前')
  assert.equal(postTimeLabel('2026-10-26 13:53:00', now), '4 天前')
  assert.equal(postTimeLabel('2026-09-30 04:13:00', now), '9月30日')
  assert.equal(postTimeLabel('2025-12-01 09:00:00', now), '2025年12月1日')
  assert.equal(postTimeLabel('', now), '')
})

test('三处页面都只经卡片的 timeLabel 显示时间', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../components/cy/post-card/index.wxml'), 'utf8')
  assert.match(wxml, /<text>\{\{timeLabel\}\}<\/text>/)
  assert.doesNotMatch(wxml, /\{\{post\.formattedCreateTime \|\| post\.timeText\}\}/)
})
