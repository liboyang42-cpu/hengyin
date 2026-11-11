#!/usr/bin/env node
'use strict'
// WXS 语法闸(x-fix1 / B-1):WXS 不是 JS,微信 wcc 只认 ES5 子集,还另有禁区。
// 本闸用 Node 自带 Acorn(ecmaVersion 5)解析每个 .wxs,再按 AST 禁掉 wcc 实测或文档明确不支持的写法。
// 需 node --expose-internals 运行(与 js-scope-lint 相同,不另加依赖)。
//   node --expose-internals scripts/wxs-syntax-gate.js            扫描全部 .wxs
//   node --expose-internals scripts/wxs-syntax-gate.js --selftest 用夹具(*.wxs.fixture,不用 .wxs 后缀以免被 DevTools 编译)证明闸能抓到违规
const fs = require('fs')
const path = require('path')

let acorn
try {
  acorn = require('internal/deps/acorn/acorn/dist/acorn')
} catch (e) {
  console.error('WXS-SYNTAX FAIL: 必须用 node --expose-internals 运行，才能使用 Node 自带 Acorn')
  process.exit(2)
}

const ROOT = path.resolve(__dirname, '..')
const FIXTURES = path.join(ROOT, 'tests', 'fixtures', 'wxs-syntax-gate')
const SKIP_DIRS = new Set(['node_modules', 'miniprogram_npm', '.git', 'tests'])

// 当函数/构造器调用会让 wcc 报错或行为不一(B-1:对象字面量里的 Number(x) → Expected variable|string)。
// 转数用 x * 1,转串用 '' + x,判 NaN 用 n !== n,日期用 getDate(),正则用 getRegExp()。
const BANNED_CALLS = new Set([
  'Number', 'String', 'Boolean', 'Object', 'Array', 'Function', 'Symbol',
  'Date', 'RegExp', 'Error', 'Promise', 'Map', 'Set', 'isNaN', 'eval', 'require'
])
const REGEXP_FLAGS = /^[gim]*$/

function walk(node, visit) {
  if (!node || typeof node.type !== 'string') return
  visit(node)
  for (const key of Object.keys(node)) {
    if (key === 'loc' || key === 'start' || key === 'end') continue
    const v = node[key]
    if (Array.isArray(v)) v.forEach(c => c && typeof c.type === 'string' && walk(c, visit))
    else if (v && typeof v.type === 'string') walk(v, visit)
  }
}

function checkSource(source, file) {
  const errors = []
  const at = (n, msg) => errors.push(`${file}:${n.loc.start.line}:${n.loc.start.column + 1} ${msg}`)
  let ast
  try {
    ast = acorn.parse(source, { ecmaVersion: 5, sourceType: 'script', locations: true, allowReturnOutsideFunction: false })
  } catch (e) {
    const loc = e.loc ? `${e.loc.line}:${e.loc.column + 1}` : '?'
    errors.push(`${file}:${loc} 非 ES5 语法(let/const/箭头函数/模板串/展开/简写属性/默认参数/for..of/class 等 WXS 均不支持): ${e.message}`)
    return errors
  }
  walk(ast, n => {
    if (n.type === 'CallExpression' && n.callee.type === 'Identifier') {
      const name = n.callee.name
      if (name === 'require') {
        const a = n.arguments[0]
        if (!(a && a.type === 'Literal' && typeof a.value === 'string' && /\.wxs$/.test(a.value))) {
          at(n, 'WXS 的 require 只能引用 .wxs 字面量路径')
        }
      } else if (BANNED_CALLS.has(name)) {
        at(n, `WXS 不支持把 ${name}() 当函数调用(B-1);转数用 x * 1,转串用 '' + x,判 NaN 用 n !== n`)
      } else if (name === 'getRegExp') {
        const f = n.arguments[1]
        if (f && !(f.type === 'Literal' && typeof f.value === 'string' && REGEXP_FLAGS.test(f.value))) {
          at(n, 'getRegExp 的 flags 只能是 g/i/m 组成的字面量')
        }
      }
    }
    if (n.type === 'NewExpression') at(n, 'WXS 不支持 new;日期用 getDate(),正则用 getRegExp()')
    if (n.type === 'Literal' && n.regex) at(n, 'WXS 不支持正则字面量,改用 getRegExp(pattern, flags)')
    if (n.type === 'ThisExpression') at(n, 'WXS 模块里不要用 this')
    if (n.type === 'Identifier' && (n.name === 'window' || n.name === 'wx' || n.name === 'getApp')) {
      at(n, `WXS 里没有 ${n.name}`)
    }
  })
  return errors
}

function listWxs(dir, out) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (ent.isDirectory()) {
      if (!SKIP_DIRS.has(ent.name)) listWxs(path.join(dir, ent.name), out)
    } else if (ent.name.endsWith('.wxs')) out.push(path.join(dir, ent.name))
  }
  return out
}

// WXML 内联 <wxs module="x"> ... </wxs> 也一并检查
function listInlineWxs(dir, out) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name)
    if (ent.isDirectory()) {
      if (!SKIP_DIRS.has(ent.name)) listInlineWxs(p, out)
    } else if (ent.name.endsWith('.wxml')) {
      const src = fs.readFileSync(p, 'utf8')
      const re = /<wxs\b[^>]*?(?:\/>|>([\s\S]*?)<\/wxs>)/g
      let m
      while ((m = re.exec(src))) {
        if (m[1] && m[1].trim()) out.push({ file: p, source: m[1] })
      }
    }
  }
  return out
}

function scan() {
  const errors = []
  const files = listWxs(ROOT, [])
  for (const f of files) errors.push(...checkSource(fs.readFileSync(f, 'utf8'), path.relative(ROOT, f)))
  const inline = listInlineWxs(ROOT, [])
  for (const b of inline) errors.push(...checkSource(b.source, path.relative(ROOT, b.file) + '(inline wxs)'))
  return { files: files.length, inline: inline.length, errors }
}

function selftest() {
  const problems = []
  for (const name of fs.readdirSync(FIXTURES).filter(n => n.endsWith('.wxs.fixture')).sort()) {
    const errs = checkSource(fs.readFileSync(path.join(FIXTURES, name), 'utf8'), name)
    const expectBad = name.startsWith('bad-')
    if (expectBad && errs.length === 0) problems.push(`${name} 应被拦截但通过了`)
    if (!expectBad && errs.length) problems.push(`${name} 应通过但被拦截: ${errs.join('; ')}`)
  }
  return problems
}

if (require.main === module) {
  if (process.argv.includes('--selftest')) {
    const p = selftest()
    if (p.length) { p.forEach(x => console.error('WXS-SYNTAX SELFTEST FAIL: ' + x)); process.exit(1) }
    console.log('WXS-SYNTAX SELFTEST: PASS')
  } else {
    const r = scan()
    if (r.errors.length) { r.errors.forEach(x => console.error('WXS-SYNTAX FAIL: ' + x)); process.exit(1) }
    console.log(`WXS-SYNTAX: PASS (${r.files} 个 .wxs, ${r.inline} 段内联 wxs)`)
  }
}

module.exports = { checkSource, scan, selftest }
