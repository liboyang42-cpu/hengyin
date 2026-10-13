'use strict'

const crypto = require('crypto')
const fs = require('fs')
const path = require('path')

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}

function pinnedPublicKey(prefix) {
  const file = String(process.env[`${prefix}_PUBLIC_KEY_FILE`] || '')
  const expectedFingerprint = String(process.env[`${prefix}_PUBLIC_KEY_SHA256`] || '')
  if (!path.isAbsolute(file) || !fs.existsSync(file)) throw new Error(`${prefix}_PUBLIC_KEY_FILE 必须是外部只读绝对路径`)
  const publicKey = fs.readFileSync(file)
  const fingerprint = `sha256:${crypto.createHash('sha256').update(publicKey).digest('hex')}`
  if (!/^sha256:[0-9a-f]{64}$/i.test(expectedFingerprint) || fingerprint !== expectedFingerprint) {
    throw new Error(`${prefix} 公钥未命中发布环境固定指纹`)
  }
  return publicKey
}

function trustedControlPlane(fixture) {
  const value = fixture && fixture.controlPlane
  if (!value || !String(value.baseUrl || '').trim() || !String(value.scenarioId || '').trim()) {
    throw new Error('fixture 缺少 controlPlane.baseUrl/scenarioId')
  }
  const actual = new URL(value.baseUrl)
  let configured
  try { configured = new URL(String(process.env.ACTION_CONTROL_PLANE_ORIGIN || '')) } catch (_) {
    throw new Error('缺少合法 ACTION_CONTROL_PLANE_ORIGIN')
  }
  if (actual.origin !== configured.origin || actual.username || actual.password
      || (!['https:', 'http:'].includes(actual.protocol))
      || (actual.protocol === 'http:' && !['127.0.0.1', 'localhost'].includes(actual.hostname))) {
    throw new Error('controlPlane.baseUrl 未命中 ACTION_CONTROL_PLANE_ORIGIN 受控来源')
  }
  pinnedPublicKey('ACTION_CONTROL_PLANE')
  return { baseUrl: actual.origin, scenarioId: String(value.scenarioId) }
}

function verifySignedEnvelope(envelope, expected, _legacyKeyName, now = Date.now()) {
  return verifyAsymmetricEnvelope(envelope, expected, pinnedPublicKey('ACTION_CONTROL_PLANE'), now)
}

function verifyAsymmetricEnvelope(envelope, expected, publicKey, now = Date.now()) {
  if (!envelope || !envelope.payload || !String(envelope.signature || '').trim()) {
    throw new Error('外部证明缺少 payload/signature')
  }
  const payload = envelope.payload
  Object.entries(expected || {}).forEach(([field, value]) => {
    if (payload[field] !== value) throw new Error(`外部证明 ${field} 未绑定本次执行`)
  })
  const issuedAt = Date.parse(payload.issuedAt)
  if (Number.isNaN(issuedAt) || Math.abs(now - issuedAt) > 5 * 60 * 1000) {
    throw new Error('外部证明 issuedAt 未绑定采集时间')
  }
  if (!crypto.verify(null, Buffer.from(stableJson(payload)), publicKey, Buffer.from(String(envelope.signature), 'base64'))) {
    throw new Error('外部证明非对称签名无效')
  }
  if (!payload.facts || typeof payload.facts !== 'object') throw new Error('外部证明缺少 facts')
  return payload
}

module.exports = { pinnedPublicKey, stableJson, trustedControlPlane, verifyAsymmetricEnvelope, verifySignedEnvelope }
