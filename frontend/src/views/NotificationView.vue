<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import {
  getNotifications,
  markNotificationRead,
  type NotificationItem
} from '../api/notification'
import SiteHeader from '../components/SiteHeader.vue'

const router = useRouter()
const notifications = ref<NotificationItem[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

const unreadCount = computed(
  () => notifications.value.filter(item => item.isRead === 0).length
)

function requireLogin() {
  if (localStorage.getItem('token')) return true
  ElMessage.warning('请先登录')
  router.replace('/login')
  return false
}

function getTypeText(item: NotificationItem) {
  if (item.type === 'LIKE') return `${item.actorNickname || `用户 ${item.actorId}`} 点赞了你的视频`
  if (item.type === 'FAVORITE') return `${item.actorNickname || `用户 ${item.actorId}`} 收藏了你的视频`
  if (item.type === 'REVIEW_TIMEOUT') return '你的视频等待审核已超时'
  if (item.type === 'VIDEO_REJECTED') return item.videoTitle
    ? `你的视频《${item.videoTitle}》审核未通过`
    : '你的视频审核未通过'
  const actor = item.actorNickname || `用户 ${item.actorId}`
  const map = {
    FOLLOW: `${actor} 关注了你`,
    COMMENT: `${actor} 评论了你的视频`,
    REPLY: `${actor} 回复了你的评论`
  }
  return map[item.type]
}

function getTypeTag(item: NotificationItem) {
  if (item.type === 'LIKE') return 'danger'
  if (item.type === 'FAVORITE') return 'info'
  if (item.type === 'REVIEW_TIMEOUT') return 'warning'
  if (item.type === 'VIDEO_REJECTED') return 'danger'
  const map = { FOLLOW: 'success', COMMENT: 'primary', REPLY: 'warning' } as const
  return map[item.type]
}

function getTypeLabel(item: NotificationItem) {
  const map: Record<NotificationItem['type'], string> = {
    FOLLOW: '关注',
    COMMENT: '评论',
    REPLY: '回复',
    LIKE: '点赞',
    FAVORITE: '收藏',
    REVIEW_TIMEOUT: '审核超时',
    VIDEO_REJECTED: '审核驳回'
  }
  return map[item.type]
}

function getTypeIcon(item: NotificationItem) {
  const map: Record<NotificationItem['type'], string> = {
    FOLLOW: '+',
    COMMENT: '评',
    REPLY: '回',
    LIKE: '♥',
    FAVORITE: '★',
    REVIEW_TIMEOUT: '!',
    VIDEO_REJECTED: '×'
  }
  return map[item.type]
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN')
}

async function loadNotifications() {
  try {
    loading.value = true
    const result = await getNotifications({ page: page.value, size: size.value })
    notifications.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取通知失败')
  } finally {
    loading.value = false
  }
}

async function openNotification(item: NotificationItem) {
  try {
    if (item.isRead === 0) {
      await markNotificationRead(item.id)
      item.isRead = 1
    }

    if (item.type === 'VIDEO_REJECTED') {
      router.push('/profile')
    } else if (item.videoId) {
      router.push(`/video/${item.videoId}`)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '更新通知状态失败')
  }
}

function changePage(value: number) {
  page.value = value
  loadNotifications()
}

onMounted(() => {
  if (requireLogin()) loadNotifications()
})
</script>

<template>
  <main class="notification-page">
    <SiteHeader max-width="1160px">
      <template #nav>
        <span class="message-label">消息中心</span>
      </template>
      <template #actions>
        <el-button text @click="router.push('/profile')">个人中心</el-button>
        <el-button @click="router.push('/')">返回主站</el-button>
      </template>
    </SiteHeader>

    <section class="container message-layout">
      <aside class="message-sidebar">
        <div class="message-sidebar__heading">消息中心</div>
        <button class="active">
          <span>●</span>
          全部通知
          <small v-if="unreadCount">{{ unreadCount }}</small>
        </button>
        <div class="message-guide">
          <strong>通知说明</strong>
          <p>点赞、收藏、关注、评论和审核结果都会汇总到这里。</p>
        </div>
      </aside>

      <section class="message-main">
        <div class="title-row">
          <div>
            <span class="title-kicker">NOTIFICATIONS</span>
            <h1>全部通知</h1>
            <p>共 {{ total }} 条消息，本页未读 {{ unreadCount }} 条</p>
          </div>
          <el-button :loading="loading" @click="loadNotifications">刷新消息</el-button>
        </div>

        <el-skeleton :loading="loading" animated :rows="5">
          <template #default>
            <div v-if="notifications.length" class="notification-list">
              <article
                v-for="item in notifications"
                :key="item.id"
                class="notification-item"
                :class="{ unread: item.isRead === 0 }"
                tabindex="0"
                @click="openNotification(item)"
                @keyup.enter="openNotification(item)"
              >
                <div class="type-icon" :class="`type-${item.type.toLowerCase()}`">
                  {{ getTypeIcon(item) }}
                </div>
                <div class="notification-content">
                  <div class="notification-title">
                    <strong>{{ getTypeText(item) }}</strong>
                    <el-tag size="small" effect="plain" :type="getTypeTag(item)">
                      {{ getTypeLabel(item) }}
                    </el-tag>
                    <span v-if="item.isRead === 0" class="unread-badge">未读</span>
                  </div>
                  <p v-if="item.content">
                    {{ item.type === 'VIDEO_REJECTED' ? `驳回原因：${item.content}` : item.content }}
                  </p>
                  <time>{{ formatDate(item.createTime) }}</time>
                </div>
                <el-icon v-if="item.videoId" class="arrow"><ArrowRight /></el-icon>
              </article>
            </div>
            <el-empty v-else description="暂时没有新消息" />
          </template>
        </el-skeleton>

        <div v-if="total > size" class="pagination">
          <el-pagination
            v-model:current-page="page"
            :page-size="size"
            :total="total"
            layout="prev, pager, next"
            background
            @current-change="changePage"
          />
        </div>
      </section>
    </section>
  </main>
</template>

<style scoped>
.notification-page { min-height: 100vh; background: #f6f7f8; color: #18191c; }
.header { height: 64px; background: #fff; border-bottom: 1px solid #e7e7e7; }
.header-content, .container { width: min(900px, calc(100% - 48px)); margin: 0 auto; }
.header-content { height: 100%; display: flex; align-items: center; justify-content: space-between; }
.logo { border: 0; background: transparent; color: #1677ff; font-size: 22px; font-weight: 700; cursor: pointer; }
.container { padding: 32px 0 48px; }
.title-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.title-row h1 { margin: 0 0 7px; font-size: 25px; }
.title-row p { margin: 0; color: #9499a0; font-size: 14px; }
.notification-list { overflow: hidden; border-radius: 12px; background: #fff; }
.notification-item { position: relative; display: flex; align-items: center; gap: 14px; padding: 20px 22px; border-bottom: 1px solid #f0f0f0; cursor: pointer; }
.notification-item:last-child { border-bottom: 0; }
.notification-item:hover { background: #fafcff; }
.notification-item.unread { background: #f3f8ff; }
.notification-dot { width: 8px; height: 8px; flex: 0 0 auto; border-radius: 50%; background: transparent; }
.unread .notification-dot { background: #1677ff; }
.notification-content { min-width: 0; flex: 1; }
.notification-title { display: flex; align-items: center; gap: 9px; }
.notification-title strong { font-size: 15px; }
.notification-content p { overflow: hidden; margin: 8px 0; color: #61666d; text-overflow: ellipsis; white-space: nowrap; }
.notification-content span { color: #9499a0; font-size: 13px; }
.arrow { color: #9499a0; }
.pagination { display: flex; justify-content: center; margin-top: 26px; }
@media (max-width: 650px) { .header-content, .container { width: min(100% - 28px, 900px); } .notification-item { padding: 16px; } }
</style>

<style scoped>
.notification-page {
  min-height: 100vh;
  background: var(--vn-page);
  color: var(--vn-text);
}

.message-label {
  padding-left: 16px;
  border-left: 1px solid var(--vn-border);
  color: var(--vn-text-secondary);
  font-size: 14px;
  font-weight: 600;
}

.container {
  width: min(1160px, calc(100% - 48px));
  margin: 0 auto;
}

.message-layout {
  padding: 30px 0 64px;
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  align-items: start;
  gap: 20px;
}

.message-sidebar {
  position: sticky;
  top: 92px;
  padding: 16px;
  border: 1px solid var(--vn-border-light);
  border-radius: 12px;
  background: #fff;
}

.message-sidebar__heading {
  padding: 5px 10px 12px;
  color: var(--vn-text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1px;
}

.message-sidebar > button {
  width: 100%;
  padding: 11px 10px;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 0;
  border-radius: 8px;
  background: var(--vn-primary-soft);
  color: var(--vn-primary-dark);
  font-size: 13px;
  font-weight: 600;
}

.message-sidebar > button > span {
  font-size: 8px;
}

.message-sidebar > button small {
  min-width: 20px;
  margin-left: auto;
  padding: 1px 5px;
  border-radius: 999px;
  background: var(--vn-accent);
  color: #fff;
  text-align: center;
}

.message-guide {
  margin-top: 16px;
  padding: 13px;
  border-radius: 9px;
  background: #f6f7f8;
}

.message-guide strong {
  font-size: 11px;
}

.message-guide p {
  margin: 5px 0 0;
  color: var(--vn-text-muted);
  font-size: 10px;
  line-height: 1.7;
}

.message-main {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--vn-border-light);
  border-radius: 13px;
  background: #fff;
}

.title-row {
  margin: 0;
  padding: 24px 26px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--vn-border-light);
}

.title-kicker {
  color: var(--vn-primary);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 1.4px;
}

.title-row h1 {
  margin: 3px 0 4px;
  font-size: 22px;
}

.title-row p {
  margin: 0;
  color: var(--vn-text-muted);
  font-size: 12px;
}

.notification-list {
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: #fff;
  box-shadow: none;
}

.notification-item {
  position: relative;
  padding: 20px 24px;
  display: flex;
  align-items: center;
  gap: 14px;
  border-bottom: 1px solid var(--vn-border-light);
  outline: none;
  background: #fff;
  cursor: pointer;
  transition: background .2s;
}

.notification-item:last-child {
  border-bottom: 0;
}

.notification-item:hover,
.notification-item:focus,
.notification-item.unread {
  background: #fafbfc;
}

.notification-item.unread::before {
  content: "";
  position: absolute;
  top: 18px;
  bottom: 18px;
  left: 0;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--vn-primary);
}

.type-icon {
  width: 42px;
  height: 42px;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--vn-primary-soft);
  color: var(--vn-primary-dark);
  font-size: 15px;
  font-weight: 800;
}

.type-like,
.type-video_rejected {
  background: #fff0f4;
  color: var(--vn-accent);
}

.type-favorite {
  background: #fff8e8;
  color: #df9b16;
}

.type-review_timeout,
.type-reply {
  background: #fff5e8;
  color: #e58b20;
}

.notification-content {
  min-width: 0;
  flex: 1;
}

.notification-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.notification-title strong {
  font-size: 14px;
}

.unread-badge {
  color: var(--vn-primary-dark) !important;
  font-size: 10px !important;
}

.notification-content p {
  display: -webkit-box;
  overflow: hidden;
  margin: 7px 0;
  color: var(--vn-text-secondary);
  font-size: 13px;
  line-height: 1.65;
  white-space: normal;
  text-overflow: initial;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.notification-content time {
  color: var(--vn-text-muted);
  font-size: 11px;
}

.arrow {
  color: var(--vn-text-muted);
}

.pagination {
  margin: 0;
  padding: 22px;
  display: flex;
  justify-content: center;
  border-top: 1px solid var(--vn-border-light);
}

@media (max-width: 760px) {
  .container {
    width: min(100% - 24px, 1160px);
  }

  .message-layout {
    grid-template-columns: 1fr;
    padding-top: 18px;
  }

  .message-sidebar {
    display: none;
  }

  .title-row,
  .notification-item {
    padding-right: 16px;
    padding-left: 16px;
  }

  .type-icon {
    width: 38px;
    height: 38px;
  }
}
</style>
