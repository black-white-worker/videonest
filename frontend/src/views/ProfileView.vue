<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules
} from 'element-plus'
import { useRouter } from 'vue-router'
import {
  deleteCreatorVideo,
  getCreatorProfile,
  getCreatorVideos,
  updateCreatorVideo,
  type CreatorProfile,
  type CreatorVideo
} from '../api/creator'
import { getCategories, type VideoCategory } from '../api/video'
import {
  getMyFollowers,
  getMyFollowing,
  unfollowUser,
  type FollowUser
} from '../api/follow'

const router = useRouter()

const profile = ref<CreatorProfile | null>(null)
const videos = ref<CreatorVideo[]>([])
const categories = ref<VideoCategory[]>([])

const profileLoading = ref(false)
const videoLoading = ref(false)
const editLoading = ref(false)
const followLoading = ref(false)
const followTab = ref<'following' | 'followers'>('following')
const followUsers = ref<FollowUser[]>([])
const followPage = ref(1)
const followSize = ref(10)
const followTotal = ref(0)

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const editDialogVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editingVideoId = ref<number | null>(null)

const editForm = reactive({
  title: '',
  description: '',
  categoryId: undefined as number | undefined,
  coverObjectName: '',
  videoObjectName: '',
  duration: 0
})

const editRules: FormRules = {
  title: [
    {
      required: true,
      message: '请输入视频标题',
      trigger: 'blur'
    },
    {
      max: 100,
      message: '标题不能超过 100 个字符',
      trigger: 'blur'
    }
  ],
  categoryId: [
    {
      required: true,
      message: '请选择视频分区',
      trigger: 'change'
    }
  ],
  description: [
    {
      max: 2000,
      message: '简介不能超过 2000 个字符',
      trigger: 'blur'
    }
  ]
}

function ensureLoggedIn() {
  if (localStorage.getItem('token')) {
    return true
  }

  ElMessage.warning('请先登录')
  router.replace('/login')
  return false
}

async function loadProfile() {
  try {
    profileLoading.value = true
    profile.value = await getCreatorProfile()
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '获取个人信息失败'
    )
  } finally {
    profileLoading.value = false
  }
}

async function loadVideos() {
  try {
    videoLoading.value = true

    const result = await getCreatorVideos({
      page: currentPage.value,
      size: pageSize.value
    })

    videos.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '获取我的投稿失败'
    )
  } finally {
    videoLoading.value = false
  }
}

async function loadCategories() {
  try {
    categories.value = await getCategories()
  } catch {
    ElMessage.error('获取视频分区失败')
  }
}

async function loadFollowUsers() {
  try {
    followLoading.value = true
    const request = followTab.value === 'following' ? getMyFollowing : getMyFollowers
    const result = await request({ page: followPage.value, size: followSize.value })
    followUsers.value = result.records
    followTotal.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取关注列表失败')
  } finally {
    followLoading.value = false
  }
}

function changeFollowTab(tab: 'following' | 'followers') {
  followTab.value = tab
  followPage.value = 1
  loadFollowUsers()
}

async function removeFollowing(user: FollowUser) {
  try {
    followLoading.value = true
    await unfollowUser(user.id)
    ElMessage.success(`已取消关注 ${user.nickname}`)
    await loadFollowUsers()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消关注失败')
  } finally {
    followLoading.value = false
  }
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadVideos()
}

function getStatusText(status: CreatorVideo['status']) {
  const map = {
    PROCESSING: '转码中',
    PROCESS_FAILED: '转码失败',
    PENDING: '审核中',
    PUBLISHED: '已发布',
    REJECTED: '已驳回'
  }

  return map[status]
}

function getStatusType(status: CreatorVideo['status']) {
  const map = {
    PROCESSING: 'info',
    PROCESS_FAILED: 'danger',
    PENDING: 'warning',
    PUBLISHED: 'success',
    REJECTED: 'danger'
  } as const

  return map[status]
}

function formatDate(value?: string) {
  if (!value) {
    return '-'
  }

  return new Date(value).toLocaleString('zh-CN')
}

