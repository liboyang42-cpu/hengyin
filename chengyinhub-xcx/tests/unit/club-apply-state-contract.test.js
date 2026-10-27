const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const JS_PATH = 'pages/club/apply/index.js'
const WXML = fs.readFileSync(path.join(ROOT, 'pages/club/apply/index.wxml'), 'utf8')
const WXSS = fs.readFileSync(path.join(ROOT, 'pages/club/apply/index.wxss'), 'utf8')

/**
 * 把 utils/ 里的真模块编进页面沙箱:形变模块用 wx.createSelectorQuery、
 * 实名 util 用 getApp(),普通 require 进来的模块拿不到沙箱全局 —— 那样测到的是桩。
 * 它自己的相对 require(如 form-state)按 utils/ 目录解析,纯函数直接真 require。
 */
function compileInSandbox(rel, base) {
  const src = fs.readFileSync(path.join(ROOT, rel), 'utf8')
  const mod = { exports: {} }
  const context = Object.assign({}, base, { module: mod, exports: mod.exports })
  context.require = (dep) => (dep.startsWith('.')
    ? require(path.join(ROOT, path.dirname(rel), dep))
    : base.require(dep))
  vm.runInNewContext(src, context, { filename: rel })
  return mod.exports
}

function loadPage(mutate) {
  let source = fs.readFileSync(path.join(ROOT, JS_PATH), 'utf8')
  if (mutate) {
    const changed = mutate(source)
    assert.notEqual(changed, source, '负控锚点失效')
    source = changed
  }
  const requests = []
  const roleCallbacks = []
  const nextTicks = []
  const navigations = []
  let definition
  let snapshotReady = false
  let clubLeader = false
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserType: () => 0,
    getUserRole: () => 'player',
    setUserRole() {},
    chooseImage() {},
    sendRequest: (options) => requests.push(options),
  }
  const roleGuard = {
    load: (callback) => roleCallbacks.push(callback),
    hasSnapshot: () => snapshotReady,
    isClubLeader: () => clubLeader,
    clear() {},
  }
  const timers = []
  const sandbox = {
    // 形变收尾用 setTimeout;沙箱里不给的话页面一进形变就抛
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length },
    clearTimeout: (id) => { if (id) timers[id - 1] = null },
    getApp: () => app,
    Page: (value) => { definition = value },
    require: (id) => {
      if (id.includes('roleGuard')) return roleGuard
      if (id.includes('wizard-morph')) return compileInSandbox('utils/wizard-morph.js', sandbox)
      if (id.includes('publisher-identity')) return compileInSandbox('utils/publisher-identity.js', sandbox)
      return require(path.resolve(path.dirname(path.join(ROOT, JS_PATH)), id))
    },
    wx: {
      showModal() {}, showToast() {},
      navigateBack: () => navigations.push('back'),
      redirectTo: (options) => navigations.push(options.url),
      reLaunch: (options) => navigations.push('reLaunch:' + options.url),
      nextTick: (fn) => nextTicks.push(fn),
      // 开场形变要两级 boundingClientRect 量起止几何。喂定值进去,
      // 下面就能断言「位移是中心对中心」这件事 —— 按顶边算会差半个高度。
      createSelectorQuery: () => {
        let sel = ''
        const q = {
          in: () => q,
          select: (s) => { sel = s; return q },
          selectAll: (s) => { sel = s; return q },
          boundingClientRect: (cb) => { q._cb = cb; q._sel = sel; return q },
          exec: () => {
            if (q._sel === '.intro-cta') return q._cb({ left: 16, top: 600, width: 343, height: 48 })
            if (q._sel === '.prog__seg') {
              return q._cb([0, 1, 2, 3].map((i) => ({
                left: 16 + i * 86.75, right: 16 + i * 86.75 + 82.75,
                top: 54, width: 82.75, height: 3,
              })))
            }
            return q._cb(null)
          },
        }
        return q
      },
    },
  }
  vm.runInNewContext(source, sandbox, { filename: JS_PATH })
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done.call(this) },
  })
  return {
    page, requests, roleCallbacks, navigations, nextTicks, timers, app,
    // P-23:进页同时问一次商家身份;回最新一条 /api/merchant/access/me
    answerMerchant(res) {
      const asks = requests.filter((r) => r.url === '/api/merchant/access/me')
      assert.ok(asks.length, '进页必须问一次商家身份')
      asks[asks.length - 1].success(res || { code: 200, data: { active: false, applicationState: 'NONE' } })
    },
    setSnapshot(value) { snapshotReady = value },
    setClubLeader(value) { clubLeader = value },
  }
}

