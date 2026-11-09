# 外媒新闻监测 Android APP

纯 Kotlin 原生 Android 应用（无 Python / 无 Chaquopy）。

- 包名：`com.example.newsmonitor`
- minSdk：24（Android 7.0）
- targetSdk / compileSdk：34（Android 14）
- 技术栈：Kotlin + XML + Fragment + BottomNavigationView + OkHttp + Jsoup + Room + Coroutines
- 目标设备：红米 K50 / K90（小米澎湃 OS，Android 13/14）

核心原则：**新闻是核心资产，翻译只是展示手段。关键词命中即入库，翻译失败绝不丢新闻。**

---

## 一、项目当前状态

所有代码文件均已写完：

- Room 数据库（websites / keywords / news / logs / config）
- 新闻抓取、时间转换（统一北京时间）、Google 翻译、双重关键词匹配
- 钉钉推送（2~3 秒间隔限流）、DeepSeek AI 推荐网站
- 前台 Service（`MonitorService`，`dataSync` 类型，常驻通知）
- 5 个底部 Tab 的全部 XML 布局与 Kotlin 逻辑
- 新闻详情页、复制内容、查看原文、CSV/TXT 导出

唯一没有放进仓库的是 Gradle Wrapper 的二进制文件 `gradle-wrapper.jar`
（以及 `gradlew` / `gradlew.bat`）。这**不影响云端打包**：下面的 GitHub Actions
工作流直接安装 Gradle 8.5 来构建，不依赖 `./gradlew`。

---

## 二、推荐方案：用 GitHub Actions 云端打包（电脑无需安装 Android Studio）

原理：把项目上传到 GitHub 仓库，GitHub 的云端服务器自动安装 JDK 17、Gradle 8.5 和
Android SDK，编译出 APK，然后你把 APK 下载到红米手机安装。

仓库中已经包含工作流文件：

```
.github/workflows/build-apk.yml
```

### 准备工作

1. 一个 GitHub 账号：https://github.com
2. 本项目文件夹：`E:\05，py---py\25\NewsMonitorAndroid`
3. 红米手机、数据线（或微信 / QQ / 网盘用于传文件）

### 步骤 1：把项目上传到 GitHub

#### 方式 A：用 GitHub Desktop（推荐，能完整上传 `.github` 隐藏目录）

1. 下载安装 GitHub Desktop：https://desktop.github.com
2. 登录 GitHub 账号。
3. 菜单 `File` → `Add local repository`。
4. 选择文件夹：`E:\05，py---py\25\NewsMonitorAndroid`
5. 如果提示 “not a git repository”，点击 `create a repository here`，然后点 `Create repository`。
6. 点击右上角 `Publish repository`。
   - 选 `Public`（公开仓库，Actions 分钟数不限）或 `Private`（私有仓库，免费额度
     每月 2000 分钟，本项目一次构建约 5~10 分钟）。
7. 等待上传完成。

#### 方式 B：用浏览器上传

