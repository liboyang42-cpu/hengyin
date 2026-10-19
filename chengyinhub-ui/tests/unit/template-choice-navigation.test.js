const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const fixture = fs.readFileSync(path.resolve(__dirname, '../e2e/template-choice/fixture.js'), 'utf8')
const source = fixture.slice(fixture.indexOf('async function openCenter(page)'), fixture.indexOf('\nfunction editor(page)'))
const ORIGIN = 'http://127.0.0.1:4173', AWAY = '/audit-center/browser-away', CENTER = '/audit-center/membertemplate'

// Unit controls for the actual setup helper, not a substitute for hosted browser execution.
// The collapsed parent / hidden child relationship is copied from the failing DOM trace.
function harness({ menuVisible = true, helper = source } = {}) {
  const actions = [], state = { expanded: false, url: '' }
  const control = kind => ({
    kind,
    filter(options) {
      assert.equal(kind, 'parent')
      assert.deepEqual(options.has, { text: '合成玩法审核', options: { exact: true } })
      return this
    },
    async click(...args) {
      assert.equal(args.length, 0, 'ordinary actionable click only')
      if (kind === 'parent') {
        assert.equal(menuVisible, true)
        state.expanded = !state.expanded
        actions.push('expand-parent')
      } else if (kind === 'link') {
        assert.equal(state.expanded, true, 'child must be visible before click')
        state.url = ORIGIN + CENTER; actions.push('open-template')
      } else assert.fail('Unexpected control click')
    }
  })
  const page = {
    async goto(url) { assert.equal(url, AWAY); state.url = ORIGIN + url; actions.push('open-away') },
    locator(selector) {
      if (selector === '.sidebar-container .el-submenu__title') return control('parent')
      if (selector === `.sidebar-container a[href="${CENTER}"]`) return control('link')
      if (selector === '.el-table__fixed-right') return control('table')
      assert.fail(`Unexpected selector ${selector}`)
    },
    getByText(text, options) { return { text, options } },
    getByRole(role, options) {
      assert.equal(role, 'heading'); assert.deepEqual(options, { name: '模板中心', exact: true })
      return control('heading')
    }
  }
  const expect = target => ({
    async toBeVisible() {
      if (target.kind === 'parent') assert.equal(menuVisible, true, 'parent title must be visible')
      else if (target.kind === 'link') assert.equal(state.expanded, true, 'child link is hidden in collapsed menu')
      else assert.equal(state.url, ORIGIN + CENTER)
    },
    async toHaveURL(url) { assert.equal(state.url, url) }
  })
  const buttonByText = (target, label) => {
    assert.equal(target.kind, 'table'); assert.equal(label, '编辑'); return control('edit')
  }
  const open = new Function('expect', 'buttonByText', 'ORIGIN', 'CENTER', 'AWAY', `${helper}; return openCenter`)(expect, buttonByText, ORIGIN, CENTER, AWAY)
  return { run: () => open(page), actions }
}

test('actual setup opens the visible parent before asserting and clicking its hidden child', async () => {
  const h = harness(); await h.run()
  assert.deepEqual(h.actions, ['open-away', 'expand-parent', 'open-template'])
})

test('missing parent remains a failure rather than bypassing visible navigation', async () => {
  const h = harness({ menuVisible: false })
  await assert.rejects(h.run(), /parent title must be visible/)
  assert.deepEqual(h.actions, ['open-away'])
})

test('removing the parent click reproduces the original hidden-link failure', async () => {
  assert.match(source, /await menu\.click\(\)/)
  const h = harness({ helper: source.replace('await menu.click()', '') })
  await assert.rejects(h.run(), /child link is hidden in collapsed menu/)
  assert.deepEqual(h.actions, ['open-away'])
})
