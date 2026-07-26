<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  getPendingVideos,
  reviewVideo,
  type AdminVideoReview
} from '../api/admin'

const router = useRouter()

const videos = ref<AdminVideoReview[]>([])
const loading = ref(false)
const reviewingId = ref<number | null>(null)

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

function getCurrentUser() {
  const userInfo = localStorage.getItem('userInfo')

  if (!userInfo) {
    return null
  }

  try {
    return JSON.parse(userInfo) as {
      nickname: string
      role: 'USER' | 'ADMIN'
    }
  } catch {
    return null
  }
}

function checkAdmin() {
  const user = getCurrentUser()

  if (!localStorage.getItem('token')) {
    ElMessage.warning('请先登录')
    router.replace('/login')
    return false
  }

  if (user?.role !== 'ADMIN') {
    ElMessage.error('没有管理员权限')
    router.replace('/')
    return false
  }

  return true
}

async function loadVideos() {
  try {
    loading.value = true

    const result = await getPendingVideos({
      page: currentPage.value,
      size: pageSize.value
    })

    videos.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '获取待审核视频失败'
    )
  } finally {
    loading.value = false
  }
}

async function handleReview(
  video: AdminVideoReview,
  action: 'APPROVE' | 'REJECT'
) {
  const isApprove = action === 'APPROVE'
  const actionText = isApprove ? '通过' : '驳回'

  try {
    let rejectReason: string | undefined

    if (isApprove) {
      await ElMessageBox.confirm(
        `确定要通过投稿「${video.title}」吗？通过后视频将公开显示在首页。`,
        '确认审核',
        {
          confirmButtonText: '确认通过',
          cancelButtonText: '取消',
          type: 'success'
        }
      )
    } else {
      const result = await ElMessageBox.prompt(
        `请填写驳回投稿「${video.title}」的原因。`,
        '驳回投稿',
        {
          confirmButtonText: '确认驳回',
          cancelButtonText: '取消',
          inputPlaceholder: '例如：封面不清晰，请更换清晰封面后重新投稿',
          inputType: 'textarea',
          inputValidator: (value) => {
            if (!value || !value.trim()) {
              return '请填写驳回原因'
            }

            if (value.trim().length > 500) {
              return '驳回原因不能超过 500 个字符'
            }

            return true
          }
        }
      )

      rejectReason = result.value.trim()
    }

    reviewingId.value = video.id

    await reviewVideo(video.id, action, rejectReason)

    ElMessage.success(`已${actionText}投稿「${video.title}」`)

    if (videos.value.length === 1 && currentPage.value > 1) {
      currentPage.value--
    }

    await loadVideos()
  } catch (error) {
    if (error === 'cancel' || error === 'close') {
      return
    }

    ElMessage.error(
      error instanceof Error ? error.message : '审核操作失败'
    )
  } finally {
    reviewingId.value = null
  }
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadVideos()
}

function formatDuration(seconds: number) {
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60

  return `${String(minutes).padStart(2, '0')}:${String(
    remainSeconds
  ).padStart(2, '0')}`
}

function formatDate(value: string) {
  if (!value) {
    return '-'
  }

  return new Date(value).toLocaleString('zh-CN')
}

onMounted(() => {
  if (checkAdmin()) {
    loadVideos()
  }
})
</script>

