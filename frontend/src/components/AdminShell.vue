<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

withDefaults(
  defineProps<{
    title: string
    description: string
    eyebrow?: string
  }>(),
  {
    eyebrow: '管理中心'
  }
)

const route = useRoute()
const router = useRouter()

const navItems = [
  {
    label: '投稿审核',
    description: '视频内容与发布审核',
    path: '/admin/review',
    icon: '审'
  },
  {
    label: '评论管理',
    description: '社区互动与评论治理',
    path: '/admin/comments',
    icon: '评'
  },
  {
    label: '视频回收站',
    description: '已删除视频资源清理',
    path: '/admin/recycle-bin',
    icon: '删'
  },
  {
    label: '死信处理',
    description: '异常消息诊断与重试',
    path: '/admin/dead-letters',
    icon: '信'
  }
]

const currentUser = computed(() => {
  try {
    return JSON.parse(localStorage.getItem('userInfo') || '{}') as {
      nickname?: string
      username?: string
    }
  } catch {
    return {}
  }
})

const displayName = computed(
  () => currentUser.value.nickname || currentUser.value.username || '管理员'
)
const avatarText = computed(() => displayName.value.trim().slice(0, 1).toUpperCase())
</script>

<template>
  <div class="admin-shell">
    <header class="admin-topbar">
      <button class="brand" type="button" @click="router.push('/')">
        <span class="brand-mark" aria-hidden="true">▶</span>
        <span class="brand-name">VideoNest</span>
        <span class="brand-divider"></span>
        <span class="brand-console">创作管理</span>
      </button>

      <div class="topbar-actions">
        <button class="site-link" type="button" @click="router.push('/')">
          <span class="site-link-icon" aria-hidden="true">⌂</span>
          <span>返回主站</span>
        </button>
        <span class="topbar-divider"></span>
        <div class="admin-profile">
          <span class="admin-avatar">{{ avatarText }}</span>
          <span class="admin-profile-copy">
            <strong>{{ displayName }}</strong>
            <small>管理员</small>
          </span>
        </div>
      </div>
    </header>

    <aside class="admin-sidebar">
      <div class="sidebar-intro">
        <span class="sidebar-kicker">CONTROL CENTER</span>
        <strong>内容治理</strong>
        <p>维护安全、友好的社区环境</p>
      </div>

      <nav class="admin-nav" aria-label="管理中心导航">
        <button
          v-for="item in navItems"
          :key="item.path"
          type="button"
          class="nav-item"
          :class="{ active: route.path === item.path }"
          @click="router.push(item.path)"
        >
          <span class="nav-icon" aria-hidden="true">{{ item.icon }}</span>
          <span class="nav-copy">
            <strong>{{ item.label }}</strong>
            <small>{{ item.description }}</small>
          </span>
          <span class="nav-indicator"></span>
        </button>
      </nav>

      <div class="sidebar-status">
        <span class="status-dot"></span>
        <span>
          <strong>管理服务运行中</strong>
          <small>VideoNest Console</small>
        </span>
      </div>
    </aside>

    <main class="admin-main">
      <div class="admin-content">
        <section class="admin-heading">
          <div class="heading-copy">
            <div class="breadcrumb">
              <span>管理中心</span>
              <span aria-hidden="true">›</span>
              <strong>{{ eyebrow }}</strong>
            </div>
            <h1>{{ title }}</h1>
            <p>{{ description }}</p>
          </div>
          <div class="heading-actions">
            <slot name="actions"></slot>
          </div>
        </section>

        <slot name="overview"></slot>
        <slot></slot>
      </div>
    </main>
  </div>
</template>

<style scoped>
.admin-shell {
  --admin-accent: #00aeec;
  --admin-accent-soft: #e8f8ff;
  --admin-pink: #fb7299;
  --admin-ink: #18191c;
  --admin-muted: #61666d;
  --admin-faint: #9499a0;
  min-height: 100vh;
  background:
    radial-gradient(circle at 88% 8%, rgb(0 174 236 / 7%), transparent 25rem),
    #f5f7fa;
  color: var(--admin-ink);
}

