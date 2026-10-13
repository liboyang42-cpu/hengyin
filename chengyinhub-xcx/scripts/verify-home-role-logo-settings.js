'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { openMiniProgram, closeMiniProgram } = require('../tests/automator/harness')

const PROJECT = path.resolve(__dirname, '..')
const EVIDENCE_DIR = process.env.EVIDENCE_DIR || '/tmp/cy-home-logo-settings-v2-evidence'
const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

async function setIdentity(mp, identity) {
  await mp.evaluate((next) => {
    wx.setStorageSync('role', next.role)
    wx.setStorageSync('user_type', next.userType)
    wx.setStorageSync('user_id', 1)
    wx.setStorageSync('authorization', 'dev-token')
    wx.setStorageSync('open_id', 'dev-openid')
    if (next.debugView) wx.setStorageSync('debug_user_view', next.debugView)
    else wx.removeStorageSync('debug_user_view')
    const app = getApp()
    if (app && app.globalData) {
      app.globalData.role = next.role
      app.globalData.user_type = next.userType
      app.globalData.user_id = 1
      app.globalData.authorization = 'dev-token'
      app.globalData.open_id = 'dev-openid'
    }
  }, identity)
}

async function screenshot(mp, name) {
  const file = path.join(EVIDENCE_DIR, name)
  await mp.screenshot({ path: file })
  assert.ok(fs.statSync(file).size > 1000, `${name} 截图为空`)
  return file
}

async function currentPageAt(mp, expectedPath) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const page = await mp.currentPage()
    if (page && page.path === expectedPath) return page
    await wait(150)
  }
  const page = await mp.currentPage()
  assert.equal(page && page.path, expectedPath)
  return page
}

async function openMerchantSettings(mp) {
  await setIdentity(mp, { role: 'merchant', userType: 2 })
  let page = await mp.reLaunch('/pages/shezhi/shezhi')
  await wait(900)
  page = await currentPageAt(mp, 'pages/shezhi/shezhi')
  assert.equal(await page.data('isMerchantView'), true)
  assert.equal(await page.data('settingsTheme'), 'merchant')
  return page
}

async function tapSettingsRoute(mp, cellId, expectedPath, expectedRootSelector, screenshotName) {
  let page = await openMerchantSettings(mp)
  const cell = await page.$(`#${cellId}`)
  assert.ok(cell, `找不到设置入口 #${cellId}`)
  await cell.tap()
  await wait(2500)
  page = await currentPageAt(mp, expectedPath)
  assert.equal(await page.data('isMerchantView'), true, `${expectedPath} 未识别商家视角`)
  const root = await page.$(expectedRootSelector)
  assert.ok(root, `${expectedPath} 未渲染根节点 ${expectedRootSelector}`)
  assert.match(String(await root.attribute('class')), /theme-merchant/, `${expectedPath} 根节点未使用商家主题`)
  await wait(1200)
  const file = await screenshot(mp, screenshotName)
  return { path: page.path, file }
}

