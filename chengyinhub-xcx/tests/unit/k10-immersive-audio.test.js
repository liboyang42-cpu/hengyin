// 第十二轮 k10-immersive:一局戴耳机走完的声音状态机与作者编辑器上挡路的几处。
// 行为测试直接跑 pages/play/index.js 的真实方法(与 c06-state-and-exit-contract 同一套最小宿主)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function audioFactory(list) {
  return () => {
    const handlers = {}
    const ctx = {
      src: '', loop: false, volume: 1, paused: true, plays: 0, stops: 0, destroyed: false,
      play() { this.plays += 1; this.paused = false; (handlers.play || []).forEach(f => f()) },
      pause() { this.paused = true; (handlers.pause || []).forEach(f => f()) },
      stop() { this.stops += 1; this.paused = true; (handlers.stop || []).forEach(f => f()) },
      destroy() { this.destroyed = true },
      emit(name) { (handlers[name] || []).forEach(f => f()) },
      end() { this.paused = true; (handlers.ended || []).forEach(f => f()) },
    }
    ;['Play', 'Pause', 'Stop', 'Ended', 'Error', 'Waiting', 'Canplay'].forEach((n) => {
      ctx['on' + n] = (f) => { (handlers[n.toLowerCase()] = handlers[n.toLowerCase()] || []).push(f) }
    })
    list.push(ctx)
    return ctx
  }
}

function loadPlay() {
  const requests = []
  const audios = []
  global.getApp = () => ({
    globalData: { user_id: 9, statusBarHeight: 20, navBarHeight: 44, features: {} },
    isDevEnv: () => false, getUserID: () => 9, getAvatar: () => '',
    sendRequest: (options) => { requests.push(options) },
  })
  const storage = new Map()
  global.wx = {
    getStorageSync: key => storage.get(key) || '', setStorageSync(key, value) { storage.set(key, value) },
    getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375, screenHeight: 812, safeArea: { bottom: 800 } }),
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375, screenHeight: 812, safeArea: { bottom: 800 } }),
    getMenuButtonBoundingClientRect: () => ({ left: 300 }),
    showLoading() {}, hideLoading() {}, showToast() {}, setKeepScreenOn() {},
    createInnerAudioContext: audioFactory(audios),
    createSelectorQuery: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }) }),
  }
  let config = null
  global.Page = (cfg) => { config = cfg }
  const abs = path.join(ROOT, 'pages/play/index.js')
  const src = fs.readFileSync(abs, 'utf8')
  const dir = path.dirname(abs)
  const localRequire = (id) => require(id.startsWith('.') ? path.resolve(dir, id) : id)
  new Function('Page', 'getApp', 'wx', 'require', 'module', 'exports', src)(global.Page, global.getApp, global.wx, localRequire, { exports: {} }, {})
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = (patch, cb) => {
    Object.keys(patch || {}).forEach((key) => {
      const parts = key.split('.'); let o = page.data
      for (let i = 0; i < parts.length - 1; i++) { o[parts[i]] = o[parts[i]] || {}; o = o[parts[i]] }
      o[parts[parts.length - 1]] = patch[key]
    })
    if (cb) cb()
  }
  return { page, audios, requests }
}

