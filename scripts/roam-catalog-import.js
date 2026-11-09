#!/usr/bin/env node
'use strict'

// Offline compiler only: emits a reviewable SQL file, never connects to a database.
const fs = require('node:fs')
const path = require('node:path')
const CATALOG_PATH = path.resolve(__dirname, '../chengyinhub-xcx/subpackageRoam/data/roam-city-catalog.js')
const BOUNDS = {
  SH: [30.6,31.9,120.8,122], BJ: [39.3,40.6,115.6,117.5], HZ: [29.7,30.8,119.6,120.8],
  CD: [30.2,31,103.4,104.8], XA: [33.8,34.8,108.3,109.8], CQ: [29.1,30.2,105.9,107.2],
  SZ: [22.4,22.9,113.7,114.7], GZ: [22.7,23.7,112.9,114.1], WH: [30,31.1,113.8,115.1],
  HK: [22.1,22.6,113.8,114.5], XM: [24.3,24.9,117.8,118.5],
}
function textSql(value) {
  return 'CONVERT(0x' + Buffer.from(String(value), 'utf8').toString('hex') + ' USING utf8mb4)'
}
function checkedDate(value, now) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(value + 'T00:00:00Z')
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0,10) === value &&
    date.getTime() <= now.getTime() && now.getTime() - date.getTime() <= 90 * 86400000
}
function coordinateTemplate(catalog) {
  return catalog.places.map(place => ({
    id: place.id, city: place.city, name: place.name, address: place.address,
    provider: null, coordinateSystem: 'GCJ-02', providerPoiId: null, verificationSource: null,
    lat: null, lng: null, verifiedOn: null, matchConfirmed: false,
  }))
}
function buildImport(catalog, coordinates, city, now = new Date()) {
  if (!catalog || !Array.isArray(catalog.places) || !Array.isArray(coordinates)) throw new Error('地点目录或坐标清单格式错误')
  if (city && !BOUNDS[city]) throw new Error('未知城市码')
  const places = catalog.places.filter(place => !city || place.city === city)
  if (!places.length || new Set(places.map(place => place.id)).size !== places.length) throw new Error('地点清单为空或身份重复')
  const known = new Set(catalog.places.map(place => place.id))
  if (new Set(coordinates.map(row => row && row.id)).size !== coordinates.length ||
      coordinates.some(row => !row || !known.has(row.id))) throw new Error('坐标清单包含重复或未知地点')
  const checked = places.map(place => {
    const geo = coordinates.find(row => row.id === place.id)
    const bounds = BOUNDS[place.city]
    const validSource = geo && typeof geo.verificationSource === 'string' &&
      /^https:\/\/(?:[\w-]+\.)*(?:amap\.com|gaode\.com|map\.qq\.com)(?:\/|$)/.test(geo.verificationSource)
    if (typeof place.id !== 'string' || !/^[a-z]{2}-[a-z0-9-]+$/.test(place.id) || !geo || geo.city !== place.city || geo.name !== place.name || geo.address !== place.address ||
        !bounds || !['street','park','shop','landmark'].includes(place.category) ||
        !['amap','tencent'].includes(geo.provider) || geo.coordinateSystem !== 'GCJ-02' ||
        geo.matchConfirmed !== true || typeof geo.providerPoiId !== 'string' || !geo.providerPoiId.trim() ||
        !validSource || !checkedDate(geo.verifiedOn, now) ||
        typeof geo.lat !== 'number' || !Number.isFinite(geo.lat) ||
        typeof geo.lng !== 'number' || !Number.isFinite(geo.lng) ||
        geo.lat < bounds[0] || geo.lat > bounds[1] || geo.lng < bounds[2] || geo.lng > bounds[3]) {
      throw new Error('坐标与入口未完成核验：' + place.id)
    }
    if (!Array.isArray(place.sources) || new Set(place.sources).size < 2 ||
        place.sources.some(source => typeof source !== 'string' || !/^https:\/\/[^\s]+$/.test(source)) ||
        !checkedDate(place.reviewedOn, now) || place.reviewMethod !== 'public_sources') {
      throw new Error('来源核验缺失或超过90天：' + place.id)
    }
    const tag = 'roam:seed:v1:' + place.id
    const tags = JSON.stringify(['roam:' + place.category, tag].concat(place.tags))
    for (const [value, limit] of [[place.name,80],[place.address,255],[place.reason,255],[tags,255]]) {
      if (typeof value !== 'string' || !value.trim() || Array.from(value).length > limit) throw new Error('内容字段超长或为空：' + place.id)
    }
    return { place, geo, tag, tags }
  })
  const sql = [
    '-- 城市漫游目录：仅离线编译，未执行任何数据库操作。',
    '-- 预先核对现有 roam_poi 的 address/tags/sort 列及同名点位；先在隔离测试库执行并回读。',
    '-- 新点位 status=0（下架）；运营审核并上架后才进入附近点位接口。',
    '-- 所有条目 type=1，无商家归属、玩法模板、券模板；商家认领仍走既有审核链。',
    '-- 按种子标签防止顺序重复导入；仅供单个运营者串行执行，不替代并发唯一约束。',
    'START TRANSACTION;',
  ]
  checked.forEach(({ place, geo, tag, tags }, index) => {
    sql.push('-- ' + place.id + ' / ' + geo.provider + ' / ' + geo.verifiedOn)
    sql.push('INSERT INTO roam_poi (name, type, lat, lng, radius_m, address, description, xp, city_code, status, merchant_id, template_id, coupon_template_id, tags, sort, create_time)')
    sql.push('SELECT ' + [
      textSql(place.name), '1', geo.lat.toFixed(6), geo.lng.toFixed(6), '80', textSql(place.address),
      textSql(place.reason), '20', textSql(place.city), '0', 'NULL', 'NULL', 'NULL', textSql(tags), String(index), 'NOW()',
    ].join(', ') + ' FROM DUAL')
    sql.push('WHERE NOT EXISTS (SELECT 1 FROM roam_poi WHERE JSON_CONTAINS(CASE WHEN JSON_VALID(tags) THEN tags ELSE ' +
      textSql('[]') + ' END, JSON_QUOTE(' + textSql(tag) + ')));')
  })
  sql.push('COMMIT;', '-- 回读刚导入的下架点位；零行或数量不符时停止，不直接上架。')
  sql.push('SELECT id, name, type, city_code, status, merchant_id, template_id, coupon_template_id, lat, lng, tags FROM roam_poi WHERE ' +
    checked.map(({ tag }) => 'JSON_CONTAINS(CASE WHEN JSON_VALID(tags) THEN tags ELSE ' + textSql('[]') + ' END, JSON_QUOTE(' + textSql(tag) + '))').join(' OR ') + ';')
  return sql.join('\n') + '\n'
}
function main(args) {
  const catalog = require(CATALOG_PATH)
  if (args.length === 1 && args[0] === '--template') {
    process.stdout.write(JSON.stringify(coordinateTemplate(catalog), null, 2) + '\n')
    return
  }
  const allowed = new Set(['--coordinates','--city','--output'])
  const options = {}
  for (let i = 0; i < args.length; i += 2) {
    if (!allowed.has(args[i]) || !args[i+1] || options[args[i]]) throw new Error('参数无效')
    options[args[i]] = args[i+1]
  }
  if (!options['--coordinates'] || !options['--output']) throw new Error('用法：--template 或 --coordinates 文件 --output SQL文件 [--city SH]')
  // Validate every selected row before creating/truncating the output.
  const sql = buildImport(catalog, JSON.parse(fs.readFileSync(options['--coordinates'], 'utf8')), options['--city'])
  fs.writeFileSync(path.resolve(options['--output']), sql, { encoding: 'utf8', flag: 'wx' })
}
if (require.main === module) {
  try { main(process.argv.slice(2)) } catch (error) { process.stderr.write(error.message + '\n'); process.exitCode = 1 }
}
module.exports = { buildImport, coordinateTemplate }
