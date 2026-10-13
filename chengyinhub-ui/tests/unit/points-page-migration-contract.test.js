const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const legacyPage = fs.readFileSync(path.join(uiRoot, 'src/views/business/pointsetting/index.vue'), 'utf8')
const pointsPage = fs.readFileSync(path.join(uiRoot, 'src/views/business/points/index.vue'), 'utf8')
const routerSource = fs.readFileSync(path.join(uiRoot, 'src/router/index.js'), 'utf8')

test('旧积分设置入口只兼容跳到规范积分配置页', () => {
  assert.match(legacyPage, /\/config\/points/)
  assert.match(routerSource, /path: '\/config'/)
  assert.match(routerSource, /path: 'points'/)
  assert.match(routerSource, /business:pointsetting:list/)
  assert.doesNotMatch(legacyPage, /business:setting:edit/)
})

test('规范积分配置只承接积分规则，并把分润导向结算规则', () => {
  assert.match(pointsPage, /listSetting\(\{[\s\S]*type:\s*1/)
  assert.match(pointsPage, /business:pointsetting:edit/)
  assert.match(pointsPage, /\/p3-platform\/settlementrule/)
  assert.doesNotMatch(pointsPage, /business:setting:edit/)
  assert.doesNotMatch(pointsPage, /分润比例|分润设置/)
})
