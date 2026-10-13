<template>
  <button
    v-if="hasSources"
    type="button"
    class="cy-media-preview"
    :aria-label="`查看${label}，共 ${validSources.length} 张`"
    @click="openPreview"
    @keydown.enter.prevent="openPreview"
    @keydown.space.prevent="openPreview"
  >
    <el-image
      ref="image"
      class="cy-media-preview__image"
      :src="primarySource"
      :preview-src-list="validSources"
      :fit="fit"
      :style="sizeStyle"
      :alt="label"
      @click.stop
    >
      <div slot="error" class="cy-media-preview__error">
        <i class="el-icon-picture-outline" aria-hidden="true" />
        <span>图片不可用</span>
      </div>
    </el-image>
    <span v-if="validSources.length > 1" class="cy-media-preview__count">+{{ validSources.length - 1 }}</span>
  </button>
</template>

<script>
export default {
  name: 'CyMediaPreview',
  props: {
    sources: { type: Array, default: () => [] },
    label: { type: String, default: '图片预览' },
    width: { type: [String, Number], default: 80 },
    height: { type: [String, Number], default: 60 },
    fit: { type: String, default: 'cover' }
  },
  computed: {
    validSources() {
      return this.sources.filter(source => typeof source === 'string' && source.trim()).map(source => source.trim())
    },
    hasSources() {
      return this.validSources.length > 0
    },
    primarySource() {
      return this.validSources[0] || ''
    },
    sizeStyle() {
      return {
        width: typeof this.width === 'number' ? `${this.width}px` : this.width,
        height: typeof this.height === 'number' ? `${this.height}px` : this.height
      }
    }
  },
  methods: {
    openPreview() {
      const image = this.$refs.image
      if (image && typeof image.clickHandler === 'function') {
        image.clickHandler()
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-media-preview {
  position: relative;
  display: inline-flex;
  padding: 0;
  vertical-align: middle;
  overflow: hidden;
  border: 1px solid var(--cy-color-border-subtle);
  border-radius: var(--cy-radius-control);
  background: var(--cy-color-bg-subtle);
  cursor: pointer;
}

.cy-media-preview__image { display: block; }
.cy-media-preview__image ::v-deep .el-image__inner { transition: transform 160ms ease; }
.cy-media-preview:hover ::v-deep .el-image__inner { transform: scale(1.04); }
.cy-media-preview__count {
  position: absolute;
  right: 4px;
  bottom: 4px;
  min-width: 20px;
  padding: 1px var(--cy-legacy-5);
  border-radius: 10px;
  background: rgba(15, 23, 43, .78);
  color: #fff;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  line-height: 16px;
  text-align: center;
}
.cy-media-preview__error { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: var(--cy-color-text-tertiary); font-size: 11px; gap: 2px; }
.cy-media-preview__error i { font-size: 18px; }
.cy-media-preview:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 3px; }
</style>