async function run() {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true })
  const consoleErrors = []
  const session = await openMiniProgram({
    projectPath: PROJECT,
    port: Number(process.env.DEVTOOLS_AUTO_PORT || 9547),
    stackCallTimeoutMs: Number(process.env.DEVTOOLS_STACK_CALL_TIMEOUT_MS || 20000),
  })
  const { mp } = session
  mp.on('console', (message) => {
    if (message.type === 'error') consoleErrors.push(String(message.args && message.args[0]).slice(0, 240))
  })

  try {
    await setIdentity(mp, { role: 'player', userType: 1, debugView: 'user' })
    let page = await mp.reLaunch('/pages/index/index')
    await wait(1000)
    page = await currentPageAt(mp, 'pages/index/index')
    await page.setData({
      _needsRedirect: false,
      userInfo: { avatar: '/images/d_profile.png', nickname: '玩家' },
      heroBannerList: [{ id: 'logo-player', pic: '/images/home-banner-2.jpg', name: '城市探索' }],
      bannerCurrent: 0,
    })
    await wait(250)
    const playerLogoShot = await screenshot(mp, '01-player-home-white-chein.png')

    await setIdentity(mp, { role: 'merchant', userType: 2 })
    page = await mp.reLaunch('/pages/merchant/index/index')
    await wait(1100)
    page = await currentPageAt(mp, 'pages/merchant/index/index')
    await page.setData({
      consoleState: '',
      displayName: '商家',
      merchantInfo: {},
      userInfo: { avatar: '/images/d_profile.png', nickname: '商家' },
      dashboardLoading: false,
      dashboardError: '',
      dashboardData: { revenue: 1256 },
      joinLoading: false,
      hostLoading: false,
      todoLoading: false,
      projectCards: [],
    })
    await wait(250)
    const merchantLogoShot = await screenshot(mp, '02-merchant-home-black-chein.png')

    page = await openMerchantSettings(mp)
    const settingsMainShot = await screenshot(mp, '03-merchant-settings-main-light.png')

    const overlayEvidence = []
    for (const entry of [
      { id: 'settings-privacy', open: 'goPrivacy', close: 'closePrivacySheet', name: '04-merchant-settings-privacy-light.png' },
      { id: 'settings-service-agreement', open: 'goUserAgreement', close: 'closeAgreementSheet', name: '05-merchant-settings-agreement-light.png' },
      { id: 'settings-deregister', open: 'goDeregister', close: 'closeScene', name: '06-merchant-settings-deregister-light.png' },
    ]) {
      await page.callMethod(entry.open)
      await wait(350)
      const sceneCurrent = await page.data('sceneCurrent')
      if (entry.id === 'settings-deregister') {
        assert.equal(sceneCurrent && sceneCurrent.theme, 'merchant', `${entry.id} 未使用商家主题`)
      }
      const file = await screenshot(mp, entry.name)
      overlayEvidence.push({ id: entry.id, theme: sceneCurrent && sceneCurrent.theme, file })
      await page.callMethod(entry.close)
      await wait(150)
    }

    const routeEvidence = []
    routeEvidence.push(await tapSettingsRoute(mp, 'settings-how-to-play', 'subpackageA/pages/infomation/infomation', '.info-shell', '08-merchant-settings-how-to-play-light.png'))
    routeEvidence.push(await tapSettingsRoute(mp, 'settings-likes', 'pages/mylike/mylike', '.mylike-shell', '09-merchant-settings-likes-light.png'))
    routeEvidence.push(await tapSettingsRoute(mp, 'settings-about', 'pages/shezhi/about/index', '.about-page', '10-merchant-settings-about-light.png'))

    await setIdentity(mp, { role: 'player', userType: 1, debugView: 'user' })
    page = await mp.reLaunch('/pages/shezhi/shezhi')
    await wait(800)
    page = await currentPageAt(mp, 'pages/shezhi/shezhi')
    assert.equal(await page.data('settingsTheme'), 'player')
    await page.callMethod('goPrivacy')
    await wait(300)
    const playerSettingsShot = await screenshot(mp, '11-player-settings-privacy-dark-negative.png')

    const fatalConsoleErrors = consoleErrors.filter((message) => /SyntaxError|ReferenceError|TypeError|Component is not found|WXML/.test(message))
    assert.deepEqual(fatalConsoleErrors, [], `发现前端运行时错误: ${fatalConsoleErrors.join(' | ')}`)

    const evidence = {
      endpoint: session.endpoint,
      screenshots: [playerLogoShot, merchantLogoShot, settingsMainShot, playerSettingsShot],
      overlayEvidence,
      routeEvidence,
      consoleErrorCount: consoleErrors.length,
      fatalConsoleErrors,
    }
    console.log(JSON.stringify(evidence, null, 2))
  } finally {
    await closeMiniProgram(session)
  }
}

run().catch((error) => {
  console.error(error && error.stack ? error.stack : error)
  process.exit(1)
})
