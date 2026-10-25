const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const LITERAL_OK = /code\s*===?\s*'?200'?\s*\|\|/

function walk(dir, out) {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name)
    if (fs.statSync(full).isDirectory()) walk(full, out)
    else if (name.endsWith('.js')) out.push(full)
  }
  return out
}

// U-065:业务成功判定只有 utils/response-shape.js#isBizOk 一份;俱乐部页与 access-gate 不再各写一份 code===200 判断
test('俱乐部页与 access-gate 不再手写 code===200 判断', () => {
  const files = walk(path.join(ROOT, 'pages/club'), []).concat([path.join(ROOT, 'utils/access-gate.js')])
  const offenders = files.filter((file) => LITERAL_OK.test(fs.readFileSync(file, 'utf8')))
    .map((file) => path.relative(ROOT, file))
  assert.deepEqual(offenders, [])
})

test('isBizOk 认数字与字符串 200,其余一律不认', () => {
  const { isBizOk } = require('../../utils/response-shape.js')
  assert.equal(isBizOk({ code: 200 }), true)
  assert.equal(isBizOk({ code: '200' }), true)
  for (const res of [null, undefined, {}, { code: 500 }, { code: '0' }, { code: 201 }]) assert.equal(isBizOk(res), false)
})
