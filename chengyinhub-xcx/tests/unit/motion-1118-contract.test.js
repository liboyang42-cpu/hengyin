'use strict'
/* motion-1118 契约(2026-11-18)
 *
 * ① 双端共用动效 token:docs/design/motion-tokens.json 是双端对照表,本仓 style/tokens.wxss 的
 *    --cy-motion-* / --cy-ease-* 必须与之逐值相等(iOS 那边由 tools/check_native_motion_tokens.py 对同一份拷贝判红)。
 * ② 骨架屏 → 内容/空态/错误态:<cy-skeleton wx:if> 的同级 wx:elif / wx:else 分支(非 <block>)
 *    挂载时必须带 cy-fade-in(或 page-motion 的 --pm-* 入场),不能「啪」地换上 —— 对标 iOS 系统 App
 *    占位 → 内容的交叉淡入。<block> 不渲染节点、挂不上 class,不在本条范围(见 docs/motion-audit-1118.md)。
 * ③ cy-tabs icon 档的过渡只读 token,不写字面时长。
 */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const SKIP = new Set(['node_modules', 'miniprogram_npm', 'tests', 'docs', 'images', '.git', 'scripts'])

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP.has(e.name)) continue
    const full = path.join(dir, e.name)
    if (e.isDirectory()) walk(full, out)
    else if (e.name.endsWith('.wxml')) out.push(full)
  }
  return out
}

const TOK = /<!--[\s\S]*?-->|<\/([a-zA-Z][\w-]*)\s*>|<([a-zA-Z][\w-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/g
const VOID = new Set(['input', 'image', 'icon', 'import', 'include'])
const FADED = /cy-fade-in|cy-rise-in|cy-fly-in|cy-refresh-float/
const CHECKED = new Set(['cy-empty', 'cy-error', 'cy-inline-error', 'cy-state-shell', 'view', 'scroll-view'])

function unfadedBranches(source) {
  const toks = []
  let m
  TOK.lastIndex = 0
  while ((m = TOK.exec(source))) {
    if (m[0].startsWith('<!--')) continue
    toks.push({ close: m[1], tag: m[2], attrs: m[3] || '', self: m[4] === '/' || VOID.has(m[2]) })
  }
  let d = 0
  for (const t of toks) {
    if (t.close) { d -= 1; t.depth = d } else { t.depth = d; if (!t.self) d += 1 }
  }
  const skipSubtree = (j) => {
    const u = toks[j]
    if (u.self) return j + 1
    let k = j + 1
    while (k < toks.length && !(toks[k].close && toks[k].depth === u.depth)) k += 1
    return k + 1
  }
  const bad = []
  toks.forEach((t, i) => {
    if (t.tag !== 'cy-skeleton' || !/\bwx:if=/.test(t.attrs)) return
    let j = skipSubtree(i)
    while (j < toks.length) {
      const u = toks[j]
      if (u.close || u.depth !== t.depth || !/\bwx:(elif=|else\b)/.test(u.attrs)) break
      const cls = /\bclass="([^"]*)"/.exec(u.attrs)
      const style = /\bstyle="([^"]*)"/.exec(u.attrs)
      if (CHECKED.has(u.tag) && !(cls && FADED.test(cls[1])) && !(style && /--pm-/.test(style[1]))) {
        bad.push(`<${u.tag} ${/wx:elif="[^"]*"|wx:else/.exec(u.attrs)[0]}>`)
      }
      const isElse = /\bwx:else\b/.test(u.attrs) && !/wx:elif/.test(u.attrs)
      j = skipSubtree(j)
      if (isElse) break
    }
  })
  return bad
}

test('motion-1118 ②:骨架屏换下来的分支一律淡入,不啪地换上', () => {
  const offenders = []
  for (const file of walk(ROOT)) {
    const src = fs.readFileSync(file, 'utf8')
    if (!src.includes('<cy-skeleton')) continue
    for (const b of unfadedBranches(src)) offenders.push(`${path.relative(ROOT, file)} ${b}`)
  }
  assert.deepEqual(offenders, [], '骨架屏同级分支缺 cy-fade-in:\n' + offenders.join('\n'))
})

