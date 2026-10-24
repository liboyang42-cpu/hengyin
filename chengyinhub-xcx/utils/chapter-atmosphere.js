// 章节配色目录与存量别名。渐变只用于编辑器色卡。
const { enumCode } = require('./enum-code.js')
const PRESETS = [
  { value: 'DEFAULT', label: '黑', note: '石墨', className: 'atmosphere-swatch--default' },
  { value: 'BLUE',    label: '蓝', note: '晴空', className: 'atmosphere-swatch--blue' },
  { value: 'RED',     label: '红', note: '落霞', className: 'atmosphere-swatch--red' },
  { value: 'YELLOW',  label: '黄', note: '暖杏', className: 'atmosphere-swatch--yellow' },
  { value: 'WHITE',   label: '白', note: '珍珠', className: 'atmosphere-swatch--white' },
]

const VALUE_SET = new Set(PRESETS.map((item) => item.value))

// 存量 → 新档。改这张表要连后端 ChapterAtmospherePreset.LEGACY_ALIAS 一起改。
const LEGACY_ALIAS = {
  NIGHT: 'BLUE',     // 晴空 → 蓝
  ARCHIVE: 'YELLOW', // 旧档案纸 → 黄
  NEON: 'RED',       // 夜城灯牌 → 红
  MOSS: 'DEFAULT',   // 湿润绿影 —— 新色板里没有绿,落回默认黑
}

function normalizeAtmosphere(value) {
  const normalized = enumCode(value || 'DEFAULT')
  if (VALUE_SET.has(normalized)) return normalized
  return LEGACY_ALIAS[normalized] || 'DEFAULT'
}

function atmosphereClass(value) {
  return 'atmosphere--' + normalizeAtmosphere(value).toLowerCase()
}

module.exports = { PRESETS, LEGACY_ALIAS, normalizeAtmosphere, atmosphereClass }
