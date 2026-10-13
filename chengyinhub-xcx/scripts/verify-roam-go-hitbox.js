'use strict'

// DevTools simulator 证据；不能替代体验版上传回读或真机验收。
const assert = require('node:assert/strict')
const path = require('node:path')
const { openMiniProgram, closeMiniProgram } = require('../tests/automator/harness.js')

const projectPath = path.resolve(__dirname, '..')
const port = Number(process.env.DEVTOOLS_AUTO_PORT || 9646)
const screenshotPath = process.env.ROAM_GO_SCREENSHOT || '/private/tmp/roam-go-hitbox.png'

async function readStartState(mp) {
  return mp.evaluate(() => {
    const pages = getCurrentPages()
    const page = pages[pages.length - 1]
    const key = page && page._introSeenKey
    return {
      pending: page && page._roamStartPending,
      key,
      seen: key ? wx.getStorageSync(key) === true : false,
    }
  })
}

async function readBox(element) {
  const offset = await element.offset()
  const size = await element.size()
  const left = Number(offset.left)
  const top = Number(offset.top)
  const width = Number(size.width)
  const height = Number(size.height)
  return { left, top, width, height, right: left + width, bottom: top + height }
}

function assertControlInsideMap(controlBox, mapBox, label) {
  const centerX = controlBox.left + controlBox.width / 2
  const centerY = controlBox.top + controlBox.height / 2
  assert.ok(
    centerX >= mapBox.left && centerX <= mapBox.right
      && centerY >= mapBox.top && centerY <= mapBox.bottom,
    `${label} 中心必须真实落在预览地图内: center=(${centerX},${centerY}) map=${JSON.stringify(mapBox)}`
  )
}

async function openIntro(mp) {
  let page
  let go
  let sideButtons = []
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    page = await mp.reLaunch('/pages/roam/index')
    await page.waitFor(3000)
    go = await page.$('.go-btn')
    sideButtons = await page.$$('.go-side')
    if (go && sideButtons.length === 2) break
  }
  assert.ok(go, '3 次页面就绪检查后仍未找到 .go-btn')
  assert.equal(sideButtons.length, 2, '必须同时找到 info 与 camera 两个侧按钮')
  return { page, info: sideButtons[0], go, camera: sideButtons[1] }
}

async function prepareStart(mp, page) {
  await page.callMethod('onShow')
  const before = await readStartState(mp)
  assert.equal(before.pending, false, '点击前启动门闩必须已释放')
  if (before.key) await mp.callWxMethod('removeStorageSync', before.key)
}

async function main() {
  const session = await openMiniProgram({ projectPath, port, connectAttempts: 100, stackAttempts: 100, retryDelay: 500 })
  const { mp } = session
  try {
    let { page, info, go, camera } = await openIntro(mp)
    const map = await page.$('.intro-map__el')
    assert.ok(map, '预览地图必须已渲染')
    const mapBox = await readBox(map)
    const controlBoxes = {
      info: await readBox(info),
      go: await readBox(go),
      camera: await readBox(camera),
    }
    assertControlInsideMap(controlBoxes.info, mapBox, 'info')
    assertControlInsideMap(controlBoxes.go, mapBox, 'GO')
    assertControlInsideMap(controlBoxes.camera, mapBox, 'camera')
    // 截图是证据增强,不是判据:DevTools 没拿到 macOS「屏幕录制」权限时它会抛
    // fail to capture screenshot,而下面三次真实 tap→状态回读才是这个检查器的本体。
    // 让环境问题吞掉真正的验证 = 一个环境权限就能让整条链路无人看守。
    let screenshotNote = screenshotPath
    try {
      await mp.screenshot({ path: screenshotPath })
    } catch (e) {
      screenshotNote = `(截图被环境挡住: ${(e && e.message) || e})`
    }

    await mp.mockWxMethod('showModal', { confirm: true, cancel: false })
    await mp.mockWxMethod('getLocation', { latitude: 31.2304, longitude: 121.4737 })
    await mp.evaluate(() => {
      const app = getApp()
      app.__roamGoVerifyRecordConsent = app.recordConsent
      app.recordConsent = () => Promise.resolve({ ok: true })
    })

    await info.tap()
    await page.waitFor(100)
    const sceneCurrent = await page.data('sceneCurrent')
    assert.equal(sceneCurrent && sceneCurrent.id, 'roam-rules', 'info tap 必须打开 roam-rules 场景')
    await page.callMethod('closeScene')
    assert.ok((await page.data('sceneCurrent')) == null, '关闭规则场景后必须回到起始页')

    await prepareStart(mp, page)
    await go.tap()
    await page.waitFor(800)
    const after = await readStartState(mp)
    assert.equal(after.seen, true, 'DevTools simulator GO tap 必须进入 goStart')
    assert.equal(after.pending, false, '启动完成后门闩必须释放')
    assert.equal(await page.data('screen'), 'map', '同意并取得定位后必须真正进入漫游地图')

    ;({ page, info, go, camera } = await openIntro(mp))
    await mp.evaluate(() => {
      const pages = getCurrentPages()
      const current = pages[pages.length - 1]
      current.__roamGoVerifyTakePhoto = current.onTakePhoto
      current.__roamGoVerifyCameraCalls = 0
      current.onTakePhoto = () => { current.__roamGoVerifyCameraCalls += 1 }
    })
    await prepareStart(mp, page)
    await camera.tap()
    await page.waitFor(900)
    const cameraCalls = await mp.evaluate(() => {
      const pages = getCurrentPages()
      const current = pages[pages.length - 1]
      return current && current.__roamGoVerifyCameraCalls
    })
    assert.equal(await page.data('screen'), 'map', 'camera tap 必须先真正进入漫游地图')
    assert.equal(cameraCalls, 1, 'camera tap 必须调用一次拍照 handler')

    console.log(`PASS DevTools simulator roam controls inside map: boxes=${JSON.stringify(controlBoxes)} map=${JSON.stringify(mapBox)} screenshot=${screenshotNote}`)
  } finally {
    try {
      await mp.evaluate(() => {
        const app = getApp()
        const pages = getCurrentPages()
        const current = pages[pages.length - 1]
        if (current && current.__roamGoVerifyTakePhoto) {
          current.onTakePhoto = current.__roamGoVerifyTakePhoto
          delete current.__roamGoVerifyTakePhoto
          delete current.__roamGoVerifyCameraCalls
        }
        if (app.__roamGoVerifyRecordConsent) {
          app.recordConsent = app.__roamGoVerifyRecordConsent
          delete app.__roamGoVerifyRecordConsent
        }
      })
    } catch (_) {}
    try { await mp.restoreWxMethod('showModal') } catch (_) {}
    try { await mp.restoreWxMethod('getLocation') } catch (_) {}
    await closeMiniProgram(session)
  }
}

main().catch((error) => {
  console.error(error && error.stack || error)
  process.exit(1)
})
