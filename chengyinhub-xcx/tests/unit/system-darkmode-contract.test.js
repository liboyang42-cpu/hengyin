/* 系统深色模式契约(2026-10-22 owner 决定,取代 2026-08-22 版)
 *
 * 商家域恒浅、玩家域恒深,**都不跟随手机系统的深色开关**。
 * 旧契约要求 dark-mode.wxss / merchant-light.wxss 带 @media (prefers-color-scheme: dark) 镜像块,
 * 已被 owner 推翻;这里反过来锁:样式表里不得再出现该媒体查询。
 */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

function wxssFiles(dir, out = []) {
  for (const ent of fs.readdirSync(path.join(ROOT, dir), { withFileTypes: true })) {
    if (ent.name === 'node_modules') continue
    const rel = path.posix.join(dir, ent.name)
    if (ent.isDirectory()) wxssFiles(rel, out)
    else if (ent.name.endsWith('.wxss')) out.push(rel)
  }
  return out
}

test('全部 WXSS 不得含 prefers-color-scheme: dark(商家恒浅/玩家恒深)', () => {
  const hits = fs.readdirSync(ROOT, { withFileTypes: true })
    .filter((e) => e.isDirectory() && !['node_modules', 'tests', 'scripts', 'docs'].includes(e.name))
    .flatMap((e) => wxssFiles(e.name))
    .concat(['app.wxss'])
    .filter((f) => /@media[^{]*prefers-color-scheme\s*:\s*dark/.test(
      read(f).replace(/\/\*[\s\S]*?\*\//g, '')))
  assert.deepEqual(hits, [], `这些样式表仍跟随系统深色:${hits.join(', ')}`)
})

test('app.json 不配 themeLocation(全局 chrome 属玩家域恒深色)', () => {
  const app = JSON.parse(read('app.json'))
  assert.equal(app.themeLocation, undefined)
  assert.equal(app.tabBar.backgroundColor, '#0A0A0B', 'tabBar 应保持玩家域深色')
})

test('dark-mode.wxss 仍被 app.wxss 引入(保留文件避免 @import 断链)', () => {
  assert.match(read('app.wxss'), /style\/dark-mode\.wxss/)
})