test('motion-1118 ②:检查器自身能抓到缺口(负控)', () => {
  const bad = unfadedBranches('<view><cy-skeleton wx:if="{{a}}" /><cy-error wx:elif="{{b}}" /><view wx:else class="x"><text>1</text></view></view>')
  assert.equal(bad.length, 2)
  const ok = unfadedBranches('<cy-skeleton wx:if="{{a}}" /><cy-error wx:elif="{{b}}" class="cy-fade-in" /><block wx:else><view /></block>')
  assert.equal(ok.length, 0)
})

function pageSet() {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
  const set = new Set(app.pages)
  for (const sub of app.subpackages || app.subPackages || []) for (const p of sub.pages) set.add(sub.root.replace(/\/$/, '') + '/' + p)
  return set
}

test('motion-1118 ②:隔离组件用了 cy-fade-in 就得自己 @import fade-in.wxss', () => {
  const missing = []
  const pages = pageSet()
  for (const file of walk(ROOT)) {
    const src = fs.readFileSync(file, 'utf8')
    if (!/class="[^"]*cy-fade-in/.test(src)) continue
    const dir = path.dirname(file)
    if (pages.has(path.relative(ROOT, file).replace(/\.wxml$/, '').split(path.sep).join('/'))) continue // 页面吃 app.wxss 全局类
    const js = [path.join(dir, path.basename(file, '.wxml') + '.js'), path.join(dir, path.basename(file, '.wxml') + '.json')]
      .filter((f) => fs.existsSync(f)).map((f) => fs.readFileSync(f, 'utf8')).join('\n')
    if (!/Component\s*\(/.test(js)) continue // 页面吃 app.wxss 全局类
    if (/addGlobalClass:\s*true|apply-shared|"addGlobalClass":\s*true/.test(js)) continue
    const wxss = path.join(dir, path.basename(file, '.wxml') + '.wxss')
    const css = fs.existsSync(wxss) ? fs.readFileSync(wxss, 'utf8') : ''
    if (!/fade-in\.wxss|style\/components\.wxss/.test(css)) missing.push(path.relative(ROOT, file))
  }
  assert.deepEqual(missing, [])
})

test('motion-1118 ①:tokens.wxss 与双端对照表 docs/design/motion-tokens.json 逐值一致', () => {
  const spec = JSON.parse(fs.readFileSync(path.join(REPO, 'docs/design/motion-tokens.json'), 'utf8'))
  const css = fs.readFileSync(path.join(ROOT, 'style/tokens.wxss'), 'utf8')
  const read = (v) => {
    const m = new RegExp(v.replace(/[-]/g, '\\-') + ':\\s*([^;]+);').exec(css)
    assert.ok(m, `tokens.wxss 缺 ${v}`)
    return m[1].trim()
  }
  for (const [k, ms] of Object.entries(spec.durationMs)) {
    assert.equal(read(spec.cssVar[k]), `${ms}ms`, k)
  }
  const easeVar = { ...spec.cssVar, standard: spec.cssVar.standardEase }
  for (const [k, pts] of Object.entries(spec.easing)) {
    const got = read(easeVar[k]).replace(/\s+/g, '')
    const nums = /cubic-bezier\(([^)]*)\)/.exec(got)[1].split(',').map(Number)
    assert.deepEqual(nums, pts, k)
  }
})

test('motion-1118 ③:cy-tabs icon 档与 loading-mask 转圈不写字面时长', () => {
  const tabs = fs.readFileSync(path.join(ROOT, 'components/cy/tabs/index.wxss'), 'utf8')
  const icon = tabs.split('\n').filter((l) => l.includes('.cy-tabs--icon') && /transition:/.test(l))
  assert.ok(icon.length >= 3)
  for (const l of icon) assert.ok(!/transition:[^;]*\b\d*\.?\d+m?s\b/.test(l), l)
  const lm = fs.readFileSync(path.join(ROOT, 'components/cy/loading-mask/index.wxss'), 'utf8')
  assert.match(lm, /lm-spin var\(--cy-motion-spinner-cycle\)/)
})