.admin-topbar {
  position: fixed;
  z-index: 30;
  top: 0;
  right: 0;
  left: 0;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  border-bottom: 1px solid #eef0f3;
  background: rgb(255 255 255 / 94%);
  box-shadow: 0 2px 14px rgb(0 0 0 / 3%);
  backdrop-filter: blur(14px);
}

button {
  font: inherit;
}

.brand {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.brand-mark {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 11px;
  background: linear-gradient(135deg, #00aeec, #6bd7ff);
  box-shadow: 0 7px 18px rgb(0 174 236 / 24%);
  color: #fff;
  font-size: 13px;
}

.brand-name {
  margin-left: 11px;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: -.3px;
}

.brand-divider {
  width: 1px;
  height: 20px;
  margin: 0 13px;
  background: #e3e5e7;
}

.brand-console {
  color: var(--admin-muted);
  font-size: 14px;
  font-weight: 600;
}

.topbar-actions,
.site-link,
.admin-profile {
  display: flex;
  align-items: center;
}

.topbar-actions {
  gap: 16px;
}

.site-link {
  gap: 7px;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--admin-muted);
  cursor: pointer;
  transition: color .2s ease, background .2s ease;
}

.site-link:hover {
  background: var(--admin-accent-soft);
  color: var(--admin-accent);
}

.site-link-icon {
  font-size: 19px;
  line-height: 1;
}

.topbar-divider {
  width: 1px;
  height: 24px;
  background: #e3e5e7;
}

.admin-profile {
  gap: 9px;
}

.admin-avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 2px solid #fff;
  border-radius: 50%;
  background: linear-gradient(145deg, #fb7299, #ff9bb7);
  box-shadow: 0 3px 9px rgb(251 114 153 / 22%);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
}

.admin-profile-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.25;
}