test('身份快照完成前不露申请表；失败原位重试且草稿不丢', () => {
  const h = loadPage()
  h.page.data.leaderName = '林野'
  h.page.onLoad()
  assert.equal(h.page.data.bootstrapState, 'checking-role')
  assert.equal(h.roleCallbacks.length, 1)
  assert.match(WXML, /bootstrapState === 'ready' && mode==='form'/)

  h.roleCallbacks[0](null)
  assert.equal(h.page.data.bootstrapState, 'error')
  assert.equal(h.page.data.leaderName, '林野')

  h.page.retryBootstrap()
  h.page.retryBootstrap()
  assert.equal(h.roleCallbacks.length, 2, '同步连点只能发起一轮资格检查')
  h.setSnapshot(true)
  h.roleCallbacks[1]({ role: 'player', permission: { canApplyClub: true } })
  // P-23(2026-10-27):资格快照与商家身份都回来才放行
  assert.equal(h.page.data.bootstrapState, 'checking-role')
  h.answerMerchant()
  assert.equal(h.page.data.bootstrapState, 'ready')
  // 资格确认完成先落到四步之前的开场屏;表单必须按下「开始申请」才露出来
  assert.equal(h.page.data.mode, 'intro')
  assert.equal(h.page.data.leaderName, '林野', '开场屏不得丢草稿')
  h.page.startForm()
  assert.equal(h.page.data.mode, 'form')
  assert.equal(h.page.data.step, 1)
  assert.equal(h.page.data.leaderName, '林野')
  // 第一步的返回是退回开场屏,不是摔出页面(离场只发生在开场屏)
  h.page.onBack()
  assert.equal(h.page.data.mode, 'intro')
  assert.deepEqual(h.navigations, [], '第一步返回不得触发任何导航')
  assert.equal(h.page.data.leaderName, '林野', '退回开场屏不得丢草稿')
})

test('已是主理人的真实快照直接进入持久完成态', () => {
  const h = loadPage()
  h.page.onLoad()
  h.setSnapshot(true)
  h.setClubLeader(true)
  h.roleCallbacks[0]({ role: 'club', permission: { canApplyClub: true } })
  assert.equal(h.page.data.bootstrapState, 'ready')
  assert.equal(h.page.data.mode, 'done')
  assert.equal(h.page.data.doneState, 'ready')
})

test('提交 pending/业务失败原位可见、草稿保留且同步防重复', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    bootstrapState: 'ready', mode: 'form', step: 4, canNext: true,
    leaderName: '林野', phone: '13800138000', experience: '1-5场',
    identityRegistered: true,
  })
  h.page.submit()
  h.page.submit()
  assert.equal(h.requests.length, 1)
  assert.equal(h.page.data.submitting, true)
  assert.match(WXML, /<cy-inline-error\b[^>]*submitError[^>]*bind:action="retrySubmit"/s)
  assert.match(WXML, /loading="\{\{submitting\}\}"/)

  h.requests[0].success({ code: 500, msg: '资格服务暂不可用' })
  assert.equal(h.page.data.submitting, false)
  assert.equal(h.page.data.submitError, '资格服务暂不可用')
  assert.equal(h.page.data.step, 4)
  assert.equal(h.page.data.leaderName, '林野')

  h.page.retrySubmit()
  assert.equal(h.requests.length, 2)
  h.requests[1].success({ code: 200 })
  assert.equal(h.page.data.mode, 'done')
  assert.equal(h.page.data.doneState, 'ready')
  assert.equal(h.page.data.submissionReceipt.leaderName, '林野')
})

