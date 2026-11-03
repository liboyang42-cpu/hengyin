'use strict'

// 打磨第 3 轮 §4-10:主题页购买半屏分段下划线 .bseg__u 不再用回弹曲线,时长保留 .4s。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('.bseg__u 用 --cy-ease-out,不回弹', () => {
  const wxss = fs.readFileSync(path.join(__dirname, '../../pages/topic/index/index.wxss'), 'utf8')
  const rule = wxss.match(/\.bseg__u \{[^}]*\}/)[0]
  assert.match(rule, /transition: transform \.4s var\(--cy-ease-out\)/)
  assert.doesNotMatch(rule, /cubic-bezier\(\.34,\s*1\.32/)
})