<template>
  <main class="admin-page">
    <header class="header">
      <div class="header-content">
        <button class="logo" @click="router.push('/')">
          <span>▶</span>
          VideoNest
        </button>

        <div class="header-actions">
          <span>管理员审核</span>
          <el-button @click="router.push('/admin/comments')">
            评论管理
          </el-button>
          <el-button @click="router.push('/')">
            返回首页
          </el-button>
        </div>
      </div>
    </header>

    <section class="container">
      <div class="page-title">
        <div>
          <h1>视频投稿审核</h1>
          <p>共 {{ total }} 个待审核投稿</p>
        </div>

        <el-button :loading="loading" @click="loadVideos">
          刷新列表
        </el-button>
      </div>

      <el-skeleton :loading="loading" animated :count="4">
        <template #default>
          <div v-if="videos.length > 0" class="video-list">
            <article
              v-for="video in videos"
              :key="video.id"
              class="video-card"
            >
              <div class="cover-box">
                <img :src="video.coverUrl" :alt="video.title" />
                <span class="duration">
                  {{ formatDuration(video.duration) }}
                </span>
              </div>

              <div class="video-content">
                <div class="video-title-row">
                  <h2>{{ video.title }}</h2>
                  <el-tag type="warning">待审核</el-tag>
                </div>

                <p class="description">
                  {{ video.description || '作者暂未填写视频简介。' }}
                </p>

                <div class="meta-list">
                  <span>作者：{{ video.authorNickname }}</span>
                  <span>分区：{{ video.categoryName }}</span>
                  <span>投稿时间：{{ formatDate(video.createTime) }}</span>
                </div>

                <details class="video-preview">
                  <summary>预览视频</summary>
                  <video
                    :src="video.videoUrl"
                    controls
                    preload="metadata"
                  />
                </details>

                <div class="actions">
                  <el-button
                    type="danger"
                    plain
                    :loading="reviewingId === video.id"
                    @click="handleReview(video, 'REJECT')"
                  >
                    驳回
                  </el-button>

                  <el-button
                    type="success"
                    :loading="reviewingId === video.id"
                    @click="handleReview(video, 'APPROVE')"
                  >
                    通过审核
                  </el-button>
                </div>
              </div>
            </article>
          </div>

          <el-empty
            v-else
            description="目前没有待审核的投稿"
          />
        </template>
      </el-skeleton>

      <div v-if="total > pageSize" class="pagination">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="handlePageChange"
        />
      </div>
    </section>
  </main>
</template>

<style scoped>
.admin-page {
  min-height: 100vh;
  background: #f6f7f8;
  color: #18191c;
}

.header {
  height: 64px;
  background: #fff;
  border-bottom: 1px solid #e7e7e7;
}

.header-content,
.container {
  width: min(1100px, calc(100% - 48px));
  margin: 0 auto;
}

.header-content {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  background: transparent;
  color: #1677ff;
  font-size: 22px;
  font-weight: 700;
  cursor: pointer;
}

.logo span {
  display: inline-grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 13px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  color: #61666d;
  font-size: 14px;
}

.container {
  padding: 32px 0 48px;
}

.page-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 22px;
}

.page-title h1 {
  margin: 0 0 8px;
  font-size: 25px;
}

.page-title p {
  margin: 0;
  color: #9499a0;
  font-size: 14px;
}

.video-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.video-card {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 20px;
  padding: 18px;
  border-radius: 10px;
  background: #fff;
}

.cover-box {
  position: relative;
  overflow: hidden;
  align-self: start;
  aspect-ratio: 16 / 9;
  border-radius: 8px;
  background: #e5e7eb;
}

.cover-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.duration {
  position: absolute;
  right: 8px;
  bottom: 8px;
  padding: 3px 6px;
  border-radius: 4px;
  background: rgb(0 0 0 / 65%);
  color: #fff;
  font-size: 12px;
}

.video-content {
  min-width: 0;
}

.video-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.video-title-row h2 {
  overflow: hidden;
  margin: 0;
  font-size: 18px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.description {
  display: -webkit-box;
  overflow: hidden;
  margin: 12px 0;
  color: #61666d;
  font-size: 14px;
  line-height: 22px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.meta-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  color: #9499a0;
  font-size: 13px;
}

.video-preview {
  margin-top: 15px;
  color: #1677ff;
  font-size: 14px;
  cursor: pointer;
}

.video-preview video {
  display: block;
  width: min(100%, 520px);
  margin-top: 10px;
  border-radius: 8px;
  background: #000;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 30px;
}

@media (max-width: 720px) {
  .header-content,
  .container {
    width: min(100% - 28px, 1100px);
  }

  .header-actions > span {
    display: none;
  }

  .video-card {
    grid-template-columns: 1fr;
  }

  .cover-box {
    width: 100%;
  }
}
</style>
