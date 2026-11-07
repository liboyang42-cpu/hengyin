'use strict'

// 打磨第 3 轮 §4-3:俱乐部 角色/通知/治理 页标题直接接首个区块时不再叠 40rpx 区块间距(与结算/运营/勋章一致)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

for (const page of ['roles', 'notify', 'governance', 'settlement', 'event-ops', 'badges']) {
  test(`pages/club/${page}:cy-page-title + .section 不带上边距`, () => {
    const wxss = fs.readFileSync(path.join(__dirname, '../../pages/club', page, 'index.wxss'), 'utf8')
    assert.match(wxss, /(^|\n|, )cy-page-title \+ \.section[^{]*\{ margin-top: 0; \}/)
  })
}
