'use strict'

const catalog = require('../data/roam-city-catalog.js')
const { CATEGORY_LABELS, readTags } = require('../../utils/roam-poi-presentation.js')
const categories = Object.keys(CATEGORY_LABELS).map(id => ({ id, name: CATEGORY_LABELS[id] }))
const cities = catalog.cities.map(city => Object.assign({}, city, {
  count: catalog.places.filter(place => place.city === city.id).length,
}))
function listPlaces(city, category) {
  if (!cities.some(row => row.id === city) || !categories.some(row => row.id === category)) return []
  return catalog.places.filter(place => place.city === city && (category === 'all' || place.category === category))
    .map(place => Object.assign({}, place, {
      categoryLabel: CATEGORY_LABELS[place.category], sourceCount: place.sources.length,
    }))
}
function findPlace(id) {
  const place = catalog.places.find(row => row.id === id)
  return place ? listPlaces(place.city, 'all').find(row => row.id === id) : null
}
// 地图点位只认导入工具写进 tags 的种子标识(roam:seed:v1:<目录 id>),不按名字或地址猜。
const SEED_PREFIX = 'roam:seed:v1:'
function seedIdFromTags(tags) {
  const tag = readTags(tags).find(row => typeof row === 'string' && row.startsWith(SEED_PREFIX))
  return tag ? tag.slice(SEED_PREFIX.length) : ''
}
function sourceHost(url) {
  const match = /^https:\/\/([^/?#]+)/.exec(String(url || ''))
  return match ? match[1].replace(/^www\./, '') : ''
}
// 地点卡用:目录里核对过的推荐理由与资料来源;没有种子标识或目录里查不到就什么都不给。
function placeNoteForTags(tags) {
  const id = seedIdFromTags(tags)
  const place = id ? catalog.places.find(row => row.id === id) : null
  if (!place) return null
  return {
    reason: place.reason,
    sources: place.sources.slice(),
    sourceText: place.sources.map(sourceHost).filter(Boolean).join(' · '),
  }
}
module.exports = { cities, categories, listPlaces, findPlace, reviewNote: catalog.reviewNote, seedIdFromTags, placeNoteForTags }
