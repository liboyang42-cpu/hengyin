// 帖文详情正文保留换行(验收清单 CL-01/CL-02):列表卡的「展开」把人带到详情页看全文,
// 详情页正文是 <view>,不写 white-space 时多段正文会被折成一段。
// 每条:展示绑定 + 负控(把修复改回去必须真红)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function postTextRule(css) {
  const matched = css.match(/\.detail\.theme-dark \.post-text\s*\{([^}]*)\}/)
  assert.ok(matched, '缺少样式规则 .detail.theme-dark .post-text')
  return matched[1]
}

function assertKeepsLineBreaks(css) {
  assert.match(postTextRule(css), /white-space:\s*pre-wrap/, '帖文详情正文必须保留用户输入的换行')
}

test('帖文详情:正文按 pre-wrap 保留换行,且模板里正文两侧没有排版空白', () => {
  assertKeepsLineBreaks(read('pages/square/detail/index.wxss'))
  // pre-wrap 会把模板里包住插值的换行/缩进一并显示出来,所以正文必须紧贴插值。
  const wxml = read('pages/square/detail/index.wxml')
  assert.match(wxml, /<view class="post-text"[^>]*>\{\{info\.contents\}\}<\/view>/)
  // 页面根节点固定是 theme-dark,上面这条规则覆盖全部帖文详情。
  assert.match(wxml, /^<view class="detail theme-dark">/)
})

test('负控:去掉 pre-wrap 必须判红', () => {
  const css = read('pages/square/detail/index.wxss')
  assert.ok(css.includes('white-space: pre-wrap; } /* ds-ok */'), '负控锚点失效')
  assert.throws(() => assertKeepsLineBreaks(css.replace('white-space: pre-wrap; } /* ds-ok */', '} /* ds-ok */')),
    assert.AssertionError)
})
