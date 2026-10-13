const { test } = require('node:test')
const assert = require('node:assert/strict')
const {
  REDUCED_MOTION_KEY,
  LEGACY_REDUCED_MOTION_KEY,
  readReducedMotion,
  writeReducedMotion,
} = require('../../utils/motion-preference.js')

function memoryStorage(initial) {
  const values = Object.assign({}, initial)
  return {
    values,
    getStorageSync(key) { return values[key] },
    setStorageSync(key, value) { values[key] = value },
  }
}

test('动效偏好使用玩家路径共享键并兼容旧漫游键', () => {
  const current = memoryStorage({ [REDUCED_MOTION_KEY]: true })
  assert.equal(readReducedMotion(current), true)

  const legacy = memoryStorage({ [LEGACY_REDUCED_MOTION_KEY]: true })
  assert.equal(readReducedMotion(legacy), true)

  const explicitCurrent = memoryStorage({
    [REDUCED_MOTION_KEY]: false,
    [LEGACY_REDUCED_MOTION_KEY]: true,
  })
  assert.equal(readReducedMotion(explicitCurrent), false)
})

test('写入动效偏好同时保持旧版本页面兼容', () => {
  const storage = memoryStorage()
  assert.equal(writeReducedMotion(storage, true), true)
  assert.equal(storage.values[REDUCED_MOTION_KEY], true)
  assert.equal(storage.values[LEGACY_REDUCED_MOTION_KEY], true)

  assert.equal(writeReducedMotion(storage, false), false)
  assert.equal(readReducedMotion(storage), false)
})

test('存储不可用时读取仍回退，保存明确失败且不抛错', () => {
  const broken = {
    getStorageSync() { throw new Error('storage unavailable') },
    setStorageSync() { throw new Error('storage unavailable') },
  }
  assert.equal(readReducedMotion(broken), false)
  assert.equal(writeReducedMotion(broken, true), null)
})

for (const enabled of [true, false]) {
  test(`无存储或缺少写方法时保存 ${enabled} 明确失败`, () => {
    assert.equal(writeReducedMotion(null, enabled), null)
    assert.equal(writeReducedMotion({}, enabled), null)
  })

  test(`主键写入失败不改旧值且不尝试兼容键：${enabled}`, () => {
    const storage = memoryStorage({
      [REDUCED_MOTION_KEY]: !enabled,
      [LEGACY_REDUCED_MOTION_KEY]: !enabled,
    })
    const writes = []
    storage.setStorageSync = (key) => { writes.push(key); throw new Error('write failed') }
    assert.equal(writeReducedMotion(storage, enabled), null)
    assert.deepEqual(writes, [REDUCED_MOTION_KEY])
    assert.equal(readReducedMotion(storage), !enabled)
    assert.equal(storage.values[LEGACY_REDUCED_MOTION_KEY], !enabled)
  })

  test(`主键成功兼容键失败仍按主键重读：${enabled}`, () => {
    const storage = memoryStorage({ [LEGACY_REDUCED_MOTION_KEY]: !enabled })
    storage.setStorageSync = (key, value) => {
      if (key === LEGACY_REDUCED_MOTION_KEY) throw new Error('legacy write failed')
      storage.values[key] = value
    }
    assert.equal(writeReducedMotion(storage, enabled), enabled)
    assert.equal(readReducedMotion(storage), enabled)
    assert.equal(storage.values[LEGACY_REDUCED_MOTION_KEY], !enabled)
  })

  test(`两个键成功后新存储实例可重读：${enabled}`, () => {
    const storage = memoryStorage()
    assert.equal(writeReducedMotion(storage, enabled), enabled)
    assert.equal(storage.values[REDUCED_MOTION_KEY], enabled)
    assert.equal(storage.values[LEGACY_REDUCED_MOTION_KEY], enabled)
    assert.equal(readReducedMotion(memoryStorage(storage.values)), enabled)
  })
}