test('站点配乐:同一首只是重签名(Expires/Signature 变了)不从头再放', () => {
  const { page, audios } = loadPlay()
  page._syncStationBgm({ audioUrl: 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/a.mp3?Expires=1&Signature=x' })
  assert.equal(audios.length, 1)
  assert.equal(audios[0].plays, 1)
  page._syncStationBgm({ audioUrl: 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/a.mp3?Expires=2&Signature=y' })
  assert.equal(audios.length, 1, '换签名不能新建播放器')
  assert.equal(audios[0].stops, 0, '原来那首不能被停掉重来')
  page._syncStationBgm({ audioUrl: 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/b.mp3?Expires=3' })
  assert.equal(audios.length, 2, '真换了一首才换')
  assert.equal(audios[0].destroyed, true)
  page._stopStationBgm()
})

test('旁白讲话时配乐压低,讲完抬回;两路旁白交替时不被先停的那路提前抬回', () => {
  const { page, audios } = loadPlay()
  page._syncStationBgm({ audioUrl: 'https://x.test/upload/bgm.mp3' })
  const bgm = audios[0]
  assert.equal(bgm.volume, 0.35)
  page._duckBgm('guide', true)
  assert.equal(bgm.volume, 0.14)
  page._duckBgm('chap', true)
  page._duckBgm('guide', false)
  assert.equal(bgm.volume, 0.14, '还有一路在讲')
  page._duckBgm('chap', false)
  assert.equal(bgm.volume, 0.35)
  // 旁白讲着时才起的配乐,一上来就是压低的音量
  page._stopStationBgm()
  page._duckBgm('guide', true)
  page._syncStationBgm({ audioUrl: 'https://x.test/upload/bgm2.mp3' })
  assert.equal(audios[audios.length - 1].volume, 0.14)
  page._duckBgm('guide', false)
  page._stopStationBgm()
})

test('章节旁白每章自动只播一次:故事流重铺不再从头重放;声音钮手动重听照常', () => {
  const { page, audios } = loadPlay()
  page.data.chapter = { id: 7, audioUrl: 'https://x.test/upload/narr.mp3?Expires=1' }
  page.playChapterAudio(true)
  const guide = audios[0]
  assert.equal(guide.plays, 1)
  guide.end()
  page.data.chapter = { id: 7, audioUrl: 'https://x.test/upload/narr.mp3?Expires=2' }
  page.playChapterAudio(true)
  assert.equal(guide.plays, 1, '同一章同一段旁白,重铺不再自动播')
  page.playChapterAudio()
  assert.equal(guide.plays, 2, '玩家点声音钮仍可重听')
  page.data.chapter = { id: 8, audioUrl: 'https://x.test/upload/narr2.mp3' }
  page.playChapterAudio(true)
  assert.equal(guide.plays, 3, '下一章照常自动播')
})

test('站点语音讲解:这一站任务打开时自动讲一次;已完成的站 / 没配音频不讲;旁白播放器共用(顶掉章节旁白)', () => {
  const { page, audios } = loadPlay()
  page.data.chapter = { id: 1, audioUrl: 'https://x.test/upload/chapter.mp3' }
  page.playChapterAudio(true)
  page._autoStationVoice({ nodeId: 5, audio: 'https://x.test/upload/station.mp3', done: false })
  assert.equal(audios.length, 1, '与章节旁白共用一个播放器')
  assert.match(audios[0].src, /station\.mp3$/)
  assert.equal(page.data.audioNodeId, 5)
  const plays = audios[0].plays
  page._autoStationVoice({ nodeId: 5, audio: 'https://x.test/upload/station.mp3', done: false })
  assert.equal(audios[0].plays, plays, '同一站只自动讲一次')
  page._autoStationVoice({ nodeId: 6, audio: 'https://x.test/upload/s6.mp3', done: true })
  page._autoStationVoice({ nodeId: 7, audio: '', done: false })
  assert.equal(audios[0].plays, plays)
})

test('相机 / 扫码回来(onHide 已把加载代次 +1)仍认这一站的任务页,照片和码不再被当成过期结果丢掉', () => {
  const { page } = loadPlay()
  page._playOwner = page._playMember()
  page._playLoginContext = page._capturePlayLoginContext()
  page.data.screen = 'gamePlay'
  page.data.game = { nodeId: 3 }
  const context = page._legacyGameContext()
  page._playLoadEpoch = (page._playLoadEpoch || 0) + 1   // onHide
  assert.equal(page._isLegacyGameCurrent(context), false, '原判据:一切后台就失效(这正是丢照片的原因)')
  assert.equal(page._isLegacyGameReturn(context), true)
  page.data.screen = ''
  assert.equal(page._isLegacyGameReturn(context), false, '任务页已关:回来的结果不落')
  page.data.screen = 'gamePlay'
  page.data.game = { nodeId: 3 }
  assert.equal(page._isLegacyGameReturn(context), false, '换了一局任务也不落')
  const js = read('pages/play/index.js')
  const photo = js.slice(js.indexOf('  photoGame() {'), js.indexOf('  submitPhoto(nodeId, pic) {'))
  assert.match(photo, /app\.chooseImage\(function \(urls\) \{\n\s+if \(picked \|\| !that\._isLegacyGameReturn\(context\)\) return;/)
  const scan = js.slice(js.indexOf('  scanGame() {'), js.indexOf('  photoGame() {'))
  assert.equal((scan.match(/_isLegacyGameReturn\(context\)/g) || []).length, 2)
})

test('回前台续上 onHide 时在响的那几路(站点配乐 / 旁白),不再只硬拉主题配乐', () => {
  const js = read('pages/play/index.js')
  const show = js.slice(js.indexOf('  onShow() {'), js.indexOf('  queueStartGame(node)'))
  assert.match(show, /audioSession\.resumeAll\(\);/)
  assert.doesNotMatch(show, /this\._themeBgm\.play\(\)/)
  const audioSession = require('../../pages/play/utils/audio-session.js').createAudioSession({})
  const make = () => ({ paused: false, plays: 0, pause() { this.paused = true }, play() { this.plays += 1; this.paused = false } })
  const station = audioSession.register(make(), { kind: 'bgm' })
  const theme = audioSession.register(Object.assign(make(), { paused: true }), { kind: 'bgm' })
  audioSession.pauseAll()
  audioSession.resumeAll()
  assert.equal(station.plays, 1, '站点配乐续上')
  assert.equal(theme.plays, 0, '被站点配乐让开的主题配乐不被顶上来')
})

test('通关后结局章(含结尾音乐)要播:局已结束时服务端把结局章放在 story.chapter', async () => {
  const { page, requests } = loadPlay()
  page._playOwner = page._playMember()
  page._playLoginContext = page._capturePlayLoginContext()
  page.data.topicId = 990017
  page.data.activityId = 9
  let played = null
  page._playEndingChapter = (chapter) => { played = chapter }
  page._loadLifeEnding(() => {})
  const req = requests[requests.length - 1]
  assert.match(req.url, /journey\/ending/)
  req.success({ code: 200, data: { title: '尾声', code: 'ending1', story: { chapter: { id: 89, name: '尾声', blocks: [{ type: 'text', content: '雨停了。' }], audioUrl: 'https://x.test/upload/end.mp3' } } } })
  await new Promise(resolve => setImmediate(resolve))
  assert.ok(played, '归档结果里的结局章也要当故事流播')
  assert.equal(played.audioUrl, 'https://x.test/upload/end.mp3')
})

test('弹层玩法的配乐交给本页唯一的站点配乐通道', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /bind:actionfail="onAdvancedActionFail" bind:bgm="onAdvancedBgm" \/>/)
  const { page, audios } = loadPlay()
  page.data.advancedPlay = { show: true, node: { nodeId: 1 } }
  page.onAdvancedBgm({ detail: { music: { audioUrl: 'https://x.test/upload/panel.mp3', loop: true } } })
  assert.equal(audios.length, 1)
  assert.equal(audios[0].volume, 0.35, '跟页面配乐同一音量档,不是 1.0')
  page.data.themeBgmOn = false
  page.toggleThemeBgm()   // 开 → 关以外的分支也覆盖到:先确认关时会停
  page.onAdvancedBgm({ detail: { music: null } })
  assert.equal(audios[0].destroyed, true)
})

test('作者编辑器:结局章与序章一样不要求节点', () => {
  const policy = require('../../pages/publish/utils/publish/pro-editor-policy.js')
  assert.equal(policy.chapterHasRequiredNodes({ productType: 1 }, { name: '尾声', nodes: [], ending: { fallback: true, kind: 'fallback' } }), true)
  assert.equal(policy.chapterHasRequiredNodes({ productType: 1 }, { name: '序章', nodes: [], opening: true }), true)
  assert.equal(policy.chapterHasRequiredNodes({ productType: 1 }, { name: '第1章', nodes: [] }), false)
})

test('作者编辑器:玩法规则每一步的输入框不随内容换 key(否则每打一个字就重建、键盘收起)', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  assert.match(wxml, /<view class="cg-step" wx:for="\{\{ ruleSteps \}\}" wx:key="index" wx:for-index="si">/)
  assert.doesNotMatch(wxml, /wx:for="\{\{ ruleSteps \}\}" wx:key="\*this"/)
})

test('作者编辑器:还没保存的站点开「到达与引导」不再是一层空白', () => {
  const wxml = read('pages/publish/fabu/runtime-structure-editor.wxml')
  assert.match(wxml, /<block wx:elif="\{\{structureEditor\.kind === 'quests' && !structureEditor\.quest\}\}">\n\s+<cy-inline-error kind="error" wx:if="\{\{structureError\}\}" title="\{\{structureError\}\}" \/>/)
  const js = read('pages/publish/fabu/theme-runtime-structure.js')
  assert.match(js, /先点「完成」保存这个节点，再设置到达与引导/)
})

test('作者编辑器:玩法草稿没有封面时也有占位卡,选中有描边可见', () => {
  const wxml = read('pages/publish/fabu/index.wxml')
  assert.match(wxml, /<image wx:if="\{\{item\.imgUrl\}\}" lazy-load="true" src="\{\{item\.imgUrl\}\}" mode="aspectFill" class="temp-rail__pic" \/>\n\s+<view wx:else class="temp-rail__pic"><\/view>/)
  const tempWxml = read('pages/publish/temp/index.wxml')
  assert.match(tempWxml, /<view class="cg-audio-name">\{\{formData\.audioFileName\|\|'导览音频'\}\}<\/view>/, '文件名本身带扩展名,不再叠一个 .mp3')
})

test('拍照扫码玩法做完沿可信收尾，通用局回任务结算，不再打开旧凭证页', async () => {
  for (const config of [{qa:{enabled:true,mode:'SHOT'}},{scan:{enabled:true}}]) for (const generic of [false,true]) {
    const {page} = loadPlay(); let arrived=0, legacy=0, refreshed=0;
    page._playOwner=9; page._playLoadEpoch=1; page._isPlayAccountCurrent=()=>true;
    page._boundPlayOwner=()=>9; page._sessionKey=()=> 'run'; page._advancedGameComponent=()=>null;
    page._hasGenericProofFlow=()=>generic; page._routeState=null;
    page.onArriveTap=()=>arrived++; page.startGame=()=>legacy++; page.refreshThemeBackpack=()=>refreshed++;
    page.data.advancedPlay={show:true,node:{nodeId:12,vm:2,advancedConfig:config}};
    await page.onAdvancedReady({detail:{nodeId:12}});
    assert.equal(legacy,0); assert.equal(arrived,generic?0:1); assert.equal(refreshed,generic?1:0);
  }
});

test('活跃一局切后台保留音乐，离开或已结束时暂停', () => {
  const {page,audios}=loadPlay();
  for (const name of ['_flushAnswerRight','suspendOrientLifecycle','_stopTopbar','_stopThemePoll','_stopZeroHpTimer','_stopStoryDust','_clearPackTimers','_stopShopNpcVoice','_stopLeadPoll','_pauseSessionClock','_persistRunSession']) page[name]=()=>{};
  page._syncStationBgm({audioUrl:'https://x.test/upload/loop.mp3'});
  const music=audios[0]; page.initAudio(); page._audio.play();
  page._runStarted=true; page.onHide();
  assert.equal(music.paused,false); assert.equal(music.obeyMuteSwitch,false);
  assert.equal(page._audio.paused,false);
  page._runStarted=false; page.onHide(); assert.equal(music.paused,true); assert.equal(page._audio.paused,true);
  page.destroyAudio(); page._stopStationBgm();
});

// ---- 第十三轮 m3-media-network:弱网 / 失败的媒体不能卡住一局 ----
test('m3:语音出错不把原生 errMsg 弹给玩家;面板仍留「点此重试」', () => {
  const src = read('pages/play/index.js')
  assert.ok(!/const msg = \(err && err\.errMsg\)/.test(src), '不再取原生 errMsg 当提示')
  assert.ok(/audioError: msg \}\);\s*cyToast\('这段音频放不出来/.test(src))
})

test('m3:配乐出错最多重试两次(3s/8s),重试用同一个播放器,之后才收掉', () => {
  const timers = []
  const realSet = global.setTimeout
  global.setTimeout = (f, ms) => { timers.push({ f, ms }); return timers.length }
  try {
    const { page, audios } = loadPlay()
    page._syncStationBgm({ audioUrl: 'https://x.test/upload/bgm.mp3' })
    const bgm = audios[0]
    assert.equal(bgm.plays, 1)
    page._retryBgm(bgm, '_stationBgm', () => page._stopStationBgm())
    assert.equal(timers[0].ms, 3000)
    timers[0].f()
    assert.equal(bgm.plays, 2, '第一次重试重新播')
    page._retryBgm(bgm, '_stationBgm', () => page._stopStationBgm())
    assert.equal(timers[1].ms, 8000)
    timers[1].f()
    assert.equal(bgm.plays, 3)
    page._retryBgm(bgm, '_stationBgm', () => page._stopStationBgm())
    assert.equal(bgm.destroyed, true, '第三次出错才放弃')
    assert.equal(page._stationBgm, null)
  } finally { global.setTimeout = realSet }
})

test('m3:下一站语音只在 wifi/4g/5g 下预热一首,且不进音频会话', () => {
  const { page, audios } = loadPlay()
  let net = 'wifi'
  global.wx.getNetworkType = (o) => o.success({ networkType: net })
  page.data.nodes = [
    { nodeId: 1, audio: 'https://x.test/a.mp3', done: false },
    { nodeId: 2, audio: 'https://x.test/b.mp3', done: false },
    { nodeId: 3, audio: 'https://x.test/c.mp3', done: false },
  ]
  page._preloadNextStationAudio(page.data.nodes[0])
  assert.equal(audios.length, 1)
  assert.equal(audios[0].src, 'https://x.test/b.mp3')
  assert.equal(audios[0].volume, 0)
  assert.equal(audios[0].plays, 0, '只预热,不播')
  page._preloadNextStationAudio(page.data.nodes[0])
  assert.equal(audios.length, 1, '同一站只预热一次')
  net = '3g'
  page._preloadNextStationAudio(page.data.nodes[1])
  assert.equal(audios.length, 1, '弱网不预热')
})


test('慢旁白在play前显示加载,canplay不冒充已开始播放,play才清加载', () => {
  const { page, audios } = loadPlay();
  page.initAudio(); const audio = audios[0]; audio.play = () => {};
  page.data.chapter = { id: 90, audioUrl: '/profile/slow-narration.mp3' };
  page.playChapterAudio(true);
  assert.equal(page.data.audioNodeId, 'chapter'); assert.equal(page.data.audioLoading, true);
  audio.emit('canplay'); assert.equal(page.data.audioLoading, true);
  audio.emit('play'); assert.equal(page.data.audioLoading, false); assert.equal(page.data.audioPlaying, true);
});

test('销毁后的旧旁白和换局前的回调不覆盖当前音频状态', () => {
  const { page, audios } = loadPlay();
  page.initAudio(); const old = audios[0]; page.destroyAudio(); page.initAudio();
  page.setData({ audioNodeId: 'chapter', audioLoading: true, audioPlaying: false, audioError: '' });
  old.emit('play'); old.emit('error'); old.emit('canplay');
  assert.equal(page.data.audioLoading, true); assert.equal(page.data.audioError, '');
  page.data.topicId = 88; audios[1].emit('play');
  assert.equal(page.data.audioPlaying, false);
  page.initAudio(); assert.equal(audios.length, 3, '新局取得自己的播放器回调');
});
