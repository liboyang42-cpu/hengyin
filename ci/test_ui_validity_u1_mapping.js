'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { findU1Issues } = require('../chengyinhub-xcx/scripts/ui-validity-gates')

const root = path.resolve(__dirname, '..')
const javaSources = [{
  file: 'ApiMediaAssetController.java',
  source: fs.readFileSync(path.join(root, 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiMediaAssetController.java'), 'utf8'),
}]
const scan = source => findU1Issues([{ file: 'upload-client.js', source }], javaSources)

test('U1 checks both actual upload retry URLs against their existing backend mappings', () => {
  const source = fs.readFileSync(path.join(root, 'chengyinhub-xcx/utils/transport/upload-client.js'), 'utf8')
  const result = scan(source)
  assert.equal(result.callCount, 2)
  assert.deepEqual(result.issues, [])
})

test('U1 preserves the identifier and retry suffix of concatenated URLs', () => {
  const result = scan("sender({url:'/api/media/asset/' + id}); sender({url:'/api/media/asset/' + id + '/retry'})")
  assert.equal(result.callCount, 2)
  assert.deepEqual(result.issues, [])
})

test('U1 still rejects wrong suffixes, a missing identifier and extra route segments', () => {
  for (const expression of [
    "'/api/media/asset/' + id + '/ghost'",
    "'/api/media/asset'",
    "'/api/media/asset/' + id + '/retry/extra'",
    "'/api/media/asset/' + id + '/extra/retry'",
    "'/api/media/unknown/' + id",
  ]) {
    const result = scan(`sender({url:${expression}})`)
    assert.equal(result.callCount, 1)
    assert.equal(result.issues.length, 1, expression)
  }
})

test('U1 ignores comment and non-url literals without skipping a real missing route', () => {
  const source = "// sender({url:'/api/ghost'})\n/* sender({url:'/api/ghost'}) */\nconst note=\"url:'/api/ghost'\"; const data={reference:'/api/ghost'};sender({url:'/api/ghost'})"
  const result = scan(source)
  assert.equal(result.callCount, 1)
  assert.deepEqual(result.issues.map(issue => issue.route), ['/api/ghost'])
})

test('U1 matches the mapped path when a dynamic URL includes query parameters', () => {
  const result = scan("sender({url:'/api/media/asset/' + id + '?view=' + view})")
  assert.equal(result.callCount, 1)
  assert.deepEqual(result.issues, [])
})
