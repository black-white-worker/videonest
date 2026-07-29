<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createComment,
  deleteComment,
  favoriteVideo,
  getComments,
  getCommentReplies,
  getInteractionStatus,
  getVideoDetail,
  likeVideo,
  unfavoriteVideo,
  unlikeVideo,
  type VideoComment,
  type VideoDetail
} from '../api/video'
import { followUser, getFollowStatus, unfollowUser } from '../api/follow'
import SiteHeader from '../components/SiteHeader.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const video = ref<VideoDetail | null>(null)
const player = ref<HTMLVideoElement | null>(null)
const selectedQuality = ref<'480p' | '720p' | '1080p'>('720p')
const playbackMediaInfo = ref<{
  width: number
  height: number
  averageBitrate: number | null
} | null>(null)
let pendingPlaybackState: {
  currentTime: number
  shouldResume: boolean
} | null = null
const liked = ref(false)
const favorited = ref(false)
const interactionLoading = ref(false)
const followLoading = ref(false)
const followed = ref(false)
const comments = ref<VideoComment[]>([])
const commentContent = ref('')
const commentLoading = ref(false)
const commentSubmitting = ref(false)
const commentPage = ref(1)
const commentSize = ref(20)
const commentTotal = ref(0)
const replyLoadingIds = ref<string[]>([])
const expandedReplyIds = ref<string[]>([])
const repliesByCommentId = ref<Record<string, VideoComment[]>>({})
const replyContents = ref<Record<string, string>>({})
const replySubmittingIds = ref<string[]>([])

const qualityOptions = computed(() => {
  if (!video.value) return []

  return [
    {
      label: '480P',
      value: '480p' as const,
      url: video.value.video480pUrl,
      sizeBytes: video.value.video480pSizeBytes
    },
    {
      label: '720P',
      value: '720p' as const,
      url: video.value.video720pUrl || video.value.videoUrl,
      sizeBytes: video.value.video720pSizeBytes
    },
    {
      label: '1080P',
      value: '1080p' as const,
      url: video.value.video1080pUrl,
      sizeBytes: video.value.video1080pSizeBytes
    }
  ]
})

const currentQualityOption = computed(() =>
  qualityOptions.value.find(option => option.value === selectedQuality.value)
)

const currentVideoUrl = computed(() =>
  currentQualityOption.value?.url
    || video.value?.videoUrl
    || ''
)

const playbackMediaInfoText = computed(() => {
  if (!playbackMediaInfo.value) {
    return '正在读取当前视频参数…'
  }

  const { width, height, averageBitrate } = playbackMediaInfo.value
  const bitrateText = averageBitrate === null
    ? '平均总码率未知'
    : averageBitrate >= 1_000_000
      ? `平均总码率 ${(averageBitrate / 1_000_000).toFixed(2)} Mbps`
      : `平均总码率 ${Math.round(averageBitrate / 1000)} kbps`

  return `当前实际 ${width} × ${height} · ${bitrateText}`
})

function formatNumber(value: number) {
  if (value >= 10000) {
    return `${(value / 10000).toFixed(1)}万`
  }

  return value.toString()
}

function formatDate(value: string) {
  if (!value) {
    return ''
  }

  return new Date(value).toLocaleString('zh-CN')
}

async function loadVideoDetail() {
  const id = Number(route.params.id)

  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.error('视频 ID 不合法')
    router.push('/')
    return
  }

  try {
    loading.value = true
    video.value = await getVideoDetail(id)
    selectedQuality.value = qualityOptions.value.find(
      option => option.value === '720p' && option.url
    )?.value
      || qualityOptions.value.find(option => option.url)?.value
      || '720p'
  } catch (error) {
    const message = error instanceof Error ? error.message : '获取视频详情失败'
    ElMessage.error(message)
    router.push('/')
    return
  } finally {
    loading.value = false
  }

  // 点赞、关注和评论加载失败不应影响播放器打开。
  await Promise.allSettled([
    loadInteractionStatus(id),
    loadFollowStatus(),
    loadComments(id)
  ])
}

async function changeQuality() {
  const currentPlayer = player.value
  pendingPlaybackState = {
    currentTime: currentPlayer?.currentTime || 0,
    shouldResume: currentPlayer ? !currentPlayer.paused : false
  }
  playbackMediaInfo.value = null

  await nextTick()
  player.value?.load()
}

