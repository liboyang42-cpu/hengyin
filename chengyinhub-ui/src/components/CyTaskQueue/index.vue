<template>
  <section class="cy-task-queue" aria-labelledby="cy-task-queue-title">
    <div class="cy-task-queue__header">
      <div>
        <h2 id="cy-task-queue-title">{{ title }}</h2>
        <p v-if="description">{{ description }}</p>
      </div>
      <span class="cy-task-queue__total">{{ total }} 项</span>
    </div>

    <div v-if="loading" class="cy-task-queue__state">正在读取任务队列…</div>
    <div v-else-if="error" class="cy-task-queue__state cy-task-queue__state--error">
      {{ error }}
      <button v-if="retryable" type="button" @click="$emit('retry')">重新读取</button>
    </div>
    <div v-else-if="!items.length" class="cy-task-queue__state">当前没有需要处理的任务</div>
    <ol v-else class="cy-task-queue__list">
      <li v-for="(item, index) in items" :key="item.key">
        <button
          type="button"
          class="cy-task-queue__item"
          :aria-label="`${item.type}，${item.count} 项，进入处理`"
          @click="$emit('select', item)"
        >
          <span class="cy-task-queue__track" aria-hidden="true">
            <span class="cy-task-queue__step">{{ index + 1 }}</span>
          </span>
          <span class="cy-task-queue__body">
            <span class="cy-task-queue__title">{{ item.type }}</span>
            <span class="cy-task-queue__meta">{{ item.source }} · {{ item.description }}</span>
          </span>
          <span class="cy-task-queue__count">{{ item.count }}</span>
          <span class="cy-task-queue__action">处理 <i class="el-icon-right" /></span>
        </button>
      </li>
    </ol>
  </section>
</template>

<script>
export default {
  name: 'CyTaskQueue',
  props: {
    title: { type: String, default: '需要我处理' },
    description: { type: String, default: '' },
    items: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    error: { type: String, default: '' },
    // 无权限等不可重试的状态不显示「重新读取」
    retryable: { type: Boolean, default: true }
  },
  computed: {
    total() {
      return this.items.reduce((sum, item) => sum + Number(item.count || 0), 0)
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-task-queue {
  overflow: hidden;
  border: 1px solid var(--cy-color-border-subtle);
  border-radius: var(--cy-radius-card);
  background: var(--cy-color-bg-surface);
  box-shadow: var(--cy-shadow-card);
}

.cy-task-queue__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--cy-space-4);
  padding: var(--cy-space-5) var(--cy-space-6);
  border-bottom: 1px solid var(--cy-color-border-subtle);
}

h2 {
  margin: 0;
  color: var(--cy-color-text-primary);
  font-size: 18px;
  line-height: 1.4;
}

p {
  margin: var(--cy-space-0-5) 0 0;
  color: var(--cy-color-text-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.cy-task-queue__total {
  flex: none;
  color: var(--cy-color-brand);
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.cy-task-queue__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.cy-task-queue__item {
  position: relative;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 56px 72px;
  align-items: center;
  width: 100%;
  min-height: var(--cy-task-row-height);
  padding: 0 var(--cy-space-6);
  border: 0;
  border-bottom: 1px solid var(--cy-color-border-subtle);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

li:last-child .cy-task-queue__item { border-bottom: 0; }
.cy-task-queue__item:hover { background: var(--cy-color-bg-subtle); }
.cy-task-queue__item:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: -3px; }

.cy-task-queue__track {
  position: relative;
  align-self: stretch;
  display: flex;
  align-items: center;
}

.cy-task-queue__track::after {
  position: absolute;
  top: 36px;
  bottom: -20px;
  left: 13px;
  width: 1px;
  background: var(--cy-color-border-strong);
  content: '';
}

li:last-child .cy-task-queue__track::after { display: none; }

.cy-task-queue__step {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--cy-color-border-strong);
  border-radius: 50%;
  background: var(--cy-color-brand-soft);
  color: var(--cy-color-brand);
  font-size: 12px;
  font-weight: 800;
}

.cy-task-queue__body { min-width: 0; }
.cy-task-queue__title { display: block; color: var(--cy-color-text-primary); font-size: 14px; font-weight: 700; }
.cy-task-queue__meta { display: block; margin-top: var(--cy-legacy-5); overflow: hidden; color: var(--cy-color-text-secondary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.cy-task-queue__count { color: var(--cy-color-text-primary); font-size: 20px; font-weight: 750; font-variant-numeric: tabular-nums; text-align: right; }
.cy-task-queue__action { color: var(--cy-color-brand); font-size: 13px; font-weight: 700; text-align: right; }

.cy-task-queue__state {
  padding: 56px 24px;
  color: var(--cy-color-text-secondary);
  text-align: center;
}

.cy-task-queue__state--error { color: var(--cy-color-status-danger); }
.cy-task-queue__state button { margin-left: 8px; border: 0; background: transparent; color: var(--cy-color-brand); cursor: pointer; }

@media (max-width: 720px) {
  .cy-task-queue__item { grid-template-columns: 36px minmax(0, 1fr) 44px; padding: 0 var(--cy-space-4); }
  .cy-task-queue__action { display: none; }
}
</style>
