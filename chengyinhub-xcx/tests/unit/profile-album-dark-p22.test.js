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

test('玩家收集册用深色套件的面与边,和排名卡一致', () => {
  const album = rule('.pc-player .pc-album')
  const rank = rule('.pc-player .pc-rank')
  for (const token of ['var(--cy-color-bg-elevated)', 'var(--cy-color-border-subtle)']) {
    assert.ok(album.includes(token), 'album ' + token)
    assert.ok(rank.includes(token), 'rank ' + token)
  }
  assert.match(album, /border-radius: var\(--cy-radius-md\)/)
  assert.match(rule('.pc-player .pc-album-t'), /font-family: inherit/)
})
