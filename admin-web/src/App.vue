<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Plus, Refresh, Search, SwitchButton } from '@element-plus/icons-vue'
import { api, errorMessage } from './api'

type Page = 'dashboard' | 'users' | 'sms' | 'jobs' | 'settings' | 'audit'

const token = ref(localStorage.getItem('phonequote_admin_token') || '')
const username = ref(localStorage.getItem('phonequote_admin_name') || 'admin')
const loginForm = reactive({ username: 'admin', password: '' })
const loginLoading = ref(false)
const page = ref<Page>('dashboard')
const loading = ref(false)
const summary = reactive({ users: 0, active_users: 0, queued_jobs: 0, review_jobs: 0, failed_jobs: 0 })
const users = ref<any[]>([])
const userQuery = ref('')
const jobs = ref<any[]>([])
const jobStatus = ref('')
const smsLogs = ref<any[]>([])
const auditLogs = ref<any[]>([])
const smsConfig = reactive({
  provider: 'TEST', enabled: true, code_ttl_seconds: 300, resend_seconds: 60,
  max_attempts: 5, daily_phone_limit: 10, daily_ip_limit: 50,
  signature: '', template_id: '', provider_config: {}
})
const systemConfig = reactive({ trial_hours: 12, support_phone: '', support_wechat: '', support_message: '' })
const editorVisible = ref(false)
const editorLoading = ref(false)
const jobDetail = ref<any>(null)
const sourceUrls = ref<string[]>([])
const quoteDialogVisible = ref(false)
const quoteDialogLoading = ref(false)
const quoteUser = ref<any>(null)
const userQuoteTables = ref<any[]>([])
const quoteLibrary = ref<any[]>([])
const libraryQuery = ref('')
const assignmentTableId = ref('')
const adminUploadFiles = ref<any[]>([])
const adminUploadProgress = ref(0)
const adminUploadShared = ref(false)
const teamDialogVisible = ref(false)
const teamDialogLoading = ref(false)
const teamDetail = ref<any>(null)
const teamForm = reactive({ name: '', member_limit: 10 })

const pageInfo = computed(() => ({
  dashboard: ['运营概览', '账号、队列与异常状态总览'],
  users: ['账号管理', '充值、拉黑、重置密码与会话控制'],
  sms: ['短信验证码', '测试通道参数与验证码发送日志'],
  jobs: ['转换监视', '查看队列、处理异常并人工校价'],
  settings: ['系统设置', '试用时长与人工客服联系方式'],
  audit: ['操作审计', '管理员关键操作追踪']
}[page.value]))

const nav = [
  { key: 'dashboard', label: '运营概览', icon: 'DataAnalysis' },
  { key: 'users', label: '账号管理', icon: 'User' },
  { key: 'sms', label: '短信验证码', icon: 'Message' },
  { key: 'jobs', label: '转换监视', icon: 'Files' },
  { key: 'settings', label: '系统设置', icon: 'Setting' },
  { key: 'audit', label: '操作审计', icon: 'DocumentChecked' }
] as const

function setSession(value: string, name: string) {
  token.value = value
  username.value = name
  localStorage.setItem('phonequote_admin_token', value)
  localStorage.setItem('phonequote_admin_name', name)
}

function logout() {
  localStorage.removeItem('phonequote_admin_token')
  localStorage.removeItem('phonequote_admin_name')
  token.value = ''
  loginForm.password = ''
}

async function login() {
  loginLoading.value = true
  try {
    const { data } = await api.post('/admin/auth/login', loginForm)
    setSession(data.access_token, data.username)
    await loadDashboard()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loginLoading.value = false
  }
}

async function switchPage(value: Page) {
  page.value = value
  if (value === 'dashboard') await loadDashboard()
  if (value === 'users') await loadUsers()
  if (value === 'sms') await loadSms()
  if (value === 'jobs') await loadJobs()
  if (value === 'settings') await loadSystemConfig()
  if (value === 'audit') await loadAudit()
}

async function loadDashboard() {
  try { Object.assign(summary, (await api.get('/admin/summary')).data) }
  catch (error) { ElMessage.error(errorMessage(error)) }
}

async function loadUsers() {
  loading.value = true
  try { users.value = (await api.get('/admin/users', { params: { query: userQuery.value } })).data }
  catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loading.value = false }
}

