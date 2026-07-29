<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import AdminShell from '../components/AdminShell.vue'
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
  <AdminShell
    title="视频投稿审核"
    description="集中检查待发布视频的内容、封面与基础信息，帮助优质投稿更快与观众见面。"
    eyebrow="投稿审核"
  >
    <template #actions>
      <div class="heading-tools">
        <span class="queue-state">
          <i></i>
          {{ total }} 个待处理
        </span>
        <el-button :loading="loading" @click="loadVideos">
          刷新列表
        </el-button>
      </div>
    </template>

    <template #overview>
      <section class="overview-grid">
        <article class="stat-card primary">
          <span class="stat-icon">审</span>
          <div>
            <small>待审核投稿</small>
            <strong>{{ total }}</strong>
            <p>等待管理员处理的视频</p>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon warning">时</span>
          <div>
            <small>本页超时提醒</small>
            <strong>{{ videos.filter((video) => video.reviewTimeoutNotified === 1).length }}</strong>
            <p>建议优先完成审核</p>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon neutral">页</span>
          <div>
            <small>当前页</small>
            <strong>{{ currentPage }}</strong>
            <p>每页展示 {{ pageSize }} 条</p>
          </div>
        </article>
      </section>
    </template>

    <section class="content-panel">
      <header class="panel-header">
        <div>
          <h2>待审投稿队列</h2>
          <p>按投稿时间浏览视频，预览内容后选择通过或驳回。</p>
        </div>
        <span class="panel-count">共 {{ total }} 条</span>
      </header>

      <el-skeleton :loading="loading" animated :count="4">
        <template #default>
          <div v-if="videos.length > 0" class="video-list">
            <article
              v-for="video in videos"
              :key="video.id"
              class="video-card"
            >
              <div class="cover-column">
                <div class="cover-box">
                  <img :src="video.coverUrl" :alt="video.title" />
                  <span class="duration">
                    {{ formatDuration(video.duration) }}
                  </span>
                  <span class="review-badge">待审核</span>
                </div>
                <span class="submission-id">稿件 ID · {{ video.id }}</span>
              </div>

              <div class="video-content">
                <div class="video-title-row">
                  <h3>{{ video.title }}</h3>
                  <el-tag type="warning" effect="light">待审核</el-tag>
                  <el-tag
                    v-if="video.reviewTimeoutNotified === 1"
                    type="danger"
                    effect="dark"
                  >
                    审核已超时
                  </el-tag>
                </div>

                <p class="description">
                  {{ video.description || '作者暂未填写视频简介。' }}
                </p>

                <div class="meta-list">
                  <span>
                    <i class="meta-avatar">{{ video.authorNickname.slice(0, 1) }}</i>
                    作者：{{ video.authorNickname }}
                  </span>
                  <span>分区：{{ video.categoryName }}</span>
                  <span>投稿：{{ formatDate(video.createTime) }}</span>
                  <span v-if="video.reviewDeadline" class="deadline">
                    审核截止：{{ formatDate(video.reviewDeadline) }}
                  </span>
                </div>

                <details class="video-preview">
                  <summary>
                    <span class="preview-play">▶</span>
                    预览视频
                    <span class="preview-hint">展开播放器检查投稿内容</span>
                  </summary>
                  <video
                    :src="video.videoUrl"
                    controls
                    preload="metadata"
                  />
                </details>

                <div class="actions">
                  <span class="action-hint">审核结果将同步通知投稿作者</span>
                  <div class="action-buttons">
                    <el-button
                      type="danger"
                      plain
                      :loading="reviewingId === video.id"
                      @click="handleReview(video, 'REJECT')"
                    >
                      驳回并填写原因
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
              </div>
            </article>
          </div>

          <div v-else class="empty-wrap">
            <el-empty description="目前没有待审核的投稿">
              <el-button :loading="loading" @click="loadVideos">重新检查</el-button>
            </el-empty>
          </div>
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
  </AdminShell>
</template>

<style scoped>
.heading-tools,
.overview-grid,
.stat-card,
.panel-header,
.video-title-row,
.meta-list,
.actions,
.action-buttons {
  display: flex;
  align-items: center;
}

.heading-tools {
  gap: 10px;
}

.queue-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 11px;
  border: 1px solid #d8f2fc;
  border-radius: 8px;
  background: #f2fbff;
  color: #168fc1;
  font-size: 13px;
  font-weight: 600;
}

.queue-state i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #00aeec;
  box-shadow: 0 0 0 4px rgb(0 174 236 / 12%);
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  min-width: 0;
  gap: 15px;
  padding: 18px 20px;
  border: 1px solid #ebeef2;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 16px rgb(0 0 0 / 2%);
}

.stat-card.primary {
  border-color: #d8f2fc;
  background: linear-gradient(125deg, #fff 46%, #effaff);
}

.stat-icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 12px;
  background: #dff6ff;
  color: #00aeec;
}

.stat-icon.warning {
  background: #fff3df;
  color: #f5a623;
}

.stat-icon.neutral {
  background: #f0efff;
  color: #7367dc;
}

.stat-icon svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.7;
}

.stat-card.primary .stat-icon svg {
  fill: currentColor;
  stroke: none;
}

.stat-card > div {
  display: grid;
  min-width: 0;
  grid-template-columns: auto 1fr;
  align-items: end;
  column-gap: 9px;
}

