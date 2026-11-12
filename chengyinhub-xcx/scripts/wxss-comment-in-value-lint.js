#!/usr/bin/env node
/*
 * WXSS 声明值内注释门禁。
 * 背景:验收第四轮 R4-1,playkit-album/index.wxss 在多层 background 的两层之间写了
 *   `/* ... ; ... *​/`,微信 wcsc 把注释里的分号当成声明结束,报 `unexpected`,整个 play 页样式编译失败(页面白屏)。
 *   CI 不跑 wcsc(macOS 专属),故用静态扫描:声明值里(属性冒号之后、结束的 `;` 或 `}` 之前)的注释若含 `;` 即拒(值内不含分号的注释 wcsc 实测可编译,不拦)。
 * 用法: node scripts/wxss-comment-in-value-lint.js [--selftest]
 */
'use strict'
const fs = require('fs')
const path = require('path')

// 返回 [{line, col}]:位于声明值内的注释起点。
function findCommentsInValue(src) {
  const hits = []
  let line = 1, col = 0
  let str = null
  let paren = 0
  const stack = []      // 每层 { 的类型: 'sel' | 'decl'
  let inValue = false   // 已见属性冒号、尚未见结束符
  let seg = ''
  const NEST = /^@(media|supports|container|layer|document|scope)\b/
  for (let i = 0; i < src.length; i++) {
    const c = src[i], n = src[i + 1]
    if (c === '\n') { line++; col = 0; seg += ' '; continue }
    col++
    if (str) { if (c === '\\') { i++; col++; continue } if (c === str) str = null; continue }
    if (c === '/' && n === '*') {
      const end = src.indexOf('*/', i + 2)
      const stop = end < 0 ? src.length : end + 2
      // wcsc 实测:声明值内的普通注释可编译(大量 `/* ds-ok */` 夹在多层 background 之间),
      // 唯有注释内含 `;` 时被当作声明结束而报 unexpected(R4-1)。故只拒「值内注释含分号」。
      if (inValue && src.slice(i + 2, stop - 2).includes(';')) hits.push({ line, col })
      for (let k = i; k < stop; k++) { if (src[k] === '\n') { line++; col = 0 } else col++ }
      i = stop - 1
      continue
    }
    if (c === '"' || c === "'") { str = c; seg += c; continue }
    if (c === '(') { paren++; seg += c; continue }
    if (c === ')') { if (paren > 0) paren--; seg += c; continue }
    if (c === '{') {
      const s = seg.trim()
      stack.push(s[0] === '@' && NEST.test(s) ? 'sel' : 'decl')
      seg = ''; inValue = false; continue
    }
    if (c === '}') { stack.pop(); seg = ''; inValue = false; continue }
    if (c === ';' && paren === 0) { seg = ''; inValue = false; continue }
    if (c === ':' && !inValue && stack[stack.length - 1] === 'decl' && paren === 0) { inValue = true; seg += c; continue }
    seg += c
  }
  return hits
}

function walk(dir, out) {
  for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
    if (f.name === 'node_modules' || f.name === '.git') continue
    const p = path.join(dir, f.name)
    if (f.isDirectory()) walk(p, out)
    else if (f.name.endsWith('.wxss')) out.push(p)
  }
  return out
}

function scanAll(root) {
  const bad = []
  for (const f of walk(root, [])) {
    for (const h of findCommentsInValue(fs.readFileSync(f, 'utf8'))) bad.push(`${path.relative(root, f)}:${h.line}:${h.col}`)
  }
  return bad
}

// R4-1 复现夹具(注释放在多层 background 两层之间,内含分号)
const FIXTURE_BAD = '.a{ background:\n  linear-gradient(red, blue), /* ds-ok 漫反射;外观色 */\n  linear-gradient(a, b); }\n'
const FIXTURE_BAD2 = '.a{ color: red /* x;y */ ; }\n'
const FIXTURE_OK = '.a{ background: linear-gradient(red, blue), /* ds-ok 无分号 */ linear-gradient(a, b); color: red /* x */; }\n.a{ background: linear-gradient(red, blue); } /* ds-ok 说明;带分号 */\n/* 头注释;ok */\n.b{ color: red; /* 尾注释 */ }\n@media (min-width:1px){ .c{ top:0 } }\n'

function selftest() {
  const a = findCommentsInValue(FIXTURE_BAD).length
  const b = findCommentsInValue(FIXTURE_BAD2).length
  const c = findCommentsInValue(FIXTURE_OK).length
  if (a !== 1 || b !== 1 || c !== 0) { console.error(`selftest FAIL bad=${a} bad2=${b} ok=${c}`); return false }
  return true
}

module.exports = { findCommentsInValue, scanAll, selftest, FIXTURE_BAD, FIXTURE_OK }

if (require.main === module) {
  if (process.argv.includes('--selftest')) { process.exit(selftest() ? 0 : 1) }
  const bad = scanAll(path.resolve(__dirname, '..'))
  if (bad.length) { console.error('WXSS 声明值内含注释(wcsc 会编译失败):\n' + bad.join('\n')); process.exit(1) }
  console.log('wxss-comment-in-value: PASS')
}
