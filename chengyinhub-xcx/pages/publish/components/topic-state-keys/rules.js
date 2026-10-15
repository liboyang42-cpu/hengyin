'use strict'
// 主题状态键(/api/topic/state-keys)前端预校验与整形。
// 规则照后端 ThemeStateKey.KEY_NAME / ThemeStateKeyService:前缀即类型,run/party/deployment 三种作用域
// (本轮后端只实现 run,其余只存不用 —— 创作端只提供 run)。后端仍是最终裁判。
const KEY_NAME = /^(clue|relation|tag|counter)\.[a-z][a-z0-9_]{0,47}$/
const PREFIXES = [
  { prefix: 'clue', keyType: 'flag', label: '线索', hint: '有/没有(是否拿到某条线索)' },
  { prefix: 'counter', keyType: 'counter', label: '计数', hint: '数字(可设初始值)' },
  { prefix: 'relation', keyType: 'relation', label: '关系', hint: '与某个角色的关系' },
  { prefix: 'tag', keyType: 'tag', label: '标签', hint: '给玩家打的标签' },
]
const TYPE_LABEL = { flag: '线索', counter: '计数', relation: '关系', tag: '标签' }

function normalizeKey(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const stateKey = typeof raw.stateKey === 'string' ? raw.stateKey : ''
  if (!KEY_NAME.test(stateKey)) return null
  const keyType = typeof raw.keyType === 'string' ? raw.keyType : ''
  const label = typeof raw.label === 'string' ? raw.label.trim() : ''
  const initial = Number(raw.initialValue)
  return {
    stateKey,
    keyType,
    typeText: TYPE_LABEL[keyType] || keyType,
    label,
    initialText: keyType === 'counter' && Number.isFinite(initial) ? `初始值 ${Math.trunc(initial)}` : '',
  }
}

function normalizeKeys(list) {
  if (!Array.isArray(list)) return null
  const out = []
  for (let i = 0; i < list.length; i += 1) {
    const row = normalizeKey(list[i])
    if (!row) return null
    out.push(row)
  }
  return out
}

/** 返回 { ok:true, key } 或 { ok:false, error }。prefixIndex 指向 PREFIXES。 */
function buildKey(prefixIndex, suffix, label, initialValue) {
  const p = PREFIXES[prefixIndex]
  if (!p) return { ok: false, error: '请选择状态键类型' }
  const name = `${p.prefix}.${String(suffix || '').trim()}`
  if (!KEY_NAME.test(name)) return { ok: false, error: '键名只能用小写字母、数字和下划线，以字母开头，最长 48 位' }
  const key = { stateKey: name, keyType: p.keyType, scope: 'run', label: String(label || '').trim() }
  if (p.keyType === 'counter') {
    const text = String(initialValue == null ? '' : initialValue).trim()
    if (text !== '' && !/^-?\d{1,9}$/.test(text)) return { ok: false, error: '初始值需为整数' }
    key.initialValue = text === '' ? 0 : Number(text)
  }
  return { ok: true, key }
}

module.exports = { KEY_NAME, PREFIXES, normalizeKeys, buildKey }