test('只有真实 transport errMsg 判网络失败，业务 body 不冒充设备断网', () => {
  const business = loadPage()
  Object.assign(business.page.data, {
    bootstrapState: 'ready', step: 4, canNext: true,
    leaderName: '林野', phone: '13800138000', experience: '1-5场',
    identityRegistered: true,
  })
  business.page.submit()
  business.requests[0].fail({ msg: '网络服务暂不可用' })
  assert.equal(business.page.data.submitErrorKind, 'data')
  assert.equal(business.page.data.submitError, '网络服务暂不可用')

  const transport = loadPage()
  Object.assign(transport.page.data, business.page.data, { submitting: false, submitError: '' })
  transport.page.submit()
  transport.requests[0].fail({ errMsg: 'request:fail timeout' })
  assert.equal(transport.page.data.submitErrorKind, 'network')
  assert.match(transport.page.data.submitError, /网络/)
})

test('提交 API/payload 字段保持既有合同，卸载后迟到回调不可写状态', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    bootstrapState: 'ready', step: 4, canNext: true,
    leaderName: '林野', phone: '13800138000', identity: '户外领队', coFounders: '',
    experience: '1-5场', maxEventSize: '30', avgEventSize: '12',
    canDesignRoute: 1, canDesignTask: 0, canNpc: 0, canMerchantCoop: 1,
    hasGuideCert: 0, certImages: ['a.jpg'],
    identityRegistered: true,
  })
  h.page.submit()
  const request = h.requests[0]
  assert.equal(request.url, '/api/club/become-leader')
  assert.equal(request.method, 'POST')
  assert.deepEqual(Object.keys(JSON.parse(request.data)).sort(), [
    'avgEventSize', 'canDesignRoute', 'canDesignTask', 'canMerchantCoop', 'canNpc',
    'certImages', 'coFounders', 'experience', 'hasExperience', 'hasGuideCert',
    'identity', 'leaderName', 'maxEventSize', 'phone',
  ].sort())
  h.page.onUnload()
  request.success({ code: 200 })
  assert.equal(h.page.data.mode, 'intro', '迟到回调不得把已卸载页面的 mode 改掉')
  assert.equal(h.page.data.submitting, true, '迟到回调不得重写已卸载页面')
})

