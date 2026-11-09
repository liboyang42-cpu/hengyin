// 第十一轮 h2 第 3 项(2026-11-04 业主):编辑器里玩家/作者看得到的「抽卡」一律叫「翻卡」。
// 只认字符串字面量(注释、标识符、gameKey 'random' 不改);后端镜像文案同步改。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '..', '..')
const REPO = path.join(ROOT, '..')
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js')
const outcomeAfter = require('../../pages/publish/utils/publish/outcome-after.js')

test('玩法选择器:随机卡玩法的名字是「翻卡」', () => {
  assert.equal(catalog.findGame('random').label, '翻卡')
  const random = catalog.BASE_GAMES.find(item => item.key === 'random')
  assert.equal(random.forms.find(form => form.gameKey === 'random').label, '翻卡')
})

test('多张翻卡的「之后」提示与后端校验同一个词', () => {
  const reason = outcomeAfter.localReason({ random: { drawCount: 2 } }, 'random.items.0.after')
  assert.equal(reason, '多张翻卡请使用通过/没通过之后')
  const java = fs.readFileSync(path.join(REPO,
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplayOutcomeRules.java'), 'utf8')
  assert.match(java, /fail\("多张翻卡请用顶层通过\/没通过之后"\)/)
  const story = fs.readFileSync(path.join(REPO,
    'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/JourneyStoryValidator.java'), 'utf8')
  assert.match(story, /掷骰子 \/ 翻卡会写/)
})

function stringLiterals(source) {
  const out = []
  const re = /'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*"|`(?:[^`\\]|\\.)*`/g
  const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"])\/\/.*$/gm, '$1')
  let match
  while ((match = re.exec(withoutComments))) out.push(match[0])
  return out
}

function walk(dir, acc) {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name)
    if (fs.statSync(full).isDirectory()) walk(full, acc)
    else if (/\.(js|wxml)$/.test(name)) acc.push(full)
  }
  return acc
}

test('编辑器(pages/publish)与后台没有写给人看的「抽卡」', () => {
  const files = walk(path.join(ROOT, 'pages', 'publish'), [])
  const hits = []
  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8')
    const texts = file.endsWith('.wxml')
      ? [source.replace(/<!--[\s\S]*?-->/g, '')]
      : stringLiterals(source)
    if (texts.some(text => text.includes('抽卡'))) hits.push(path.relative(ROOT, file))
  }
  assert.deepEqual(hits, [])
})
