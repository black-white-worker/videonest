<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import AdminShell from '../components/AdminShell.vue'
import {
  getDeletedVideos,
  purgeDeletedVideo,
  type DeletedVideo
} from '../api/admin'

const router = useRouter()
const videos = ref<DeletedVideo[]>([])
const loading = ref(false)
const purgingId = ref<number | null>(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)

function isAdmin() {
  try {
    const user = JSON.parse(localStorage.getItem('userInfo') || '{}') as {
      role?: string
    }
    return Boolean(localStorage.getItem('token')) && user.role === 'ADMIN'
  } catch {
    return false
  }
}

async function loadVideos() {
  try {
    loading.value = true
    const result = await getDeletedVideos({ page: page.value, size: size.value })
    videos.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载回收站失败')
  } finally {
    loading.value = false
  }
}

async function purge(video: DeletedVideo) {
  try {
    await ElMessageBox.confirm(
      `永久删除“${video.title}”后，数据库记录、视频和封面文件均不可恢复，是否继续？`,
      '永久删除资源',
      {
        confirmButtonText: '永久删除',
        cancelButtonText: '取消',
        type: 'error'
      }
    )
    purgingId.value = video.id
    await purgeDeletedVideo(video.id)
    ElMessage.success('视频及 MinIO 资源已永久删除')
    await loadVideos()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '永久删除失败')
  } finally {
    purgingId.value = null
  }
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-'
}

onMounted(() => {
  if (!isAdmin()) {
    ElMessage.error('没有管理员权限')
    router.replace('/')
    return
  }
  loadVideos()
})
</script>

<template>
  <AdminShell
    title="视频资源回收站"
    description="集中查看已软删除的视频，在自动清理前核对资源状态，必要时执行永久删除。"
    eyebrow="资源管理"
  >
    <template #actions>
      <div class="heading-tools">
        <span class="retention-tip">◷ 到期自动清理</span>
        <el-button :loading="loading" @click="loadVideos">刷新列表</el-button>
      </div>
    </template>

    <template #overview>
      <section class="overview-grid">
        <article class="stat-card">
          <span class="stat-icon trash">删</span>
          <div>
            <small>回收站资源</small>
            <strong>{{ total }}</strong>
            <p>个待清理视频</p>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon schedule">时</span>
          <div>
            <small>本页等待清理</small>
            <strong>{{ videos.filter((video) => !video.purgeError).length }}</strong>
            <p>个资源任务</p>
          </div>
        </article>
        <article class="stat-card">
          <span class="stat-icon danger">!</span>
          <div>
            <small>本页清理异常</small>
            <strong>{{ videos.filter((video) => Boolean(video.purgeError)).length }}</strong>
            <p>个任务需关注</p>
          </div>
        </article>
      </section>
    </template>

    <section class="content-panel">
      <header class="panel-header">
        <div>
          <h2>已删除视频</h2>
          <p>永久删除会同时清理数据库记录、视频文件与封面文件。</p>
        </div>
        <span class="panel-count">共 {{ total }} 条</span>
      </header>

      <div class="safety-notice">
        <span class="notice-icon">!</span>
        <p>
          <strong>谨慎执行永久删除</strong>
          操作完成后资源不可恢复；常规情况下可等待系统按计划自动清理。
        </p>
      </div>

      <div class="table-wrap">
        <el-table :data="videos" v-loading="loading" row-key="id">
          <el-table-column label="视频信息" min-width="290">
            <template #default="{ row }">
              <div class="video-cell">
                <img v-if="row.coverUrl" :src="row.coverUrl" :alt="row.title" />
                <span v-else class="cover-placeholder">▶</span>
                <div>
                  <strong>{{ row.title }}</strong>
                  <small>
                    {{ row.authorNickname || `用户 ${row.authorId}` }}
                    <i>·</i>
                    ID {{ row.id }}
                  </small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="删除前状态" width="130">
            <template #default="{ row }">
              <span class="source-status">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="删除时间" width="180">
            <template #default="{ row }">{{ formatDate(row.deletedAt) }}</template>
          </el-table-column>
          <el-table-column label="自动清理时间" width="180">
            <template #default="{ row }">
              <span class="purge-time">{{ formatDate(row.purgeAfter) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="清理状态" min-width="210">
            <template #default="{ row }">
              <div class="purge-status">
                <el-tag v-if="row.purgeError" type="danger" effect="light">
                  失败 {{ row.purgeAttempts }} 次
                </el-tag>
                <el-tag v-else type="info" effect="plain">等待自动清理</el-tag>
                <p v-if="row.purgeError" class="error">{{ row.purgeError }}</p>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button
                type="danger"
                plain
                size="small"
                :loading="purgingId === row.id"
                @click="purge(row)"
              >
                永久删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="total > size" class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="loadVideos"
        />
      </div>
    </section>
  </AdminShell>
</template>

<style scoped>
.heading-tools,
.retention-tip,
.overview-grid,
.stat-card,
.panel-header,
.safety-notice,
.video-cell {
  display: flex;
  align-items: center;
}

.heading-tools {
  gap: 10px;
}

.retention-tip {
  gap: 6px;
  color: #61666d;
  font-size: 12px;
}

.retention-tip svg {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: #00aeec;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.7;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  gap: 14px;
  min-width: 0;
  padding: 18px 20px;
  border: 1px solid #ebeef2;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 16px rgb(0 0 0 / 2%);
}

.stat-icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 12px;
}

.stat-icon.trash {
  background: #eff1f4;
  color: #61666d;
}

.stat-icon.schedule {
  background: #e7f8ff;
  color: #00aeec;
}

.stat-icon.danger {
  background: #fff0f1;
  color: #f04f5f;
}

.stat-icon svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.65;
}

