<template>
  <div id="tags-view-container" class="tags-view-container">
    <scroll-pane ref="scrollPane" class="tags-view-wrapper" @scroll="handleScroll">
      <router-link
        v-for="tag in visitedViews"
        ref="tag"
        :key="tag.tagKey"
        :class="{ 'active': isActive(tag), 'has-icon': tagsIcon }"
        :to="{ path: tag.path, query: tag.query }"
        class="tags-view-item"
        :style="activeStyle(tag)"
        :title="tag.context ? `${tag.title} · ${tag.context}` : tag.title"
        :aria-current="isActive(tag) ? 'page' : null"
        @click.middle.native="!isAffix(tag)?closeSelectedTag(tag):''"
        @contextmenu.prevent.native="openMenu(tag,$event)"
      >
        <svg-icon v-if="tagsIcon && tag.meta && tag.meta.icon && tag.meta.icon !== '#'" :icon-class="tag.meta.icon" />
        <i v-if="isAffix(tag)" class="el-icon-star-on tag-pin" aria-label="固定页签" />
        <span class="tag-title">{{ tag.title }}</span>
        <span v-if="tag.context" class="tag-context">{{ tag.context }}</span>
        <span v-if="!isAffix(tag)" class="el-icon-close" @click.prevent.stop="closeSelectedTag(tag)" />
      </router-link>
    </scroll-pane>
    <ul v-show="visible" :style="{left:left+'px',top:top+'px'}" class="contextmenu">
      <li @click="refreshSelectedTag(selectedTag)"><i class="el-icon-refresh-right"></i> 刷新页面</li>
      <li v-if="!isAffix(selectedTag)" @click="closeSelectedTag(selectedTag)"><i class="el-icon-close"></i> 关闭当前</li>
      <li @click="closeOthersTags"><i class="el-icon-circle-close"></i> 关闭其他</li>
      <li v-if="!isFirstView()" @click="closeLeftTags"><i class="el-icon-back"></i> 关闭左侧</li>
      <li v-if="!isLastView()" @click="closeRightTags"><i class="el-icon-right"></i> 关闭右侧</li>
      <li @click="closeAllTags(selectedTag)"><i class="el-icon-circle-close"></i> 全部关闭</li>
    </ul>
  </div>
</template>

<script>
import ScrollPane from './ScrollPane'
import path from 'path'
import { getViewKey } from '@/utils/tagsView'

export default {
  components: { ScrollPane },
  data() {
    return {
      visible: false,
      top: 0,
      left: 0,
      selectedTag: {},
      affixTags: []
    }
  },
  computed: {
    visitedViews() {
      return this.$store.state.tagsView.visitedViews
    },
    routes() {
      return this.$store.state.permission.routes
    },
    theme() {
      return this.$store.state.settings.theme
    },
    tagsIcon() {
      return this.$store.state.settings.tagsIcon
    }
  },
  watch: {
    $route() {
      this.addTags()
      this.moveToCurrentTag()
    },
    visible(value) {
      if (value) {
        document.body.addEventListener('click', this.closeMenu)
      } else {
        document.body.removeEventListener('click', this.closeMenu)
      }
    }
  },
  mounted() {
    this.initTags()
    this.addTags()
  },
  methods: {
    isActive(route) {
      return route.tagKey === getViewKey(this.$route)
    },
    activeStyle(tag) {
      return {}
    },
    isAffix(tag) {
      return tag.meta && tag.meta.affix
    },
    isFirstView() {
      const firstRecentView = this.visitedViews.find(tag => !this.isAffix(tag))
      return !firstRecentView || this.selectedTag.tagKey === firstRecentView.tagKey
    },
    isLastView() {
      const lastView = this.visitedViews[this.visitedViews.length - 1]
      return !lastView || this.selectedTag.tagKey === lastView.tagKey
    },
    filterAffixTags(routes, basePath = '/') {
      let tags = []
      routes.forEach(route => {
        if (route.meta && route.meta.affix) {
          const tagPath = path.resolve(basePath, route.path)
          tags.push({
            fullPath: tagPath,
            path: tagPath,
            name: route.name,
            meta: { ...route.meta }
          })
        }
        if (route.children) {
          const tempTags = this.filterAffixTags(route.children, route.path)
          if (tempTags.length >= 1) {
            tags = [...tags, ...tempTags]
          }
        }
      })
      return tags
    },
    initTags() {
      const affixTags = this.affixTags = this.filterAffixTags(this.routes)
      for (const tag of affixTags) {
        // Must have tag name
        if (tag.name) {
          this.$store.dispatch('tagsView/addVisitedView', tag)
        }
      }
    },
    addTags() {
      const { name } = this.$route
      if (name) {
        this.$store.dispatch('tagsView/addView', this.$route)
      }
    },
    moveToCurrentTag() {
      const tags = Array.isArray(this.$refs.tag) ? this.$refs.tag : [this.$refs.tag]
      this.$nextTick(() => {
        for (const tag of tags) {
          if (tag && getViewKey(tag.to) === getViewKey(this.$route)) {
            this.$refs.scrollPane.moveToTarget(tag)
            break
          }
        }
      })
    },
    refreshSelectedTag(view) {
      this.$tab.refreshPage(view)
      if (this.$route.meta.link) {
        this.$store.dispatch('tagsView/delIframeView', this.$route)
      }
    },
    closeSelectedTag(view) {
      this.$tab.closePage(view).then(({ visitedViews }) => {
        if (this.isActive(view)) {
          this.toLastView(visitedViews, view)
        }
      })
    },
    closeRightTags() {
      this.$tab.closeRightPage(this.selectedTag).then(visitedViews => {
        if (!visitedViews.find(i => i.tagKey === getViewKey(this.$route))) {
          this.toLastView(visitedViews)
        }
      })
    },
    closeLeftTags() {
      this.$tab.closeLeftPage(this.selectedTag).then(visitedViews => {
        if (!visitedViews.find(i => i.tagKey === getViewKey(this.$route))) {
          this.toLastView(visitedViews)
        }
      })
    },
    closeOthersTags() {
      this.$router.push(this.selectedTag.fullPath).catch(()=>{})
      this.$tab.closeOtherPage(this.selectedTag).then(() => {
        this.moveToCurrentTag()
      })
    },
    closeAllTags(view) {
      this.$tab.closeAllPage().then(({ visitedViews }) => {
        if (this.affixTags.some(tag => getViewKey(tag) === getViewKey(this.$route))) {
          return
        }
        this.toLastView(visitedViews, view)
      })
    },
    toLastView(visitedViews, view) {
      const latestView = visitedViews.slice(-1)[0]
      if (latestView) {
        this.$router.push(latestView.fullPath)
      } else {
        // now the default is to redirect to the home page if there is no tags-view,
        // you can adjust it according to your needs.
        if (view.name === 'Dashboard') {
          // to reload home page
          this.$router.replace({ path: '/redirect' + view.fullPath })
        } else {
          this.$router.push('/')
        }
      }
    },
    openMenu(tag, e) {
      const menuMinWidth = 105
      const offsetLeft = this.$el.getBoundingClientRect().left // container margin left
      const offsetWidth = this.$el.offsetWidth // container width
      const maxLeft = offsetWidth - menuMinWidth // left boundary
      const left = e.clientX - offsetLeft + 15 // 15: margin right

      if (left > maxLeft) {
        this.left = maxLeft
      } else {
        this.left = left
      }

      this.top = e.clientY
      this.visible = true
      this.selectedTag = tag
    },
    closeMenu() {
      this.visible = false
    },
    handleScroll() {
      this.closeMenu()
    }
  }
}
</script>

