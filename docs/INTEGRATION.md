# 前后端联调与部署

## 配套目录

```text
workspace/
  phonequote-frontend/
    admin-web/
    app/
    deploy/Dockerfile.web
    deploy/nginx.conf
  phonequote-backend/
    server/
    deploy/Dockerfile.api
    deploy/Dockerfile.ocr
    deploy/tls/
    docker-compose.yml
    .env
```

在后端目录执行 Compose。web 的构建上下文指向 ../phonequote-frontend；API 和 worker 的构建上下文为后端根目录。将两个仓库一同下载不代表需要把源码重新混合到一个仓库。

## 请求链路

生产：浏览器 `/admin/` → Nginx → `/api/v1/*` → FastAPI；Android HTTPS → 同一入口。Vite 开发代理仅用于本机，生产不运行 Vite dev server。管理网页与 API 同域，因此通常不需要另配浏览器跨域地址。

Nginx 将 X-Forwarded-For 覆盖为实际对端，固定内网地址 172.30.80.10；API 仅信任这个地址。默认网络为 172.30.80.0/24。若环境冲突，同时修改后端 Compose 的网络/web 地址以及 Dockerfile.api 的 forwarded-allow-ips，不能改为信任所有来源。

证书路径位于后端 deploy/tls，挂载到 web 的 /etc/nginx/tls。请勿提交私钥、生产 .env、数据库或上传报价表到任何仓库。

## 接口约定

前缀 /api/v1。用户与管理员均使用 Authorization: Bearer <token>，两种令牌不能互换。客户端不应自己构造 JWT。注册先请求 /auth/sms/send，再 /auth/register；生产短信发送目前尚待真实提供商适配。报价表上传使用 multipart/form-data，文件列表 files，名称 name，共享标记 share_with_team。

上传返回 job_id（响应字段 id）及状态，客户端应轮询 /quote-tables/jobs/{job_id}；代理故障时可返回 QUEUED，数据库 outbox 会重试，不应立即重复上传。READY 后再请求 /quote-tables/{table_id}/download，核验 X-Content-SHA256。REVIEW_REQUIRED 需要管理员核对发布。

云端下载过期时间为 24 小时，已下载的手机本地副本按现有产品语义保留；云端删除不会自动擦除本地副本。退出用户登录会立即撤销旧令牌。

## 发布配置

Android release API 地址以 https:// 开头并以 /api/v1/ 结尾；证书域名必须匹配。后端 production APP_BASE_URL 使用同一 HTTPS 入口。首次部署和旧数据迁移流程见后端 FIXES.md；实际管理员密码与 JWT_SECRET 仅在本地 .env 配置。
