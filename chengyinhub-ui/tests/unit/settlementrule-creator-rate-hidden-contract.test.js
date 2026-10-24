const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const repoRoot = path.resolve(uiRoot, '..')
const page = fs.readFileSync(path.join(uiRoot, 'src/views/business/settlementrule/index.vue'), 'utf8')
const read = (file) => fs.readFileSync(path.join(repoRoot, file), 'utf8')

// owner 2026-10-23 D6(U-161):H8 已取消发布者分润,后台不再给「创作者比例」输入框;
// 列与服务端两道闸(写入拒非 0、入账拒非 0)保持不动。
test('结算规则弹窗不再渲染创作者比例输入', () => {
  const template = page.slice(0, page.indexOf('<script>'))
  assert.doesNotMatch(template, /v-model="form\.creatorRate"/)
  assert.doesNotMatch(template, /<el-form-item[^>]*prop="creatorRate"/)
})

test('新增规则仍提交 creatorRate: 0,编辑沿用行内既有值,服务端闸门仍在', () => {
  assert.match(page, /reset\(\)\s*\{[\s\S]*?creatorRate: 0,/)
  assert.match(page, /handleUpdate\(row\) \{ this\.form = \{ \.\.\.row \}/)
  const ruleService = read('chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SettlementRuleServiceImpl.java')
  assert.match(ruleService, /creatorRate 必须为 0/)
  const entryService = read('chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SettlementEntryServiceImpl.java')
  assert.match(entryService, /creatorRate 必须为 0\(份额全并商家\),拒绝入账/)
})
