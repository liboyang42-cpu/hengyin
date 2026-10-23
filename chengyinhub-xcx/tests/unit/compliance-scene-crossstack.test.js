// P2-032:小程序上报的 (docType, scene) 必须都在后端 ApiComplianceController.DOCUMENT_SCENES 白名单里。
// 对不上时 /api/compliance/consents 只回「协议类型或场景不合法」,对应入口(提现/报名/核销/定位…)整条走不通。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const sourceInventory = require('../../scripts/mini-program-source-inventory')
const withdrawForm = require('../../utils/withdraw-form.js')
const { VERSIONS } = require('../../utils/compliance/usage-consent.js')

const XCX_ROOT = path.join(__dirname, '../..')
const SERVER_PATH = path.join(XCX_ROOT, '../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiComplianceController.java')

function serverScenes(source) {
  const table = new Map()
  for (const m of source.matchAll(/scenes\.put\("([a-z_]+)",\s*setOf\(([^)]*)\)\);/g)) {
    table.set(m[1], new Set(Array.from(m[2].matchAll(/"([a-z_]+)"/g), x => x[1])))
  }
  return table
}

function literalPairs(source) {
  return Array.from(source.matchAll(/docType\s*:\s*['"]([a-z_]+)['"]\s*,\s*scene\s*:\s*['"]([a-z_]+)['"]/g),
    m => [m[1], m[2]])
}

function clientPairs() {
  const pairs = []
  for (const file of sourceInventory.productionFiles('.js')) {
    const rel = path.relative(XCX_ROOT, file).split(path.sep).join('/')
    for (const pair of literalPairs(fs.readFileSync(file, 'utf8'))) pairs.push([rel, ...pair])
  }
  const merchant = fs.readFileSync(path.join(XCX_ROOT, 'pages/play/merchant/index.js'), 'utf8')
  const doc = /const CONSENT_DOC = '([a-z_]+)'/.exec(merchant)
  const scene = /const CONSENT_SCENE = '([a-z_]+)'/.exec(merchant)
  assert.ok(doc && scene, 'pages/play/merchant 的 CONSENT_DOC/CONSENT_SCENE 解析失败')
  pairs.push(['pages/play/merchant/index.js', doc[1], scene[1]])
  pairs.push(['utils/withdraw-form.js', withdrawForm.CONSENT_DOC_TYPE, withdrawForm.CONSENT_SCENE])
  for (const docType of Object.keys(VERSIONS)) pairs.push(['utils/compliance/usage-consent.js', docType, 'app_launch'])
  return pairs
}

test('后端场景白名单可解析且非空(防正则失效恒绿)', () => {
  const table = serverScenes(fs.readFileSync(SERVER_PATH, 'utf8'))
  assert.ok(table.size >= 10, '只解析到 ' + table.size + ' 个 docType')
  assert.ok(table.get('bank_account_collection').has('withdrawal'))
})

test('小程序每一处同意上报的 docType/scene 都在后端白名单内', () => {
  const table = serverScenes(fs.readFileSync(SERVER_PATH, 'utf8'))
  const pairs = clientPairs()
  assert.ok(pairs.length >= 10, '只找到 ' + pairs.length + ' 处上报,扫描可能失效')
  const bad = pairs.filter(([, docType, scene]) => !(table.get(docType) && table.get(docType).has(scene)))
  assert.deepEqual(bad, [], '这些 docType/scene 后端不收,接口会直接拒绝')
})

test('negative control:后端删掉一个场景,扫描必须报出来', () => {
  const source = fs.readFileSync(SERVER_PATH, 'utf8').replace('setOf("withdrawal")', 'setOf("withdraw")')
  const table = serverScenes(source)
  const bad = clientPairs().filter(([, docType, scene]) => !(table.get(docType) && table.get(docType).has(scene)))
  assert.ok(bad.some(([, docType]) => docType === 'bank_account_collection'))
})
