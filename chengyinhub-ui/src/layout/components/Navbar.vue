<template>
  <div class="navbar">
    <hamburger id="hamburger-container" :is-active="sidebar.opened" class="hamburger-container" @toggleClick="toggleSideBar" />

    <breadcrumb v-if="!topNav" id="breadcrumb-container" class="breadcrumb-container" />
    <top-nav v-if="topNav" id="topmenu-container" class="topmenu-container" />

    <div class="right-menu">
      <template v-if="device!=='mobile'">
        <el-tooltip content="菜单搜索" effect="dark" placement="bottom">
          <search id="header-search" class="right-menu-item" />
        </el-tooltip>

        <button type="button" class="navbar-action" aria-label="打开待办" @click="goTodo">
          <i class="el-icon-bell" />
          <span>待办</span>
        </button>

        <screenfull id="screenfull" class="right-menu-item hover-effect" />

      </template>

      <span class="role-chip" title="当前角色">当前角色 · {{ currentRole }}</span>

      <el-dropdown class="avatar-container right-menu-item hover-effect" trigger="hover">
        <div class="avatar-wrapper">
          <img :src="avatar" class="user-avatar">
          <span class="user-nickname"> {{ nickName }} </span>
        </div>
        <el-dropdown-menu slot="dropdown">
          <router-link to="/user/profile">
            <el-dropdown-item>个人中心</el-dropdown-item>
          </router-link>
          <el-dropdown-item divided @click.native="logout">
            <span>退出登录</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <div class="right-menu-item hover-effect setting" @click="setLayout" v-if="setting">
        <svg-icon icon-class="more-up" />
      </div>
    </div>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import Breadcrumb from '@/components/Breadcrumb'
import TopNav from '@/components/TopNav'
import Hamburger from '@/components/Hamburger'
import Screenfull from '@/components/Screenfull'
import Search from '@/components/HeaderSearch'

export default {
  emits: ['setLayout'],
  components: {
    Breadcrumb,
    TopNav,
    Hamburger,
    Screenfull,
    Search
  },
  computed: {
    ...mapGetters([
      'sidebar',
      'avatar',
      'device',
      'nickName',
      'roles'
    ]),
    setting: {
      get() {
        return this.$store.state.settings.showSettings
      }
    },
    topNav: {
      get() {
        return this.$store.state.settings.topNav
      }
    },
    currentRole() {
      if (this.roles.includes('admin')) return '系统管理员'
      if (this.roles.includes('cy_finance')) return '财务结算'
      if (this.roles.includes('cy_auditor')) return '审核风控'
      if (this.roles.includes('cy_support')) return '客服售后'
      if (this.roles.includes('cy_operator')) return '日常运营'
      return '当前账号'
    }
  },
  methods: {
    toggleSideBar() {
      this.$store.dispatch('app/toggleSideBar')
    },
    setLayout(event) {
      this.$emit('setLayout')
    },
    goTodo() {
      this.$router.push('/')
    },
    logout() {
      this.$confirm('确定注销并退出系统吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        this.$store.dispatch('LogOut').then(() => {
          location.href = '/index'
        })
      }).catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
.navbar {
  height: 64px;
  overflow: hidden;
  position: relative;
  background: var(--cy-color-bg-surface);
  border-bottom: 1px solid var(--cy-color-border-subtle);
  box-shadow: none;

  .hamburger-container {
    line-height: 60px;
    height: 100%;
    float: left;
    cursor: pointer;
    transition: background .3s;
    -webkit-tap-highlight-color:transparent;

    &:hover {
      background: rgba(0, 0, 0, .025)
    }
  }

  .breadcrumb-container {
    float: left;
  }

  .topmenu-container {
    position: absolute;
    left: 50px;
  }

  .errLog-container {
    display: inline-block;
    vertical-align: top;
  }

  .right-menu {
    float: right;
    height: 100%;
    line-height: 64px;
    padding-right: var(--cy-space-3);

    &:focus {
      outline: none;
    }

    .right-menu-item {
      display: inline-block;
      padding: 0 var(--cy-space-1-5);
      height: 100%;
      font-size: 18px;
      color: var(--cy-color-text-secondary);
      vertical-align: text-bottom;

      &.hover-effect {
        cursor: pointer;
        transition: background .3s;

        &:hover {
          background: var(--cy-color-bg-subtle)
        }
      }
    }

    .avatar-container {
      margin-right: 0px;
      padding-right: 0px;

      .avatar-wrapper {
        margin-top: 13px;
        position: relative;

        .user-avatar {
          cursor: pointer;
          width: 38px;
          height: 38px;
          border: 3px solid var(--cy-color-brand-soft);
          border-radius: 50%;
        }

        .user-nickname{
          position: relative;
          bottom: 13px;
          font-size: 14px;
          font-weight: bold;
        }

        .el-icon-caret-bottom {
          cursor: pointer;
          position: absolute;
          right: -20px;
          top: 25px;
          font-size: 12px;
        }
      }
    }
  }

  .navbar-action {
    display: inline-flex;
    align-items: center;
    gap: var(--cy-space-0-5);
    min-width: 44px;
    height: 44px;
    margin: var(--cy-space-1-5) var(--cy-space-1);
    padding: 0 var(--cy-space-3);
    border: 1px solid var(--cy-color-border-subtle);
    border-radius: var(--cy-radius-dialog);
    background: var(--cy-color-bg-subtle);
    color: var(--cy-color-text-secondary);
    font: inherit;
    cursor: pointer;
  }

  .navbar-action:hover { background: var(--cy-color-bg-subtle); color: var(--cy-color-text-primary); }

  .role-chip {
    display: inline-block;
    height: var(--cy-space-7);
    margin: var(--cy-space-4) var(--cy-space-1-5);
    padding: 0 var(--cy-space-3);
    border-radius: 999px;
    background: var(--cy-color-brand-soft);
    color: var(--cy-color-brand);
    font-size: 12px;
    font-weight: 700;
    line-height: 32px;
    vertical-align: top;
  }
}
</style>
