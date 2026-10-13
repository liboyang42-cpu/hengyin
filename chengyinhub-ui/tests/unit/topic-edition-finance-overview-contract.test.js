'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const detail = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/topic/detail.vue'), 'utf8')
const api = fs.readFileSync(path.resolve(__dirname, '../../src/api/business/topic.js'), 'utf8')
const repoRoot = path.resolve(__dirname, '../../..')
const controllerOf = name => fs.readFileSync(
  path.resolve(repoRoot, 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business', name), 'utf8')

/** Controller 真正读得到的请求体键名（`body.get("x")` / `text(body, "x")` / `longOf(body, "x")` …）。 */
function bodyKeysRead (java) {
  const keys = new Set()
  for (const m of java.matchAll(/body(?:\.get\(|,\s*)"([A-Za-z0-9_]+)"/g)) keys.add(m[1])
  return keys
}

test('期级成本工时 CM N_BE 共用对象 scope 后台入口且 N_BE 只展示', () => {
  assert.match(api, /topic-edition-funds\/\$\{id\}\/overview/)
  assert.match(api, /topic-edition-funds\/\$\{id\}\/cost/)
  assert.match(detail, /平台八项成本/)
  assert.match(detail, /贡献Margin|CM/)
  assert.match(detail, /financeOverview\.snapshotKind/)
  assert.match(detail, /最终快照|暂结快照/)
  assert.match(detail, /只展示，不作开售闸/)
})

// ★后端已把整条 mutable 工时写链删除（Controller/接口/impl/mapper/XML 五个点）。
// 这条合同测试原本还在断言那个死路由存在 —— 一条绿着的、钉住已死契约的测试，
// 它当时的作用是阻止任何人把前端改对。现在反转成：死路由不得再出现，两个窄动作必须接上。
test('旧的可变工时写入口已彻底消失，不许任何形式回到前端', () => {
  assert.doesNotMatch(api, /topic-edition-funds\/\$\{id\}\/hours/,
    '旧 PUT /business/topic-edition-funds/{id}/hours 已被后端删除，点下去必 404')
  assert.doesNotMatch(api, /saveTopicEditionHours/, '旧的 api 函数必须整条删掉，不是保留后不用')
  assert.doesNotMatch(detail, /saveTopicEditionHours/)
  // 请求体自证 confirmStatus='CONFIRMED' 正是旧后门的形状，前端不得再提供这个写入口。
  // ⚠️ 只禁「表单绑定」，不禁只读表格里那一列 —— 后者是展示，不是入口。
  assert.doesNotMatch(detail, /(hoursForm|confirmHoursForm)\.confirmStatus/,
    '确认状态由后端 CAS 决定（旧状态必须是 REPORTED），前端表单不得再传')
  assert.doesNotMatch(detail, /v-model="hoursForm\.reportedActualHours"/,
    '自报工时是俱乐部侧的动作，后台不得代填')
  assert.match(detail, /<el-table-column prop="confirmStatus"/,
    '只读表格里的确认状态列是展示，应当保留')
})

// 2026-08-25 产品裁决：实际工时（自报 → 后台确认 → 更正）与 EXTRA 整条退役，
// 理由是线下无法核实，而 EXTRA 是 ¥40／人小时 —— 自报即计酬是一条资损通道。
// 这条契约从「两个窄动作都要在」反转成「计划工时留下、实际工时的入口必须消失」。
test('计划工时保留（F 的金额来源），实际工时确认的后台入口必须已摘除', () => {
  // ⚠️ 右边界是 load-bearing：不带 ` 的话 /hours\/plan/ 会被 `hours/planned` 这类超串命中，
  // 「把端点改成 plan 的超串」对这条断言隐形（与 feedback-word-boundary-false-green 同族）。
  assert.match(api, /club-compensation\/\$\{id\}\/hours\/plan`/)
  assert.match(api, /planTopicEditionHours/)
  assert.match(detail, /planTopicEditionHours\(this\.topicDetail\.id, this\.hoursForm\)/)

  // 留着按钮 = 给运营一个点了必然报错的入口（后端 rejectRetiredHoursWrite 直接拒）。
  assert.doesNotMatch(api, /export function confirmTopicEditionHours/)
  assert.doesNotMatch(detail, /confirmTopicEditionHours\(/)
  assert.doesNotMatch(detail, /v-hasPermi="\['business:clubhours:confirm'\]"/)
})

// 退役必须是可被判红的：这几个入口一旦有人加回来，后端会用
// EDITION_ACTUAL_HOURS_RETIRED 拒掉，运营点了只会看到 500。
test('EXTRA（额外工时）的后台入口不得重新出现', () => {
  assert.doesNotMatch(api, /extra\/preapprove/, 'EXTRA 预批端点已退役')
  assert.doesNotMatch(api, /extra\/correct/, 'EXTRA 更正端点已退役')
  assert.doesNotMatch(detail, /preapproveClubExtraHours|correctClubExtraHours/)
  assert.doesNotMatch(detail, /v-hasPermi="\['business:clubextra:approve'\]"/)
})

test('negative control：把 EXTRA 端点写回 api 层，退役契约必须判红', () => {
  const revived = api + '\nexport function x(t) { return request({ url: `/business/club-compensation/${t}/extra/preapprove` }) }\n'
  assert.throws(() => assert.doesNotMatch(revived, /extra\/preapprove/), assert.AssertionError)
})

// ★A4 是硬开售闸：没有一版已审核的对公收款人，探店日期次在控制台上根本开不了售。
// 这两条路（写资料 / 财务审核）此前后台零入口 —— 那不是「体验缺失」，是主链路断开。
test('四个 A 的十六个新端点在后台都有入口，且各挂自己的专用权限', () => {
  const routes = [
    /club-payee\/\$\{clubId\}`/,
    /club-payee\/\$\{clubId\}\/verify\/\$\{profileId\}`/,
    /club-payee\/\$\{clubId\}\/payout-detail`/,
    // 2026-08-25：extra/preapprove 与 extra/correct 随 EXTRA 整条退役而删除，
    // 十六个端点变十四个；下面另有一条契约盯着它们不许回来。
    /club-compensation\/\$\{topicId\}\/quality\/confirm`/,
    /club-compensation\/\$\{topicId\}\/quality\/exclude`/,
    /edition-final\/\$\{topicId\}`/,
    /edition-final\/\$\{topicId\}\/payable\/\$\{payableId\}\/reverse`/,
    /edition-final\/\$\{topicId\}\/reaccrue`/,
    /edition-final\/\$\{topicId\}\/batch`/,
    /edition-final\/\$\{topicId\}\/batch\/\$\{batchId\}\/confirm`/,
    /edition-final\/\$\{topicId\}\/batch\/\$\{batchId\}\/paid`/,
    /edition-final\/\$\{topicId\}\/batch\/\$\{batchId\}\/invoice`/,
    /edition-final\/\$\{topicId\}\/batch\/\$\{batchId\}\/invoice\/red`/
  ]
  for (const route of routes) assert.match(api, route)

  const permissions = [
    // business:clubextra:approve 已随 EXTRA 退役从按钮上摘除（权限点本身留在库里不清）。
    'business:clubpayee:verify', 'business:clubpayee:payout',
    'business:clubquality:confirm', 'business:clubquality:exclude', 'business:editionfinal:execute',
    'business:clubpayable:confirm', 'business:clubpayable:paid'
  ]
  for (const permission of permissions) {
    assert.match(detail, new RegExp(`v-hasPermi="\\['${permission.replace(/:/g, ':')}'\\]"`),
      `${permission} 必须有自己的按钮，否则这个动作在后台不可达`)
  }

  // 批次三个动作走的是带 topicId 的路径：它们动的是和 FINAL/红冲同一批钱，
  // 必须过同一道对象级 scope（一期一批次一张发票）。
  assert.doesNotMatch(api, /url: `\/business\/edition-final\/batch`/,
    '不带期次的建批次路由会把两期两份合同的钱并进同一张发票')
})

// ★路由对 ≠ 它能成功。上一版契约只断言路由字符串与权限字符串，于是「财务审核收款人」按钮
// 发的是 observedLicenseHash、Controller 读的是 licenseHash —— 恒 null ⇒ isBlank ⇒ 无条件抛
// PAYEE_LICENSE_HASH_MISMATCH ⇒ verify_status 永远停在 PENDING ⇒ A4 硬开售闸永远满足不了，
// 而这条测试一路绿着。请求体键名必须两边对齐，且这条断言要能因任一侧改名而变红。
test('后台按钮发出去的请求体键名必须是后端真读得到的那些', () => {
  const payeeKeys = bodyKeysRead(controllerOf('ClubCorporatePayeeController.java'))
  assert.ok(payeeKeys.has('pass') && payeeKeys.has('licenseHash'),
    `ClubCorporatePayeeController 读的键是 ${[...payeeKeys].join(',')}；契约按它对齐`)

  const verifyCall = detail.match(/verifyClubPayee\([^)]*\{([^}]*)\}/)
  assert.ok(verifyCall, 'detail.vue 必须有唯一的 verifyClubPayee 调用')
  // 只取属性名位置（`{` 或 `,` 之后）的标识符，别把三元运算符的 `:` 当成键名。
  const sentKeys = [...verifyCall[1].matchAll(/(?:^|,)\s*([A-Za-z0-9_]+)\s*:/g)].map(m => m[1])
  assert.ok(sentKeys.length > 0, 'verifyClubPayee 必须带请求体')
  for (const key of sentKeys) {
    assert.ok(payeeKeys.has(key),
      `detail.vue 发的 "${key}" 后端根本不读 —— 按钮会恒失败（Controller 读: ${[...payeeKeys].join(',')}）`)
  }

  // 批次四个动作的请求体同理：付款凭证号 / 发票号 / 红字发票号一个都不能改名。
  const finalKeys = bodyKeysRead(controllerOf('TopicEditionFinalizationController.java'))
  for (const key of ['paymentReference', 'invoiceNo', 'redInvoiceNo', 'reason', 'clubId']) {
    assert.ok(finalKeys.has(key), `TopicEditionFinalizationController 必须仍然读 ${key}`)
    assert.match(detail, new RegExp(`\\{\\s*${key}:`), `detail.vue 必须仍然发 ${key}`)
  }
})

// ★两个 Q 写动作的理由必须一路走到后端。
// 一次确认不可逆（CAS 旧状态写死 PENDING），过错排除更重 —— 它是全仓唯一能推翻一个已确认 Q
// 决定的动作，方向是**加钱**（每维 +200，一期最多 +600），触发条件只是操作者自行断言归责方。
// 本仓 LogAspect 不写请求体（setOperParam/setJsonResult 全仓 0 命中），不落 reason 列，
// 「是哪一笔事故、依据什么定的责」就只存在于操作者的记忆里。
test('质量确认与过错排除都必须把理由发给后端，并且后端真读得到它', () => {
  const compensationKeys = bodyKeysRead(controllerOf('ClubEditionCompensationController.java'))
  assert.ok(compensationKeys.has('reason'),
    `ClubEditionCompensationController 必须读 reason（现读：${[...compensationKeys].join(',')}）`)

  for (const method of ['confirmQuality', 'excludeQuality']) {
    const block = methodBlock(method)
    // 断言的是「请求体里带了 reason 这个键」，不是「文件里出现过 reason 这个词」。
    assert.match(block, /\{\s*\.\.\.form,\s*reason:/,
      `detail.vue 的 ${method} 必须把理由放进请求体，否则后端只能拒（EDITION_QUALITY_REASON_REQUIRED）`)
    // 空理由后端一律拒，前端要在发出去之前就挡住，别让人填完一路再吃 500。
    assert.match(block, /inputPattern:\s*\/\\S\//,
      `${method} 的理由输入必须非空校验，空理由后端本来就会拒`)
  }

  // 读得回来：这一列是这两个不可逆动作唯一留得下来的判据，操作面上必须能看到。
  assert.match(detail, /prop="reason"/,
    '质量决定表必须把理由渲染出来，只写不读等于没有')
})

/** 取出 detail.vue 里某个 methods 方法的源码块（缩进 4 空格的 `name() {` … `\n    },`）。 */
function methodBlock (name) {
  const block = detail.match(new RegExp(`\\n    ${name}\\s*\\([^)]*\\)\\s*\\{[\\s\\S]*?\\n    \\},`))
  assert.ok(block, `detail.vue 里找不到方法 ${name}`)
  return block[0]
}

// ★「质量奖励一次确认」是不可逆的钱：confirmQualityCas 的旧状态写死 PENDING，落成 CONFIRMED 就改不回来。
// Element UI 的 MessageBox 在未开 distinguishCancelAndClose 时，取消按钮 / 右上角 × / ESC / 点遮罩
// 四种动作都以 'cancel' reject；`.then(() => true, () => false)` 把这四种一视同仁地映射成
// pass=false 并立即发请求 ⇒ 误按一次 ESC = 该维永久 −¥200，三维全中 = 一期 −¥600。
// 后端的 requireBoolean 刚把「JSON 字符串 true 静默变 false」堵死，前端不得从另一个方向把它引回来。
test('质量奖励一次确认：关闭 / ESC 是中止，只有显式「判不过」才发 pass=false', () => {
  const block = methodBlock('confirmQuality')
  assert.match(block, /distinguishCancelAndClose:\s*true/,
    '不区分 cancel 与 close，右上角 × 就会和「判不过 0」走同一条 reject')
  assert.match(block, /closeOnPressEscape:\s*false/, 'ESC 不得直接触发这个不可逆决定')
  assert.match(block, /closeOnClickModal:\s*false/, '点遮罩不得直接触发这个不可逆决定')
  assert.doesNotMatch(block, /\.then\(\s*\(\)\s*=>\s*true\s*,\s*\(\)\s*=>\s*false\s*\)/,
    '这个形状把「中止」和「否决」混成一个值，正是本条缺陷本体')
  assert.match(block, /reason\s*===\s*'cancel'/,
    '只有 reason === cancel（显式点了「判不过 0」）才允许发 pass=false，其余一律中止')
})

// ★A4 的财务审核是本仓**唯一**的一次审核（刻意不设 maker/checker，理由在
// ClubCorporatePayeeServiceImpl.verify）。service 支持 pass=false → VERIFY_REJECTED，
// 而后台只发得出 pass:true 的话，资料有问题时审核人只能「不动」⇒ profile 永久 PENDING ⇒
// 该俱乐部承接的期次开不了售，失败面上还看不出是「被拒」还是「没人审」。
// ⚠️ 驳回同样是三态：中止 ≠ 驳回；且驳回不该要求填执照哈希（service 只在 pass 时比对）。
test('财务审核收款人：通过与驳回都发得出，取消是中止不是驳回', () => {
  const block = methodBlock('verifyPayee')
  assert.match(block, /pass:\s*pass\b/,
    'pass 不得硬编码 true —— 驳回分支在后台必须真的存在')
  assert.match(block, /distinguishCancelAndClose:\s*true/, '中止与驳回必须分得开')
  assert.match(block, /reason\s*===\s*'cancel'/, '只有显式点了「驳回」才发 pass=false')
  assert.match(block, /pass\s*\?[^\n]*licenseHash|licenseHash:\s*pass\s*\?/,
    '驳回不该被执照哈希卡住：service 只在 pass=true 时比对它')
  // 补驳回入口不等于恢复两人制：service 侧那条「录入人 ≠ 审核人」的比较是**刻意没有**的
  // （单超管环境下加上就所有 profile 永不过审）。这一条钉住它没被顺手加回来。
  const payeeService = fs.readFileSync(path.resolve(repoRoot,
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ClubCorporatePayeeServiceImpl.java'),
  'utf8')
  assert.doesNotMatch(payeeService, /getCreatedBy\(\)[^\n]*(equals|==)[^\n]*financeUserId|financeUserId[^\n]*equals\([^\n]*getCreatedBy/,
    'A4 是一次财务审核，不设 maker/checker（E3：宁可明确没有，不要假装有）')
})

// ★16 个端点全是写动作、零只读出口时，红冲 / 更正 / 批次四个动作都要求操作者先连生产库把 id 捞出来
// ——「后台是这套动作的唯一操作面」这句话就不成立了；叠加「批次不绑期」就是「输错一个数字把钱记到别的期」。
test('资金动作的 id 有来源：只读操作面接上了，建批次也回显 id', () => {
  assert.match(api, /getTopicEditionLedger/)
  assert.match(api, /url: `\/business\/edition-final\/\$\{topicId\}`, method: 'get'/,
    '只读操作面必须是 GET，不能靠写端点的返回值凑')
  assert.match(detail, /getTopicEditionLedger\(this\.topicDetail\.id\)/)
  assert.match(detail, /查本期资金明细/, '明细必须有自己的入口按钮')

  // 三张表把 id 真的显示出来：id 只在 prompt 提示语里出现是不够的。
  for (const rows of ['editionLedger.payables', 'editionLedger.batches', 'editionLedger.extraApprovals']) {
    assert.match(detail, new RegExp(`:data="${rows.replace(/\./g, '\\.')} \\|\\| \\[\\]"`),
      `${rows} 必须渲染成表格，操作者才看得到 id`)
  }
  assert.match(detail, /<el-table-column prop="id" label="id"/)
  assert.match(detail, /<el-table-column prop="invoiceStatus"/, '发票轴状态必须可读')
  assert.match(detail, /<el-table-column prop="batchStatus"/, '付款轴状态必须可读')

  // 建批次的返回值不许再被整个丢掉：确认/付款/开票三步接着就要填这个 id。
  assert.match(detail, /批次已建立 #/)
  assert.doesNotMatch(detail, /\.then\(\(\) => this\.\$modal\.msgSuccess\('批次已建立'\)\)/)

  // 候选 id 必须来自明细，不是让人凭空敲。
  assert.match(detail, /candidateHint\(this\.editionLedger\.payables/)
  assert.match(detail, /candidateHint\(this\.editionLedger\.batches/)
})

// 红冲 / 重计提的理由是必填且**落库**的：本仓 LogAspect 不写请求体，
// sys_oper_log 里查不到理由，前端不得把它做成可选。
test('红冲与重计提都必须带上会落库的理由', () => {
  assert.match(detail, /reverseClubPayable\(this\.topicDetail\.id, payableId, \{ reason: value \}\)/)
  assert.match(detail, /reaccrueClubPayable\(this\.topicDetail\.id, \{ reason: value \}\)/)
})