// 2026-09-06:本页版式以本轮裁决为准(类名 .prog__seg / .opt / .cap),
// 标题下的副标题按裁决删除 —— 所以不再断言「问句与说明成对进 data」。
// 断言只换选择器,保护点(读屏语义 + 触控下限)照旧,且下限比 88rpx 更高。
test('步骤、选项与主操作具备读屏语义和 88rpx 触控下限', () => {
  assert.match(WXML, /class="prog[^"]*"[^>]*aria-role="status"[^>]*aria-label="第\{\{step\}\}步，共4步，\{\{stepTitle\}\}"/s)
  assert.match(WXML, /class="opt [^>]*aria-role="radio"[^>]*aria-checked=/s)
  assert.match(WXML, /class="cap [^>]*aria-role="checkbox"[^>]*aria-checked=/s)
  // 单选项与能力行都是整行可点,触控高度必须都过 88rpx 下限
  assert.match(WXSS, /\.opt\s*\{[^}]*min-height:\s*96rpx/s)
  assert.match(WXSS, /\.cap\s*\{[^}]*min-height:\s*126rpx/s)
})

test('epoch 负控：移除保护后旧资格回调会覆盖新一轮结果', () => {
  const h = loadPage((source) => source.replaceAll(
    'if (bootstrapEpoch !== that._bootstrapEpoch) return;',
    ''
  ))
  h.page.onLoad()
  h.roleCallbacks[0](null)
  h.page.retryBootstrap()
  h.setSnapshot(true)
  h.roleCallbacks[1]({ role: 'player', permission: { canApplyClub: true } })
  h.answerMerchant()
  assert.equal(h.page.data.bootstrapState, 'ready')
  h.setSnapshot(false)
  h.roleCallbacks[0](null)
  assert.equal(h.page.data.bootstrapState, 'error', '负控应复现旧回调覆盖新状态')
})

test('换步重播大标题进场动画：stepIn 先落再抬，类名两端真对得上', () => {
  const h = loadPage()
  h.page.data.stepIn = true

  h.page.refreshStep(2)
  assert.equal(h.page.data.step, 2)
  assert.equal(h.page.data.stepIn, false, 'setData 同步阶段必须先摘掉 .in，否则动画不重播')
  assert.equal(h.nextTicks.length, 1, '必须在下一帧把 .in 加回来')
  h.nextTicks[0]()
  assert.equal(h.page.data.stepIn, true)

  // 状态机要落到真类名上才不是空转;进场动画复用全仓 .cy-rise-in,不自造第二套关键帧
  assert.match(WXML, /class="step-head \{\{stepIn \? 'cy-rise-in' : ''\}\}"/)
  // 步骤内容不靠 JS,靠 wx:if 重挂重播,必须是真实节点而非 <block>
  for (const n of [1, 2, 3, 4]) {
    assert.match(WXML, new RegExp(`<view class="step cy-rise-in" wx:if="\\{\\{step===${n}\\}\\}">`))
  }
})

test('减动效兜底是真接线，不是白蹭共用样式表里的那条规则', () => {
  const JS = fs.readFileSync(path.join(ROOT, JS_PATH), 'utf8')
  // 共用兜底 `.cy-motion-reduced .cy-rise-in` 要生效，根节点必须真的挂得上这个类，
  // 且 reducedMotion 真的从设置项读出来 —— 少任一环，兜底就只是写在别处的一句空话。
  assert.match(WXML, /<view class="theme-dark \{\{reducedMotion \? 'cy-motion-reduced' : ''\}\}">/)
  assert.match(JS, /readReducedMotion/)
  assert.match(JS, /onShow\(\)[\s\S]{0,200}readReducedMotion\(\)/)
})

test('负控：refreshStep 不摘 .in 时动画不会重播', () => {
  const h = loadPage((source) => source.replace('      stepIn: false\n', ''))
  h.page.data.stepIn = true
  h.page.refreshStep(2)
  assert.equal(h.page.data.stepIn, true, '.in 没被摘掉 = 同一个动画不会重新触发')
})

test('开场形变的起止几何按中心对中心算，不是按顶边', () => {
  const h = loadPage()
  h.page.data.bootstrapState = 'ready'
  h.page.data.reducedMotion = false
  h.page.startForm()

  assert.equal(h.page.data.mode, 'form')
  assert.equal(h.page.data.morphing, true, '形变期间旧开场屏要留着退场')
  const m = h.page.data.morph
  assert.ok(m, '没量到几何就不该进形变态')
  // CTA 中心 y = 600 + 48/2 = 624;进度轨中心 y = 54 + 3/2 = 55.5
  assert.equal(m.dy, 55.5 - 624, 'scaleY 以中心为原点，位移必须按中心算')
  assert.equal(m.sy, 3 / 48, '纵向压扁比 = 轨道高 / 胶囊高')
  // 白色占比 = 第一段 / 整条轨道(含段间空隙),不是写死的 25%
  assert.equal(m.fx, 82.75 / (16 + 3 * 86.75 + 82.75 - 16))
  assert.equal(m.x, 16); assert.equal(m.w, 343)

  h.page.endMorph()
  assert.equal(h.page.data.morphing, false)
  assert.equal(h.page.data.morph, null)
})

test('负控：位移按顶边算(漏掉中心修正)必须判红', () => {
  // 中心修正现在住在 utils/wizard-morph.js 里,负控直接改那份源码
  const src = fs.readFileSync(path.join(ROOT, 'utils/wizard-morph.js'), 'utf8')
  const broken = src.replace(
    'dy: (first.top + first.height / 2) - (cta.top + cta.height / 2),',
    'dy: first.top - cta.top,')
  assert.notEqual(broken, src, '负控锚点失效')
  assert.match(src, /first\.height \/ 2\) - \(cta\.top \+ cta\.height \/ 2\)/,
    '位移必须按中心对中心算:scaleY 以中心为原点，按顶边算会差半个胶囊高')
})

test('减动效时不放形变，直接切到第一步', () => {
  const h = loadPage()
  h.page.data.bootstrapState = 'ready'
  h.page.data.reducedMotion = true
  h.page.startForm()
  assert.equal(h.page.data.mode, 'form')
  assert.equal(h.page.data.morphing, false)
  assert.equal(h.page.data.morph, null)
})

// P-23(owner 2026-10-27 p111):已有商家行的账号进页就说明并给去商家端的出口,不填完 4 步才报错
for (const [label, res, active, target] of [
  ['申请中的商家行', { code: 200, data: { active: false, applicationState: 'PENDING', roleCode: 'MERCHANT_OWNER', merchant: { id: 3 } } }, false, '/pages/merchant/apply/index'],
  ['生效中的商家(含员工)', { code: 200, data: { active: true, roleCode: 'MERCHANT_OWNER', merchant: { id: 3 } } }, true, 'reLaunch:/pages/merchant/index/index'],
  ['异常商家行(403)', { code: 403, msg: '', roleViewSuppressed: true }, false, '/pages/merchant/apply/index'],
]) {
  test(`P-23 ${label}:进页即说明并引导到商家端`, () => {
    const h = loadPage()
    h.page.onLoad()
    h.setSnapshot(true)
    h.roleCallbacks[0]({ role: 'player', permission: { canApplyClub: true } })
    h.answerMerchant(res)
    assert.equal(h.page.data.bootstrapState, 'merchant')
    assert.equal(h.page.data.merchantActive, active)
    h.page.goMerchantSide()
    assert.deepEqual(h.navigations, [target])
  })
}

test('P-23 本地身份已是商家:不问后端,直接说明并给「切换到商家端」', () => {
  const h = loadPage()
  h.app.getUserRole = () => 'merchant'
  h.page.onLoad()
  assert.equal(h.page.data.bootstrapState, 'merchant')
  assert.equal(h.page.data.merchantActive, true)
  assert.equal(h.roleCallbacks.length, 0)
  assert.match(WXML, /bootstrapState === 'merchant'[\s\S]*primary="\{\{merchantActive \? '切换到商家端' : '查看商家入驻'\}\}"[\s\S]*bind:primary="goMerchantSide"/)
})

test('P-23 商家身份问不到时不拦人(后端提交仍兜底)', () => {
  const h = loadPage()
  h.page.onLoad()
  h.setSnapshot(true)
  h.roleCallbacks[0]({ role: 'player', permission: { canApplyClub: true } })
  h.requests.find((r) => r.url === '/api/merchant/access/me').fail({ errMsg: 'request:fail timeout' })
  assert.equal(h.page.data.bootstrapState, 'ready')
})

test('申请主理人不要求实名，直接提交资料且不发送身份证字段', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    bootstrapState: 'ready', mode: 'form', step: 4,
    leaderName: '测试主理人', phone: 'test_contact', experience: '没有经验',
  })
  h.page.refreshStep(4)
  assert.equal(h.requests.length, 0)
  assert.equal(h.page.data.canNext, true)
  h.page.submit()
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].url, '/api/club/become-leader')
  assert.doesNotMatch(h.requests[0].data, /realName|idCard/)
  h.requests[0].success({ code: 200 })
  assert.equal(h.page.data.mode, 'done')
})