function formatDuration(seconds: number) {
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60

  return `${String(minutes).padStart(2, '0')}:${String(
    remainSeconds
  ).padStart(2, '0')}`
}

function openEditDialog(video: CreatorVideo) {
  editingVideoId.value = video.id

  editForm.title = video.title
  editForm.description = video.description || ''
  editForm.categoryId = video.categoryId
  editForm.coverObjectName = video.coverObjectName
  editForm.videoObjectName = video.videoObjectName
  editForm.duration = video.duration

  editDialogVisible.value = true
}

async function submitEdit() {
  if (!editFormRef.value || !editingVideoId.value) {
    return
  }

  const valid = await editFormRef.value.validate().catch(() => false)

  if (!valid) {
    return
  }

  try {
    editLoading.value = true

    await updateCreatorVideo(editingVideoId.value, {
      title: editForm.title.trim(),
      description: editForm.description.trim(),
      categoryId: editForm.categoryId!,
      coverObjectName: editForm.coverObjectName,
      videoObjectName: editForm.videoObjectName,
      duration: editForm.duration
    })

    ElMessage.success('视频信息已更新')
    editDialogVisible.value = false

    await Promise.all([loadVideos(), loadProfile()])
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '更新视频失败'
    )
  } finally {
    editLoading.value = false
  }
}

async function handleDelete(video: CreatorVideo) {
  try {
    await ElMessageBox.confirm(
      `确定删除《${video.title}》吗？删除后无法恢复。`,
      '删除视频',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消'
      }
    )

    await deleteCreatorVideo(video.id)

    ElMessage.success('视频已删除')

    // 当前页只有一条数据且不是第一页时，自动返回上一页
    if (videos.value.length === 1 && currentPage.value > 1) {
      currentPage.value -= 1
    }

    await Promise.all([loadVideos(), loadProfile()])
  } catch (error) {
    // 点击取消不提示错误
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(
        error instanceof Error ? error.message : '删除视频失败'
      )
    }
  }
}

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')

  ElMessage.success('已退出登录')
  router.push('/login')
}

onMounted(() => {
  if (!ensureLoggedIn()) {
    return
  }

  loadProfile()
  loadVideos()
  loadCategories()
  loadFollowUsers()
})
</script>

