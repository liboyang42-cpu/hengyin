const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const legacyPage = fs.readFileSync(path.join(uiRoot, 'src/views/business/userpermission/index.vue'), 'utf8')
const canonicalPage = fs.readFileSync(path.join(uiRoot, 'src/views/business/config/role-quota/index.vue'), 'utf8')
const routerSource = fs.readFileSync(path.join(uiRoot, 'src/router/index.js'), 'utf8')

test('旧角色配额入口只兼容跳到规范配置页', () => {
  assert.match(legacyPage, /path:\s*'\/config\/role-quota'/)
  assert.match(legacyPage, /query:\s*this\.\$route\.query/)
  assert.doesNotMatch(legacyPage, /listPermission|updatePermission/)
})

test('规范角色配额路由保持原读取权限并注册在配置域', () => {
  assert.match(routerSource, /path:\s*'\/config\/role-quota'/)
  assert.match(routerSource, /business:userpermission:list/)
  assert.match(routerSource, /roleQuotaConfig/)
})

test('角色配额页只使用既有角色级读写合同，并保留失败态', () => {
  assert.match(canonicalPage, /import \{ listPermission, updatePermission \} from '@\/api\/business\/userpermission'/)
  assert.match(canonicalPage, /<cy-resource-state/)
  assert.match(canonicalPage, /loadError/)
  assert.match(canonicalPage, /updatePermission\(this\.toUpdatePayload\(this\.editForm\)\)/)
  assert.match(canonicalPage, /v-hasPermi="\['business:userpermission:edit'\]"/)
  assert.doesNotMatch(canonicalPage, /updatePermission\(row\)/)
})

test('页面明确角色级配置边界，不伪造当前使用量', () => {
  assert.match(canonicalPage, /当前只提供角色级配置，不包含当前使用量与配置版本/)
  assert.match(canonicalPage, /toUpdatePayload\(form\)/)
  assert.match(canonicalPage, /id:\s*form\.id/)
  assert.match(canonicalPage, /maxThemes:\s*form\.maxThemes/)
  assert.match(canonicalPage, /canPublishCoupon:\s*form\.canPublishCoupon/)
  assert.doesNotMatch(canonicalPage, /role:\s*form\.role/)
})

test('列表归一化保留组件上下文，空配额不会被改写成零', () => {
  assert.match(canonicalPage, /\.map\(row => this\.normalizeRow\(row\)\)/)
  assert.match(canonicalPage, /quotaFormValue\(value\)/)
  assert.match(canonicalPage, /value == null \|\| value === '' \? null : Number\(value\)/)
  assert.doesNotMatch(canonicalPage, /Number\(row\.maxThemes \|\| 0\)/)
  assert.doesNotMatch(canonicalPage, /Number\(row\.maxEvents \|\| 0\)/)
  assert.doesNotMatch(canonicalPage, /Number\(row\.maxCoupons \|\| 0\)/)
})

test('空配额按服务端语义明确展示为不限', () => {
  assert.match(canonicalPage, /return value == null \? '不限' : `\$\{value\} 个`/)
  assert.doesNotMatch(canonicalPage, /return value == null \? '未配置'/)
})
