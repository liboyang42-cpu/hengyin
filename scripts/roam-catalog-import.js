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
const PRECISIONS = ['poi', 'street', 'start', 'approx']
// 入口精度不是门牌级的(街道/起点/近似)时放宽到店半径,避免玩家站在街上却点不亮。
const RADIUS_BY_PRECISION = { poi: 80, street: 150, start: 150, approx: 150 }
const KEYED_SYSTEMS = /^(GCJ-02|WGS84\(HK,no offset\))$/
function optionalMedia(geo, id) {
  const cover = geo.coverImg == null || geo.coverImg === '' ? null : geo.coverImg
  const credit = geo.photoCredit == null || geo.photoCredit === '' ? null : geo.photoCredit
  if (cover !== null && (typeof cover !== 'string' || Array.from(cover).length > 500 || !/^(https:\/\/|\/)[^\s]+$/.test(cover))) throw new Error('封面地址无效：' + id)
  if (credit !== null && (typeof credit !== 'string' || Array.from(credit).length > 2000)) throw new Error('照片署名无效：' + id)
  if (cover !== null && credit === null) throw new Error('封面缺少来源与许可署名：' + id)
  return { cover, credit }
}
// 按目录 id 索引的坐标清单(OSM 公开地理数据整理):{ id: { gcj02:[lat,lng], coordSystem, precision, source, verifiedOn } | { missing:true } }
// 缺坐标的地点不进 SQL(保持未发布),并在 SQL 头注释里列出。
function keyedRows(catalog, keyed, city, now) {
  const known = new Set(catalog.places.map(place => place.id))
  for (const id of Object.keys(keyed)) if (!known.has(id)) throw new Error('坐标清单包含未知地点：' + id)
  const rows = []
  const skipped = []
  for (const place of catalog.places.filter(item => !city || item.city === city)) {
    const geo = keyed[place.id]
    if (!geo || geo.missing === true) { skipped.push(place.id); continue }
    const bounds = BOUNDS[place.city]
    const ok = bounds && Array.isArray(geo.gcj02) && geo.gcj02.length === 2 &&
      geo.gcj02.every(value => typeof value === 'number' && Number.isFinite(value)) &&
      geo.gcj02[0] >= bounds[0] && geo.gcj02[0] <= bounds[1] && geo.gcj02[1] >= bounds[2] && geo.gcj02[1] <= bounds[3] &&
      typeof geo.coordSystem === 'string' && KEYED_SYSTEMS.test(geo.coordSystem) &&
      (geo.coordSystem === 'GCJ-02' || place.city === 'HK') &&
      PRECISIONS.includes(geo.precision) && typeof geo.source === 'string' && geo.source.trim() &&
      checkedDate(geo.verifiedOn, now)
    if (!ok) throw new Error('坐标未通过校验：' + place.id)
    const media = optionalMedia(geo, place.id)
    rows.push({ id: place.id, lat: geo.gcj02[0], lng: geo.gcj02[1], precision: geo.precision, source: geo.source,
      provider: 'osm', verifiedOn: geo.verifiedOn, cover: media.cover, credit: media.credit })
  }
  return { rows, skipped }
}
function buildImport(catalog, coordinates, city, now = new Date(), options = {}) {
  const status = options.status === undefined ? 0 : options.status
  if (status !== 0 && status !== 1) throw new Error('状态只能是 0 或 1')
  const keyed = coordinates && !Array.isArray(coordinates) && typeof coordinates === 'object'
  if (!catalog || !Array.isArray(catalog.places) || (!Array.isArray(coordinates) && !keyed)) throw new Error('地点目录或坐标清单格式错误')
  let skipped = []
  if (keyed) {
    if (city && !BOUNDS[city]) throw new Error('未知城市码')
    const result = keyedRows(catalog, coordinates, city, now)
    skipped = result.skipped
    coordinates = result.rows.map(row => Object.assign({}, row, { city: catalog.places.find(place => place.id === row.id).city }))
    catalog = { places: catalog.places.filter(place => coordinates.some(row => row.id === place.id)) }
    if (!catalog.places.length) throw new Error('没有可导入的地点')
  }
  if (city && !BOUNDS[city]) throw new Error('未知城市码')
  const places = catalog.places.filter(place => !city || place.city === city)
  if (!places.length || new Set(places.map(place => place.id)).size !== places.length) throw new Error('地点清单为空或身份重复')
  const known = new Set(catalog.places.map(place => place.id))
  if (new Set(coordinates.map(row => row && row.id)).size !== coordinates.length ||
      coordinates.some(row => !row || !known.has(row.id))) throw new Error('坐标清单包含重复或未知地点')
  const checked = places.map(place => {
    const geo = coordinates.find(row => row.id === place.id)
    const bounds = BOUNDS[place.city]
    let precision = 'poi'
    let provider
    if (keyed) {
      if (!geo) throw new Error('坐标与入口未完成核验：' + place.id)
      precision = geo.precision
      provider = geo.provider
    } else {
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
      provider = geo.provider
    }
    if (!Array.isArray(place.sources) || new Set(place.sources).size < 2 ||
        place.sources.some(source => typeof source !== 'string' || !/^https:\/\/[^\s]+$/.test(source)) ||
        !checkedDate(place.reviewedOn, now) || place.reviewMethod !== 'public_sources') {
      throw new Error('来源核验缺失或超过90天：' + place.id)
    }
    const media = keyed ? { cover: geo.cover, credit: geo.credit } : optionalMedia(geo, place.id)
    const tag = 'roam:seed:v1:' + place.id
    const tags = JSON.stringify(['roam:' + place.category, tag].concat(place.tags))
    // A5.5/B5.3c:推荐理由放进 quotes_json(带出处),description 只放到访说明;不再把 reason 写进 description。
    const quotes = JSON.stringify([{ text: place.reason, source: '城瘾编辑推荐', author: '城瘾', date: place.reviewedOn, url: place.sources[0] }])
    for (const [value, limit] of [[place.name,80],[place.address,255],[place.reason,255],[tags,255],[quotes,4000]]) {
      if (typeof value !== 'string' || !value.trim() || Array.from(value).length > limit) throw new Error('内容字段超长或为空：' + place.id)
    }
    if (place.visitNote != null && (typeof place.visitNote !== 'string' || Array.from(place.visitNote).length > 255)) {
      throw new Error('到访说明超长：' + place.id)
    }
    return { place, geo, tag, tags, quotes, precision, provider, cover: media.cover, credit: media.credit }
  })
  const seedMatch = tag => 'JSON_CONTAINS(CASE WHEN JSON_VALID(tags) THEN tags ELSE ' + textSql('[]') + ' END, JSON_QUOTE(' + textSql(tag) + '))'
  const sql = [
    '-- 城市漫游目录：仅离线编译，未执行任何数据库操作。',
    '-- 预先核对现有 roam_poi 的 address/tags/sort 列及同名点位；先在隔离测试库执行并回读。',
    status === 1
      ? '-- 本文件 status=1（上架）：仅限隔离测试库；执行后玩家侧立即可见。配套 revert 文件可撤回。'
      : '-- 新点位 status=0（下架）；运营审核并上架后才进入附近点位接口。',
    '-- 所有条目 type=1，无商家归属、玩法模板、券模板；商家认领仍走既有审核链。',
    '-- 按种子标签防止顺序重复导入；仅供单个运营者串行执行，不替代并发唯一约束。',
  ]
  if (skipped.length) sql.push('-- 无坐标，未导入、保持未发布：' + skipped.join(', '))
  sql.push('START TRANSACTION;')
  checked.forEach(({ place, geo, tag, tags, quotes, precision, provider, cover, credit }, index) => {
    sql.push('-- ' + place.id + ' / ' + provider + ' / ' + precision + ' / ' + geo.verifiedOn + (geo.source ? ' / ' + String(geo.source).replace(/\s+/g, ' ') : ''))
    sql.push('INSERT INTO roam_poi (name, type, lat, lng, radius_m, address, description, xp, city_code, status, merchant_id, template_id, coupon_template_id, tags, sort, create_time, cover_img, photo_credit, quotes_json)')
    sql.push('SELECT ' + [
      textSql(place.name), '1', geo.lat.toFixed(6), geo.lng.toFixed(6), String(RADIUS_BY_PRECISION[precision] || 80), textSql(place.address),
      place.visitNote ? textSql(place.visitNote) : 'NULL', '20', textSql(place.city), String(status), 'NULL', 'NULL', 'NULL',
      textSql(tags), String(index), 'NOW()',
      cover === null ? 'NULL' : textSql(cover), credit === null ? 'NULL' : textSql(credit), textSql(quotes),
    ].join(', ') + ' FROM DUAL')
    sql.push('WHERE NOT EXISTS (SELECT 1 FROM roam_poi WHERE ' + seedMatch(tag) + ');')
  })
  if (status === 1) {
    // 重跑时历史下架行也要落到上架态;范围与导入一致(type=1、无商家、有坐标、带本批种子标签)。
    sql.push('UPDATE roam_poi SET status = 1 WHERE type = 1 AND merchant_id IS NULL AND lat IS NOT NULL AND lng IS NOT NULL AND (' +
      checked.map(({ tag }) => seedMatch(tag)).join(' OR ') + ');')
  }
  sql.push('COMMIT;', status === 1 ? '-- 回读本批点位；数量不符时停止。' : '-- 回读刚导入的下架点位；零行或数量不符时停止，不直接上架。')
  sql.push('SELECT id, name, type, city_code, status, merchant_id, template_id, coupon_template_id, lat, lng, tags FROM roam_poi WHERE ' +
    checked.map(({ tag }) => seedMatch(tag)).join(' OR ') + ';')
  const result = sql.join('\n') + '\n'
  if (Array.isArray(options.tags)) checked.forEach(item => options.tags.push(item.tag))
  return result
}
// 与导入配套的撤回:先删「没有任何玩家记录引用」的本批行,仍被发现/打卡记录引用的行只下架,不丢流水。
function buildRevert(tags) {
  const match = tag => 'JSON_CONTAINS(CASE WHEN JSON_VALID(tags) THEN tags ELSE ' + textSql('[]') + ' END, JSON_QUOTE(' + textSql(tag) + '))'
  const any = tags.map(match).join(' OR ')
  return [
    '-- 城市漫游目录导入的撤回文件(仅隔离测试库)：只影响 type=1、无商家归属、带本批种子标签的行。',
    'START TRANSACTION;',
    'DELETE FROM roam_poi WHERE type = 1 AND merchant_id IS NULL AND (' + any + ')',
    '  AND NOT EXISTS (SELECT 1 FROM roam_poi_found f WHERE f.poi_id = roam_poi.id)',
    '  AND NOT EXISTS (SELECT 1 FROM roam_poi_reward_log r WHERE r.poi_id = roam_poi.id);',
    'UPDATE roam_poi SET status = 0 WHERE type = 1 AND merchant_id IS NULL AND (' + any + ');',
    'COMMIT;',
    'SELECT COUNT(*) AS remaining_rows FROM roam_poi WHERE ' + any + ';',
  ].join('\n') + '\n'
}
function main(args) {
  const catalog = require(CATALOG_PATH)
  if (args.length === 1 && args[0] === '--template') {
    process.stdout.write(JSON.stringify(coordinateTemplate(catalog), null, 2) + '\n')
    return
  }
  const allowed = new Set(['--coordinates','--city','--output','--status','--revert-output'])
  const options = {}
  for (let i = 0; i < args.length; i += 2) {
    if (!allowed.has(args[i]) || !args[i+1] || options[args[i]]) throw new Error('参数无效')
    options[args[i]] = args[i+1]
  }
  if (!options['--coordinates'] || !options['--output']) {
    throw new Error('用法：--template 或 --coordinates 文件 --output SQL文件 [--city SH] [--status 0|1] [--revert-output 撤回SQL文件]')
  }
  if (options['--status'] !== undefined && !['0','1'].includes(options['--status'])) throw new Error('--status 只能是 0 或 1')
  const status = options['--status'] === '1' ? 1 : 0
  const tags = []
  // Validate every selected row before creating/truncating the output.
  const sql = buildImport(catalog, JSON.parse(fs.readFileSync(options['--coordinates'], 'utf8')), options['--city'], new Date(), { status, tags })
  const revert = options['--revert-output'] ? buildRevert(tags) : null
  fs.writeFileSync(path.resolve(options['--output']), sql, { encoding: 'utf8', flag: 'wx' })
  if (revert) fs.writeFileSync(path.resolve(options['--revert-output']), revert, { encoding: 'utf8', flag: 'wx' })
}
if (require.main === module) {
  try { main(process.argv.slice(2)) } catch (error) { process.stderr.write(error.message + '\n'); process.exitCode = 1 }
}
module.exports = { buildImport, buildRevert, coordinateTemplate }