.admin-profile-copy strong {
  overflow: hidden;
  max-width: 112px;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-profile-copy small {
  color: var(--admin-faint);
  font-size: 11px;
}

.admin-sidebar {
  position: fixed;
  z-index: 20;
  top: 64px;
  bottom: 0;
  left: 0;
  width: 232px;
  display: flex;
  flex-direction: column;
  padding: 24px 14px 18px;
  border-right: 1px solid #ebeef2;
  background: #fff;
}

.sidebar-intro {
  padding: 0 12px 21px;
}

.sidebar-kicker {
  display: block;
  margin-bottom: 7px;
  color: var(--admin-accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 1.45px;
}

.sidebar-intro strong {
  display: block;
  font-size: 16px;
}

.sidebar-intro p {
  margin: 5px 0 0;
  color: var(--admin-faint);
  font-size: 12px;
  line-height: 1.5;
}

.admin-nav {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.nav-item {
  position: relative;
  display: flex;
  width: 100%;
  align-items: center;
  gap: 11px;
  padding: 11px 12px;
  overflow: hidden;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--admin-muted);
  text-align: left;
  cursor: pointer;
  transition: color .2s ease, background .2s ease, transform .2s ease;
}

.nav-item:hover {
  background: #f6f8fa;
  color: var(--admin-ink);
}

.nav-item.active {
  background: linear-gradient(90deg, #e9f9ff, #f5fcff);
  color: var(--admin-accent);
}

.nav-icon {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  place-items: center;
  border-radius: 9px;
  background: #f2f4f7;
  font-size: 12px;
  font-weight: 700;
  transition: background .2s ease;
}

.nav-item.active .nav-icon {
  background: #d9f4ff;
}

.nav-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.nav-copy strong {
  font-size: 14px;
  font-weight: 600;
}

.nav-copy small {
  overflow: hidden;
  color: var(--admin-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-indicator {
  position: absolute;
  top: 11px;
  right: 0;
  bottom: 11px;
  width: 3px;
  border-radius: 3px 0 0 3px;
  background: var(--admin-accent);
  opacity: 0;
  transform: translateX(3px);
  transition: opacity .2s ease, transform .2s ease;
}

.nav-item.active .nav-indicator {
  opacity: 1;
  transform: translateX(0);
}

.sidebar-status {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: auto;
  padding: 13px 12px;
  border: 1px solid #edf0f3;
  border-radius: 10px;
  background: #fafbfc;
}

.status-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 8px;
  border-radius: 50%;
  background: #34c759;
  box-shadow: 0 0 0 4px rgb(52 199 89 / 12%);
}

.sidebar-status > span:last-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.sidebar-status strong {
  font-size: 11px;
  font-weight: 600;
}

.sidebar-status small {
  color: var(--admin-faint);
  font-size: 10px;
}

.admin-main {
  min-height: 100vh;
  margin-left: 232px;
  padding-top: 64px;
}

.admin-content {
  width: min(1320px, calc(100% - 56px));
  margin: 0 auto;
  padding: 31px 0 56px;
}

.admin-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  margin-bottom: 24px;
}

.heading-copy {
  min-width: 0;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 10px;
  color: var(--admin-faint);
  font-size: 12px;
}

.breadcrumb > span[aria-hidden] {
  color: #b7bbc1;
  font-size: 16px;
}

.breadcrumb strong {
  color: var(--admin-accent);
  font-weight: 600;
}

.admin-heading h1 {
  margin: 0;
  font-size: clamp(25px, 2.2vw, 32px);
  font-weight: 750;
  letter-spacing: -.7px;
  line-height: 1.2;
}

.admin-heading p {
  max-width: 720px;
  margin: 9px 0 0;
  color: var(--admin-muted);
  font-size: 14px;
  line-height: 1.7;
}

.heading-actions {
  flex: 0 0 auto;
}

@media (max-width: 960px) {
  .admin-sidebar {
    top: 64px;
    right: 0;
    bottom: auto;
    width: auto;
    height: 68px;
    padding: 8px 18px;
    border-right: 0;
    border-bottom: 1px solid #ebeef2;
    box-shadow: 0 4px 14px rgb(0 0 0 / 3%);
  }

  .sidebar-intro,
  .sidebar-status {
    display: none;
  }

  .admin-nav {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 7px;
  }

  .nav-item {
    justify-content: center;
    padding: 8px 12px;
  }

  .nav-icon {
    width: 30px;
    height: 30px;
    flex-basis: 30px;
  }

  .nav-copy {
    flex: 0 1 auto;
  }

  .nav-copy small,
  .nav-indicator {
    display: none;
  }

  .admin-main {
    margin-left: 0;
    padding-top: 132px;
  }
}

@media (max-width: 640px) {
  .admin-topbar {
    height: 58px;
    padding: 0 14px;
  }

  .brand-mark {
    width: 32px;
    height: 32px;
    border-radius: 9px;
  }

  .brand-name {
    margin-left: 8px;
    font-size: 18px;
  }

  .brand-divider,
  .brand-console,
  .topbar-divider,
  .admin-profile-copy,
  .site-link span {
    display: none;
  }

  .topbar-actions {
    gap: 8px;
  }

  .site-link {
    padding: 7px;
  }

  .admin-avatar {
    width: 31px;
    height: 31px;
  }

  .admin-sidebar {
    top: 58px;
    height: 60px;
    padding: 6px 8px;
    overflow-x: auto;
  }

  .admin-nav {
    min-width: 440px;
    gap: 4px;
  }

  .nav-item {
    gap: 6px;
    padding: 7px 8px;
  }

  .nav-icon {
    width: 27px;
    height: 27px;
    flex-basis: 27px;
  }

  .nav-copy strong {
    font-size: 12px;
  }

  .admin-main {
    padding-top: 118px;
  }

  .admin-content {
    width: calc(100% - 28px);
    padding: 23px 0 38px;
  }

  .admin-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
    margin-bottom: 20px;
  }

  .admin-heading h1 {
    font-size: 25px;
  }

  .heading-actions {
    width: 100%;
  }
}
</style>
