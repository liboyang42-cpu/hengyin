<template>
  <el-dialog title="官方据点 · 地点与游戏" :visible="visible" width="760px" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @close="$emit('close')">
    <el-alert title="保存后进入待审核，审核通过才出现在地图。官方地点不创建商家归属。" type="info" :closable="false" />
    <el-form v-loading="loading" label-width="100px" size="small" class="official-poi-form" @submit.native.prevent>
      <el-form-item label="地点名称"><el-input v-model="poi.name" maxlength="80" /></el-form-item>
      <el-form-item label="街道地址"><el-input v-model="poi.address" maxlength="255" /></el-form-item>
      <el-form-item label="城市码"><el-input v-model="poi.cityCode" placeholder="如310100" maxlength="20" /></el-form-item>
      <el-form-item label="GCJ02坐标"><el-input-number v-model="poi.lat" :min="-90" :max="90" :precision="6" aria-label="纬度" /><el-input-number v-model="poi.lng" :min="-180" :max="180" :precision="6" aria-label="经度" /></el-form-item>
      <el-form-item label="分类标签"><el-input v-model="poi.tags" placeholder="多个标签用逗号分隔" maxlength="255" /></el-form-item>
      <el-form-item label="地点介绍"><el-input v-model="poi.description" type="textarea" maxlength="255" show-word-limit placeholder="推荐理由、开放安排及门票说明" /></el-form-item>
      <el-form-item label="地点相册"><image-upload v-model="pictures" :limit="6" :file-size="20" /><span>第一张作为封面；玩家详情完整展示原图比例。</span></el-form-item>
      <el-form-item label="公开引述">
        <div v-for="(quote, index) in quotes" :key="index" class="official-poi-quote">
          <el-input v-model="quote.text" type="textarea" maxlength="160" show-word-limit placeholder="原文摘录（逐字照抄，不改写）" />
          <el-input v-model="quote.source" maxlength="40" placeholder="来源，如 小红书 / 澎湃新闻" />
          <el-input v-model="quote.author" maxlength="40" placeholder="作者或账号（可选）" />
          <el-input v-model="quote.date" maxlength="10" placeholder="发布日期 YYYY-MM-DD（可选）" />
          <el-input v-model="quote.url" maxlength="500" placeholder="原文链接 https://…" />
          <el-button type="text" @click="quotes.splice(index, 1)">删除这条</el-button>
        </div>
        <el-button v-if="quotes.length < 6" size="mini" @click="quotes.push(blankQuote())">添加一条</el-button>
        <span>玩家看到的是“来源 · 作者”标注的摘录，与玩家评价分开；须核对原文后再填。</span>
      </el-form-item>
      <el-form-item label="游戏名称"><el-input v-model="game.title" maxlength="80" /></el-form-item>
      <el-form-item label="游戏介绍"><el-input v-model="game.description" type="textarea" maxlength="255" /></el-form-item>
      <el-form-item label="拍照核验"><el-switch v-model="poi.photoRequired" active-text="先拍照，再答题" inactive-text="仅观察答题" /></el-form-item>
      <el-form-item v-if="poi.photoRequired" label="拍照任务"><el-input v-model="game.photoRequireDesc" type="textarea" maxlength="255" placeholder="说明到场后拍什么；须符合场馆拍摄要求" /></el-form-item>
      <el-form-item label="观察题"><el-input v-model="game.questionName" maxlength="255" /></el-form-item>
      <el-form-item v-for="letter in letters" :key="letter" :label="'选项' + letter"><el-input v-model="game['question' + letter]" maxlength="255" /></el-form-item>
      <el-form-item label="正确答案"><el-radio-group v-model="game.correctAnswer"><el-radio-button v-for="letter in letters" :key="letter" :label="letter" /></el-radio-group></el-form-item>
      <el-form-item label="完成奖励">完成{{ poi.photoRequired ? '拍照和' : '' }}答题后获得本地点探索勋章；不要求消费。</el-form-item>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
    </el-form>
    <span slot="footer"><el-button :disabled="saving" @click="$emit('close')">取消</el-button><el-button type="primary" :loading="saving" :disabled="loading" @click="save">保存并提交审核</el-button></span>
  </el-dialog>
