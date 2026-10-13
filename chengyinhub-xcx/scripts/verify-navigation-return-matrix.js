'use strict'

const fs = require('fs')
const path = require('path')
const { launchFixed, wait } = require('./_infra')

const outDir = process.env.UI_AUDIT_OUT
if (!outDir) throw new Error('UI_AUDIT_OUT is required')
fs.mkdirSync(outDir, { recursive: true })

const CASES = [
  { id: 'publish-fabu', route: '/pages/publish/fabu/index', handler: 'goBack', expected: 'pages/template/index' },
  { id: 'publish-temp', route: '/pages/publish/temp/index', handler: 'goBack', expected: 'pages/template/index' },
  { id: 'club-enroll', route: '/pages/club/enroll/index?clubId=7', handler: 'goBack', expected: 'pages/club/detail/index' },
  { id: 'topic-pricing', route: '/pages/topic/pricing/index?topicId=9', handler: 'goBack', expected: 'pages/topic/index/index' },
  { id: 'merchant-topic-apply', route: '/pages/topic/merchantapply/index', handler: 'onSheetClose', expected: 'pages/merchant/index/index' },
]

function routes(pages) {
  return pages.map((page) => String(page.path || '').replace(/^\//, '').split('?')[0])
}

async function waitForTop(mp, expected, timeout = 5000) {
  const deadline = Date.now() + timeout
  let stack = []
  while (Date.now() < deadline) {
    stack = routes(await mp.pageStack())
    if (stack.at(-1) === expected) return stack
    await wait(150)
  }
  throw new Error(`expected top=${expected}, actual=${stack.join(' -> ')}`)
}

async function main() {
  const mp = await launchFixed()
  const result = { project: process.cwd(), cases: [], consoleErrors: [] }
  mp.on('console', (message) => {
    if (message.type === 'error') result.consoleErrors.push(String(message.args && message.args[0] || '').slice(0, 240))
  })

  try {
    for (const item of CASES) {
      await mp.reLaunch(item.route)
      await wait(900)
      const page = await mp.currentPage()
      const before = routes(await mp.pageStack())
      const screenshot = path.join(outDir, `${item.id}.png`)
      await mp.screenshot({ path: screenshot })
      await page.callMethod(item.handler)
      const after = await waitForTop(mp, item.expected)
      result.cases.push({ id: item.id, before, after, screenshot })
    }
    result.verdict = 'PASS'
    fs.writeFileSync(path.join(outDir, 'result.json'), `${JSON.stringify(result, null, 2)}\n`)
    console.log(JSON.stringify(result, null, 2))
  } finally {
    await mp.close().catch(() => {})
  }
}

main().catch((error) => {
  console.error(error && error.stack || error)
  process.exitCode = 1
})
