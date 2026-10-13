<template>
  <section class="cy-evidence-panel">
    <header class="cy-evidence-panel__heading">
      <p v-if="eyebrow">{{ eyebrow }}</p>
      <h3>{{ title }}</h3>
    </header>
    <div v-if="textItems.length || imageItems.length" class="cy-evidence-panel__content">
      <div v-for="(item, index) in textItems" :key="`text-${index}`" class="cy-evidence-panel__text">
        <h4 v-if="item.label">{{ item.label }}</h4>
        <pre>{{ item.content }}</pre>
      </div>
      <div v-if="imageItems.length" class="cy-evidence-panel__images">
        <el-image
          v-for="(item, index) in imageItems"
          :key="`${item.src}-${index}`"
          :src="item.src"
          :alt="item.alt || title"
          :preview-src-list="previewImages"
          fit="cover"
          lazy
        />
      </div>
    </div>
    <el-empty v-else :description="emptyText" :image-size="80" />
  </section>
</template>

<script>
export default {
  name: 'CyEvidencePanel',
  props: {
    eyebrow: { type: String, default: '' },
    title: { type: String, required: true },
    items: { type: Array, default: () => [] },
    emptyText: { type: String, default: '暂无证据' }
  },
  computed: {
    textItems() {
      return this.items.filter(item => item.kind === 'text' && item.content)
    },
    imageItems() {
      return this.items.filter(item => item.kind === 'image' && item.src)
    },
    previewImages() {
      return this.imageItems.map(item => item.src).filter((src, index, all) => all.indexOf(src) === index)
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-evidence-panel { margin-top: var(--cy-space-3-5); }
.cy-evidence-panel__heading p { margin: 0 0 4px; color: var(--cy-color-brand); font-size: 11px; font-weight: 700; letter-spacing: .1em; text-transform: uppercase; }
.cy-evidence-panel__heading h3 { margin: 0; color: var(--cy-color-text-primary); font-size: 15px; }
.cy-evidence-panel__content { margin-top: var(--cy-space-2-5); }
.cy-evidence-panel__text { padding: 16px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-card); background: var(--cy-color-bg-surface); }
.cy-evidence-panel__text + .cy-evidence-panel__text { margin-top: var(--cy-space-1-5); }
.cy-evidence-panel__text h4 { margin: 0 0 var(--cy-space-1-5); color: var(--cy-color-text-secondary); font-size: 13px; }
.cy-evidence-panel__text pre { max-height: 240px; margin: 0; overflow: auto; color: var(--cy-color-text-secondary); font-family: inherit; font-size: 12px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; }
.cy-evidence-panel__images { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.cy-evidence-panel__images .el-image { width: 100%; height: 150px; border-radius: var(--cy-radius-card); background: var(--cy-color-bg-subtle); }

@media (max-width: 1100px) {
  .cy-evidence-panel__images { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 720px) {
  .cy-evidence-panel__images { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
