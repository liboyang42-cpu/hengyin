// Exact Vue SFC + real draft service, served only by the Java loopback test harness.
// Does not build or claim to test the full authenticated RuoYi application.
const fs = require('fs')
const path = require('path')
const compiler = require('vue-template-compiler')
const output = process.argv[2]
if (!output) throw new Error('Pass an isolated output directory')
const source = fs.readFileSync(path.join(__dirname, '../../src/views/business/city-board-drafts/index.vue'), 'utf8')
const sfc = compiler.parseComponent(source)
const compiled = compiler.compile(sfc.template.content)
if (compiled.errors.length) throw new Error(compiled.errors.join('\n'))
const script = sfc.script.content.replace(/^import .*$/m, '').replace('export default', 'const component =')
const adapter = `const cityBoardDraftsEnabled = true;
const call = async (path, data) => { const response = await fetch('/business/city-board-drafts' + path, data ? { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-City-Draft-Test': 'isolated' }, body: JSON.stringify(data) } : {}); const result = await response.json(); if (result.code !== 200) throw Object.assign(new Error(result.msg), { code: result.code, errorCode: result.errorCode }); return result; };
const readCityDraftCatalog = () => call('/catalog'); const readCityDraft = id => call('/' + encodeURIComponent(id)); const validateCityDraft = cmd => call('/validate', cmd); const saveCityDraft = cmd => call('/save', cmd); const previewCityDraft = cmd => call('/preview', cmd);`
const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>City draft isolated evaluation</title><style>body{font:16px system-ui;background:#f5f7fb;margin:24px} ${sfc.styles.map(s => s.content).join('\n')}</style></head><body><div id="app"></div><script src="/vue.js"></script><script>${adapter}\n${script}\nwindow.cityDraft = new Vue({ ...component, template: ${JSON.stringify(sfc.template.content)}, beforeCreate() { this.$store = { getters: Vue.observable({ token: 'isolated-test-context', roles: ['test-platform'], permissions: ['test-edit'] }) }; this.$route = Vue.observable({ fullPath: '/platform/city-board-drafts' }) } }).$mount('#app');</script></body></html>`
fs.mkdirSync(output, { recursive: true }); fs.writeFileSync(path.join(output, 'index.html'), html)
fs.copyFileSync(require.resolve('vue/dist/vue.js'), path.join(output, 'vue.js'))
console.log('Built isolated exact-SFC harness:', output)
