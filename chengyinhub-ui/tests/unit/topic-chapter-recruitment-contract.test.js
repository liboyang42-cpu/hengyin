'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const source = fs.readFileSync(
  path.resolve(__dirname, '../../src/views/business/topic/detail.vue'), 'utf8')

test('平台从零建章可写 recruitEnabled 与 categoryId', () => {
  assert.match(source, /v-model="chapterForm\.recruitEnabled"/)
  assert.match(source, /v-model="chapterForm\.categoryId"/)
  assert.match(source, /recruitEnabled:\s*this\.chapterForm\.recruitEnabled === 1 \? 1 : 0/)
  assert.match(source, /categoryId:\s*this\.chapterForm\.recruitEnabled === 1/)
  assert.doesNotMatch(source, /payload\.recruitEnabled\s*=\s*0/)
})