1. 登录 GitHub，点右上角 `+` → `New repository`。
2. 仓库名例如 `NewsMonitorAndroid`，选择 Public 或 Private，点 `Create repository`。
3. 点 `Add file` → `Upload files`。
4. 把 **NewsMonitorAndroid 文件夹里面的内容**（不是外层文件夹本身）拖进浏览器：
   `app/`、`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、
   `gradle/`、`README.md` 等，必须位于仓库**根目录**。
5. 如果 `.github` 目录没有被上传（浏览器隐藏文件夹可能拖不进去）：
   - 点 `Add file` → `Create new file`
   - 文件名输入：`.github/workflows/build-apk.yml`
   - 把本项目里该文件的内容原样粘贴进去，点 `Commit changes`。
6. 点 `Commit changes` 完成上传。

> 重要：仓库根目录必须能直接看到 `app/` 和 `settings.gradle.kts`。
> 如果仓库里变成了 `NewsMonitorAndroid/app/...` 这种多一层目录，工作流会找不到项目。

### 步骤 2：等待 GitHub 云端编译

1. 打开仓库页面，点顶部 `Actions`。
2. 左侧选择 `Build APK`。
3. 可以点右侧 `Run workflow` → `Run workflow` 手动触发；
   上传 / 提交代码时也会自动触发（监听 `main` / `master` 分支）。
4. 首次构建通常 5~10 分钟。
5. 状态变成绿色对勾即成功；红色叉号表示失败，点进去看日志。

### 步骤 3：下载 APK

1. 点进成功的那一次运行。
2. 页面底部 `Artifacts` 区域，点击 `NewsMonitor-debug-apk` 下载 zip。
3. 解压 zip，得到 `app-debug.apk`。

说明：这是 **debug APK**，使用 Android 调试签名，**可以直接安装使用**，
但不要用于上架应用商店。后续如需正式 release 签名版，见第六节。

### 步骤 4：传到红米手机并安装

1. 用数据线、微信文件传输助手、QQ、网盘等把 `app-debug.apk` 传到手机。
2. 在手机文件管理器中点击 APK。
3. 首次安装会提示“允许安装未知应用”，选择允许（针对文件管理器或浏览器）。
4. 澎湃 OS 可能弹出“安全检测 / 纯净模式”提示：
   - 选择“继续安装”；
   - 若被拦截：设置 → 应用设置 → 应用管理 → 找到文件管理器/浏览器 →
     “安装未知应用” → 允许。
5. 安装完成后打开“外媒监测”。

---

## 三、安装后的必要设置（小米 / 澎湃 OS）

为了保证亮屏前台稳定运行 2~3 小时，请做以下设置：

1. **通知权限**：首次打开 APP 时允许通知（Android 13+ 必须）。
2. **省电策略**：
   设置 → 应用设置 → 应用管理 → 外媒监测 → 省电策略 → **无限制**。
3. **自启动**：应用信息 → 自启动 / 后台启动 → **允许**。
4. **后台运行**：允许后台运行；在最近任务里把 APP 卡片**上锁**（可选）。
5. **保持屏幕常亮**：把系统“自动锁屏”时间调长，或使用开发者选项中的
   “不锁定屏幕”（仅充电时）。APP 按需求不申请常亮屏幕权限。
6. **通知栏**：开始监测后应看到常驻通知“外媒监测运行中”。

APP 内首次使用顺序：

1. **设置 Tab**：填写 DeepSeek API Key、钉钉机器人 Webhook、轮询间隔，点“保存设置”。
2. **新闻源管理 Tab**：手动新增、批量导入（TXT/CSV，每行 `名称,URL,备注`），
   或使用“AI 推荐网站”。
3. **关键词管理 Tab**：导入中文 / 英文关键词。
4. **监测控制 Tab**：设置轮询间隔 → 点“开始监测”。
5. **新闻列表 Tab**：查看命中新闻；点卡片进入详情页，可复制 AI 分析文本或打开原文。

---

## 四、GitHub Actions 工作流说明

`.github/workflows/build-apk.yml` 做了以下事情：

1. 检出仓库代码；
2. 安装 JDK 17（AGP 8.3.2 要求）；
3. 安装 Gradle 8.5（不依赖仓库里的 gradle-wrapper.jar）；
4. 执行 `gradle :app:assembleDebug`；
5. 把 `app/build/outputs/apk/debug/*.apk` 上传为可下载的 Artifact。

首次构建需要下载 Gradle 与 Android 依赖，时间较长属于正常。
之后有缓存会快很多。

---

## 五、常见问题

**Q1：Actions 构建失败，提示找不到 Android SDK？**
GitHub 的 `ubuntu-latest` 运行器自带 Android SDK 与 34 平台，正常不会缺。
请先确认仓库根目录结构正确（根目录有 `app/` 和 `settings.gradle.kts`）。
仍然失败时点开失败步骤，把报错行发出来再排查。

**Q2：APK 下载后安装失败 / “应用未安装”？**
- 先卸载手机上旧版本（签名不同会冲突）；
- 确认手机剩余空间充足；
- 确认 APK 完整（zip 解压后再传，不要直接安装 zip）。

**Q3：开始监测后没有通知？**
- 检查通知权限是否允许；
- 检查通知渠道“外媒监测”是否被关闭；
- 检查省电策略是否为“无限制”。

**Q4：监测一段时间后被停止？**
- 锁屏 / 切后台被杀属于正常现象（需求明确不做保活）；
- Android 14+ 前台服务单次运行有约 6 小时上限，超时被系统停止属正常；
- 保持亮屏、省电无限制、自启动允许可以显著提升稳定性。

**Q5：翻译失败会不会丢新闻？**
不会。关键词命中即入库；翻译失败时列表显示原文并带“原文”角标，
详情页显示“本条翻译失败，以下为原文内容”，复制内容也复制原文。

**Q6：GitHub Actions 构建的是 debug 还是 release？**
当前是 debug 版，可直接安装。需要正式 release 版时见下一节。

---

## 六、以后安装 Android Studio 时的打包方式（可选）

1. 安装 Android Studio（Hedgehog 或更新版本均可）。
2. `Open` 选择 `E:\05，py---py\25\NewsMonitorAndroid`。
3. Android Studio 会创建 `local.properties`（SDK 路径）并提示补全 Gradle Wrapper：
   - 最简单方法：新建一个空项目，把它的 `gradlew`、`gradlew.bat`、
     `gradle/wrapper/gradle-wrapper.jar` 复制到本项目对应位置；
   - 或点击 IDE 提示的 Gradle Sync 相关修复。
4. 等待 Gradle Sync 完成（会自动下载 SDK 34、依赖）。
5. 打 debug 包：`Build` → `Build App Bundle(s) / APK(s)` → `Build APK(s)`，
   输出在 `app/build/outputs/apk/debug/app-debug.apk`。
6. 打正式包：`Build` → `Generate Signed Bundle / APK` → `APK`，
   按向导创建/选择 keystore，选择 `release`，生成的 APK 在
   `app/build/outputs/apk/release/`。

---

## 七、目录结构

```
NewsMonitorAndroid/
├── .github/workflows/build-apk.yml     # GitHub Actions 云端打包工作流
├── README.md                           # 本文件
├── .gitignore
├── build.gradle.kts                    # 根构建脚本（AGP 8.3.2 / Kotlin 1.9.24 / KSP）
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts                # 应用模块依赖与配置
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/example/newsmonitor/
        │   │   ├── App.kt
        │   │   ├── data/               # Room 实体、DAO、数据库、配置
        │   │   ├── network/            # 抓取、翻译、钉钉、DeepSeek、OkHttp
        │   │   ├── service/            # 前台监测 Service 与状态
        │   │   ├── ui/                 # 5 个 Tab、详情页、各 Adapter/Dialog
        │   │   └── util/               # 时间转换、UI 小工具
        │   └── res/                    # layout / drawable / menu / values
        └── test/                        # 时间转换单元测试
```

---

## 八、安全提醒

- DeepSeek API Key、钉钉 Webhook、百度 OCR 密钥都保存在手机本地 Room
  `config` 表，不写死在代码里。
- 如果把项目上传到 **Public** 仓库，请确认代码中没有填入真实密钥
  （当前代码中没有硬编码任何密钥）。
- 钉钉机器人 Webhook 泄露后任何人都能向群里发消息，建议使用私有仓库。