test('删除最后一张失败资质图后恢复无资质提交', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    bootstrapState: 'ready', mode: 'form', step: 4,
    certImages: ['wxfile://tmp/failed-cert.jpg'], certErrorIndexes: [0],
  })
  h.page.validate()
  assert.equal(h.page.data.canNext, false)

  h.page.removeCert({ detail: { index: 0 } })

  assert.equal(h.page.data.certImages.length, 0)
  assert.equal(h.page.data.certErrorIndexes.length, 0)
  assert.equal(h.page.data.canNext, true)
  h.page.submit()
  assert.equal(h.requests.length, 1)
  assert.equal(JSON.parse(h.requests[0].data).certImages, '')
})

test('删除一张资质图后仍有失败项不得提交', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    bootstrapState: 'ready', mode: 'form', step: 4,
    certImages: ['wxfile://tmp/first.jpg', 'wxfile://tmp/second.jpg'],
    certErrorIndexes: [0, 1],
  })
  h.page.validate()
  h.page.removeCert({ detail: { index: 0 } })

  assert.equal(h.page.data.certImages.length, 1)
  assert.equal(h.page.data.certErrorIndexes.length, 1)
  assert.equal(h.page.data.certErrorIndexes[0], 0)
  assert.equal(h.page.data.canNext, false)
  h.page.submit()
  assert.equal(h.requests.length, 0)
})

