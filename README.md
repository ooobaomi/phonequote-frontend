# PhoneQuote 前端与 Android 客户端

手机回收报价辅助系统的客户端仓库，基于代码审查修复版拆分。包括 Vue 管理后台和 Android OCR/悬浮报价客户端，业务 API 独立在后端仓库维护。

## 仓库结构

```text
admin-web/       Vue 3 + TypeScript + Element Plus 管理后台
app/             Android Java 客户端、OCR、报价表缓存与悬浮窗
app/src/test/    OCR 解析单元测试
gradle/          Gradle Wrapper
build.gradle     Android 根构建脚本
deploy/          管理后台 Nginx 镜像与 TLS 入口配置
docs/            联调及部署说明
```

## 管理后台本地运行

准备 Node.js 22 和 npm：

```sh
cd admin-web
npm ci
npm run dev
```

访问终端显示的 `/admin/` 地址。Vite 将 `/api` 转发到 `http://127.0.0.1:8000`，需先启动后端。前端认证使用后端颁发的 Bearer token，不在此仓库保存真实账号凭据。

```sh
npm run build
```

构建同时进行 TypeScript/Vue 类型检查，静态产物位于 `admin-web/dist/`。生产路径固定 `/admin/`；API 路径固定 `/api/v1`，由同域代理转发。TypeScript 配置为 noEmit，避免生成同名 JS 覆盖源代码解析。

## Android 构建

准备 JDK 17、Android SDK 35 与 Build-Tools 34；通过 Android Studio 配置 SDK，或设置 ANDROID_HOME。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
.\gradlew.bat assembleRelease -PphoneQuoteApiUrl=https://你的域名/api/v1/
```

Linux/macOS 可运行 `sh ./gradlew`。Debug 默认连接 Android 模拟器宿主地址 `http://10.0.2.2:8000/api/v1/`。Release 必须显式提供 HTTPS 地址且以 `/api/v1/` 结尾；未提供地址或提供 HTTP 会使构建失败。Release 禁止明文请求，debug 独立允许开发 HTTP。

Release 构建产物需要配置自己的签名后用于正式发布。本仓库不提交 APK、签名私钥、local.properties 或历史数据库。实体手机不能把 10.0.2.2 当作电脑地址；联调时需修改 debug 地址或配置自己的开发访问方式。

## 功能

- 管理后台：用户、团队、账号有效期、短信配置、报价转换任务、人工校对与发布。
- Android：登录/注册、团队与报价表管理、下载本地报价包、屏幕采集、OCR 解析与悬浮报价。
- Android OCR 解析基于 ML Kit，后端 OCR 转换由独立 worker 处理，二者不是同一个进程。

## 与后端一起部署

两个仓库克隆在同一父目录，保持目录名 phonequote-frontend 和 phonequote-backend。在后端目录执行 Docker Compose，它会使用本仓库的 `deploy/Dockerfile.web` 构建网页镜像；详情见 [联调与部署](docs/INTEGRATION.md)。

## 已验证

拆分前修复版：Android 20 项单测、Debug 与 Release 构建通过；管理前端类型检查与生产构建通过。Release 编译验证使用 example.invalid，不代表已配置真实域名。拆分后验证记录见 [验证说明](docs/VERIFICATION.md)。

未进行真实手机 UI、证书握手或生产业务数据回归。未指定开源许可证；公开可见不等于授予任意再分发许可。