<template>
  <main class="profile-page">
    <header class="header">
      <div class="header-content">
        <button class="logo" @click="router.push('/')">
          <span>▶</span>
          VideoNest
        </button>

        <div class="header-actions">
          <el-button type="primary" plain @click="router.push('/upload')">
            投稿
          </el-button>

          <el-button @click="router.push('/')">
            返回首页
          </el-button>

          <el-button type="danger" plain @click="logout">
            退出登录
          </el-button>
        </div>
      </div>
    </header>

    <section class="container">
      <el-skeleton :loading="profileLoading" animated>
        <template #default>
          <section v-if="profile" class="profile-card">
            <div class="avatar">
              {{ profile.nickname.slice(0, 1).toUpperCase() }}
            </div>

            <div class="profile-info">
              <h1>{{ profile.nickname }}</h1>
              <p>@{{ profile.username }}</p>

              <el-tag :type="profile.role === 'ADMIN' ? 'warning' : 'info'">
                {{ profile.role === 'ADMIN' ? '管理员' : '普通用户' }}
              </el-tag>
            </div>

            <div class="stats">
              <div>
                <strong>{{ profile.totalVideoCount }}</strong>
                <span>全部投稿</span>
              </div>

              <div>
                <strong>{{ profile.pendingVideoCount }}</strong>
                <span>审核中</span>
              </div>

              <div>
                <strong>{{ profile.publishedVideoCount }}</strong>
                <span>已发布</span>
              </div>

              <div>
                <strong>{{ profile.rejectedVideoCount }}</strong>
                <span>已驳回</span>
              </div>
            </div>
          </section>
        </template>
      </el-skeleton>

      <section class="follow-section">
        <div class="section-title">
          <div>
            <h2>我的关注</h2>
            <p>管理你关注的创作者和粉丝</p>
          </div>
          <el-button :loading="followLoading" @click="loadFollowUsers">刷新</el-button>
        </div>

        <el-tabs
          :model-value="followTab"
          @tab-change="changeFollowTab($event as 'following' | 'followers')"
        >
          <el-tab-pane label="我的关注" name="following" />
          <el-tab-pane label="我的粉丝" name="followers" />
        </el-tabs>

        <el-skeleton :loading="followLoading" animated :rows="3">
          <template #default>
            <div v-if="followUsers.length" class="follow-list">
              <article v-for="user in followUsers" :key="user.id" class="follow-user">
                <div class="follow-avatar">{{ user.nickname.slice(0, 1).toUpperCase() }}</div>
                <div class="follow-user-info">
                  <strong>{{ user.nickname }}</strong>
                  <span>@{{ user.username }} · {{ formatDate(user.followedAt) }}</span>
                </div>
                <el-button
                  v-if="followTab === 'following'"
                  plain
                  @click="removeFollowing(user)"
                >取消关注</el-button>
              </article>
            </div>
            <el-empty v-else :description="followTab === 'following' ? '你还没有关注任何用户' : '暂时还没有粉丝'" />
          </template>
        </el-skeleton>

        <div v-if="followTotal > followSize" class="pagination">
          <el-pagination
            v-model:current-page="followPage"
            :page-size="followSize"
            :total="followTotal"
            layout="prev, pager, next"
            background
            @current-change="loadFollowUsers"
          />
        </div>
      </section>

      <section class="submission-section">
        <div class="section-title">
          <div>
            <h2>我的投稿</h2>
            <p>共 {{ total }} 个视频投稿</p>
          </div>

          <el-button :loading="videoLoading" @click="loadVideos">
            刷新
          </el-button>
        </div>

        <el-skeleton :loading="videoLoading" animated :count="4">
          <template #default>
            <div v-if="videos.length > 0" class="video-list">
              <article
                v-for="video in videos"
                :key="video.id"
                class="video-card"
              >
                <div class="cover-box">
                  <img
                    v-if="video.coverUrl"
                    :src="video.coverUrl"
                    :alt="video.title"
                  />
                  <span v-else class="processing-cover">处理中</span>

                  <span class="duration">
                    {{ formatDuration(video.duration) }}
                  </span>
                </div>

                <div class="video-content">
                  <div class="title-row">
                    <h3>{{ video.title }}</h3>

                    <el-tag :type="getStatusType(video.status)">
                      {{ getStatusText(video.status) }}
                    </el-tag>
                  </div>

                  <p class="description">
                    {{ video.description || '暂无视频简介。' }}
                  </p>

                  <div class="meta">
                    <span>分区：{{ video.categoryName }}</span>
                    <span>投稿时间：{{ formatDate(video.createTime) }}</span>

                    <span v-if="video.status === 'PUBLISHED'">
                      播放：{{ video.viewCount }}
                    </span>
                  </div>

                  <div
                    v-if="video.status === 'REJECTED' && video.rejectReason"
                    class="reject-reason"
                  >
                    <strong>驳回原因：</strong>
                    {{ video.rejectReason }}
                  </div>

                  <div
                    v-if="video.status === 'PROCESS_FAILED' && video.processError"
                    class="reject-reason"
                  >
                    <strong>转码失败：</strong>
                    {{ video.processError }}
                  </div>

                  <div class="video-actions">
                    <el-button
                      v-if="video.status === 'PUBLISHED'"
                      type="primary"
                      plain
                      size="small"
                      @click="router.push(`/video/${video.id}`)"
                    >
                      查看视频
                    </el-button>

                    <el-button size="small" @click="openEditDialog(video)">
                      编辑信息
                    </el-button>

                    <el-button
                      type="danger"
                      plain
                      size="small"
                      @click="handleDelete(video)"
                    >
                      删除
                    </el-button>
                  </div>
                </div>
              </article>
            </div>

            <el-empty
              v-else
              description="你还没有投稿，去发布第一个视频吧"
            >
              <el-button type="primary" @click="router.push('/upload')">
                去投稿
              </el-button>
            </el-empty>
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
    </section>

    <el-dialog
      v-model="editDialogVisible"
      title="编辑视频信息"
      width="520px"
      destroy-on-close
    >
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-position="top"
      >
        <el-form-item label="视频标题" prop="title">
          <el-input
            v-model="editForm.title"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="视频分区" prop="categoryId">
          <el-select
            v-model="editForm.categoryId"
            placeholder="请选择视频分区"
            class="full-width"
          >
            <el-option
              v-for="category in categories"
              :key="category.id"
              :label="category.name"
              :value="category.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="视频简介" prop="description">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="请输入视频简介"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editDialogVisible = false">
          取消
        </el-button>

        <el-button
          type="primary"
          :loading="editLoading"
          @click="submitEdit"
        >
          保存修改
        </el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.profile-page {
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
  gap: 10px;
}

