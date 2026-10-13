'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const {
  REDUCED_MOTION_KEY,
  LEGACY_REDUCED_MOTION_KEY,
  readReducedMotion,
  writeReducedMotion,
} = require('../../utils/motion-preference')

for (const page of ['roam', 'play']) {
  const source = fs.readFileSync(path.resolve(__dirname, `../../pages/${page}/index.js`), 'utf8')
  const method = source.match(/  toggleReducedMotion\(\) \{[\s\S]*?\n  \},/)
  assert.ok(method, `${page} 必须保留真实 toggleReducedMotion 方法`)

  for (const initial of [false, true]) {
    for (const failure of ['no-storage', 'primary', 'legacy', 'none']) {
      test(`${page} 原偏好 ${initial} / ${failure}：反馈、状态、重读一致`, () => {
        const values = {
          [REDUCED_MOTION_KEY]: initial,
          [LEGACY_REDUCED_MOTION_KEY]: initial,
        }
        const storage = failure === 'no-storage' ? {} : {
          getStorageSync(key) { return values[key] },
          setStorageSync(key, value) {
            if ((failure === 'primary' && key === REDUCED_MOTION_KEY)
                || (failure === 'legacy' && key === LEGACY_REDUCED_MOTION_KEY)) {
              throw new Error('storage write failed')
            }
            values[key] = value
          },
        }
        const messages = []
        const stopped = []
        const writes = []
        const definition = vm.runInNewContext(`({${method[0]}})`, {
          writeReducedMotion: (_, value) => writeReducedMotion(storage, value),
          cyToast: (message) => messages.push(message),
          clearInterval: (timer) => stopped.push(timer),
        })
        const target = { scale: 16, center: { latitude: 1, longitude: 2 } }
        const instance = {
          data: { reducedMotion: initial, mapScale: 10 },
          _zoomAnim: 'current-zoom',
          _zoomLock: true,
          _zoomTarget: target,
          setData(patch) { writes.push(patch); Object.assign(this.data, patch) },
        }
        definition.toggleReducedMotion.call(instance)
        if (failure === 'no-storage' || failure === 'primary') {
          assert.deepEqual(messages, ['设置未保存，请重试'])
          assert.equal(instance.data.reducedMotion, initial)
          assert.deepEqual(writes, [])
          assert.deepEqual(stopped, [])
          assert.equal(instance._zoomAnim, 'current-zoom')
          assert.equal(instance._zoomLock, true)
          assert.equal(instance._zoomTarget, target)
          if (failure === 'primary') assert.equal(readReducedMotion(storage), initial)
        } else {
          assert.deepEqual(messages, [initial ? '已恢复动态效果' : '已减少动态效果'])
          assert.equal(instance.data.reducedMotion, !initial)
          assert.equal(readReducedMotion(storage), !initial)
          assert.equal(values[LEGACY_REDUCED_MOTION_KEY], failure === 'legacy' ? initial : !initial)
          if (page === 'roam' && !initial) {
            assert.deepEqual(stopped, ['current-zoom'])
            assert.equal(instance._zoomAnim, null)
            assert.equal(instance._zoomLock, false)
            assert.equal(instance._zoomTarget, null)
            assert.equal(instance.data.mapScale, 16)
            assert.equal(instance.data.center, target.center)
          } else {
            assert.deepEqual(stopped, [])
          }
        }
      })
    }
  }
}
