/**
 * R9-26 回归: 后台正文不直接展示原始 Markdown 或实现术语。
 *
 * 背景: 核销异常页出现 **Markdown** / G2 硬门 / 每场必炸, 核销证据页要求理解
 * memberId/registrationId/status 常量, 漫游治理说明直接提 roam_poi 表名, 官方 360
 * 以 THEME_VERIFIED_FINISH/FULFILLMENT_MERCHANT/ACCEPTED 等原始枚举呈现职责与状态。
 * 运营需要的是业务名称、判据和下一步, 技术标识应放进可展开详情而不是正文。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

test('R9-26 核销异常页不再出现原始 Markdown 与内部代号', () => {
  const source = read('src/views/business/verificationReview/index.vue')

  assert.doesNotMatch(source, /\*\*/, '正文不得残留 Markdown 加粗标记')
  assert.doesNotMatch(source, /G2\s*硬门|G2/, '不得直接展示 G2 这类内部闸门代号')
  assert.doesNotMatch(source, /每场必炸/, '不得使用「每场必炸」这类内部口语')
  assert.doesNotMatch(source, /placeholder="topicId"/, '筛选项占位符要用业务名')
  assert.doesNotMatch(source, /placeholder="memberId"/, '筛选项占位符要用业务名')

  assert.match(source, /连扫过密|一分钟内连续核销/, '要用可读的入队原因说明')
  assert.match(source, /核销率偏高|核销率超过设定阈值/, '要用可读的核销率说明')
  assert.match(source, /placeholder="主题编号"/, '主题筛选项用业务名')
  assert.match(source, /placeholder="核销负责人编号"/, '负责人筛选项用业务名')
})

test('R9-26 核销证据页用业务名而不是字段名解释口径', () => {
  const source = read('src/views/business/verifyevidence/index.vue')

  assert.doesNotMatch(source, /不以历史 status 常量推断/, '不得在正文出现 status 常量')
  assert.doesNotMatch(source, /memberId 是核销商家/, '不得用 memberId 解释口径')
  assert.doesNotMatch(source, /商家 memberId|玩家 memberId/, '占位符不得暴露 memberId')
  assert.doesNotMatch(source, /输入 registrationId/, '占位符不得暴露 registrationId')
  assert.doesNotMatch(source, /输入 topicId/, '占位符不得暴露 topicId')
  assert.doesNotMatch(source, /memberId #\{\{/, '表格正文不得直接标 memberId')

  assert.match(source, /核销商家编号|报名玩家编号|商家编号|玩家编号/, '要用业务名替代字段名')
})

test('R9-26 漫游治理说明不再暴露数据库表名', () => {
  const source = read('src/views/business/roampoi/index.vue')
  assert.doesNotMatch(source, /roam_poi/i, '正文不得出现数据库表名 roam_poi')
  assert.match(source, /据点档案|据点资产/, '要用业务对象名替代表名')
})

test('R9-26 官方 360 用业务名称呈现职责与状态, 不裸露原始枚举', () => {
  const source = read('src/views/business/officialevent/index.vue')

  assert.doesNotMatch(source, /\{\{\s*party\.role\s*\}\}/, '责任关系不得直接渲染原始 role 枚举')
  assert.doesNotMatch(source, /\{\{\s*party\.partyType\s*\}\}/, '责任关系不得直接渲染原始 partyType 枚举')
  assert.doesNotMatch(source, /\{\{\s*party\.status\s*\}\}/, '责任关系不得直接渲染原始 status 枚举')
  assert.doesNotMatch(source, /\{\{\s*item\.requiredRole\s*\}\}/, '关键覆盖不得直接渲染原始 requiredRole 枚举')
  assert.doesNotMatch(source, /等待 VALID evidence/, '正文不得出现 VALID evidence')
  assert.doesNotMatch(source, /只读有效 participation/, '正文不得出现 participation')
  assert.doesNotMatch(source, /只计 VALID evidence/, '正文不得出现 VALID evidence')
  assert.doesNotMatch(source, /证据而非 progress/, '正文不得出现 progress')
  assert.doesNotMatch(source, /evidence 和外部履约动作/, '正文不得出现 evidence')
  assert.doesNotMatch(source, /从 PENDING 如实回显/, '正文不得出现 PENDING')
  assert.doesNotMatch(source, /V1 progress|V2 evidence/, '正文不得出现 progress/evidence')
  assert.doesNotMatch(source, /（Gate 0）|\(Gate 0\)/, '不得直接展示 Gate 0 内部代号')

  assert.match(source, /partyRoleText\(party\.role\)/, '职责要走业务名映射')
  assert.match(source, /partyTypeText\(party\.partyType\)/, '主体类型要走业务名映射')
  assert.match(source, /partyStatusText\(party\.status\)/, '关系状态要走业务名映射')
  assert.match(source, /coverageTargetText\(/, '关键覆盖要走业务名映射')
})

test('R9-26 官方 360 任务/绑定/参与/发奖列不再裸露原始码值', () => {
  const source = read('src/views/business/officialevent/index.vue')

  assert.doesNotMatch(source, /prop="missionCode"/, '任务列不得直接以裸字段渲染')
  assert.doesNotMatch(source, /prop="bindingCode"/, '绑定列不得直接以裸字段渲染')
  assert.doesNotMatch(source, /prop="state"/, '参与状态不得直接渲染原始 state')
  assert.doesNotMatch(source, /prop="rewardStatus"/, '奖励投递不得直接渲染原始 rewardStatus')
  assert.doesNotMatch(source, /\{\{\s*activity360\.pauseNotificationStatus\s*\|\|/, '暂停通知状态不得直接渲染原始枚举')

  assert.match(source, /bindingTypeText\(scope\.row\.bindingType\)/, '绑定列要走业务类型映射')
  assert.match(source, /participationStateText\(scope\.row\.state\)/, '参与状态要走业务名映射')
  assert.match(source, /rewardStatusText\(scope\.row\.rewardStatus\)/, '奖励投递要走业务名映射')
  assert.match(source, /pauseNotificationText\(activity360\.pauseNotificationStatus\)/, '暂停通知状态要走业务名映射')
  assert.match(source, /scope\.row\.title/, '任务列要显示任务名称而不是只露编号')
})

test('R9-26 探店日主题详情的现场漏斗不再出现 G2 内部代号与「硬门」', () => {
  const source = read('src/views/business/topic/detail.vue')

  assert.doesNotMatch(source, /现场漏斗与 G2 硬门/, '区块标题要用业务说法')
  assert.doesNotMatch(source, /'G2 判定：通过'|'G2 判定：失败'/, '判定结论不得直接展示 G2')
  assert.doesNotMatch(source, /请检查后端 G2 口径/, '异常兜底文案不得把 G2 抛给运营')
  assert.doesNotMatch(source, /硬门/, '全文不得再出现「硬门」这类内部代号')

  assert.match(source, /现场漏斗与达标线（只读）/, '标题改业务说法')
  assert.match(source, /'达标判定：通过'/, '判定结论改业务说法')
  assert.match(source, /达标要求 ≥95%/, '覆盖率提示改业务说法')
})
