<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useRouter } from 'vue-router'
import { createVideo, uploadCover, uploadVideo } from '../api/creator-video'
import { getCategories, type VideoCategory } from '../api/video'

const router = useRouter()
const formRef = ref<FormInstance>()

const categories = ref<VideoCategory[]>([])
const categoryLoading = ref(false)
const coverUploading = ref(false)
const videoUploading = ref(false)
const submitting = ref(false)
const coverPreviewUrl = ref('')

const form = reactive({
  title: '',
  description: '',
  categoryId: undefined as number | undefined,
  coverObjectName: '',
  videoObjectName: '',
  duration: 0
})

const rules: FormRules = {
  title: [
    { required: true, message: '请输入视频标题', trigger: 'blur' },
    { max: 100, message: '标题不能超过 100 个字符', trigger: 'blur' }
  ],
  categoryId: [
    { required: true, message: '请选择投稿分区', trigger: 'change' }
  ],
  description: [
    { max: 2000, message: '简介不能超过 2000 个字符', trigger: 'blur' }
  ]
}

const durationText = computed(() => {
  if (!form.duration) {
    return '上传视频后自动识别时长'
  }

  const minutes = Math.floor(form.duration / 60)
  const seconds = form.duration % 60

  return `${minutes}:${String(seconds).padStart(2, '0')}`
})

function ensureLoggedIn() {
  if (localStorage.getItem('token')) {
    return true
  }

  ElMessage.warning('请先登录后再投稿')
  router.replace('/login')
  return false
}

async function loadCategories() {
  try {
    categoryLoading.value = true
    categories.value = await getCategories()
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '获取视频分区失败'
    )
  } finally {
    categoryLoading.value = false
  }
}

function validateImage(file: File) {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片格式的封面文件')
    return false
  }

  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('封面图片不能超过 10MB')
    return false
  }

  return true
}

async function handleCoverChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]

  if (!file || !validateImage(file)) {
    return
  }

  if (coverPreviewUrl.value) {
    URL.revokeObjectURL(coverPreviewUrl.value)
  }

  coverPreviewUrl.value = URL.createObjectURL(file)

  try {
    coverUploading.value = true
    form.coverObjectName = await uploadCover(file)
    ElMessage.success('封面上传成功')
  } catch (error) {
    form.coverObjectName = ''
    ElMessage.error(
      error instanceof Error ? error.message : '封面上传失败'
    )
  } finally {
    coverUploading.value = false
    input.value = ''
  }
}

function getVideoDuration(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const video = document.createElement('video')

    video.preload = 'metadata'

    video.onloadedmetadata = () => {
      URL.revokeObjectURL(url)
      resolve(Math.max(1, Math.round(video.duration)))
    }

    video.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('无法读取视频时长，请更换视频文件'))
    }

    video.src = url
  })
}

async function handleVideoChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]

  if (!file) {
    return
  }

  if (!file.type.startsWith('video/')) {
    ElMessage.error('请选择视频文件')
    return
  }

  if (file.size > 2 * 1024 * 1024 * 1024) {
    ElMessage.error('视频文件不能超过 2GB')
    return
  }

  try {
    videoUploading.value = true

    const [objectName, duration] = await Promise.all([
      uploadVideo(file),
      getVideoDuration(file)
    ])

    form.videoObjectName = objectName
    form.duration = duration

    ElMessage.success('视频上传成功')
  } catch (error) {
    form.videoObjectName = ''
    form.duration = 0

    ElMessage.error(
      error instanceof Error ? error.message : '视频上传失败'
    )
  } finally {
    videoUploading.value = false
    input.value = ''
  }
}

async function submit() {
  if (!formRef.value || !ensureLoggedIn()) {
    return
  }

  const valid = await formRef.value.validate().catch(() => false)

  if (!valid) {
    return
  }

  if (!form.coverObjectName) {
    ElMessage.warning('请先上传视频封面')
    return
  }

  if (!form.videoObjectName || !form.duration) {
    ElMessage.warning('请先上传视频文件')
    return
  }

  try {
    submitting.value = true

    await createVideo({
      categoryId: form.categoryId!,
      title: form.title.trim(),
      description: form.description.trim(),
      coverObjectName: form.coverObjectName,
      videoObjectName: form.videoObjectName,
      duration: form.duration
    })

    ElMessage.success('投稿已提交，等待管理员审核')
    router.push('/')
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : '投稿提交失败'
    )
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (ensureLoggedIn()) {
    loadCategories()
  }
})