.container {
  padding: 30px 0 48px;
}

.profile-card {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 28px;
  border-radius: 12px;
  background: #fff;
}

.avatar {
  display: grid;
  width: 68px;
  height: 68px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 28px;
  font-weight: 600;
}

.profile-info h1 {
  margin: 0 0 7px;
  font-size: 23px;
}

.profile-info p {
  margin: 0 0 9px;
  color: #9499a0;
  font-size: 14px;
}

.stats {
  display: flex;
  margin-left: auto;
  gap: 38px;
}

.stats div {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
}

.stats strong {
  font-size: 22px;
}

.stats span {
  color: #7a7f87;
  font-size: 13px;
}

.submission-section {
  margin-top: 24px;
  padding: 26px;
  border-radius: 12px;
  background: #fff;
}

.follow-section {
  margin-top: 24px;
  padding: 26px;
  border-radius: 12px;
  background: #fff;
}

.follow-list {
  display: flex;
  flex-direction: column;
}

.follow-user {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 0;
  border-bottom: 1px solid #f0f0f0;
}

.follow-user:last-child {
  border-bottom: 0;
}

.follow-avatar {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 50%;
  background: #e8f3ff;
  color: #1677ff;
  font-weight: 600;
}

.follow-user-info {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.follow-user-info span {
  overflow: hidden;
  color: #9499a0;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 22px;
}

.section-title h2 {
  margin: 0 0 7px;
  font-size: 21px;
}

.section-title p {
  margin: 0;
  color: #9499a0;
  font-size: 14px;
}

.video-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.video-card {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 18px;
  padding-bottom: 18px;
  border-bottom: 1px solid #f0f0f0;
}

.video-card:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.cover-box {
  position: relative;
  overflow: hidden;
  aspect-ratio: 16 / 9;
  border-radius: 8px;
  background: #e5e7eb;
}

.cover-box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.processing-cover {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  color: #7a7f87;
  font-size: 14px;
}

.duration {
  position: absolute;
  right: 7px;
  bottom: 7px;
  padding: 2px 5px;
  border-radius: 4px;
  background: rgb(0 0 0 / 65%);
  color: #fff;
  font-size: 12px;
}

.video-content {
  min-width: 0;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.title-row h3 {
  overflow: hidden;
  margin: 0;
  font-size: 17px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.description {
  display: -webkit-box;
  overflow: hidden;
  margin: 10px 0;
  color: #61666d;
  font-size: 14px;
  line-height: 21px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  color: #9499a0;
  font-size: 13px;
}

.reject-reason {
  margin-top: 13px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #fff2f0;
  color: #cf1322;
  font-size: 14px;
  line-height: 22px;
}

.video-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 13px;
}

.full-width {
  width: 100%;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 28px;
}

@media (max-width: 720px) {
  .header-content,
  .container {
    width: min(100% - 28px, 1100px);
  }

  .header-actions .el-button:first-child {
    display: none;
  }

  .profile-card {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .stats {
    width: 100%;
    justify-content: space-between;
    margin: 10px 0 0;
    gap: 8px;
  }

  .submission-section {
    padding: 20px;
  }

  .video-card {
    grid-template-columns: 1fr;
  }
}
</style>