test('删除失败资质图后仍有上传项不得提交', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    bootstrapState: 'ready', mode: 'form', step: 4,
    certImages: ['wxfile://tmp/uploading.jpg', 'wxfile://tmp/failed.jpg'],
    certUploading: [0], certErrorIndexes: [1],
  })
  h.page.validate()
  h.page.removeCert({ detail: { index: 1 } })

  assert.equal(h.page.data.certErrorIndexes.length, 0)
  assert.equal(h.page.data.certUploading.length, 1)
  assert.equal(h.page.data.canNext, false)
  h.page.submit()
  assert.equal(h.requests.length, 0)
})

test('删除失败资质图后错误标记随索引重排贴到正确图片', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    bootstrapState: 'ready', mode: 'form', step: 4,
    certImages: ['/profile/upload/ok-a.jpg', 'wxfile://tmp/failed-b.jpg', '/profile/upload/ok-c.jpg', 'wxfile://tmp/failed-d.jpg'],
    certErrorIndexes: [1, 3],
  })
  h.page.validate()
  h.page.removeCert({ detail: { index: 1 } })

  assert.deepEqual(h.page.data.certImages, ['/profile/upload/ok-a.jpg', '/profile/upload/ok-c.jpg', 'wxfile://tmp/failed-d.jpg'])
  assert.deepEqual(h.page.data.certErrorIndexes, [2])
  assert.equal(h.page.data.certImages[h.page.data.certErrorIndexes[0]], 'wxfile://tmp/failed-d.jpg')
  assert.equal(h.page.data.canNext, false)
})

test('异常删除索引不删错资质图也不清错误标记', () => {
  for (const index of [-1, 2, 9, 0.5, NaN, '0', '', null, undefined, true]) {
    const h = loadPage()
    const certImages = ['wxfile://tmp/first.jpg', 'wxfile://tmp/second-failed.jpg']
    Object.assign(h.page.data, {
      bootstrapState: 'ready', mode: 'form', step: 4,
      certImages: certImages.slice(), certErrorIndexes: [1],
    })
    h.page.validate()
    h.page.removeCert({ detail: { index } })
    assert.deepEqual(h.page.data.certImages, certImages, String(index))
    assert.deepEqual(h.page.data.certErrorIndexes, [1], String(index))
    assert.equal(h.page.data.canNext, false, String(index))
  }
  const h = loadPage()
  Object.assign(h.page.data, { step: 4, certImages: ['wxfile://tmp/only.jpg'], certErrorIndexes: [0] })
  h.page.removeCert({})
  h.page.removeCert(undefined)
  assert.deepEqual(h.page.data.certImages, ['wxfile://tmp/only.jpg'])
})

