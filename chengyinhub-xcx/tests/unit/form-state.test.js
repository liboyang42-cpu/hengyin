const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function loadFormState() {
  return require(path.join(ROOT, 'utils/form-state.js'))
}

function hasCopiedMobileRegex(source) {
  return /\^1\[3-9\]\\d\{9\}\$/.test(source)
}

function findCopiedMobileRegexes() {
  const excludedDirs = new Set(['.git', 'node_modules', 'miniprogram_npm', 'tests'])
  const matches = []
  function walk(dir) {
    fs.readdirSync(dir, { withFileTypes: true }).forEach((entry) => {
      if (entry.isDirectory() && excludedDirs.has(entry.name)) return
      const file = path.join(dir, entry.name)
      if (entry.isDirectory()) walk(file)
      else if (entry.name.endsWith('.js') && file !== path.join(ROOT, 'utils/form-state.js')) {
        const source = fs.readFileSync(file, 'utf8')
        if (hasCopiedMobileRegex(source)) matches.push(path.relative(ROOT, file))
      }
    })
  }
  walk(ROOT)
  return matches
}

test('form-state: 手机号校验与表单完成态派生只有一个公共真源', () => {
  const { isValidMobile, deriveCanSubmit } = loadFormState()
  assert.equal(isValidMobile('13800138000'), true)
  assert.equal(isValidMobile('19912345678'), true)
  assert.equal(isValidMobile('12800138000'), false)
  assert.equal(isValidMobile('1380013800'), false)
  assert.equal(isValidMobile(' 13800138000 '), false, '调用方是否 trim 必须保持原页面语义')
  assert.equal(deriveCanSubmit([true, 1, 'filled']), true)
  assert.equal(deriveCanSubmit([true, false, true]), false)
})

function gbCheckCode(first17) {
  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
  let sum = 0
  for (let i = 0; i < 17; i += 1) sum += Number(first17.charAt(i)) * weights[i]
  const code = (12 - (sum % 11)) % 11
  return code === 10 ? 'X' : String(code)
}

test('form-state: 身份证校验位为 2 的合法号码必须放行（校验位表 11 项，与后端 IdCardUtils 同口径）', () => {
  const { isValidIdCard } = loadFormState()
  assert.equal(gbCheckCode('11010519900307009'), '2')
  assert.equal(isValidIdCard('110105199003070092'), true)
  assert.equal(isValidIdCard('110105199003070093'), false)
  assert.equal(isValidIdCard('11010519900307009X'), false)
  assert.equal(isValidIdCard('110105199003070017'), true)
  assert.equal(isValidIdCard('31010119750101001x'), true)
  for (let seq = 0; seq < 1000; seq += 1) {
    const first17 = `11010519900307${String(seq).padStart(3, '0')}`
    assert.equal(isValidIdCard(first17 + gbCheckCode(first17)), true, first17)
  }
})

test('form-state: 全仓不得遗留手机号正则拷贝', () => {
  assert.deepEqual(findCopiedMobileRegexes(), [])
})

test('负控：任一页面重新复制手机号正则时公共真源契约必须判红', () => {
  const copiedSource = "const phonePattern = /^1[3-9]\\d{9}$/"
  assert.equal(hasCopiedMobileRegex(copiedSource), true, '扫描器必须能识别回潮的正则源码')
  const broken = hasCopiedMobileRegex(copiedSource) ? ['pages/example/index.js'] : []
  assert.throws(() => assert.deepEqual(broken, []), assert.AssertionError)
})