.stat-card small {
  grid-column: 1 / -1;
  color: #61666d;
  font-size: 12px;
}

.stat-card strong {
  margin-top: 3px;
  font-size: 25px;
  line-height: 1;
}

.stat-card p {
  overflow: hidden;
  margin: 0;
  color: #9499a0;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-panel {
  overflow: hidden;
  border: 1px solid #ebeef2;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 6px 24px rgb(0 0 0 / 3%);
}

.panel-header {
  justify-content: space-between;
  gap: 20px;
  padding: 20px 22px;
  border-bottom: 1px solid #eef0f3;
}

.panel-header h2 {
  margin: 0;
  font-size: 17px;
}

.panel-header p {
  margin: 5px 0 0;
  color: #9499a0;
  font-size: 12px;
}

.panel-count {
  flex: 0 0 auto;
  padding: 5px 9px;
  border-radius: 6px;
  background: #f1f3f5;
  color: #61666d;
  font-size: 12px;
}

.video-list {
  display: flex;
  flex-direction: column;
}

.video-card {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 22px;
  padding: 22px;
  border-bottom: 1px solid #eef0f3;
  transition: background .2s ease;
}

.video-card:last-child {
  border-bottom: 0;
}

.video-card:hover {
  background: #fcfdfe;
}

.cover-column {
  min-width: 0;
}

.cover-box {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  border-radius: 9px;
  background: #e5e7eb;
}

.cover-box::after {
  position: absolute;
  inset: auto 0 0;
  height: 35%;
  background: linear-gradient(transparent, rgb(0 0 0 / 26%));
  content: '';
  pointer-events: none;
}

.cover-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform .3s ease;
}

.video-card:hover .cover-box img {
  transform: scale(1.025);
}

.duration,
.review-badge {
  position: absolute;
  z-index: 1;
  color: #fff;
  font-size: 11px;
}

.duration {
  right: 7px;
  bottom: 7px;
  padding: 3px 6px;
  border-radius: 4px;
  background: rgb(0 0 0 / 65%);
}

.review-badge {
  top: 8px;
  left: 8px;
  padding: 4px 7px;
  border-radius: 5px;
  background: rgb(0 174 236 / 92%);
  font-weight: 600;
}

.submission-id {
  display: block;
  margin-top: 8px;
  color: #b0b3b8;
  font-size: 11px;
}

.video-content {
  min-width: 0;
}

.video-title-row {
  gap: 8px;
}

.video-title-row h3 {
  overflow: hidden;
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.description {
  display: -webkit-box;
  overflow: hidden;
  margin: 10px 0 12px;
  color: #61666d;
  font-size: 13px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.meta-list {
  flex-wrap: wrap;
  gap: 7px 18px;
  color: #9499a0;
  font-size: 12px;
}

.meta-list span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.meta-avatar {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border-radius: 50%;
  background: #e8f8ff;
  color: #00aeec;
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
}

.meta-list .deadline {
  color: #e58b18;
}

.video-preview {
  margin-top: 14px;
  padding: 0;
  border-radius: 8px;
  background: #f7f9fb;
  color: #168fc1;
  font-size: 12px;
}

.video-preview summary {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 10px 12px;
  cursor: pointer;
  list-style: none;
}

.video-preview summary::-webkit-details-marker {
  display: none;
}

.preview-play {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border-radius: 50%;
  background: #00aeec;
  color: #fff;
  font-size: 8px;
}

.preview-hint {
  margin-left: auto;
  color: #9499a0;
  font-size: 11px;
}

.video-preview video {
  display: block;
  width: min(100%, 620px);
  margin: 0 12px 12px;
  border-radius: 7px;
  background: #000;
}

.actions {
  justify-content: space-between;
  gap: 16px;
  margin-top: 15px;
  padding-top: 14px;
  border-top: 1px dashed #e8eaed;
}

.action-hint {
  color: #b0b3b8;
  font-size: 11px;
}

.action-buttons {
  gap: 8px;
}

.empty-wrap {
  padding: 48px 20px 58px;
}

.pagination {
  display: flex;
  justify-content: center;
  padding: 20px;
  border-top: 1px solid #eef0f3;
}

@media (max-width: 1120px) {
  .overview-grid {
    grid-template-columns: 1fr 1fr;
  }

  .stat-card:last-child {
    display: none;
  }

  .video-card {
    grid-template-columns: 215px minmax(0, 1fr);
  }
}

@media (max-width: 720px) {
  .heading-tools {
    justify-content: space-between;
  }

  .overview-grid {
    grid-template-columns: 1fr;
  }

  .stat-card:last-child {
    display: flex;
  }

  .video-card {
    grid-template-columns: 1fr;
    padding: 17px;
  }

  .panel-header {
    align-items: flex-start;
  }

  .video-title-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .video-title-row h3 {
    width: 100%;
    white-space: normal;
  }

  .actions {
    align-items: stretch;
    flex-direction: column;
  }

  .action-buttons {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .action-buttons :deep(.el-button) {
    width: 100%;
    margin: 0;
  }

  .preview-hint {
    display: none;
  }
}

@media (max-width: 480px) {
  .queue-state {
    font-size: 12px;
  }

  .overview-grid {
    display: none;
  }

  .panel-header {
    padding: 17px;
  }

  .action-buttons {
    grid-template-columns: 1fr;
  }
}
</style>
