// S3b 赞助任务后台:接真实后端路由;标签「广告」不可编辑;计费按钮只在资金开关打开时出现;菜单与权限齐全。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
const readRepo = (p) => fs.readFileSync(path.join(__dirname, '../../..', p), 'utf8')

test('api 文件只用后端已有的 /business/sponsoredQuest 路由', () => {
  const api = read('src/api/business/sponsoredQuest.js')
  const controller = readRepo('chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/SponsoredQuestAdminController.java')
  assert.match(controller, /@RequestMapping\("\/business\/sponsoredQuest"\)/)
  for (const route of ['/list', '/state', '/{id}', '/{id}/status', '/{id}/charges']) {
    assert.ok(controller.includes('"' + route + '"'), 'controller missing ' + route)
  }
  for (const url of ["'/business/sponsoredQuest/list'", "'/business/sponsoredQuest/state'", "'/business/sponsoredQuest'",
    "'/business/sponsoredQuest/' + id", "'/business/sponsoredQuest/' + id + '/status'", "'/business/sponsoredQuest/' + id + '/charges'"]) {
    assert.ok(api.includes(url), 'api missing ' + url)
  }
})

test('页面:标签来自后端且不可编辑;计费表单只在资金开关打开时出现', () => {
  const vue = read('src/views/business/sponsoredquest/index.vue')
  assert.match(vue, /\{\{ s\.row\.label \}\}/)
  const dialog = vue.slice(vue.indexOf('<el-dialog'), vue.indexOf('计费记录" :visible'))
  assert.doesNotMatch(dialog, /v-model="form\.label/)
  assert.match(vue, /<el-form v-else :inline="true" size="small">/)
  assert.match(vue, /v-hasPermi="\['business:sponsoredQuest:charge'\]"/)
  assert.match(vue, /不会改变迷雾地图、地点排序或搜索结果/)
})

test('菜单迁移注册了页面组件与三项权限', () => {
  const sql = readRepo('chengyinhub-admin/sql/migration_20261115_sponsored_quests.sql')
  assert.match(sql, /'business\/sponsoredquest\/index'/)
  for (const perm of ['list', 'edit', 'charge']) assert.match(sql, new RegExp('business:sponsoredQuest:' + perm))
  assert.match(sql, /^-- Rollback/m)
  assert.doesNotMatch(sql, /sys_role_menu/)
})