<style lang="scss" scoped>
.tags-view-container {
  height: 38px;
  width: 100%;
  background: var(--cy-color-bg-surface);
  border-bottom: 1px solid var(--cy-color-border-subtle);
  box-shadow: none;
  .tags-view-wrapper {
    .tags-view-item {
      display: inline-block;
      position: relative;
      cursor: pointer;
      height: 28px;
      line-height: 28px;
      border: 1px solid transparent;
      border-radius: 9px;
      color: var(--cy-color-text-secondary);
      background: var(--cy-color-bg-subtle);
      padding: 0 var(--cy-space-1-5);
      font-size: 12px;
      text-decoration: none;
      white-space: nowrap;
      margin-left: var(--cy-legacy-5);
      margin-top: var(--cy-space-1);
      &:first-of-type {
        margin-left: var(--cy-legacy-15);
      }
      &:last-of-type {
        margin-right: var(--cy-legacy-15);
      }
      &.active {
        background: var(--cy-color-brand-soft) !important;
        color: var(--cy-color-brand-strong);
        border-color: #DED6FF !important;
        font-weight: 700;
        &::before {
          content: '';
          background: var(--cy-color-brand);
          display: inline-block;
          width: var(--cy-space-2);
          height: var(--cy-space-2);
          border-radius: 50%;
          position: relative;
          margin-right: 2px;
        }
      }
      .tag-pin {
        margin-right: var(--cy-legacy-3);
        color: var(--cy-color-brand-strong);
      }
      .tag-context {
        margin-left: var(--cy-space-1);
        color: var(--cy-color-text-tertiary);
        font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      }
    }
  }

  .tags-view-item.active.has-icon::before {
    content: none !important;
  }

  .contextmenu {
    margin: 0;
    background: var(--cy-color-bg-surface);
    z-index: 3000;
    position: absolute;
    list-style-type: none;
    padding: var(--cy-legacy-5) 0;
    border-radius: 4px;
    font-size: 12px;
    font-weight: 400;
    color: #333;
    box-shadow: 2px 2px 3px 0 rgba(0, 0, 0, .3);
    li {
      margin: 0;
      padding: 7px var(--cy-space-4); /* ds-ok: 7px 无匹配 token */
      cursor: pointer;
      &:hover {
        background: #eee;
      }
    }
  }
}
</style>

<style lang="scss">
//reset element css of el-icon-close
.tags-view-wrapper {
  .tags-view-item {
    .el-icon-close {
      width: var(--cy-space-4);
      height: var(--cy-space-4);
      vertical-align: 2px;
      border-radius: 50%;
      text-align: center;
      transition: all .3s cubic-bezier(.645, .045, .355, 1);
      transform-origin: 100% 50%;
      &:before {
        transform: scale(.6);
        display: inline-block;
        vertical-align: -3px;
      }
      &:hover {
        background-color: #b4bccc;
        color: var(--cy-color-text-inverse);
      }
    }
  }
}
</style>
