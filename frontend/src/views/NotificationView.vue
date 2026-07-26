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
  const actor = item.actorNickname || `用户 ${item.actorId}`
  const map = {
    FOLLOW: `${actor} 关注了你`,
    COMMENT: `${actor} 评论了你的视频`,
    REPLY: `${actor} 回复了你的评论`
  }
  return map[item.type]
}

function getTypeTag(item: NotificationItem) {
  const map = { FOLLOW: 'success', COMMENT: 'primary', REPLY: 'warning' } as const
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

    if (item.videoId) {
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
    <header class="header">
      <div class="header-content">
        <button class="logo" @click="router.push('/')">VideoNest</button>
        <el-button @click="router.push('/')">返回首页</el-button>
      </div>
    </header>

    <section class="container">
      <div class="title-row">
        <div>
          <h1>通知中心</h1>
          <p>未读通知 {{ unreadCount }} 条</p>
        </div>
        <el-button :loading="loading" @click="loadNotifications">刷新</el-button>
      </div>

      <el-skeleton :loading="loading" animated :rows="5">
        <template #default>
          <div v-if="notifications.length" class="notification-list">
            <article
              v-for="item in notifications"
              :key="item.id"
              class="notification-item"
              :class="{ unread: item.isRead === 0 }"
              @click="openNotification(item)"
            >
              <div class="notification-dot" />
              <div class="notification-content">
                <div class="notification-title">
                  <el-tag size="small" :type="getTypeTag(item)">
                    {{ item.type === 'FOLLOW' ? '关注' : item.type === 'COMMENT' ? '评论' : '回复' }}
                  </el-tag>
                  <strong>{{ getTypeText(item) }}</strong>
                </div>
                <p v-if="item.content">{{ item.content }}</p>
                <span>{{ formatDate(item.createTime) }}</span>
              </div>
              <el-icon v-if="item.videoId" class="arrow"><ArrowRight /></el-icon>
            </article>
          </div>
          <el-empty v-else description="暂时没有通知" />
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