async function recharge(user: any) {
  try {
    const { value } = await ElMessageBox.prompt('输入增加的小时数，例如 24 或 720', `为 ${user.phone} 充值`, {
      confirmButtonText: '确认充值', cancelButtonText: '取消', inputPattern: /^\d+$/, inputErrorMessage: '请输入整数小时'
    })
    await api.post(`/admin/users/${user.id}/recharge`, { hours: Number(value) })
    ElMessage.success('充值成功')
    await loadUsers()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function setExpiry(user: any) {
  try {
    const { value } = await ElMessageBox.prompt('输入到期时间，例如 2026-08-31T23:59:00', `设置 ${user.phone} 到期时间`, {
      confirmButtonText: '保存', cancelButtonText: '取消', inputValue: formatInputDate(user.expires_at)
    })
    await api.post(`/admin/users/${user.id}/recharge`, { expires_at: value })
    ElMessage.success('到期时间已更新')
    await loadUsers()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function blacklist(user: any) {
  try {
    const { value } = await ElMessageBox.prompt('请填写拉黑原因', `拉黑 ${user.phone}`, {
      confirmButtonText: '确认拉黑', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '原因不能为空'
    })
    await api.post(`/admin/users/${user.id}/blacklist`, { reason: value })
    ElMessage.success('账号已拉黑')
    await loadUsers()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function unblacklist(user: any) {
  try {
    await ElMessageBox.confirm(`解除 ${user.phone} 的拉黑状态？`, '解除拉黑', { type: 'warning' })
    await api.post(`/admin/users/${user.id}/unblacklist`)
    ElMessage.success('账号已恢复')
    await loadUsers()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function resetPassword(user: any) {
  try {
    const { value } = await ElMessageBox.prompt('输入至少 8 位且包含字母和数字的临时密码', `重置 ${user.phone} 密码`, {
      confirmButtonText: '设置临时密码', cancelButtonText: '取消', inputType: 'password'
    })
    await api.post(`/admin/users/${user.id}/reset-password`, { temporary_password: value })
    ElMessage.success('临时密码已设置')
    await loadUsers()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function forceLogout(user: any) {
  try {
    await ElMessageBox.confirm(`让 ${user.phone} 的所有设备退出登录？`, '强制退出', { type: 'warning' })
    await api.post(`/admin/users/${user.id}/force-logout`)
    ElMessage.success('所有会话已退出')
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function enableTeam(user: any) {
  try {
    const { value } = await ElMessageBox.prompt('团队总人数包含主账号，默认 10 人', `为 ${user.name || user.phone} 开通团队`, {
      confirmButtonText: '确认开通', cancelButtonText: '取消', inputValue: '10',
      inputPattern: /^([1-9]|[1-9]\d|[1-4]\d\d|500)$/,
      inputErrorMessage: '请输入 1 至 500 的整数'
    })
    await api.post(`/admin/users/${user.id}/team-capability`, { enabled: true, member_limit: Number(value) })
    ElMessage.success('团队资格已开通，用户可在 App 创建团队')
    await loadUsers()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function openTeam(user: any) {
  if (!user.team_id) return ElMessage.info('该账号已具备团队资格，等待在 App 创建团队')
  teamDialogVisible.value = true
  teamDialogLoading.value = true
  try {
    teamDetail.value = (await api.get(`/admin/teams/${user.team_id}`)).data
    teamForm.name = teamDetail.value.name
    teamForm.member_limit = teamDetail.value.member_limit
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { teamDialogLoading.value = false }
}

async function saveTeam() {
  if (!teamDetail.value) return
  try {
    teamDetail.value = (await api.patch(`/admin/teams/${teamDetail.value.id}`, {
      name: teamForm.name,
      member_limit: teamForm.member_limit
    })).data
    ElMessage.success('团队设置已保存')
    await loadUsers()
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function toggleTeamStatus() {
  if (!teamDetail.value) return
  const next = teamDetail.value.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'
  const action = next === 'PAUSED' ? '暂停' : '恢复'
  try {
    await ElMessageBox.confirm(`${action}后将影响团队全部成员，确认继续？`, `${action}团队`, { type: 'warning' })
    teamDetail.value = (await api.patch(`/admin/teams/${teamDetail.value.id}`, { status: next })).data
    ElMessage.success(`团队已${action}`)
    await loadUsers()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function openUserQuotes(user: any) {
  quoteUser.value = user
  quoteDialogVisible.value = true
  adminUploadFiles.value = []
  adminUploadShared.value = false
  assignmentTableId.value = ''
  await Promise.all([loadUserQuoteTables(), loadQuoteLibrary()])
}

async function loadUserQuoteTables() {
  if (!quoteUser.value) return
  quoteDialogLoading.value = true
  try { userQuoteTables.value = (await api.get(`/admin/users/${quoteUser.value.id}/quote-tables`)).data }
  catch (error) { ElMessage.error(errorMessage(error)) }
  finally { quoteDialogLoading.value = false }
}

async function loadQuoteLibrary() {
  try { quoteLibrary.value = (await api.get('/admin/quote-tables/library', { params: { query: libraryQuery.value } })).data }
  catch (error) { ElMessage.error(errorMessage(error)) }
}

async function uploadForUser() {
  const files = adminUploadFiles.value.map((value: any) => value.raw).filter(Boolean) as File[]
  if (!files.length) return ElMessage.warning('请先选择报价图片或文件')
  const total = files.reduce((sum, file) => sum + file.size, 0)
  if (files.length > 30 || total > 200 * 1024 * 1024) return ElMessage.error('最多30个文件且总大小不能超过200MB')
  const body = new FormData()
  body.append('name', '后台代上传报价表')
  body.append('share_with_team', String(adminUploadShared.value))
  files.forEach((file) => body.append('files', file, file.name))
  adminUploadProgress.value = 1
  try {
    await api.post(`/admin/users/${quoteUser.value.id}/quote-tables/uploads`, body, {
      onUploadProgress: (event) => { adminUploadProgress.value = event.total ? Math.round(event.loaded * 100 / event.total) : 0 }
    })
    ElMessage.success('报价表已上传并进入转换队列')
    adminUploadFiles.value = []
    await Promise.all([loadUserQuoteTables(), loadUsers()])
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { adminUploadProgress.value = 0 }
}

async function assignQuoteTable() {
  if (!assignmentTableId.value) return ElMessage.warning('请选择要分配的已发布报价表')
  try {
    await ElMessageBox.confirm('将复制一份独立报价表给该账号，确认继续？', '分配报价表', { type: 'warning' })
    await api.post(`/admin/users/${quoteUser.value.id}/quote-tables/assign`, { source_table_id: assignmentTableId.value })
    ElMessage.success('独立报价表副本已分配')
    assignmentTableId.value = ''
    await Promise.all([loadUserQuoteTables(), loadQuoteLibrary(), loadUsers()])
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function deleteUserCloudQuote(table: any) {
  if (!quoteUser.value) return
  try {
    await ElMessageBox.confirm(
      `将删除“${table.name}”的云端报价、共享关系和所有成员选择记录；用户手机上的已下载副本不会被远程删除。`,
      '删除云端报价表',
      { type: 'error', confirmButtonText: '删除云端', cancelButtonText: '取消' }
    )
    await api.delete(`/admin/users/${quoteUser.value.id}/quote-tables/${table.id}/cloud`)
    ElMessage.success('云端报价表已删除')
    await Promise.all([loadUserQuoteTables(), loadQuoteLibrary(), loadUsers(), loadAudit()])
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function loadSms() {
  loading.value = true
  try {
    Object.assign(smsConfig, (await api.get('/admin/sms/config')).data)
    smsLogs.value = (await api.get('/admin/sms/logs')).data
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loading.value = false }
}

async function saveSmsConfig() {
  try {
    await api.put('/admin/sms/config', smsConfig)
    ElMessage.success('短信配置已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function loadJobs() {
  loading.value = true
  try { jobs.value = (await api.get('/admin/jobs', { params: { status: jobStatus.value } })).data }
  catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loading.value = false }
}

function clearSourceUrls() {
  sourceUrls.value.forEach(URL.revokeObjectURL)
  sourceUrls.value = []
}

async function openJob(job: any) {
  editorVisible.value = true
  editorLoading.value = true
  clearSourceUrls()
  try {
    const { data } = await api.get(`/admin/jobs/${job.id}`)
    data.items = data.items.map((item: any) => ({
      ...item,
      aliasesText: (item.aliases || []).join('，'),
      gradesText: (item.grades || []).map((grade: any) => `${grade.name}:${grade.price}`).join('，')
    }))
    jobDetail.value = data
    for (const file of data.files.filter((value: any) => value.mime_type.startsWith('image/'))) {
      const response = await api.get(`/admin/jobs/${job.id}/files/${file.id}`, { responseType: 'blob' })
      sourceUrls.value.push(URL.createObjectURL(response.data))
    }
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { editorLoading.value = false }
}

function parseGrades(text: string) {
  return text.split(/[，,]/).filter(Boolean).map((value) => {
    const parts = value.split(/[:：]/)
    return { name: parts[0]?.trim(), price: Number(parts[1]) }
  })
}

async function saveJobItems() {
  try {
    const items = jobDetail.value.items.map((item: any) => ({
      id: item.id,
      brand: item.brand,
      model: item.model,
      aliases: item.aliasesText.split(/[，,]/).map((v: string) => v.trim()).filter(Boolean),
      storage: item.storage,
      configuration: item.configuration,
      grades: parseGrades(item.gradesText),
      confidence: item.confidence,
      source_file_id: item.source_file_id,
      source_row: item.source_row
    }))
    await api.put(`/admin/jobs/${jobDetail.value.id}/items`, { items })
    ElMessage.success('校价草稿已保存')
    await openJob(jobDetail.value)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function publishJob() {
  try {
    await ElMessageBox.confirm('发布后 App 将可以下载此版本，确认发布？', '发布报价表', { type: 'warning' })
    await api.post(`/admin/jobs/${jobDetail.value.id}/publish`)
    ElMessage.success('报价表已发布')
    editorVisible.value = false
    await loadJobs()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function retryJob(job: any) {
  try {
    await api.post(`/admin/jobs/${job.id}/retry`)
    ElMessage.success('任务已重新排队')
    await loadJobs()
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function cancelJob(job: any) {
  try {
    await ElMessageBox.confirm('确认取消此任务？', '取消任务', { type: 'warning' })
    await api.post(`/admin/jobs/${job.id}/cancel`)
    ElMessage.success('任务已取消')
    await loadJobs()
  } catch (error: any) { if (error !== 'cancel') ElMessage.error(errorMessage(error)) }
}

async function loadSystemConfig() {
  try { Object.assign(systemConfig, (await api.get('/admin/system/config')).data) }
  catch (error) { ElMessage.error(errorMessage(error)) }
}

async function saveSystemConfig() {
  try {
    await api.put('/admin/system/config', systemConfig)
    ElMessage.success('系统设置已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

async function loadAudit() {
  loading.value = true
  try { auditLogs.value = (await api.get('/admin/audit-logs')).data }
  catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loading.value = false }
}

function statusLabel(status: string) {
  return ({ ACTIVE: '正常', PAUSED: '已暂停', BLACKLISTED: '已拉黑', QUEUED: '排队中', OCR: 'OCR 识别', NORMALIZING: '格式转换', REVIEW_REQUIRED: '待人工审核', READY: '已发布', EXPIRED: '云端已过期', FAILED: '失败', CANCELLED: '已取消' } as any)[status] || status
}

function accountTypeLabel(type: string) {
  return ({ PERSONAL: '个人账号', TEAM_OWNER_PENDING: '待创建团队', TEAM_OWNER: '团队主账号', TEAM_MEMBER: '团队子账号' } as any)[type] || type
}

function formatDate(value: string) { return value ? new Date(value + (value.endsWith('Z') ? '' : 'Z')).toLocaleString('zh-CN', { hour12: false }) : '—' }
function formatInputDate(value: string) { return value ? new Date(value + (value.endsWith('Z') ? '' : 'Z')).toISOString().slice(0, 19) : '' }
function remaining(seconds: number) { const h = Math.floor(seconds / 3600); return `${Math.floor(h / 24)} 天 ${h % 24} 小时` }
function fileSize(bytes: number) { return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${(bytes / 1024).toFixed(1)} KB` }
function cloudTime(value: string, available: boolean) {
  if (!available) return '云端已过期'
  const seconds = Math.max(0, Math.floor((new Date(value + (value?.endsWith('Z') ? '' : 'Z')).getTime() - Date.now()) / 1000))
  const hours = Math.floor(seconds / 3600)
  return `剩余 ${hours} 小时 ${Math.floor((seconds % 3600) / 60)} 分`
}

onMounted(async () => {
  window.addEventListener('admin-session-expired', logout)
  if (token.value) await loadDashboard()
})
onBeforeUnmount(() => {
  window.removeEventListener('admin-session-expired', logout)
  clearSourceUrls()
})
</script>

<template>
  <div v-if="!token" class="login-page">
    <section class="login-intro">
      <span class="eyebrow">PHONE QUOTE OPERATIONS</span>
      <h1>让账号与报价表转换<br>始终清楚可控</h1>
      <p>集中管理试用时间、账号状态、短信验证码和报价表 OCR 队列。异常数据进入人工校价，发布后同步到用户手机。</p>
    </section>
    <section class="login-panel">
      <div class="login-card card">
        <h2>管理员登录</h2>
        <p class="hint">请输入独立的后台管理员账号</p>
        <el-form label-position="top" @submit.prevent="login">
          <el-form-item label="管理员账号"><el-input v-model="loginForm.username" size="large" /></el-form-item>
          <el-form-item label="密码"><el-input v-model="loginForm.password" type="password" size="large" show-password @keyup.enter="login" /></el-form-item>
          <el-button type="primary" size="large" style="width:100%" :loading="loginLoading" @click="login">登录后台</el-button>
        </el-form>
      </div>
    </section>
  </div>

  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark">报</div><div><h1>回收报价后台</h1><p>运营与转换中心</p></div></div>
      <nav class="nav">
        <button v-for="item in nav" :key="item.key" :class="{active: page === item.key}" @click="switchPage(item.key)">
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
        </button>
      </nav>
    </aside>
    <main class="main">
      <header class="topbar">
        <div><h2>{{ pageInfo[0] }}</h2><p>{{ pageInfo[1] }}</p></div>
        <div class="toolbar"><span class="muted">{{ username }}</span><el-button :icon="SwitchButton" @click="logout">退出</el-button></div>
      </header>

      <template v-if="page === 'dashboard'">
        <div class="metric-grid">
          <div class="metric card"><div class="label">注册账号</div><div class="value">{{ summary.users }}</div></div>
          <div class="metric card"><div class="label">有效账号</div><div class="value">{{ summary.active_users }}</div></div>
          <div class="metric card"><div class="label">队列处理中</div><div class="value accent">{{ summary.queued_jobs }}</div></div>
          <div class="metric card"><div class="label">等待人工审核</div><div class="value">{{ summary.review_jobs }}</div></div>
          <div class="metric card"><div class="label">失败任务</div><div class="value">{{ summary.failed_jobs }}</div></div>
        </div>
        <div class="card card-pad" style="margin-top:18px">
          <div class="section-title"><h3>当前运行策略</h3></div>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="OCR 并发">单 Worker 顺序处理</el-descriptions-item>
            <el-descriptions-item label="默认试用">12 小时</el-descriptions-item>
            <el-descriptions-item label="云端数据保留">发布后 24 小时</el-descriptions-item>
            <el-descriptions-item label="正常结果">自动发布</el-descriptions-item>
            <el-descriptions-item label="异常结果">人工校价发布</el-descriptions-item>
            <el-descriptions-item label="App 行为">下载导入后手动切换</el-descriptions-item>
          </el-descriptions>
        </div>
      </template>

      <section v-if="page === 'users'" class="card card-pad">
        <div class="section-title"><h3>用户账号</h3><div class="toolbar"><el-input v-model="userQuery" clearable placeholder="搜索姓名或手机号" style="width:240px" @keyup.enter="loadUsers" /><el-button type="primary" :icon="Search" @click="loadUsers">查询</el-button></div></div>
        <el-table v-loading="loading" :data="users" stripe>
          <el-table-column prop="name" label="姓名" width="120"><template #default="scope">{{ scope.row.name || '待补全' }}</template></el-table-column>
          <el-table-column prop="phone" label="手机号" width="135" />
          <el-table-column label="账号类型" width="120"><template #default="scope">{{ accountTypeLabel(scope.row.account_type) }}</template></el-table-column>
          <el-table-column label="团队" min-width="150"><template #default="scope"><span>{{ scope.row.team_name || '—' }}</span><div v-if="scope.row.team_status" class="muted">{{ statusLabel(scope.row.team_status) }}</div></template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="scope"><span :class="`status-dot status-${scope.row.status}`"></span>{{ statusLabel(scope.row.status) }}</template></el-table-column>
          <el-table-column label="剩余时间" width="130"><template #default="scope">{{ remaining(scope.row.remaining_seconds) }}<div v-if="scope.row.expiry_source === 'TEAM_OWNER'" class="muted">随主账号</div></template></el-table-column>
          <el-table-column label="到期时间" width="180"><template #default="scope">{{ formatDate(scope.row.expires_at) }}</template></el-table-column>
          <el-table-column prop="quote_table_count" label="报价表" width="80" />
          <el-table-column label="最后登录" min-width="180"><template #default="scope">{{ formatDate(scope.row.last_login_at) }}<div class="muted">{{ scope.row.last_login_ip || '' }}</div></template></el-table-column>
          <el-table-column label="操作" width="510" fixed="right"><template #default="scope">
            <el-button link type="primary" @click="openUserQuotes(scope.row)">报价表</el-button>
            <el-button v-if="scope.row.account_type === 'PERSONAL'" link type="primary" @click="enableTeam(scope.row)">开通团队</el-button>
            <el-button v-if="scope.row.account_type !== 'PERSONAL'" link type="primary" @click="openTeam(scope.row)">团队详情</el-button>
            <el-button v-if="scope.row.account_type !== 'TEAM_MEMBER'" link type="primary" @click="recharge(scope.row)">充值</el-button><el-button v-if="scope.row.account_type !== 'TEAM_MEMBER'" link @click="setExpiry(scope.row)">设到期</el-button>
            <el-button v-if="scope.row.status === 'ACTIVE'" link type="danger" @click="blacklist(scope.row)">拉黑</el-button><el-button v-else link type="success" @click="unblacklist(scope.row)">解封</el-button>
            <el-button link @click="resetPassword(scope.row)">重置密码</el-button><el-button link @click="forceLogout(scope.row)">强退</el-button>
          </template></el-table-column>
        </el-table>
      </section>

      <template v-if="page === 'sms'">
        <section class="card card-pad">
          <div class="section-title"><h3>验证码通道配置</h3><el-button type="primary" :icon="Check" @click="saveSmsConfig">保存配置</el-button></div>
          <el-form label-position="top" class="form-grid">
            <el-form-item label="服务商"><el-select v-model="smsConfig.provider" style="width:100%"><el-option label="测试通道" value="TEST" /><el-option label="阿里云短信（预留）" value="ALIYUN" /><el-option label="腾讯云短信（预留）" value="TENCENT" /></el-select></el-form-item>
            <el-form-item label="通道状态"><el-switch v-model="smsConfig.enabled" active-text="启用" inactive-text="暂停" /></el-form-item>
            <el-form-item label="验证码有效期（秒）"><el-input-number v-model="smsConfig.code_ttl_seconds" :min="60" :max="1800" style="width:100%" /></el-form-item>
            <el-form-item label="重复发送间隔（秒）"><el-input-number v-model="smsConfig.resend_seconds" :min="10" :max="600" style="width:100%" /></el-form-item>
            <el-form-item label="最大验证错误次数"><el-input-number v-model="smsConfig.max_attempts" :min="1" :max="20" style="width:100%" /></el-form-item>
            <el-form-item label="单手机号每日上限"><el-input-number v-model="smsConfig.daily_phone_limit" :min="1" style="width:100%" /></el-form-item>
            <el-form-item label="单 IP 每日上限"><el-input-number v-model="smsConfig.daily_ip_limit" :min="1" style="width:100%" /></el-form-item>
            <el-form-item label="短信签名"><el-input v-model="smsConfig.signature" placeholder="接入真实通道时填写" /></el-form-item>
            <el-form-item label="模板 ID"><el-input v-model="smsConfig.template_id" placeholder="接入真实通道时填写" /></el-form-item>
          </el-form>
        </section>
        <section class="card card-pad" style="margin-top:18px">
          <div class="section-title"><h3>测试验证码日志</h3><el-button :icon="Refresh" @click="loadSms">刷新</el-button></div>
          <el-table v-loading="loading" :data="smsLogs" stripe><el-table-column prop="phone" label="手机号" width="140" /><el-table-column prop="provider" label="通道" width="100" /><el-table-column prop="test_code" label="测试验证码" width="130" /><el-table-column prop="status" label="状态" width="100" /><el-table-column prop="message" label="说明" /><el-table-column label="时间" width="180"><template #default="scope">{{ formatDate(scope.row.created_at) }}</template></el-table-column></el-table>
        </section>
      </template>

      <section v-if="page === 'jobs'" class="card card-pad">
        <div class="section-title"><h3>报价表转换任务</h3><div class="toolbar"><el-select v-model="jobStatus" clearable placeholder="全部状态" style="width:170px" @change="loadJobs"><el-option v-for="state in ['QUEUED','OCR','NORMALIZING','REVIEW_REQUIRED','READY','FAILED','CANCELLED']" :key="state" :label="statusLabel(state)" :value="state" /></el-select><el-button :icon="Refresh" @click="loadJobs">刷新</el-button></div></div>
        <el-table v-loading="loading" :data="jobs" stripe>
          <el-table-column prop="table_name" label="报价表" min-width="180" /><el-table-column prop="owner_phone" label="用户" width="135" />
          <el-table-column label="状态" width="140"><template #default="scope"><span :class="`status-dot status-${scope.row.status}`"></span>{{ statusLabel(scope.row.status) }}</template></el-table-column>
          <el-table-column label="进度" width="160"><template #default="scope"><el-progress :percentage="scope.row.progress" :stroke-width="8" /></template></el-table-column>
          <el-table-column prop="file_count" label="文件" width="70" /><el-table-column prop="message" label="当前阶段" min-width="180" /><el-table-column label="创建时间" width="180"><template #default="scope">{{ formatDate(scope.row.created_at) }}</template></el-table-column>
          <el-table-column label="操作" width="210" fixed="right"><template #default="scope"><el-button link type="primary" @click="openJob(scope.row)">查看 / 校价</el-button><el-button v-if="['FAILED','CANCELLED','REVIEW_REQUIRED'].includes(scope.row.status)" link @click="retryJob(scope.row)">重试</el-button><el-button v-if="['QUEUED','REVIEW_REQUIRED'].includes(scope.row.status)" link type="danger" @click="cancelJob(scope.row)">取消</el-button></template></el-table-column>
        </el-table>
      </section>

      <section v-if="page === 'settings'" class="card card-pad">
        <div class="section-title"><h3>注册与客服设置</h3><el-button type="primary" :icon="Check" @click="saveSystemConfig">保存设置</el-button></div>
        <el-form label-position="top" style="max-width:720px">
          <el-form-item label="新用户默认试用时长（小时）"><el-input-number v-model="systemConfig.trial_hours" :min="0" :max="8760" style="width:100%" /></el-form-item>
          <el-form-item label="人工客服电话"><el-input v-model="systemConfig.support_phone" placeholder="显示在 App 忘记密码弹窗" /></el-form-item>
          <el-form-item label="客服微信"><el-input v-model="systemConfig.support_wechat" placeholder="显示在 App 忘记密码弹窗" /></el-form-item>
          <el-form-item label="忘记密码提示"><el-input v-model="systemConfig.support_message" type="textarea" :rows="4" /></el-form-item>
        </el-form>
      </section>

      <section v-if="page === 'audit'" class="card card-pad">
        <div class="section-title"><h3>管理员操作记录</h3><el-button :icon="Refresh" @click="loadAudit">刷新</el-button></div>
        <el-table v-loading="loading" :data="auditLogs" stripe><el-table-column prop="action" label="动作" width="190" /><el-table-column prop="target_type" label="对象类型" width="120" /><el-table-column prop="target_id" label="对象 ID" min-width="230" /><el-table-column prop="ip_address" label="IP" width="140" /><el-table-column label="详情" min-width="240"><template #default="scope"><code>{{ JSON.stringify(scope.row.detail) }}</code></template></el-table-column><el-table-column label="时间" width="180"><template #default="scope">{{ formatDate(scope.row.created_at) }}</template></el-table-column></el-table>
      </section>
    </main>

    <el-dialog v-model="editorVisible" title="转换结果校对与发布" width="94%" top="3vh" destroy-on-close @closed="clearSourceUrls">
      <div v-loading="editorLoading" class="editor-grid" v-if="jobDetail">
        <aside class="source-pane">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="用户">{{ jobDetail.owner_phone }}</el-descriptions-item><el-descriptions-item label="状态">{{ statusLabel(jobDetail.status) }}</el-descriptions-item><el-descriptions-item label="置信度">{{ Math.round(jobDetail.confidence * 100) }}%</el-descriptions-item>
          </el-descriptions>
          <div style="margin-top:12px" v-for="(url, index) in sourceUrls" :key="url"><img :src="url" :alt="`原始报价图 ${index + 1}`" /></div>
          <el-collapse style="margin-top:12px"><el-collapse-item title="查看 OCR 原文"><div class="ocr-text">{{ jobDetail.raw_ocr_text || '暂无 OCR 原文' }}</div></el-collapse-item></el-collapse>
        </aside>
        <section style="overflow:auto">
          <el-alert v-if="jobDetail.error" :title="jobDetail.error" type="warning" :closable="false" style="margin-bottom:12px" />
          <el-table :data="jobDetail.items" border height="600">
            <el-table-column label="品牌" width="100"><template #default="scope"><el-input v-model="scope.row.brand" /></template></el-table-column>
            <el-table-column label="标准型号" width="190"><template #default="scope"><el-input v-model="scope.row.model" /></template></el-table-column>
            <el-table-column label="别名" width="170"><template #default="scope"><el-input v-model="scope.row.aliasesText" placeholder="逗号分隔" /></template></el-table-column>
            <el-table-column label="存储" width="100"><template #default="scope"><el-input v-model="scope.row.storage" /></template></el-table-column>
            <el-table-column label="完整配置" width="120"><template #default="scope"><el-input v-model="scope.row.configuration" /></template></el-table-column>
            <el-table-column label="成色价格（名称:价格，逗号分隔）" min-width="380"><template #default="scope"><el-input v-model="scope.row.gradesText" /></template></el-table-column>
            <el-table-column label="置信度" width="90"><template #default="scope">{{ Math.round(scope.row.confidence * 100) }}%</template></el-table-column>
            <el-table-column width="70"><template #default="scope"><el-button link type="danger" @click="jobDetail.items.splice(scope.$index, 1)">删除</el-button></template></el-table-column>
          </el-table>
        </section>
      </div>
      <template #footer><el-button @click="editorVisible = false">关闭</el-button><el-button :icon="Plus" @click="jobDetail.items.push({brand:'',model:'',aliasesText:'',storage:'',configuration:'',gradesText:'靓机:0',confidence:1})">增加一行</el-button><el-button @click="saveJobItems">保存草稿</el-button><el-button type="primary" @click="publishJob">校验并发布</el-button></template>
    </el-dialog>

    <el-dialog v-model="quoteDialogVisible" :title="`账号报价表 · ${quoteUser?.phone || ''}`" width="86%" top="5vh">
      <div class="form-grid">
        <section class="card card-pad">
          <h3>代用户上传并识别</h3>
          <p class="muted">支持图片、PDF、Excel、CSV 和 JSON，最多30个文件、总计200MB。</p>
          <el-upload v-model:file-list="adminUploadFiles" :auto-upload="false" multiple :limit="30" style="margin-top:12px">
            <el-button>选择报价文件</el-button>
          </el-upload>
          <el-switch v-if="quoteUser?.team_id" v-model="adminUploadShared" active-text="共享到该账号团队" style="margin-top:12px" />
          <el-progress v-if="adminUploadProgress" :percentage="adminUploadProgress" style="margin-top:12px" />
          <el-button type="primary" style="margin-top:12px" @click="uploadForUser">上传并进入转换队列</el-button>
        </section>
        <section class="card card-pad">
          <h3>分配已有报价表</h3>
          <p class="muted">只显示云端仍在24小时有效期内的已发布报价表，分配后生成独立副本。</p>
          <div class="toolbar" style="margin-top:12px">
            <el-input v-model="libraryQuery" clearable placeholder="名称或所属手机号" @keyup.enter="loadQuoteLibrary" />
            <el-button :icon="Search" @click="loadQuoteLibrary">搜索</el-button>
          </div>
          <el-select v-model="assignmentTableId" filterable placeholder="选择报价表" style="width:100%;margin-top:12px">
            <el-option v-for="table in quoteLibrary" :key="table.id" :value="table.id" :label="`${table.name} · ${table.owner_phone} · ${cloudTime(table.cloud_expires_at, table.cloud_available)}`" />
          </el-select>
          <el-button type="primary" style="margin-top:12px" @click="assignQuoteTable">复制并分配给此账号</el-button>
        </section>
      </div>
      <el-divider>该账号的报价表</el-divider>
      <el-table v-loading="quoteDialogLoading" :data="userQuoteTables" stripe max-height="430">
        <el-table-column prop="name" label="名称" min-width="210" />
        <el-table-column prop="source_type" label="来源" width="110" />
        <el-table-column prop="uploader_name" label="上传者" width="120" />
        <el-table-column label="范围" width="100"><template #default="scope">{{ scope.row.shared_with_team ? '团队共享' : '私人' }}</template></el-table-column>
        <el-table-column prop="brand_summary" label="品牌和条目" min-width="180" />
        <el-table-column label="状态" width="120"><template #default="scope">{{ statusLabel(scope.row.status) }}</template></el-table-column>
        <el-table-column label="云端可用" width="170"><template #default="scope"><span :class="scope.row.cloud_available ? 'success-text' : 'danger-text'">{{ cloudTime(scope.row.cloud_expires_at, scope.row.cloud_available) }}</span></template></el-table-column>
        <el-table-column label="App选择" width="90"><template #default="scope">{{ scope.row.active ? `优先级 ${scope.row.selection_order}` : '未启用' }}</template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="scope"><el-button link type="danger" @click="deleteUserCloudQuote(scope.row)">删除云端</el-button></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="quoteDialogVisible = false">关闭</el-button><el-button :icon="Refresh" @click="loadUserQuoteTables">刷新</el-button></template>
    </el-dialog>

    <el-dialog v-model="teamDialogVisible" title="团队管理" width="760px" destroy-on-close>
      <div v-loading="teamDialogLoading" v-if="teamDetail">
        <el-alert :title="teamDetail.status === 'ACTIVE' ? '团队当前正常使用' : '团队已暂停，全部成员会停止登录和识别'" :type="teamDetail.status === 'ACTIVE' ? 'success' : 'warning'" :closable="false" />
        <el-form label-position="top" class="form-grid" style="margin-top:18px">
          <el-form-item label="团队名称"><el-input v-model="teamForm.name" maxlength="100" /></el-form-item>
          <el-form-item label="总人数上限（包含主账号）"><el-input-number v-model="teamForm.member_limit" :min="teamDetail.member_count" :max="500" style="width:100%" /></el-form-item>
        </el-form>
        <div class="toolbar" style="justify-content:flex-end;margin-bottom:16px"><el-button @click="toggleTeamStatus" :type="teamDetail.status === 'ACTIVE' ? 'danger' : 'success'">{{ teamDetail.status === 'ACTIVE' ? '暂停团队' : '恢复团队' }}</el-button><el-button type="primary" @click="saveTeam">保存设置</el-button></div>
        <el-descriptions :column="3" border style="margin-bottom:16px"><el-descriptions-item label="主账号">{{ teamDetail.owner_name }}</el-descriptions-item><el-descriptions-item label="手机号">{{ teamDetail.owner_phone }}</el-descriptions-item><el-descriptions-item label="共享报价表">{{ teamDetail.shared_table_count }} 份</el-descriptions-item></el-descriptions>
        <el-table :data="teamDetail.members" stripe max-height="360"><el-table-column prop="name" label="姓名" /><el-table-column prop="phone" label="手机号" width="140" /><el-table-column label="角色" width="110"><template #default="scope">{{ scope.row.role === 'OWNER' ? '主账号' : '子账号' }}</template></el-table-column><el-table-column label="账号状态" width="110"><template #default="scope">{{ statusLabel(scope.row.status) }}</template></el-table-column><el-table-column label="加入时间" width="180"><template #default="scope">{{ formatDate(scope.row.joined_at) }}</template></el-table-column></el-table>
      </div>
      <template #footer><el-button @click="teamDialogVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>
