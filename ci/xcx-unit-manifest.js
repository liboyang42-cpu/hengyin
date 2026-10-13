#!/usr/bin/env node
'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const REPO_ROOT = path.resolve(__dirname, '..')
const XCX_ROOT = path.join(REPO_ROOT, 'chengyinhub-xcx')
const UNIT_GLOB = 'tests/unit/**/*.test.js'
const MIN_UNIT_FILES = 294
const SENTINELS = [
  'tests/unit/activity-detail-single-source-contract.test.js',
  'tests/unit/nav-route-exists.test.js',
  'tests/unit/payment-readiness-contract.test.js',
  'tests/unit/requestbody-dynamic-url.test.js',
  'tests/unit/smoke.test.js',
  'tests/unit/title-system-owner-contract.test.js',
  'tests/unit/wxml-template-data-contract.test.js',
]

function discoverUnitTests(root = XCX_ROOT, pattern = UNIT_GLOB) {
  if (typeof fs.globSync !== 'function') {
    throw new Error('unit manifest requires Node 22+ fs.globSync, matching CI')
  }
  return fs.globSync(pattern, { cwd: root }).sort()
}

function readUnitScript(root = XCX_ROOT) {
  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
  return pkg.scripts && pkg.scripts['test:unit']
}

function manifestViolations({ files, script }) {
  const violations = []
  const match = typeof script === 'string'
    ? script.match(/^node\s+--test\s+(["'])([^"']+)\1$/)
    : null

  if (!match) {
    violations.push('package.json test:unit 必须由 node --test 运行一个显式引用的 glob')
  } else if (match[2] !== UNIT_GLOB) {
    violations.push(`test:unit glob 漂移: ${match[2]} (expected ${UNIT_GLOB})`)
  }

  if (!Array.isArray(files)) {
    violations.push('unit test manifest 不是文件数组')
    return violations
  }
  if (files.length < MIN_UNIT_FILES) {
    violations.push(`unit test manifest 只发现 ${files.length} 个文件，低于基线 ${MIN_UNIT_FILES}`)
  }
  const known = new Set(files)
  for (const sentinel of SENTINELS) {
    if (!known.has(sentinel)) violations.push(`unit test manifest 缺关键哨兵: ${sentinel}`)
  }
  return violations
}

function assertUnitManifest(input) {
  assert.deepEqual(manifestViolations(input), [],
    '小程序 unit glob/文件集合发生漂移，不能用少跑测试的绿代替全量门禁')
}

function currentManifest(root = XCX_ROOT) {
  return {
    files: discoverUnitTests(root),
    script: readUnitScript(root),
  }
}

function runSelftest() {
  const manifest = currentManifest()
  assertUnitManifest(manifest)

  const missingSentinel = manifest.files.filter((file) => file !== SENTINELS[0])
  assert.throws(() => assertUnitManifest({ ...manifest, files: missingSentinel }), {
    name: 'AssertionError',
    message: /小程序 unit glob\/文件集合发生漂移/,
  })

  assert.throws(() => assertUnitManifest({ ...manifest, script: 'node --test "tests/unit/smoke.test.js"' }), {
    name: 'AssertionError',
    message: /小程序 unit glob\/文件集合发生漂移/,
  })
}

if (require.main === module) {
  const selftest = process.argv.includes('--selftest')
  if (selftest) runSelftest()
  const manifest = currentManifest()
  assertUnitManifest(manifest)
  console.log(`xcx unit manifest: ${manifest.files.length} files, ${SENTINELS.length} sentinels`)
}

module.exports = {
  MIN_UNIT_FILES,
  SENTINELS,
  UNIT_GLOB,
  assertUnitManifest,
  currentManifest,
  discoverUnitTests,
  manifestViolations,
  readUnitScript,
}
