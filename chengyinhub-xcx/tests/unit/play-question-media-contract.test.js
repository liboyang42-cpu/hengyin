const assert = require('node:assert/strict')
const { test, beforeEach } = require('node:test')

const PLAY = '../../pages/play/index.js'

// 题面媒体到达玩家端的行为测。静态匹配挡不住「字段读到了但没贴到 opts 上」这类 ——
// 那会让选项图在编辑器里配得好好的、玩家端一张不显示,且没有任何报错。

let pageConfig
let audio

global.getApp = () => ({ globalData: {}, getUserID: () => 1, sendRequest: () => {} })

global.wx = {
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }),
  getStorageSync: () => null,
  setStorageSync: () => {},
  removeStorageSync: () => {},
  showToast: () => {},
  previewImage: () => {},
  createSelectorQuery: () => ({
    selectAll: () => ({ boundingClientRect: () => {} }),
    select: () => ({ boundingClientRect: () => {}, scrollOffset: () => {} }),
    exec: () => {},
  }),
  createInnerAudioContext: () => {
    audio = {
      src: '', played: 0, paused: 0, stopped: 0,
      play() { this.played += 1 }, pause() { this.paused += 1 }, stop() { this.stopped += 1 },
      onPlay() {}, onPause() {}, onStop() {}, onEnded() {}, onError() {}, onWaiting() {}, onCanplay() {},
      destroy() {},
    }
    return audio
  },
}

global.Page = config => { pageConfig = config }

