'use strict'
// 主题状态键(/api/topic/state-keys)前端整形。作者只看中文名字;键名由系统生成,不显示也不让手打。
// 规则照后端 ThemeStateKey.KEY_NAME / ThemeStateKeyService。后端仍是最终裁判。
const KEY_NAME = /^(clue|relation|tag|counter)\.[a-z][a-z0-9_]{0,47}$/
// 「线索与物品」里只列线索和标签;计数、关系属于属性 / 进度钟,在「d20 与角色设定」里管。
const KINDS = {
  clue: { prefix: 'clue', keyType: 'flag', label: '线索' },
  tag: { prefix: 'tag', keyType: 'tag', label: '标签' },
}
const TYPE_LABEL = { flag: '线索', counter: '计数', relation: '关系', tag: '标签' }

function normalizeKey(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const stateKey = typeof raw.stateKey === 'string' ? raw.stateKey : ''
  if (!KEY_NAME.test(stateKey)) return null
  const keyType = typeof raw.keyType === 'string' ? raw.keyType : ''
  const label = typeof raw.label === 'string' ? raw.label.trim() : ''
  const usedIn = Array.isArray(raw.usedIn) ? raw.usedIn.filter(v => typeof v === 'string' && v) : []
  return {
    stateKey,
    keyType,
    kind: stateKey.slice(0, stateKey.indexOf('.')),
    typeText: TYPE_LABEL[keyType] || '',
    label,
    usedIn,
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

/** 名字为空时的兜底;不回落到键名。 */
function displayName(row) { return (row && row.label) || (KINDS[row && row.kind] || {}).label || '' }

function nameError(kind, name, taken) {
  const k = KINDS[kind] || (kind === 'item' ? { label: '物品' } : null)
  if (!k) return '请再试一次。'
  if (!name) return ''
  if (name.length > 30) return '名字最多 30 个字。'
  if ((taken || []).includes(name)) return `已有同名的${k.label}。`
  return ''
}

/** 系统生成的键名:前缀 + k + 时间与随机数(base36),满足 KEY_NAME 且不与已有键冲突。 */
function newKey(kind, name, existing, seed) {
  const k = KINDS[kind]
  if (!k) return null
  const used = new Set((existing || []).map(row => row.stateKey))
  let n = Number.isFinite(seed) ? seed : Date.now()
  let stateKey
  do {
    stateKey = `${k.prefix}.k${Math.abs(Math.trunc(n)).toString(36)}`
    n += 1
  } while (used.has(stateKey))
  return { stateKey, keyType: k.keyType, scope: 'run', label: String(name || '').trim() }
}

/** 删除被拒时后端回「…引用位置:A、B」;只取位置,带键名的前半句不显示。 */
function parseUsedIn(msg) {
  const text = String(msg || '')
  const at = text.search(/引用位置[:：]/)
  if (at < 0) return []
  return text.slice(at + 5).split('、').map(v => v.trim()).filter(Boolean)
}

module.exports = { KEY_NAME, KINDS, normalizeKeys, displayName, nameError, newKey, parseUsedIn }
