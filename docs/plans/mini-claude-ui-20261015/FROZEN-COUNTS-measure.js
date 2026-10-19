'use strict'
// BL-171：冻结计数实测脚本（与各计数测试同一扫描口径）。用法：node docs/plans/mini-claude-ui-20261015/FROZEN-COUNTS-measure.js chengyinhub-xcx
// usage: node measure.js <xcxRoot>  → JSON of frozen-count actuals
const fs = require('fs'), path = require('path')
const ROOT = path.resolve(process.argv[2])
const out = {}
const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
out.appPages = (app.pages || []).length + (app.subpackages || app.subPackages || []).reduce((s, p) => s + (p.pages || []).length, 0)
try { out.routes = require(path.join(ROOT, 'scripts/uiaudit/build-route-matrix.js')).build().routes.length } catch (e) { out.routes = 'ERR ' + e.message.slice(0, 60) }
// nav-page-title files
const navSrc = fs.readFileSync(path.join(ROOT, 'tests/unit/nav-page-title-spacing-contract.test.js'), 'utf8')
const PAGE_ROOTS = eval(navSrc.match(/const PAGE_ROOTS = (\[[\s\S]*?\])/)[1])
const wx = []
const walk = (dir) => { for (const e of fs.readdirSync(path.join(ROOT, dir), { withFileTypes: true })) { const rel = path.join(dir, e.name); if (e.isDirectory()) { if (e.name !== 'components') walk(rel) } else if (rel.endsWith('.wxml')) wx.push(rel) } }
PAGE_ROOTS.forEach((r) => { if (fs.existsSync(path.join(ROOT, r))) walk(r) })
out.pageTitlePages = wx.filter((r) => fs.readFileSync(path.join(ROOT, r), 'utf8').includes('<cy-page-title')).length
// GET counts
const prod = (dir) => { const o = []; for (const e of fs.readdirSync(dir, { withFileTypes: true })) { if (['tests', 'node_modules', 'miniprogram_npm'].includes(e.name)) continue; const f = path.join(dir, e.name); if (e.isDirectory()) o.push(...prod(f)); else if (e.isFile() && e.name.endsWith('.js')) o.push(f) } return o }
let direct = 0, wrapper = 0
prod(ROOT).forEach((f) => { const s = fs.readFileSync(f, 'utf8'); direct += (s.match(/method\s*:\s*['"]GET['"]/g) || []).length; wrapper += (s.match(/\breq\([\s\S]{0,180}?,\s*['"]GET['"]\s*,/g) || []).length })
out.getDirect = direct; out.getWrapper = wrapper
// success toast
const SKIP = new Set(['node_modules', 'miniprogram_npm', 'dist', '.git', 'tests', 'scripts', 'design'])
const w2 = (dir, o = []) => { for (const e of fs.readdirSync(dir, { withFileTypes: true })) { if (e.isDirectory()) { if (!SKIP.has(e.name)) w2(path.join(dir, e.name), o) } else if (e.name.endsWith('.js')) o.push(path.join(dir, e.name)) } return o }
let toast = 0
for (const f of w2(ROOT)) { const s = fs.readFileSync(f, 'utf8'); for (const m of s.matchAll(/showToast\(\s*\{([^{}]*)\}/g)) if (/icon:\s*['"]success['"]/.test(m[1])) toast++; toast += (s.match(/\b(?:cy)?[tT]oast\.success\(/g) || []).length }
out.successToast = toast
try { out.ledgerControls = require(path.join(ROOT, 'scripts/uiaudit/build-action-ledger.js')).buildLedger().controlCount } catch (e) { out.ledgerControls = 'ERR ' + e.message.slice(0, 60) }
console.log(JSON.stringify(out))
