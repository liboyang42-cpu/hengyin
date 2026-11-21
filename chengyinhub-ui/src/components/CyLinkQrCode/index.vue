<template>
  <!-- ONB-5:与小程序码同一 scene 的标准二维码。微信、iOS 相机和城瘾 App「扫一扫」都能打开。 -->
  <div class="cy-link-qr">
    <img v-if="dataUrl" :src="dataUrl" :alt="label" class="cy-link-qr__img" />
    <span v-else-if="failed" class="danger-text">通用码生成失败</span>
    <div class="cy-link-qr__meta">
      <span>{{ label }}</span>
      <a v-if="dataUrl" :href="dataUrl" :download="fileName">下载</a>
    </div>
  </div>
</template>

<script>
import QRCode from 'qrcode'

/** 后端 IPlayerWxacodeService.linkFor 的同一前缀;接口已返回 linkQr 时优先传 link。 */
const DOOR_LINK_BASE = 'https://www.chengyinhub.com/door?scene='

export default {
  name: 'CyLinkQrCode',
  props: {
    scene: { type: String, default: '' },
    link: { type: String, default: '' },
    label: { type: String, default: 'App 与微信通用码' },
    size: { type: Number, default: 160 }
  },
  data() {
    return { dataUrl: '', failed: false }
  },
  computed: {
    target() {
      if (this.link) return this.link
      return this.scene ? DOOR_LINK_BASE + encodeURIComponent(this.scene.trim()) : ''
    },
    fileName() {
      return 'chengyin-link-' + (this.scene || 'code') + '.png'
    }
  },
  watch: {
    target: { immediate: true, handler: 'render' }
  },
  methods: {
    render(value) {
      this.dataUrl = ''
      this.failed = false
      if (!value) return
      QRCode.toDataURL(value, { width: this.size, margin: 1, errorCorrectionLevel: 'M' })
        .then(url => { if (value === this.target) this.dataUrl = url })
        .catch(() => { if (value === this.target) this.failed = true })
    }
  }
}
</script>

<style scoped>
.cy-link-qr { display: inline-flex; flex-direction: column; align-items: flex-start; }
.cy-link-qr__img { display: block; }
.cy-link-qr__meta { display: flex; gap: 8px; font-size: 12px; }
</style>