async function shareVideo() {
  if (!video.value) return

  try {
    if (navigator.share) {
      await navigator.share({
        title: video.value.title,
        text: `来 VideoNest 看看《${video.value.title}》`,
        url: window.location.href
      })
      return
    }

    await navigator.clipboard.writeText(window.location.href)
    ElMessage.success('视频链接已复制')
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    ElMessage.warning('暂时无法分享，请手动复制浏览器地址')
  }
}

function handleLoadedMetadata() {
  const currentPlayer = player.value
  if (!currentPlayer) return

  const duration = Number.isFinite(currentPlayer.duration) && currentPlayer.duration > 0
    ? currentPlayer.duration
    : video.value?.duration || 0
  const sizeBytes = currentQualityOption.value?.sizeBytes
  const averageBitrate = sizeBytes && duration > 0
    ? sizeBytes * 8 / duration
    : null

  playbackMediaInfo.value = {
    width: currentPlayer.videoWidth,
    height: currentPlayer.videoHeight,
    averageBitrate
  }

  const playbackState = pendingPlaybackState
  pendingPlaybackState = null
  if (!playbackState) return

  if (playbackState.currentTime > 0 && Number.isFinite(currentPlayer.duration)) {
    currentPlayer.currentTime = Math.min(
      playbackState.currentTime,
      Math.max(0, currentPlayer.duration - 0.1)
    )
  }
  if (playbackState.shouldResume) {
    currentPlayer.play().catch(() => undefined)
  }
}

function isMyVideo(): boolean {
  const userInfo = localStorage.getItem('userInfo')
  if (!video.value || !userInfo) return false

  try {
    return JSON.parse(userInfo).userId === video.value.authorId
  } catch {
    return false
  }
}

async function loadFollowStatus() {
  if (!video.value || !localStorage.getItem('token') || isMyVideo()) return
  const status = await getFollowStatus(video.value.authorId)
  followed.value = status.followed
}

async function toggleFollow() {
  if (!video.value || !requireLogin() || isMyVideo()) return

  try {
    followLoading.value = true
    if (followed.value) {
      await unfollowUser(video.value.authorId)
    } else {
      await followUser(video.value.authorId)
    }
    followed.value = !followed.value
    ElMessage.success(followed.value ? '已关注' : '已取消关注')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '关注操作失败')
  } finally {
    followLoading.value = false
  }
}

async function loadComments(videoId: number) {
  try {
    commentLoading.value = true
    const result = await getComments(
      videoId,
      commentPage.value,
      commentSize.value
    )
    comments.value = result.records
    commentTotal.value = result.total
  } catch (error) {
    const message = error instanceof Error ? error.message : '获取评论失败'
    ElMessage.error(message)
  } finally {
    commentLoading.value = false
  }
}

async function loadInteractionStatus(videoId: number) {
  if (!localStorage.getItem('token')) {
    return
  }

  const status = await getInteractionStatus(videoId)
  liked.value = status.liked
  favorited.value = status.favorited

  if (video.value) {
    video.value.likeCount = status.likeCount
    video.value.favoriteCount = status.favoriteCount
  }
}

function requireLogin(): boolean {
  if (localStorage.getItem('token')) {
    return true
  }

  ElMessage.warning('请先登录后再操作')
  router.push('/login')
  return false
}

async function toggleLike() {
  if (!video.value || !requireLogin()) {
    return
  }

  try {
    interactionLoading.value = true

    if (liked.value) {
      await unlikeVideo(video.value.id)
    } else {
      await likeVideo(video.value.id)
    }

    await loadInteractionStatus(video.value.id)
  } catch (error) {
    const message = error instanceof Error ? error.message : '点赞操作失败'
    ElMessage.error(message)
  } finally {
    interactionLoading.value = false
  }
}

async function toggleFavorite() {
  if (!video.value || !requireLogin()) {
    return
  }

  try {
    interactionLoading.value = true

    if (favorited.value) {
      await unfavoriteVideo(video.value.id)
    } else {
      await favoriteVideo(video.value.id)
    }

    await loadInteractionStatus(video.value.id)
  } catch (error) {
    const message = error instanceof Error ? error.message : '收藏操作失败'
    ElMessage.error(message)
  } finally {
    interactionLoading.value = false
  }
}

