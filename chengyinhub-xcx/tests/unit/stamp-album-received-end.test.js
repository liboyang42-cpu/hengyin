'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const WXML = fs.readFileSync(path.join(__dirname, '../../components/cy/scene-roam-stamp-album/index.wxml'), 'utf8')

test('BL-49 「收到的」分页读完有到底提示,只在 received tab、有内容时出现', () => {
  const m = WXML.match(/<view class="album-more" wx:elif="\{\{noMore && items\.length && tab === 'received'\}\}"[^>]*>([^<]+)<\/view>/)
  assert.ok(m, 'received 到底提示存在')
  assert.equal(m[1], '收到的邮票都在这里了')
  // 同一 wx:if 链的最后一档:加载中/失败/还有下一页都优先于到底提示
  assert.ok(WXML.indexOf(m[0]) > WXML.indexOf('继续展开下一页'))
})

test('BL-49 「收到的」空态仍在(total === 0 时诚实说明,不给假引导按钮)', () => {
  assert.match(WXML, /还没有收到的邮票/)
})