</template>

<script>
import ImageUpload from '@/components/ImageUpload'
import { officialPoiDraft, saveOfficialPoi } from '@/api/business/roamPoi'

const blankPoi = () => ({ name: '', address: '', cityCode: '', lat: null, lng: null, tags: '', description: '', photoRequired: true })
const blankQuote = () => ({ text: '', source: '', author: '', date: '', url: '' })
const blankGame = () => ({ title: '', description: '', photoRequireDesc: '', questionName: '', questionA: '', questionB: '', questionC: '', questionD: '', correctAnswer: 'A' })
function assetKey(value) {
  const text = String(value || '').trim()
  const marker = text.indexOf('/profile/')
  return marker >= 0 ? text.slice(marker + 9) : text.replace(/^\//, '')
}
export default {
  components: { ImageUpload },
  props: { visible: Boolean, poiId: [Number, String] },
  data: () => ({ poi: blankPoi(), game: blankGame(), pictures: '', quotes: [], letters: ['A', 'B', 'C', 'D'], saving: false, loading: false, error: '', sequence: 0 }),
  watch: { visible: { immediate: true, handler(value) { if (value) this.load(); else this.sequence++ } } },
  methods: {
    blankQuote,
    async load() {
      const sequence = ++this.sequence
      this.poi = blankPoi(); this.game = blankGame(); this.pictures = ''; this.quotes = []; this.error = ''; this.loading = false
      if (!this.poiId) return
      this.loading = true
      try {
        const result = await officialPoiDraft(this.poiId)
        if (sequence !== this.sequence || !this.visible) return
        this.poi = { ...blankPoi(), ...result.data.poi }
        this.game = { ...blankGame(), ...(result.data.game || {}) }
        let images = []
        try { images = JSON.parse(this.poi.galleryJson || '[]') } catch (_) {}
        let quotes = []
        try { quotes = JSON.parse(this.poi.quotesJson || '[]') } catch (_) {}
        this.quotes = (Array.isArray(quotes) ? quotes : []).map(row => ({ ...blankQuote(), ...row }))
        this.pictures = (Array.isArray(images) && images.length ? images : [this.poi.coverImg]).filter(Boolean).map(key => '/profile/' + assetKey(key)).join(',')
      } catch (_) { if (sequence === this.sequence) this.error = '据点资料读取失败，请关闭后重试' }
      finally { if (sequence === this.sequence) this.loading = false }
    },
    async save() {
      if (this.saving || this.loading) return
      const images = this.pictures.split(',').filter(Boolean).map(assetKey)
      if (!images.length) { this.error = '请添加地点照片'; return }
      const quotes = this.quotes.map(row => ({ text: row.text.trim(), source: row.source.trim(), author: row.author.trim(), date: row.date.trim(), url: row.url.trim() }))
        .filter(row => row.text || row.source || row.url)
        .map(row => Object.fromEntries(Object.entries(row).filter(([, value]) => value)))
      if (quotes.some(row => !row.text || !row.source || !/^https:\/\//.test(row.url || ''))) { this.error = '每条公开引述都要有原文、来源和 https 原文链接'; return }
      this.saving = true; this.error = ''
      try {
        await saveOfficialPoi({ poi: { ...this.poi, coverImg: images[0], galleryJson: JSON.stringify(images), quotesJson: quotes.length ? JSON.stringify(quotes) : null }, game: this.game })
        this.$modal.msgSuccess('已提交审核，尚未上架')
        this.$emit('saved'); this.$emit('close')
      } catch (error) { this.error = error.message || '未保存，请保留内容重试' }
      finally { this.saving = false }
    }
  }
}
</script>

<style scoped>
.official-poi-quote { display: grid; gap: 6px; margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px solid #ebeef5; }
.official-poi-form { margin-top: 20px; max-height: 65vh; overflow-y: auto; padding-right: 12px; }
</style>
