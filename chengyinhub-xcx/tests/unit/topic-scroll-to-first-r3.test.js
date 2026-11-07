'use strict'

// 打磨第 3 轮 §4-11:主题页回到第一屏 350ms,减弱动态时直接到位。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('scrollToFirst 时长 350ms,减弱动态为 0', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/topic/index/index.js'), 'utf8')
  const body = js.slice(js.indexOf('  scrollToFirst() {'), js.indexOf('  scrollToFirst() {') + 600)
  assert.match(body, /duration: this\.data\.reducedMotion \? 0 : 350,/)
})