test('真实页面 setData 回调延后执行时删除最后失败图后仍恢复提交', () => {
  const h = loadPage()
  const pending = []
  // 真机 setData 回调在渲染层回包后才执行；回调前按钮仍应保持原态，回调后必须恢复。
  h.page.setData = function (patch, done) {
    Object.assign(this.data, patch)
    if (done) pending.push(done)
  }
  Object.assign(h.page.data, {
    bootstrapState: 'ready', mode: 'form', step: 4,
    certImages: ['wxfile://tmp/failed-cert.jpg'], certErrorIndexes: [0], canNext: false,
  })
  h.page.removeCert({ detail: { index: 0 } })
  assert.equal(h.page.data.certImages.length, 0)
  assert.equal(h.page.data.canNext, false)
  assert.equal(pending.length, 1, '删除后必须挂 validate 回调')
  pending.shift().call(h.page)
  assert.equal(h.page.data.canNext, true)
  h.page.submit()
  assert.equal(h.requests.length, 1)
})

test('资质图片上传失败或仍在上传时不得提交本机临时路径', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    bootstrapState: 'ready', mode: 'form', step: 4,
    identityRegistered: true,
    certImages: ['wxfile://tmp/failed-cert.jpg'],
    certErrorIndexes: [0],
  })
  h.page.validate()
  assert.equal(h.page.data.canNext, false)
  h.page.submit()
  assert.equal(h.requests.length, 0)

  h.page.data.certErrorIndexes = []
  h.page.data.certUploading = [0]
  h.page.validate()
  assert.equal(h.page.data.canNext, false)
  h.page.submit()
  assert.equal(h.requests.length, 0)
})

function assertDirectLeaderActivationCopy(wxml) {
  assert.doesNotMatch(wxml, /有证的话审核会快一些|等待审核|待审核|审核通过后/, '即时生效的身份申请不得暗示等待审核')
  assert.match(wxml, /填完身份立即生效，不用等审核/)
  assert.match(wxml, /class="st-desc">你已成为俱乐部主理人，身份立即生效。/)
  assert.match(wxml, /用于后续等级评定/)
}

test('主理人身份立即生效，资质只说明后续等级评定', () => {
  assertDirectLeaderActivationCopy(WXML)
})

test('负控：资质文案恢复审核提速暗示必须判红', () => {
  const mutated = WXML.replace('用于后续等级评定', '有证的话审核会快一些')
  assert.notEqual(mutated, WXML, '文案负控锚点必须存在')
  assert.throws(() => assertDirectLeaderActivationCopy(mutated), /即时生效的身份申请不得暗示等待审核/)
})

test('负控：完成态失去身份立即生效提示必须判红', () => {
  const mutated = WXML.replace('你已成为俱乐部主理人，身份立即生效。', '身份申请已提交。')
  assert.notEqual(mutated, WXML, '完成态负控锚点必须存在')
  assert.throws(() => assertDirectLeaderActivationCopy(mutated))
})

// 2026-10-27 P-24(owner 真机 p98):形变保留,但白色段一开始就快速缩到第一段 ——
// 不再先画满整条、到收尾才突然变短;翻 morphRun 要等一帧,不能和首帧合并。标题叠在形变条之上。
test('P-24 形变条先快速缩到当前步长度，再随位移就位；标题不被形变条压住', () => {
  const css = fs.readFileSync(path.join(ROOT, 'style/wizard-intro.wxss'), 'utf8')
  assert.match(css, /\.morph__fill\s*\{[^}]*transition:\s*transform\s+220ms\s+cubic-bezier\(\.2, \.8, \.2, 1\)/s)
  const util = fs.readFileSync(path.join(ROOT, 'utils/wizard-morph.js'), 'utf8')
  assert.match(util, /setTimeout\(function \(\) \{\s*page\.setData\(\{ morphRun: true \}\);/)
  assert.doesNotMatch(util, /wx\.nextTick\(function \(\) \{ page\.setData\(\{ morphRun: true \}\); \}\)/)
  assert.match(WXSS, /\.step-head \{ position: relative; z-index: 21; \}/)
})
