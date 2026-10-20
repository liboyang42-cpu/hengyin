'use strict'

// 圈层主题候选表只在 pages/play 与 pages/topic 两个分包里用。主包已满(3 MiB 发版棘轮),所以不放 utils/,
// 两个分包各留一份;分包之间不能互相 require,这里钉住两份必须逐字一致,防止改一份忘了另一份。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const PLAY = 'pages/play/utils/circle-theme.js'
const TOPIC = 'pages/topic/utils/circle-theme.js'

test('圈层主题候选表两份分包副本逐字一致,且不回到主包', () => {
  const play = fs.readFileSync(path.join(ROOT, PLAY), 'utf8')
  const topic = fs.readFileSync(path.join(ROOT, TOPIC), 'utf8')
  assert.equal(topic, play, TOPIC + ' 必须与 ' + PLAY + ' 逐字一致')
  assert.equal(fs.existsSync(path.join(ROOT, 'utils/circle-theme.js')), false, '候选表不放主包 utils/')
  const a = require(path.join(ROOT, PLAY))
  const b = require(path.join(ROOT, TOPIC))
  assert.deepEqual(Object.keys(b).sort(), Object.keys(a).sort())
  assert.deepEqual(b.PRIMARY_THEME_CODES, a.PRIMARY_THEME_CODES)
})