onBeforeUnmount(() => {
  if (coverPreviewUrl.value) {
    URL.revokeObjectURL(coverPreviewUrl.value)
  }
})
</script>

<template>
  <main class="upload-page">
    <header class="header">
      <div class="header-content">
        <button class="logo" @click="router.push('/')">
          <span>▶</span>
          VideoNest
        </button>

        <el-button @click="router.push('/')">
          返回首页
        </el-button>
      </div>
    </header>

    <section class="upload-card">
      <div class="heading">
        <h1>发布视频</h1>
        <p>上传完成后将进入审核，审核通过后会显示在首页。</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
      >
        <el-form-item label="视频标题" prop="title">
          <el-input
            v-model="form.title"
            maxlength="100"
            show-word-limit
            placeholder="请输入标题"
          />
        </el-form-item>

        <el-form-item label="投稿分区" prop="categoryId">
          <el-select
            v-model="form.categoryId"
            :loading="categoryLoading"
            placeholder="请选择分区"
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
            v-model="form.description"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="介绍一下你的视频内容（可选）"
          />
        </el-form-item>

        <div class="file-grid">
          <div class="file-field">
            <label>
              视频封面
              <span class="required">*</span>
            </label>

            <div class="cover-upload">
              <img
                v-if="coverPreviewUrl"
                :src="coverPreviewUrl"
                alt="封面预览"
              />
              <span v-else>建议使用 16:9 图片</span>

              <div v-if="coverUploading" class="mask">
                正在上传…
              </div>
            </div>

            <label class="select-file">
              <input
                type="file"
                accept="image/*"
                :disabled="coverUploading"
                @change="handleCoverChange"
              />
              {{ form.coverObjectName ? '重新选择封面' : '选择封面图片' }}
            </label>
          </div>

          <div class="file-field">
            <label>
              视频文件
              <span class="required">*</span>
            </label>

            <div class="video-upload">
              <strong>
                {{ form.videoObjectName ? '视频已上传' : '选择视频文件' }}
              </strong>
              <span>{{ durationText }}</span>

              <div v-if="videoUploading" class="mask">
                正在上传，请勿离开此页面…
              </div>
            </div>

            <label class="select-file">
              <input
                type="file"
                accept="video/*"
                :disabled="videoUploading"
                @change="handleVideoChange"
              />
              {{ form.videoObjectName ? '重新选择视频' : '选择视频文件' }}
            </label>
          </div>
        </div>

        <div class="actions">
          <el-button size="large" @click="router.push('/')">
            取消
          </el-button>

          <el-button
            type="primary"
            size="large"
            :loading="submitting"
            :disabled="coverUploading || videoUploading"
            @click="submit"
          >
            提交投稿
          </el-button>
        </div>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.upload-page {
  min-height: 100vh;
  background: #f6f7f8;
  color: #18191c;
}

.header {
  height: 64px;
  background: #fff;
  border-bottom: 1px solid #e7e7e7;
}

.header-content {
  width: min(960px, calc(100% - 32px));
  height: 100%;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.logo {
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

.upload-card {
  width: min(760px, calc(100% - 32px));
  margin: 32px auto 48px;
  padding: 32px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 3px 12px rgb(0 0 0 / 4%);
}

.heading {
  margin-bottom: 28px;
}

.heading h1 {
  margin: 0 0 8px;
  font-size: 26px;
}

.heading p {
  margin: 0;
  color: #7a7f87;
  font-size: 14px;
}

.full-width {
  width: 100%;
}

.file-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  margin: 8px 0 30px;
}

.file-field > label:first-child {
  display: block;
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
}

.required {
  color: #f56c6c;
}

.cover-upload,
.video-upload {
  position: relative;
  overflow: hidden;
  display: grid;
  aspect-ratio: 16 / 9;
  place-items: center;
  border: 1px dashed #cdd0d6;
  border-radius: 8px;
  background: #f7f8fa;
  color: #909399;
}

.cover-upload img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.video-upload {
  align-content: center;
  gap: 8px;
}

.video-upload strong {
  color: #4e5969;
}

.mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgb(0 0 0 / 55%);
  color: #fff;
  font-size: 14px;
}

.select-file {
  display: inline-block;
  margin-top: 10px;
  color: #1677ff;
  font-size: 14px;
  cursor: pointer;
}

.select-file input {
  display: none;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

@media (max-width: 600px) {
  .upload-card {
    margin-top: 16px;
    padding: 22px;
  }

  .file-grid {
    grid-template-columns: 1fr;
    gap: 18px;
  }

  .actions .el-button {
    flex: 1;
  }
}
</style>