.stat-card > div {
  display: grid;
  min-width: 0;
  grid-template-columns: auto auto;
  align-items: end;
  column-gap: 7px;
}

.stat-card small {
  grid-column: 1 / -1;
  color: #61666d;
  font-size: 12px;
}

.stat-card strong {
  margin-top: 3px;
  font-size: 25px;
  line-height: 1.05;
}

.stat-card p {
  overflow: hidden;
  margin: 0 0 1px;
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
  padding: 5px 9px;
  border-radius: 6px;
  background: #f1f3f5;
  color: #61666d;
  font-size: 12px;
}

.safety-notice {
  align-items: flex-start;
  gap: 10px;
  margin: 16px 20px 0;
  padding: 12px 14px;
  border: 1px solid #ffe1c2;
  border-radius: 9px;
  background: #fff9f2;
}

.notice-icon {
  display: grid;
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  place-items: center;
  border-radius: 50%;
  background: #f5a623;
  color: #fff;
  font-size: 12px;
  font-weight: 800;
}

.safety-notice p {
  margin: 0;
  color: #9a6a2c;
  font-size: 12px;
  line-height: 1.65;
}

.safety-notice strong {
  margin-right: 8px;
  color: #80521a;
}

.table-wrap {
  overflow-x: auto;
  padding-top: 10px;
}

.table-wrap :deep(.el-table) {
  --el-table-header-bg-color: #fafbfc;
  --el-table-row-hover-bg-color: #f7fbfd;
  min-width: 1040px;
}

.table-wrap :deep(.el-table th.el-table__cell) {
  height: 46px;
  color: #61666d;
  font-size: 12px;
  font-weight: 600;
}

.table-wrap :deep(.el-table td.el-table__cell) {
  padding: 14px 0;
}

.video-cell {
  gap: 12px;
}

.video-cell img,
.cover-placeholder {
  width: 104px;
  flex: 0 0 104px;
  aspect-ratio: 16 / 9;
  border-radius: 7px;
}

.video-cell img {
  object-fit: cover;
}

.cover-placeholder {
  display: grid;
  place-items: center;
  background: #eef1f4;
  color: #b0b3b8;
}

.cover-placeholder svg {
  width: 23px;
  fill: currentColor;
}

.video-cell > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 7px;
}

.video-cell strong {
  overflow: hidden;
  color: #303236;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video-cell small {
  color: #9499a0;
  font-size: 11px;
}

.video-cell small i {
  padding: 0 4px;
  font-style: normal;
}

.source-status {
  display: inline-block;
  padding: 4px 7px;
  border-radius: 5px;
  background: #f1f3f5;
  color: #61666d;
  font-size: 11px;
}

.purge-time {
  color: #61666d;
  font-size: 12px;
}

.purge-status {
  max-width: 280px;
}

.error {
  overflow: hidden;
  margin: 6px 0 0;
  color: #f04f5f;
  font-size: 11px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pagination {
  display: flex;
  justify-content: center;
  padding: 20px;
  border-top: 1px solid #eef0f3;
}

@media (max-width: 880px) {
  .overview-grid {
    grid-template-columns: 1fr 1fr;
  }

  .stat-card:last-child {
    grid-column: 1 / -1;
  }
}

@media (max-width: 620px) {
  .heading-tools {
    justify-content: space-between;
  }

  .retention-tip {
    display: none;
  }

  .overview-grid {
    grid-template-columns: 1fr;
  }

  .stat-card:last-child {
    grid-column: auto;
  }

  .panel-header {
    align-items: flex-start;
  }

  .safety-notice {
    margin: 13px 14px 0;
  }
}
</style>
