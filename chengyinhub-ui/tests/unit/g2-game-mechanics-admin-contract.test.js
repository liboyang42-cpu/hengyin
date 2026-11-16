// G2 游戏机制后台:寻宝与试走页面只调后端已有路由;菜单与权限齐全且不授予任何角色;不出现金额。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
const readRepo = (p) => fs.readFileSync(path.join(__dirname, '../../..', p), 'utf8')

test('api 文件只用后端控制器里的路由', () => {
  const api = read('src/api/business/gameMechanics.js')
  const controller = readRepo('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/GameMechanicsAdminController.java')
  for (const route of ['/business/treasureHunt/list', '/business/treasureHunt/{id}', '/business/treasureHunt', '/business/treasureHunt/{id}/{decision}',
    '/business/walkTest/list', '/business/walkTest/{topicId}', '/business/walkTest/{topicId}/waive', '/business/walkTest/{topicId}/unwaive']) {
    assert.ok(controller.includes('"' + route + '"'), 'controller missing ' + route)
  }
  for (const url of ["'/business/treasureHunt/list'", "'/business/treasureHunt/' + id", "'/business/treasureHunt'",
    "'/business/walkTest/list'", "'/business/walkTest/' + topicId", "'/business/walkTest/' + topicId + '/waive'"]) {
    assert.ok(api.includes(url), 'api missing ' + url)
  }
})

test('页面:按权限显示操作;退回与豁免都要写理由;AI 起草有标注', () => {
  const hunt = read('src/views/business/treasurehunt/index.vue')
  assert.match(hunt, /v-hasPermi="\['business:treasureHunt:edit'\]"/)
  assert.match(hunt, /AI 起草/)
  assert.match(hunt, /请写明退回原因/)
  assert.doesNotMatch(hunt, /元|¥|price|amount/)
  const walk = read('src/views/business/walktest/index.vue')
  assert.match(walk, /v-hasPermi="\['business:walkTest:waive'\]"/)
  assert.match(walk, /请写明豁免理由/)
})

test('菜单迁移注册了两个页面与四项权限,不授予角色', () => {
  const sql = readRepo('chengyinhub-admin/sql/migration_20261115_game_mechanics.sql')
  assert.match(sql, /'business\/treasurehunt\/index'/)
  assert.match(sql, /'business\/walktest\/index'/)
  for (const perm of ['treasureHunt:list', 'treasureHunt:edit', 'walkTest:list', 'walkTest:waive']) assert.match(sql, new RegExp('business:' + perm))
  assert.match(sql, /^-- ROLLBACK/m)
  assert.doesNotMatch(sql, /sys_role_menu/)
})