async function submitComment() {
  if (!video.value || !requireLogin()) {
    return
  }

  const content = commentContent.value.trim()

  if (!content) {
    ElMessage.warning('请输入评论内容')
    return
  }

  try {
    commentSubmitting.value = true
    await createComment(video.value.id, content)
    commentContent.value = ''
    commentPage.value = 1
    await loadComments(video.value.id)
    ElMessage.success('评论发布成功')
  } catch (error) {
    const message = error instanceof Error ? error.message : '评论发布失败'
    ElMessage.error(message)
  } finally {
    commentSubmitting.value = false
  }
}

async function removeComment(commentId: string) {
  if (!video.value || !requireLogin()) {
    return
  }

  try {
    commentLoading.value = true
    await deleteComment(video.value.id, commentId)
    await loadComments(video.value.id)
    ElMessage.success('评论已删除')
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除评论失败'
    ElMessage.error(message)
  } finally {
    commentLoading.value = false
  }
}

function isReplyExpanded(commentId: string) {
  return expandedReplyIds.value.includes(commentId)
}

async function toggleReplies(comment: VideoComment) {
  if (isReplyExpanded(comment.id)) {
    expandedReplyIds.value = expandedReplyIds.value.filter(id => id !== comment.id)
    return
  }
  try {
    replyLoadingIds.value.push(comment.id)
    const result = await getCommentReplies(video.value!.id, comment.id)
    repliesByCommentId.value[comment.id] = result.records
    expandedReplyIds.value.push(comment.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取回复失败')
  } finally {
    replyLoadingIds.value = replyLoadingIds.value.filter(id => id !== comment.id)
  }
}

async function submitReply(comment: VideoComment) {
  if (!video.value || !requireLogin()) return
  const content = (replyContents.value[comment.id] || '').trim()
  if (!content) return ElMessage.warning('请输入回复内容')
  try {
    replySubmittingIds.value.push(comment.id)
    await createComment(video.value.id, content, comment.id)
    replyContents.value[comment.id] = ''
    const result = await getCommentReplies(video.value.id, comment.id)
    repliesByCommentId.value[comment.id] = result.records
    if (!isReplyExpanded(comment.id)) expandedReplyIds.value.push(comment.id)
    comment.replyCount = (comment.replyCount || 0) + 1
    ElMessage.success('回复发布成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '回复发布失败')
  } finally {
    replySubmittingIds.value = replySubmittingIds.value.filter(id => id !== comment.id)
  }
}

function changeCommentPage(page: number) {
  if (!video.value) {
    return
  }

  commentPage.value = page
  loadComments(video.value.id)
}

function isMyComment(comment: VideoComment): boolean {
  const userInfo = localStorage.getItem('userInfo')

  if (!userInfo) {
    return false
  }

  try {
    return JSON.parse(userInfo).userId === comment.userId
  } catch {
    return false
  }
}

function canDeleteComment(comment: VideoComment): boolean {
  const userInfo = localStorage.getItem('userInfo')

  if (!userInfo) {
    return false
  }

  try {
    const currentUser = JSON.parse(userInfo)
    return currentUser.userId === comment.userId ||
      (
        String(currentUser.role).trim().toUpperCase() === 'ADMIN' &&
        String(comment.parentId) === '0'
      )
  } catch {
    return false
  }
}

function goHome() {
  router.push('/')
}

onMounted(() => {
  loadVideoDetail()
})
</script>

<template>
  <main class="detail-page">
    <SiteHeader max-width="1380px">
      <template #nav>
        <button class="site-nav-link" @click="goHome">首页</button>
        <button class="site-nav-link" @click="router.push('/profile')">个人中心</button>
      </template>
      <template #actions>
        <el-button text @click="router.push('/notifications')">消息</el-button>
        <el-button type="primary" @click="router.push('/upload')">+ 投稿</el-button>
      </template>
    </SiteHeader>

    <section class="container">
      <el-skeleton :loading="loading" animated :rows="12">
        <template #default>
          <template v-if="video">
            <div class="watch-layout">
              <div class="watch-main">
                <section class="video-heading">
                  <div class="video-heading__top">
                    <span class="category">{{ video.categoryName }}</span>
                    <span class="publish-time">{{ formatDate(video.publishTime) }}</span>
                  </div>
                  <h1>{{ video.title }}</h1>
                  <div class="statistics">
                    <span>▶ {{ formatNumber(video.viewCount) }} 播放</span>
                    <span>♥ {{ formatNumber(video.likeCount) }} 点赞</span>
                    <span>★ {{ formatNumber(video.favoriteCount) }} 收藏</span>
                  </div>
                </section>

                <div class="video-player-box">
                  <div v-if="qualityOptions.length" class="quality-switcher">
                    <span>清晰度</span>
                    <el-radio-group v-model="selectedQuality" size="small" @change="changeQuality">
                      <el-radio-button
                        v-for="option in qualityOptions"
                        :key="option.value"
                        :value="option.value"
                        :disabled="!option.url"
                      >
                        {{ option.label }}
                      </el-radio-button>
                    </el-radio-group>
                  </div>
                  <video
                    ref="player"
                    :poster="video.coverUrl"
                    :src="currentVideoUrl"
                    controls
                    class="video-player"
                    @loadedmetadata="handleLoadedMetadata"
                  >
                    当前浏览器不支持视频播放。
                  </video>
                  <div class="playback-media-info">
                    <span class="live-dot" />
                    {{ playbackMediaInfoText }}
                  </div>
                </div>

                <section class="video-action-bar">
                  <button
                    class="video-action"
                    :class="{ active: liked }"
                    :disabled="interactionLoading"
                    @click="toggleLike"
                  >
                    <span>♥</span>
                    <strong>{{ liked ? '已点赞' : '点赞' }}</strong>
                    <small>{{ formatNumber(video.likeCount) }}</small>
                  </button>
                  <button
                    class="video-action favorite"
                    :class="{ active: favorited }"
                    :disabled="interactionLoading"
                    @click="toggleFavorite"
                  >
                    <span>★</span>
                    <strong>{{ favorited ? '已收藏' : '收藏' }}</strong>
                    <small>{{ formatNumber(video.favoriteCount) }}</small>
                  </button>
                  <button class="video-action" @click="shareVideo">
                    <span>↗</span>
                    <strong>分享</strong>
                    <small>复制链接</small>
                  </button>
                </section>

                <section class="description-card">
                  <div class="description-card__title">
                    <h2>视频简介</h2>
                    <span>{{ video.categoryName }}</span>
                  </div>
                  <p>{{ video.description || '该视频暂未填写简介。' }}</p>
                </section>

                <section class="comment-section">
                  <div class="comment-header">
                    <div>
                      <span class="section-kicker">COMMENTS</span>
                      <h2>评论区</h2>
                    </div>
                    <span>{{ commentTotal }} 条评论</span>
                  </div>

                  <div class="comment-editor">
                    <div class="editor-avatar">我</div>
                    <el-input
                      v-model="commentContent"
                      type="textarea"
                      :rows="3"
                      maxlength="500"
                      show-word-limit
                      placeholder="发一条友善的评论吧"
                    />
                    <el-button
                      type="primary"
                      :loading="commentSubmitting"
                      @click="submitComment"
                    >
                      发布
                    </el-button>
                  </div>

                  <el-skeleton :loading="commentLoading" animated :rows="4">
                    <template #default>
                      <div v-if="comments.length" class="comment-list">
                        <article
                          v-for="comment in comments"
                          :key="comment.id"
                          class="comment-item"
                        >
                          <div class="comment-avatar">
                            {{ comment.nickname.slice(0, 1) }}
                          </div>
                          <div class="comment-main">
                            <div class="comment-name">{{ comment.nickname }}</div>
                            <p>{{ comment.content }}</p>
                            <div class="comment-meta">
                              <span>{{ formatDate(comment.createdAt) }}</span>
                              <el-button link @click="toggleReplies(comment)">
                                {{ isReplyExpanded(comment.id) ? '收起回复' : `回复${comment.replyCount ? ` (${comment.replyCount})` : ''}` }}
                              </el-button>
                              <el-button
                                v-if="canDeleteComment(comment)"
                                link
                                type="danger"
                                @click="removeComment(comment.id)"
                              >
                                删除
                              </el-button>
                            </div>
                            <div v-if="isReplyExpanded(comment.id)" class="reply-area">
                              <el-skeleton :loading="replyLoadingIds.includes(comment.id)" animated :rows="2">
                                <template #default>
                                  <div
                                    v-for="reply in repliesByCommentId[comment.id] || []"
                                    :key="reply.id"
                                    class="reply-item"
                                  >
                                    <strong>{{ reply.nickname }}</strong>：{{ reply.content }}
                                    <el-button
                                      v-if="isMyComment(reply)"
                                      link
                                      type="danger"
                                      size="small"
                                      @click="removeComment(reply.id)"
                                    >
                                      删除
                                    </el-button>
                                  </div>
                                  <el-empty
                                    v-if="!(repliesByCommentId[comment.id] || []).length"
                                    description="暂无回复"
                                    :image-size="55"
                                  />
                                </template>
                              </el-skeleton>
                              <div class="reply-editor">
                                <el-input
                                  v-model="replyContents[comment.id]"
                                  maxlength="500"
                                  placeholder="写下你的回复"
                                  @keyup.enter="submitReply(comment)"
                                />
                                <el-button
                                  type="primary"
                                  :loading="replySubmittingIds.includes(comment.id)"
                                  @click="submitReply(comment)"
                                >
                                  回复
                                </el-button>
                              </div>
                            </div>
                          </div>
                        </article>
                      </div>

                      <el-empty v-else description="暂无评论，来抢沙发吧" />
                    </template>
                  </el-skeleton>

                  <div v-if="commentTotal > commentSize" class="comment-pagination">
                    <el-pagination
                      v-model:current-page="commentPage"
                      :page-size="commentSize"
                      :total="commentTotal"
                      layout="prev, pager, next"
                      background
                      @current-change="changeCommentPage"
                    />
                  </div>
                </section>
              </div>

              <aside class="watch-sidebar">
                <section class="author-card">
                  <div class="author-card__cover" />
                  <div class="author-card__body">
                    <div class="avatar">{{ video.authorNickname.slice(0, 1) }}</div>
                    <div class="author-card__identity">
                      <strong>{{ video.authorNickname }}</strong>
                      <span>@{{ video.authorUsername }}</span>
                    </div>
                    <p>喜欢这个视频？关注创作者，及时发现更多新内容。</p>
                    <el-button
                      v-if="!isMyVideo()"
                      :type="followed ? 'default' : 'primary'"
                      :loading="followLoading"
                      class="follow-button"
                      @click="toggleFollow"
                    >
                      {{ followed ? '已关注' : '+ 关注' }}
                    </el-button>
                    <el-tag v-else type="info">这是你的投稿</el-tag>
                  </div>
                </section>

                <section class="media-panel">
                  <div class="side-panel-title">
                    <h3>播放信息</h3>
                    <span>{{ selectedQuality.toUpperCase() }}</span>
                  </div>
                  <dl>
                    <div>
                      <dt>当前参数</dt>
                      <dd>{{ playbackMediaInfoText.replace('当前实际 ', '') }}</dd>
                    </div>
                    <div>
                      <dt>视频分区</dt>
                      <dd>{{ video.categoryName }}</dd>
                    </div>
                    <div>
                      <dt>发布时间</dt>
                      <dd>{{ formatDate(video.publishTime) }}</dd>
                    </div>
                  </dl>
                </section>

                <section class="community-note">
                  <strong>一起维护友善社区</strong>
                  <p>尊重原创，理性交流。优质评论会让创作者更有动力。</p>
                </section>
              </aside>
            </div>
          </template>
        </template>
      </el-skeleton>
    </section>
  </main>
</template>

<style scoped>
.detail-page {
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
  width: min(1200px, calc(100% - 48px));
  margin: 0 auto;
}

.header-content {
  height: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1677ff;
  font-size: 22px;
  font-weight: 700;
  cursor: pointer;
}

.logo-icon {
  width: 28px;
  height: 28px;
  display: inline-flex;
  justify-content: center;
  align-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 14px;
}

.container {
  padding-top: 30px;
  padding-bottom: 50px;
}

.video-player-box {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  background: #000;
  box-shadow: 0 8px 26px rgb(0 0 0 / 14%);
}

.video-player {
  width: 100%;
  max-height: 680px;
  display: block;
  background: #000;
}

.playback-media-info {
  padding: 8px 14px;
  background: #111;
  color: #c9d1d9;
  font-size: 12px;
  line-height: 1.5;
}

.video-info {
  margin-top: 24px;
  padding: 26px 30px;
  border-radius: 12px;
  background: #fff;
}

.category {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 5px;
  background: #e8f3ff;
  color: #1677ff;
  font-size: 13px;
}

h1 {
  margin: 14px 0 10px;
  font-size: 26px;
}

.statistics {
  display: flex;
  flex-wrap: wrap;
  gap: 15px;
  color: #9499a0;
  font-size: 14px;
}

.quality-switcher {
  position: absolute;
  z-index: 1;
  top: 14px;
  right: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: rgb(0 0 0 / 62%);
  color: #fff;
  font-size: 13px;
}

.interaction-actions {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}

.author {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 24px;
}

.avatar {
  width: 44px;
  height: 44px;
  display: flex;
  justify-content: center;
  align-items: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-size: 19px;
}

.author strong {
  display: block;
}

.author p {
  margin: 4px 0 0;
  color: #9499a0;
  font-size: 13px;
}

.author .el-button {
  margin-left: auto;
}

.description h3 {
  margin: 0 0 10px;
  font-size: 17px;
}

.description p {
  margin: 0;
  color: #61666d;
  line-height: 1.8;
  white-space: pre-wrap;
}

.comment-section {
  margin-top: 24px;
  padding: 26px 30px;
  border-radius: 12px;
  background: #fff;
}

.comment-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.comment-header h2 {
  margin: 0;
  font-size: 20px;
}

.comment-header span,
.comment-meta {
  color: #9499a0;
  font-size: 13px;
}

.comment-editor {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  margin: 18px 0 24px;
}

.comment-editor .el-button {
  flex-shrink: 0;
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.comment-item {
  display: flex;
  gap: 12px;
}

.comment-avatar {
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e8f3ff;
  color: #1677ff;
}

.comment-main {
  min-width: 0;
  flex: 1;
}

.comment-name {
  color: #61666d;
  font-size: 14px;
}

.comment-main p {
  margin: 7px 0;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.comment-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.comment-pagination {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

.reply-area {
  margin-top: 12px;
  padding: 12px;
  border-radius: 8px;
  background: #f7f8fa;
}

.reply-item {
  padding: 7px 0;
  color: #45474b;
  font-size: 14px;
  line-height: 1.6;
}

.reply-editor {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

@media (max-width: 700px) {
  .header-content,
  .container {
    width: min(100% - 28px, 1200px);
  }

  .video-info {
    padding: 20px;
  }

  .comment-section {
    padding: 20px;
  }

  .comment-editor {
    flex-direction: column;
    align-items: stretch;
  }

  h1 {
    font-size: 21px;
  }
}
/* 企业后台式的信息卡片、内容层级与播放器视觉。 */
.detail-page{background:var(--vn-page);color:var(--vn-text)}
.header{height:68px;background:rgb(255 255 255 / 94%);border-color:var(--vn-border);position:sticky;top:0;z-index:10;backdrop-filter:blur(14px)}
.header-content,.container{width:min(1120px,calc(100% - 48px))}.logo{color:#172b4d;letter-spacing:-.4px}.logo-icon{border-radius:9px;background:linear-gradient(135deg,#1677ff,#5b9cff);box-shadow:0 4px 10px rgb(22 119 255 / 25%)}
.container{padding-top:34px}.video-player-box{border-radius:var(--vn-radius);box-shadow:0 14px 34px rgb(16 24 40 / 16%)}
.video-info,.comment-section{padding:28px 32px;border:1px solid var(--vn-border);border-radius:var(--vn-radius);box-shadow:var(--vn-shadow)}
.video-info h1{color:#172b4d;font-size:28px}.statistics,.comment-header span,.comment-meta{color:var(--vn-text-muted)}.avatar{border-radius:12px;background:linear-gradient(135deg,#1677ff,#5b9cff)}.description p{color:var(--vn-text-secondary)}.reply-area{background:#f8fafc;border:1px solid #edf0f5}
@media (max-width:700px){.header{height:60px}.header-content,.container{width:min(100% - 28px,1120px)}.container{padding-top:22px}.video-info,.comment-section{padding:20px}.video-info h1{font-size:22px}}
</style>

<style scoped>
.detail-page {
  min-height: 100vh;
  background: var(--vn-page);
  color: var(--vn-text);
}

.container {
  width: min(1380px, calc(100% - 64px));
  margin: 0 auto;
  padding: 30px 0 72px;
}

.watch-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 318px;
  align-items: start;
  gap: 24px;
}

.watch-main {
  min-width: 0;
}

.video-heading {
  margin-bottom: 18px;
}

.video-heading__top {
  display: flex;
  align-items: center;
  gap: 10px;
}

.category {
  display: inline-flex;
  padding: 4px 9px;
  border: 1px solid #bdeeff;
  border-radius: 6px;
  background: var(--vn-primary-soft);
  color: var(--vn-primary-dark);
  font-size: 12px;
}

.publish-time {
  color: var(--vn-text-muted);
  font-size: 12px;
}

.video-heading h1 {
  margin: 10px 0 9px;
  color: var(--vn-text);
  font-size: clamp(22px, 2.1vw, 30px);
  line-height: 1.35;
  letter-spacing: -.6px;
}

.statistics {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  color: var(--vn-text-muted);
  font-size: 13px;
}

.video-player-box {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  background: #070707;
  box-shadow: 0 16px 40px rgb(0 0 0 / 16%);
}

.video-player {
  width: 100%;
  max-height: none;
  aspect-ratio: 16 / 9;
  display: block;
  background: #000;
  object-fit: contain;
}

.quality-switcher {
  position: absolute;
  z-index: 2;
  top: 14px;
  right: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 8px;
  background: rgb(0 0 0 / 62%);
  color: #fff;
  font-size: 12px;
  backdrop-filter: blur(12px);
}

.quality-switcher :deep(.el-radio-button__inner) {
  padding: 6px 10px;
  border-color: rgb(255 255 255 / 12%);
  background: rgb(255 255 255 / 8%);
  color: rgb(255 255 255 / 78%);
}

.quality-switcher :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: var(--vn-primary);
  color: #fff;
}

.playback-media-info {
  padding: 8px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: #151515;
  color: #b7bdc4;
  font-size: 12px;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #67d18b;
  box-shadow: 0 0 0 3px rgb(103 209 139 / 14%);
}

.video-action-bar {
  min-height: 70px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--vn-border);
}

.video-action {
  min-width: 116px;
  display: grid;
  grid-template-columns: 24px auto;
  grid-template-rows: auto auto;
  align-items: center;
  column-gap: 7px;
  padding: 9px 13px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--vn-text-secondary);
  text-align: left;
  cursor: pointer;
  transition: .2s;
}

.video-action:hover {
  background: #f1f2f3;
}

.video-action > span {
  grid-row: 1 / 3;
  color: var(--vn-text-muted);
  font-size: 22px;
}

.video-action strong {
  font-size: 13px;
  font-weight: 600;
}

.video-action small {
  color: var(--vn-text-muted);
  font-size: 11px;
}

.video-action.active,
.video-action.active > span {
  color: var(--vn-primary-dark);
}

.video-action.favorite.active,
.video-action.favorite.active > span {
  color: #f5a623;
}

.description-card,
.comment-section {
  margin-top: 18px;
  border: 1px solid var(--vn-border-light);
  border-radius: 12px;
  background: #fff;
}

.description-card {
  padding: 20px 22px;
}

.description-card__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.description-card h2,
.comment-header h2 {
  margin: 0;
  font-size: 18px;
}

.description-card__title span {
  padding: 3px 8px;
  border-radius: 5px;
  background: #f1f2f3;
  color: var(--vn-text-muted);
  font-size: 11px;
}

.description-card p {
  margin: 12px 0 0;
  color: var(--vn-text-secondary);
  font-size: 14px;
  line-height: 1.9;
  white-space: pre-wrap;
}

.comment-section {
  padding: 26px 28px;
}

.comment-header {
  margin-bottom: 22px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}

.section-kicker {
  color: var(--vn-primary);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 1.4px;
}

.comment-header h2 {
  margin-top: 3px;
}

.comment-header > span {
  color: var(--vn-text-muted);
  font-size: 13px;
}

.comment-editor {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
  margin-bottom: 24px;
  padding: 16px;
  border-radius: 11px;
  background: #f6f7f8;
}

.editor-avatar,
.comment-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--vn-primary), #6ddcff);
  color: #fff;
  font-weight: 700;
}

.editor-avatar {
  width: 38px;
  height: 38px;
}

.comment-editor :deep(.el-textarea__inner) {
  min-height: 82px !important;
  border: 0;
  background: #fff;
}

.comment-editor .el-button {
  min-height: 38px;
}

.comment-list {
  border-top: 1px solid var(--vn-border-light);
}

.comment-item {
  padding: 22px 0;
  display: flex;
  gap: 13px;
  border-bottom: 1px solid var(--vn-border-light);
}

.comment-avatar {
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  font-size: 14px;
}

.comment-main {
  min-width: 0;
  flex: 1;
}

.comment-name {
  color: var(--vn-text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.comment-main > p {
  margin: 8px 0 6px;
  color: var(--vn-text);
  font-size: 14px;
  line-height: 1.75;
}

.comment-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  color: var(--vn-text-muted);
  font-size: 12px;
}

.reply-area {
  margin-top: 12px;
  padding: 13px 15px;
  border: 1px solid var(--vn-border-light);
  border-radius: 9px;
  background: #f7f8fa;
}

.reply-item {
  padding: 8px 0;
  color: var(--vn-text-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.reply-item strong {
  color: var(--vn-primary-dark);
}

.reply-editor {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}

.comment-pagination {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

.watch-sidebar {
  position: sticky;
  top: 88px;
  display: grid;
  gap: 14px;
}

.author-card,
.media-panel,
.community-note {
  overflow: hidden;
  border: 1px solid var(--vn-border-light);
  border-radius: 12px;
  background: #fff;
}

.author-card__cover {
  height: 76px;
  background:
    radial-gradient(circle at 78% 0%, rgb(251 114 153 / 40%), transparent 42%),
    linear-gradient(135deg, #c7f2ff, #eaf9ff 55%, #fff0f5);
}

.author-card__body {
  padding: 0 20px 20px;
  text-align: center;
}

.avatar {
  width: 66px;
  height: 66px;
  margin: -33px auto 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 4px solid #fff;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--vn-primary), #62d9ff);
  color: #fff;
  font-size: 25px;
  font-weight: 800;
}

.author-card__identity strong,
.author-card__identity span {
  display: block;
}

.author-card__identity strong {
  font-size: 16px;
}

.author-card__identity span {
  margin-top: 3px;
  color: var(--vn-text-muted);
  font-size: 12px;
}

.author-card__body p {
  margin: 13px 0;
  color: var(--vn-text-secondary);
  font-size: 12px;
  line-height: 1.65;
}

.follow-button {
  width: 100%;
}

.media-panel {
  padding: 18px;
}

.side-panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.side-panel-title h3 {
  margin: 0;
  font-size: 15px;
}

.side-panel-title span {
  padding: 3px 7px;
  border-radius: 5px;
  background: var(--vn-primary-soft);
  color: var(--vn-primary-dark);
  font-size: 11px;
  font-weight: 700;
}

.media-panel dl {
  margin: 14px 0 0;
}

.media-panel dl > div {
  padding: 10px 0;
  border-top: 1px solid var(--vn-border-light);
}

.media-panel dt {
  margin-bottom: 4px;
  color: var(--vn-text-muted);
  font-size: 11px;
}

.media-panel dd {
  margin: 0;
  color: var(--vn-text-secondary);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.community-note {
  padding: 16px 18px;
  background: linear-gradient(135deg, #effbff, #fff);
}

.community-note strong {
  color: var(--vn-primary-dark);
  font-size: 13px;
}

.community-note p {
  margin: 6px 0 0;
  color: var(--vn-text-muted);
  font-size: 11px;
  line-height: 1.65;
}

@media (max-width: 1100px) {
  .container {
    width: min(100% - 40px, 1000px);
  }

  .watch-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .watch-sidebar {
    position: static;
    grid-template-columns: 1fr 1fr;
  }

  .community-note {
    display: none;
  }
}

@media (max-width: 700px) {
  .container {
    width: min(100% - 24px, 1000px);
    padding-top: 18px;
  }

  .video-heading h1 {
    font-size: 21px;
  }

  .quality-switcher {
    position: static;
    justify-content: space-between;
    border: 0;
    border-radius: 0;
    background: #151515;
  }

  .video-action-bar {
    justify-content: space-between;
  }

  .video-action {
    min-width: 0;
    flex: 1;
    padding-inline: 8px;
  }

  .video-action small {
    display: none;
  }

  .comment-section,
  .description-card {
    padding: 20px 16px;
  }

  .comment-editor {
    grid-template-columns: minmax(0, 1fr);
  }

  .editor-avatar {
    display: none;
  }

  .comment-editor .el-button {
    width: 100%;
  }

  .reply-editor {
    flex-direction: column;
  }

  .watch-sidebar {
    grid-template-columns: 1fr;
  }
}
</style>