function setAtPath(target, rawPath, value) {
  const parts = rawPath.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

function makePage() {
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.setData = function (patch, callback) {
    Object.keys(patch).forEach(key => setAtPath(page.data, key, patch[key]))
    if (callback) callback()
  }
  return page
}

beforeEach(() => {
  audio = null
  pageConfig = null
  delete require.cache[require.resolve(PLAY)]
  require(PLAY)
})

test('normNode 把后端下发的题面媒体带进节点,不在这一层丢掉', () => {
  const page = makePage()

  const node = page.normNode({
    nodeId: 7, validationMethod: 3, question: '题目',
    questionImg: 'https://cdn/q.jpg', questionAudio: 'https://cdn/q.mp3',
    options: { A: '甲', B: '乙' },
    optionMedia: { A: { img: 'https://cdn/a.jpg' } },
  }, 0)

  assert.equal(node.questionImg, 'https://cdn/q.jpg')
  assert.equal(node.questionAudio, 'https://cdn/q.mp3')
  assert.deepEqual(node.optionMedia, { A: { img: 'https://cdn/a.jpg' } })
})

test('答题卡按字母把媒体贴到对应选项上,没配媒体的选项拿到空串而不是 undefined', () => {
  const page = makePage()
  page.data.sheet = {
    show: true,
    node: page.normNode({
      nodeId: 7, validationMethod: 3, question: '题目',
      questionImg: 'https://cdn/q.jpg', questionAudio: 'https://cdn/q.mp3',
      options: { A: '甲', B: '乙' },
      optionMedia: { B: { img: 'https://cdn/b.jpg', audio: 'https://cdn/b.mp3' } },
    }, 0),
  }

  page.startGame()

  assert.deepEqual(page.data.game.opts, [
    { k: 'A', v: '甲', img: '', audio: '' },
    { k: 'B', v: '乙', img: 'https://cdn/b.jpg', audio: 'https://cdn/b.mp3' },
  ])
  assert.equal(page.data.game.qImg, 'https://cdn/q.jpg')
  assert.equal(page.data.game.qAudio, 'https://cdn/q.mp3')
})

test('startGame 可直接接收以 nodeId 为真源的归一化节点', () => {
  const page = makePage()
  const node = page.normNode({ nodeId: 7, validationMethod: 6, gameTitle: '收纳安装' }, 0)

  page.startGame(node)

  assert.equal(page.data.game.nodeId, 7)
  assert.equal(page.data.screen, 'gamePlay')
})

test('后端没下发 optionMedia 的老响应照常渲染选项,不因为少一个字段炸掉', () => {
  const page = makePage()
  page.data.sheet = {
    show: true,
    node: page.normNode({ nodeId: 7, validationMethod: 3, question: '题目', options: { A: '甲' } }, 0),
  }

  page.startGame()

  assert.deepEqual(page.data.game.opts, [{ k: 'A', v: '甲', img: '', audio: '' }])
})

// ★ 题干音、选项音、节点语音导览共用同一个 InnerAudioContext。
//   键必须互不相撞,否则「点选项 B 的音」会被判成「正在播的那段」而变成暂停。
test('题干音与选项音用不同的播放键,互不误判成同一段', () => {
  const page = makePage()
  page.data.game = {
    qAudio: 'https://cdn/q.mp3',
    opts: [{ k: 'A', v: '甲', img: '', audio: 'https://cdn/a.mp3' }],
  }

  page.toggleQuestionAudio()
  assert.equal(page.data.audioNodeId, 'q')
  assert.equal(audio.src, 'https://cdn/q.mp3')

  page.data.audioPlaying = true
  page.toggleOptionAudio({ currentTarget: { dataset: { k: 'A' } } })

  assert.equal(page.data.audioNodeId, 'optA')
  assert.equal(audio.src, 'https://cdn/a.mp3', '换的是源，不该被当成「同一段 → 暂停」')
  assert.equal(audio.paused, 0)
})

test('没配音频的选项点了不出声,也不把播放器状态改掉', () => {
  const page = makePage()
  page.data.game = { qAudio: '', opts: [{ k: 'A', v: '甲', img: '', audio: '' }] }

  page.toggleOptionAudio({ currentTarget: { dataset: { k: 'A' } } })

  assert.equal(audio, null, '不该为一个没有音频的选项创建播放器')
  assert.equal(page.data.audioNodeId, null)
})

test('旧商家节奏题干与选项音映射到实际分包路径，源配置不改写', () => {
  const page = makePage()
  page.data.game = {
    qAudio: '/audio/merchant-games/rhythm-three.mp3',
    opts: [{ k: 'A', audio: '/audio/merchant-games/rhythm-one.mp3' }],
  }
  page.toggleQuestionAudio()
  assert.equal(audio.src, '/pages/play/audio/merchant-games/rhythm-three.mp3')
  assert.equal(page.data.game.qAudio, '/audio/merchant-games/rhythm-three.mp3')
  page.toggleOptionAudio({ currentTarget: { dataset: { k: 'A' } } })
  assert.equal(audio.src, '/pages/play/audio/merchant-games/rhythm-one.mp3')
  assert.equal(page.data.game.opts[0].audio, '/audio/merchant-games/rhythm-one.mp3')
})

test('未知旧商家音频不创建播放器、不覆盖正在播放的状态', () => {
  const page = makePage()
  page.data.game = { qAudio: '/audio/merchant-games/missing.mp3' }
  page.data.audioNodeId = 'optA'
  page.toggleQuestionAudio()
  assert.equal(audio, null)
  assert.equal(page.data.audioNodeId, 'optA')
})

test('v5.1 盲品配置必须算 hasAdvanced，否则开始任务打不开 playkit', () => {
  const page = makePage()
  const node = page.normNode({
    nodeId: 8,
    advancedConfigJson: JSON.stringify({ schemaVersion: 1, blindTaste: { enabled: true, title: '闭上眼' } }),
  }, 0)
  assert.equal(node.hasAdvanced, true)
})

test('通关探索值不得用站点数乘 12', () => {
  const src = require('fs').readFileSync(require('path').join(__dirname, '../../pages/play/index.js'), 'utf8')
  assert.doesNotMatch(src, /finishScore:\s*this\.data\.total\s*\*\s*12/)
})


test('旧商家节奏题音频归一到已打包路径，源配置不改写', () => {
  const page = makePage()
  page.data.game = {
    qAudio: '/audio/merchant-games/rhythm-three.mp3',
    opts: [{ k: 'A', audio: '/audio/merchant-games/rhythm-one.mp3' }],
  }
  page.toggleQuestionAudio()
  assert.equal(audio.src, '/pages/play/audio/merchant-games/rhythm-three.mp3')
  assert.equal(page.data.game.qAudio, '/audio/merchant-games/rhythm-three.mp3')
  page.toggleOptionAudio({ currentTarget: { dataset: { k: 'A' } } })
  assert.equal(audio.src, '/pages/play/audio/merchant-games/rhythm-one.mp3')
  assert.equal(page.data.game.opts[0].audio, '/audio/merchant-games/rhythm-one.mp3')
})

test('未知旧商家音频不创建播放器、不覆盖正在播放的状态', () => {
  const page = makePage()
  page.data.game = { qAudio: '/audio/merchant-games/missing.mp3' }
  page.data.audioNodeId = 'optA'
  page.toggleQuestionAudio()
  assert.equal(audio, null)
  assert.equal(page.data.audioNodeId, 'optA')
  page._toggleMediaAudio('optA', 'https://cdn/current.mp3')
  const before = { src: audio.src, played: audio.played, stopped: audio.stopped }
  page.toggleQuestionAudio()
  assert.deepEqual({ src: audio.src, played: audio.played, stopped: audio.stopped }, before)
  assert.equal(page.data.audioNodeId, 'optA')
})

// 实走复核:后端签名链接是 http://,真机不放 http 音频。自有域名升 https,外域原样不动
test('站点语音与题面媒体的自有域名 http 链接升 https', () => {
  const page = makePage()
  const node = page.normNode({ nodeId: 7, validationMethod: 3,
    audioUrl: 'http://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/a.mp3?Signature=x',
    questionAudio: 'http://www.chengyinhub.com/prod-api/profile/q.mp3',
    questionImg: 'http://example.com/q.jpg' }, 0)
  assert.equal(node.audio, 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/a.mp3?Signature=x')
  assert.equal(node.questionAudio, 'https://www.chengyinhub.com/prod-api/profile/q.mp3')
  assert.equal(node.questionImg, 'http://example.com/q.jpg')
})

// 实走复核:站点卡不写「00:00–23:59」、导语不从句中硬切、简介与标题重复时不再写一遍
test('站点卡:全天营业不挂、导语取整句、简介重复标题时隐藏', () => {
  const page = makePage()
  page._routeInteractionBlocked = () => false
  page._lockedTip = () => false
  page.selectComponent = () => null
  const node = page.normNode({ nodeId: 9, name: '结语 · 1945年以后', description: '1945年以后', address: '故事玩法',
    businessTime: '00:00-23:59', storyText: '你面前的霍山路119—121号，曾是美犹联合分配委员会的上海办公处。JDC 1914年成立于纽约。' }, 0)
  page.openSheet(node)
  const s = page.data.sheet.node
  assert.equal(s.metaLine, '故事玩法')
  assert.equal(s.gameLede, '你面前的霍山路119—121号，曾是美犹联合分配委员会的上海办公处。')
  assert.equal(s.description, '')
  page.openSheet(Object.assign({}, node, { businessTime: '06:00-18:00', description: '纪念碑' }))
  assert.equal(page.data.sheet.node.metaLine, '故事玩法 · 06:00-18:00')
  assert.equal(page.data.sheet.node.description, '纪念碑')
})
