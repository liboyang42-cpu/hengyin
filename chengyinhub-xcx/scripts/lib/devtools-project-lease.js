'use strict'

const fs = require('fs')
const os = require('os')
const path = require('path')

function leasePathForPort(port, options) {
  const directory = (options && options.directory) || os.tmpdir()
  return path.join(directory, `chengyin-devtools-${Number(port)}.lease.json`)
}

function normalizeLease(value) {
  if (!value || typeof value !== 'object') throw new Error('DevTools project 租约必须是对象')
  const port = Number(value.port)
  const sourceSha = String(value.sourceSha || '').toLowerCase()
  if (!Number.isInteger(port) || port <= 0 || port > 65535) throw new Error('DevTools project 租约端口非法')
  if (!/^[0-9a-f]{40}$/.test(sourceSha)) throw new Error('DevTools project 租约 HEAD 非法')
  if (!value.projectPath) throw new Error('DevTools project 租约缺少 projectPath')
  return {
    projectPath: path.resolve(String(value.projectPath)),
    sourceSha,
    port,
  }
}

function assertProjectLease(expectedValue, actualValue) {
  const expected = normalizeLease(expectedValue)
  const actual = normalizeLease(actualValue)
  if (actual.projectPath !== expected.projectPath) {
    throw new Error(`DevTools project 不匹配: expected=${expected.projectPath} actual=${actual.projectPath}`)
  }
  if (actual.sourceSha !== expected.sourceSha) {
    throw new Error(`DevTools HEAD 不匹配: expected=${expected.sourceSha} actual=${actual.sourceSha}`)
  }
  if (actual.port !== expected.port) {
    throw new Error(`DevTools 端口不匹配: expected=${expected.port} actual=${actual.port}`)
  }
  return actual
}

function readProjectLease(port, options) {
  const file = leasePathForPort(port, options)
  if (!fs.existsSync(file)) return null
  try {
    return normalizeLease(JSON.parse(fs.readFileSync(file, 'utf8')))
  } catch (error) {
    throw new Error(`DevTools project 租约损坏: ${error.message}`)
  }
}

function writeProjectLease(value, options) {
  const lease = normalizeLease(value)
  const file = leasePathForPort(lease.port, options)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const temporary = `${file}.${process.pid}.tmp`
  fs.writeFileSync(temporary, `${JSON.stringify(lease)}\n`, { encoding: 'utf8', mode: 0o600, flag: 'wx' })
  fs.renameSync(temporary, file)
  return lease
}

module.exports = {
  assertProjectLease,
  leasePathForPort,
  normalizeLease,
  readProjectLease,
  writeProjectLease,
}
