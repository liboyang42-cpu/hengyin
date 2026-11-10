// P-22(2026-11-02 走查):玩家页深色,收集册与「总积分排名」同一张卡的面、边、字,不再是单独的浅色纸页。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const wxss = fs.readFileSync(path.resolve(__dirname, '../../subpackageMember/components/cy/profile/index.wxss'), 'utf8')

function rule(selector) {
  const i = wxss.indexOf(selector + ' {')
  assert.ok(i >= 0, '缺 ' + selector)
  return wxss.slice(i, wxss.indexOf('}', i))
}

// 2026-11-10 r2 a4-v3-34 业主「收集册底部不需要灰色的底 直接在背景上」:P-22 的「同排名卡一张卡」改为不垫卡底,
// 仍是玩家深色套件的字(无衬线、继承深色字色),不退回浅色纸页。
test('玩家收集册直接落在深色页面背景上,不垫卡底、不回浅色纸页', () => {
  const album = rule('.pc-player .pc-album')
  assert.match(album, /background: transparent; border: 0;/)
  assert.doesNotMatch(album, /#FAFBFD|radial-gradient/, '不回浅色纸页')
  assert.match(album, /color: var\(--cy-color-text-primary\)/)
  assert.match(rule('.pc-player .pc-rank'), /var\(--cy-color-bg-elevated\)/, '排名卡不受影响')
  assert.match(rule('.pc-player .pc-album-t'), /font-family: inherit/)
})
