// PRIVATE-GROUP-backend-club-post-reference / #334 F3:
// 服务端对「被引对象当前已不可见」的帖子回 refUnavailable=true(不投影标题封面)。
// 俱乐部详情只给中性的「引用不可用」标签,不渲染成绩卡/模板卡,也不猜是删了还是转私密;
// 发帖时引用被拒按机器错误码映射文案并撤掉这条引用。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const PAGE = 'pages/club/detail/index.js'

function sliceFunction(src, signature) {
  const start = src.indexOf(signature)
  assert.ok(start > 0, '找不到 ' + signature)
  let depth = 0
  let end = start
  for (; end < src.length; end += 1) {
    if (src[end] === '{') depth += 1
    else if (src[end] === '}') { depth -= 1; if (depth === 0) { end += 1; break } }
  }
  return src.slice(start, end)
}

function loadNormalizer() {
  const sandbox = {
    module: {}, console,
    formatPostTime: (v) => String(v || ''),
    isCompletedShare: require(path.join(ROOT, 'utils/feed-play-card.js')).isCompletedShare,
    hasFeedPlayCover: require(path.join(ROOT, 'utils/feed-play-card.js')).hasFeedPlayCover,
    decodeDisplayText: require(path.join(ROOT, 'utils/display-text.js')).decodeDisplayText,
  }
  vm.runInNewContext(sliceFunction(read(PAGE), 'function normalizeClubPost(') + '\nmodule.exports = normalizeClubPost;',
    sandbox, { filename: PAGE })
  return sandbox.module.exports
}

const normalize = loadNormalizer()
const base = { id: 1, authorMemberId: 7, nickname: '林小野', content: '外滩夜巡走完了', images: '' }

test('refUnavailable 的引用帖不渲染成绩卡/模板卡,只打中性标记', () => {
  for (const refType of [1, 2]) {
    const post = normalize(Object.assign({}, base, { refType, refId: 88, refUnavailable: true }), 9)
    assert.equal(post.refUnavailable, true)
    assert.equal(post.isCompletionShare, false)
    assert.equal(post.hasPlayCover, false)
  }
})

test('即使残留了展示字段,refUnavailable 也压过卡片判定', () => {
  const post = normalize(Object.assign({}, base, {
    refType: 1, refId: 88, sportName: '旧标题', sportCover: '/old.png', refUnavailable: true,
  }), 9)
  assert.equal(post.isCompletionShare, false)
  assert.equal(post.hasPlayCover, false)
})

test('可见引用与普通帖不受影响', () => {
  const visible = normalize(Object.assign({}, base, { refType: 1, refId: 88, sportName: '外滩夜巡', sportCover: '/c.png' }), 9)
  assert.equal(visible.refUnavailable, false)
  assert.equal(visible.isCompletionShare, true)
  const plain = normalize(Object.assign({}, base, { refUnavailable: true }), 9)
  assert.equal(plain.refUnavailable, false, '没有引用的帖子不显示引用标签')
})

test('club/detail 用中性文案渲染引用不可用标签,不出现下架/私密字样', () => {
  const wxml = read('pages/club/detail/index.wxml')
  const tag = wxml.match(/<view slot="special" class="post-ref-unavailable"[^>]*>([^<]*)<\/view>/)
  assert.ok(tag, '应在帖卡 special slot 渲染引用不可用标签')
  assert.match(tag[0], /wx:if="\{\{item\.refUnavailable\}\}"/)
  assert.equal(tag[1], '引用不可用')
})

test('发帖引用被拒的机器错误码都有映射文案,且不说成下架/私密', () => {
  // 2026-10-27 P-20:俱乐部发帖改走 cy-post-compose 俱乐部模式,错误码映射随发布请求搬进组件
  const src = read('pages/square/components/cy/post-compose/index.js')
  const block = src.slice(src.indexOf('const CLUB_POST_REF_ERROR_TEXT'), src.indexOf('};', src.indexOf('const CLUB_POST_REF_ERROR_TEXT')) + 2)
  const sandbox = { module: {} }
  vm.runInNewContext(block + '\nmodule.exports = CLUB_POST_REF_ERROR_TEXT;', sandbox)
  const map = sandbox.module.exports
  for (const code of ['CLUB_POST_REF_UNAVAILABLE', 'CLUB_POST_REF_NOT_JOINED', 'CLUB_POST_REF_INVALID']) {
    assert.ok(map[code], code + ' 需要映射')
    assert.doesNotMatch(map[code], /下架|私密|不公开/)
  }
})
