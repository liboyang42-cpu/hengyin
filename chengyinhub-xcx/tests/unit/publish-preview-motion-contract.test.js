const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const { parseJavaScript } = require('../../scripts/lib/xcx-scan')

const ROOT = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

const attr = (tag, name) => (tag.match(new RegExp(name + '="([^"]*)"')) || [])[1]
const completeHost = wxml => (wxml.match(/<cy-play-verdict\b[^>]*>/g) || []).find(tag => tag.includes("pvStepKey === 'complete'"))

function completeVerdictContract(wxml, changed = {}) {
  const host = completeHost(wxml) || ''
  const css = changed.css || read('pages/play/components/play-verdict/index.wxss')
  const js = changed.js || read('pages/play/components/play-verdict/index.js')
  const markup = read('pages/play/components/play-verdict/index.wxml')
  return ['kind="win"', 'show="{{true}}"', 'full-color="{{true}}"', 'closable="{{true}}"', 'bind:close="completePreview"']
    .every(value => host.includes(value))
    && Boolean(attr(host, 'cta-text')) && !/cg-pv-complete/.test(wxml)
    && /pv--closable\.pv--full-color\.pv--win \.pv__fill\s*\{\s*background: var\(--cy-color-status-success\)/.test(css)
    && markup.includes("reducedMotion ? 'pv--reduced'") && markup.includes('reduced-motion="{{reducedMotion}}"')
    && /\.pv--reduced \.pv__fill\s*\{ transition: none;/.test(css)
    && /\.pv--reduced \.pv__wave\s*\{ animation: none;/.test(css)
    && /\.pv--reduced \.pv__bubble\s*\{ animation: none; display: none;/.test(css)
    && /\.pv--reduced \.pv__body\s*\{ transition: none;/.test(css)
    && /\.pv--closable\.pv--full-color\.pv--win:not\(\.pv--reduced\) \.pv__bubble\s*\{ display: block;/.test(css)
    && /behaviors: \[reducedMotionBehavior\]/.test(js)
}

function verdictDefinition() {
  let definition
  vm.runInNewContext(read('pages/play/components/play-verdict/index.js'), {
    Component(value) { definition = value },
    require(name) { return name.includes('keep-numerals') ? { tidyText: text => text } : {} },
  })
  return definition
}

// 装载真实预览方法，不复制完成判定；唯一平台边界是导航配色，没有正式API通道。
function previewMethods(source) {
  const names = new Set(['completePreview', 'pvNext', '_applyPvStep', 'closePreview', '_buildPvSteps', 'hasModule', 'syncNativeTheme'])
  const parts = new Map()
  function walk(node) {
    if (!node || typeof node !== 'object') return
    if (node.type === 'Property' && names.has(node.key.name) && node.value.type === 'FunctionExpression') parts.set(node.key.name, source.slice(node.start, node.end))
    for (const [key, value] of Object.entries(node)) {
      if (key === 'loc') continue
      if (Array.isArray(value)) value.forEach(walk)
      else if (value && typeof value === 'object') walk(value)
    }
  }
  walk(parseJavaScript(source))
  assert.equal(parts.size, names.size, '必须装载全部真实预览方法')
  return vm.runInNewContext('({' + [...parts.values()].join(',') + '})', { applyTheme() {} })
}

function assertPreviewActions(wxml, js) {
  const host = completeHost(wxml), definition = verdictDefinition()
  const cta = read('pages/play/components/play-verdict/index.wxml').match(/<view class="pv__cta"[^>]*>/)[0]
  for (const reward of [false, true, 'empty-switch']) {
    const page = Object.assign(previewMethods(js), {
      data: { previewVisible: true, from: 'fabu', moduleList: [{ key: 'finish' }, { key: 'reward' }],
        storyBeats: [], rwAny: Boolean(reward), rwText: Boolean(reward), rwCoupon: false, rwMedal: false,
        formData: { feedbackText: reward === true ? '原奖励内容' : '' }, advanced: { original: true } },
      setData(patch) { Object.assign(this.data, patch) },
    })
    const original = JSON.stringify(page.data.advanced)
    const steps = page._buildPvSteps()
    Object.assign(page.data, { pvSteps: steps, pvStep: steps.indexOf('complete'), pvStepKey: 'complete' })
    assert.equal(vm.runInNewContext(attr(host, 'cta-text').slice(2, -2), page.data), reward === true ? '领取奖励' : '完成')
    const click = () => definition.methods[attr(cta, 'bindtap')].call({ triggerEvent(name) {
      assert.equal(name, 'close')
      page[attr(host, 'bind:close')]()
    } })
    for (const flag of ['_hidden', '_disposed', '_unloaded']) {
      page[flag] = true; click(); page[flag] = false
      assert.equal(page.data.pvStepKey, 'complete', '退役点击不能消费完成态')
    }
    click()
    if (reward === true) {
      assert.equal(page.data.pvStepKey, 'reward')
      assert.equal(page.data.previewVisible, true)
      click()
      assert.equal(page.data.pvStepKey, 'reward', '旧完成事件不能跳过奖励')
    } else {
      assert.equal(page.data.previewVisible, false, '无实际奖励的完成必须关闭预览')
      click()
      assert.equal(page.data.previewVisible, false)
    }
    assert.equal(JSON.stringify(page.data.advanced), original)
  }
}

function hasPreviewMotionContract(wxml, wxss) {
  const panels = wxml.match(/<view\b[^>]*class="[^"]*\bcg-pv-panel\b[^"]*"[^>]*>/g) || []
  /* 2026-09-11 玩法模块改成单选之后,这一页不再有 .cg-tile(完成方式那串 tile
     与问答的「怎么答」三选一一起退场),按压反馈的承载换成选择玩法那张半屏里的行。
     判据跟着换,坏掉的形态照样红:见下面 hover-class 那条。 */
  const oneShotRules = ['.cg-pv-panel', '.cg-rw-cfg']
    .every((selector) => {
      const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const rule = wxss.match(new RegExp(`${escaped}\\s*\\{[^}]*animation:[^;}]+;`, 'm'))
      return Boolean(rule && /var\(--cy-motion-/.test(rule[0]) && /\bboth\b/.test(rule[0]) && !/\binfinite\b/.test(rule[0]))
    })
  // 2026-10-28 p81 业主「整体页面重新设计」:顶部圆点换成分段进度条(走到的段都亮)
  return /class="cg-pv-seg \{\{ pvStep >= idx \? 'on' : '' \}\}"/.test(wxml)
    && ['arrival', 'story', 'challenge', 'reward'].every(key => panels.some(tag => tag.includes("pvStepKey === '" + key + "'")))
    && completeVerdictContract(wxml)
    && /class="cg-pv-medal-ico"/.test(wxml)
    && !/cg-pv-roadmap|cg-pv-medal-coin|cg-pv-medal-face--back/.test(wxml)
    // 2026-10-22 选择玩法改成半屏标签条(Figma 胶囊):按压反馈的承载换成玩法标签
    && /class="cg-tag-pill[^"]*"/.test(wxml)
    && /bindtap="pickR5GameTag"[^>]*hover-class="cy-pressed"/.test(wxml)
    && /\.cg-pv-seg\s*\{[^}]*transition:[^;}]*var\(--cy-motion-/s.test(wxss)
    && /\.cg--reduced-motion[^}]*animation:\s*none\s*!important/s.test(wxss)
    && oneShotRules
}

test('玩家预览分段进度与勋章 UI，只增加一次性步骤和选择反馈', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  const wxss = read('pages/publish/temp/index.wxss')
  assert.equal(hasPreviewMotionContract(wxml, wxss), true)
})

test('负控：把一次性预览动效改回 infinite 时契约必须判红', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  const wxss = read('pages/publish/temp/index.wxss')
  const mutated = wxss.replace(/\bboth\b/g, 'infinite')
  assert.notEqual(mutated, wxss, '负控必须真实改动 animation iteration/fill')
  assert.equal(hasPreviewMotionContract(wxml, mutated), false)
})

test('真实verdict CTA：无奖励退出、有奖励进入原reward，空开关和退役事件不冒进', () => {
  assertPreviewActions(read('pages/publish/temp/index.wxml'), read('pages/publish/temp/index.js'))
})

test('其他三个caller保留默认配色、继续、neutral和原判定', () => {
  const definition = verdictDefinition()
  assert.equal(definition.properties.fullColor.value, false)
  assert.equal(definition.properties.ctaText.value, '继续')
  assert.equal(definition.properties.neutral.value, false)
  assert.equal(definition.properties.closable.value, false)
  assert.equal(definition.methods._failWord(true), '时间到')
  assert.equal(definition.methods._failWord(false), '失败')
  const tags = ['pages/play/index.wxml', 'pages/play/components/play-stage/index.wxml', 'pages/publish/temp/index.wxml']
    .flatMap(file => read(file).match(/<cy-play-verdict\b[^>]*>/g) || []).filter(tag => !tag.includes("pvStepKey === 'complete'"))
  assert.equal(tags.length, 3)
  const challenge = tags.find(tag => tag.includes("pvStepKey === 'challenge'"))
  assert.ok(challenge, '必须核对真实作者challenge caller')
  for (const tag of tags.filter(tag => tag !== challenge)) assert.doesNotMatch(tag, /full-color=|cta-text=/)
  assert.equal(attr(challenge, 'cta-text'), "{{pvVerdict.kind === 'fail' ? '再试一次' : pvStep < pvSteps.length - 1 ? '查看结果' : '完成'}}", 'challenge显式描述重试、完成或下一结果动作')
  const expression = attr(challenge, 'full-color')
  assert.ok(expression, '已批准QA成功接现成fullColor')
  const preview = require('../../pages/publish/utils/publish/advanced-game-preview.js')
  const check = value => {
    const evaluate = (pvKit, kind) => vm.runInNewContext(value.slice(2, -2), { pvKit, pvVerdict: { kind } })
    for (const mode of ['TYPE', 'PICK', 'SHOT']) for (const judge of [false, true, undefined]) {
      const kit = preview.buildPreviewKit({ qa: { enabled: true, mode, judge } })
      for (const kind of ['win', 'fail']) {
        assert.equal(evaluate(kit, kind), kind === 'win' && mode !== 'SHOT' && judge !== false,
          '只有真实builder的有判定TYPE/PICK成功能开绿屏')
      }
    }
    const nonQA = preview.buildPreviewKit({ coinFlip: { enabled: true } })
    for (const kind of ['win', 'fail']) assert.equal(evaluate(nonQA, kind), false, '非QA保留原配色')
    assert.equal(evaluate({}, 'win'), false, '未配置默认不获得成功绿屏')
  }
  check(expression)
  assert.throws(() => check('{{true}}'), /只有真实builder|非QA|默认/, '无条件变绿必须被负控拒绝')
})

test('负控：移除fullcolor或减动效guard、恢复黑色完成页必须判红', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  const css = read('pages/play/components/play-verdict/index.wxss')
  const js = read('pages/play/components/play-verdict/index.js')
  for (const changed of [
    wxml.replace('full-color="{{true}}"', ''),
    wxml.replace('text="任务完成！"', 'class="cg-pv-complete" text="任务完成！"'),
  ]) {
    assert.notEqual(changed, wxml, '负控必须真实变异')
    assert.equal(completeVerdictContract(changed), false)
  }
  const noReducedBubbleGuard = css.replace(':not(.pv--reduced)', '')
  const noBehavior = js.replace('behaviors: [reducedMotionBehavior]', 'behaviors: []')
  assert.notEqual(noReducedBubbleGuard, css)
  assert.notEqual(noBehavior, js)
  assert.equal(completeVerdictContract(wxml, { css: noReducedBubbleGuard }), false)
  assert.equal(completeVerdictContract(wxml, { js: noBehavior }), false)
})

test('负控：错接CTA或恢复rwAny分流不能冒充正常退出', () => {
  const wxml = read('pages/publish/temp/index.wxml'), js = read('pages/publish/temp/index.js')
  const wrongCta = wxml.replace('bind:close="completePreview"', 'bind:close="pvNext"')
  const wrongBranch = js.replace("this.data.pvSteps[this.data.pvStep + 1] === 'reward'", 'this.data.rwAny')
  assert.notEqual(wrongCta, wxml)
  assert.notEqual(wrongBranch, js)
  for (const [markup, code] of [[wrongCta, js], [wxml, wrongBranch]]) {
    assert.throws(() => assertPreviewActions(markup, code), /无实际奖励的完成必须关闭预览/)
  }
})
