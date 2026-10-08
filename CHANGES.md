# CHANGES — 相对官方 termux-app 的改动对照

- **对比基准**：官方 [termux/termux-app](https://github.com/termux/termux-app) `master` 快照
  （源：`termux-app.tar.gz`，顶层目录 `termux-app-master/`，508 个文件）
- **对比方法**：`diff -rq 基准 本仓库`（全文差异 44 项，逐类列于下）
- **改动目的**：把官方 Termux 定制为「智衍 / SelfEvolution」——品牌化外壳 +
  双屏开屏（原生 + WebView 加载页）+ 内嵌浏览器首屏 + 可预置项目运行时的 bootstrap 定制构建

## 一、修改的文件（24 个）

### 1. 构建与依赖（6）

| 文件 | 改动 |
|---|---|
| `build.gradle` | `google()` → **阿里云 maven 镜像**（国内构建提速） |
| `gradle.properties` | +`org.gradle.jvmargs`（3G 堆 + 网络超时）、+`android.builder.sdkDownload=false`（禁用 SDK 自动下载） |
| `app/build.gradle` | ① 版本：`versionCode 20261002` / `versionName 0.118.0-se4`；② **发布签名** `keystore/release.jks`（口令走 `KS_PASS` 环境变量，文件不入库）；③ `downloadBootstrap()` 智衍补丁：**本地 zip 存在即跳过官方校验直接使用**；④ 输出命名 `termux-app_<variant>-release_universal.apk`；⑤ 应用名等 manifestPlaceholders（智衍）；⑥ `validateVersionName()` semver 校验 |
| `terminal-emulator/build.gradle` | +`buildToolsVersion "36.0.0"`（固定构建工具） |
| `terminal-view/build.gradle` | +`buildToolsVersion "36.0.0"` |
| `termux-shared/build.gradle` | +`buildToolsVersion "36.0.0"` |

### 2. 品牌与文案（4）

| 文件 | 改动 |
|---|---|
| `termux-shared/.../TermuxConstants.java` | `TERMUX_APP_NAME`: "Termux" → **"智衍"**（包名保持 `com.termux`） |
| `termux-shared/.../res/values/strings.xml` | 同上 entity 改「智衍」 |
| `app/.../res/values/strings.xml` | 应用名「智衍」；+`application_name_en=SelfEvolution`；初始化文案「正在初始化智衍运行环境…」 |
| `app/.../res/values/colors.xml` | +`se_bg #0B1220`（品牌深空底色） |

### 3. 开屏与图标（12）

| 文件 | 改动 |
|---|---|
| `app/.../res/values/themes.xml` | `Theme.TermuxActivity`：`windowBackground=@drawable/splash_se` + Android 12+ `windowSplashScreenBackground`、`windowSplashScreenAnimatedIcon=@drawable/splash_icon`（1024 高分定制图标，替代 192px mipmap —— 平板 288dp 容器放大 4 倍导致模糊的根因） |
| `app/.../res/values-night/themes.xml` | 同上（暗色主题同步） |
| `app/.../res/values/themes.xml` | +`Theme.SelfWebView.DayNight.NoActionBar`：内嵌浏览器页状态栏 `#16171d`（与前端暗色背景统一），覆盖上游基主题的 `red_800(#C4001D)` 红色状态栏 |
| `res/mipmap-{m,h,x,xx,xxx}hdpi/ic_launcher{,_round}.png` ×10 | 替换为**智衍像素「智」渐变图标**（legacy 全套） |
| （对照）`res/mipmap-anydpi-v26/` | **已删除**——自适应图标在部分设备前景渲染异常，回退 legacy 完整设计（round-73 实测决策） |

### 4. 首屏链路与清单（3）

| 文件 | 改动 |
|---|---|
| `app/.../AndroidManifest.xml` | ① `android:usesCleartextTraffic="true"`（内嵌页访问 `http://localhost:5173` 需要）；② 注册 **`SelfWebViewActivity`** 并设 `launchMode="singleTop"`（P1-③：重复 `am start` 走 onNewIntent，不再叠实例） |
| `app/.../TermuxActivity.java` | ① `onCreate` 中冷启动**立即拉起 `SelfWebViewActivity`**（终端与 bootstrap 初始化转后台照常进行，返回键回终端）；② **P1-① 后台重开自愈**：`onResume`/`onNewIntent` 调 `ensureSelfWebView()`——singleTask clear-top 销毁 WebView 后补拉，防循环三闸（`sUserExited`/`sLaunchPending`/`sAlive` 弱引用判活） |
| `app/.../TermuxInstaller.java` | 解压阶段所有文件统一 `chmod 0700`（修复 zip 内 npm/restart.sh 等执行位丢失 → Permission denied） |

## 二、新增的文件（本工程原创）

| 文件/目录 | 说明 |
|---|---|
| `app/src/main/java/com/termux/app/SelfWebViewActivity.java` | 内嵌浏览器首屏：加载 `assets/se_loading.html` → 每秒轮询 `:3000`/`:5173` → 双服务就绪自动跳前端；含文件选择器（上传）与 DownloadManager（下载）两大修复；**P1 补丁**：① 静态 `sAlive`/`sUserExited`/`sLaunchPending` 供宿主判活补拉；② `onSaveInstanceState` 保存导航目标 + 主文档失败解锁重试回加载页 + 30s 超时错误页；③ `onNewIntent`（singleTop 幂等）；**主页禁回启动页**：进入 `localhost:5173` 时一次性 `clearHistory()`（回加载页时重置标志，错误恢复循环同样覆盖） |
| `app/src/main/assets/se_loading.html` | **首屏加载页**：智字呼吸动画 + 标语 + SelfEvolution + 「制作人:老实人留一手」+ 正在启动服务…；标题**响应式居中**（`min(30px,6.6vw)` + `nowrap` + `text-align:center`——修手机窄屏 14 字换行末行左贴边的“没居中”） |
| `app/src/main/assets/se_error.html` | **P1-② 超时错误页**：30s 探测失败展示；「重新检测服务」（回加载页续轮询）+「打开终端」（JS 桥 `SeBridge.openTerminal()`，置退出标记防补拉循环） |
| `app/src/main/res/drawable/splash_se.xml` | 原生开屏 layer-list（se_bg 底色 + 居中 `splash_compose`） |
| `app/src/main/res/drawable-nodpi/splash_icon.png` | 系统开屏（Android 12+）专用图标：1024 画布 + 800 图形（较 288dp 显示缩小约 22%，平板上不再上采样） |
| `app/src/main/res/drawable-nodpi/splash_compose.png` | 开屏合成图（图标盒 520→430 缩小 + 标语 + SelfEvolution + 制作人行，1400×1000） |
| `app/src/main/res/drawable-*/ic_foreground_layer.png` ×5 | 图标前景层（密度分桶） |
| `overlay/` | 自定义 overlay 小件：品牌 `etc/motd`、`etc/profile.d/90-brand-init.sh`（双服务守护 + 浏览器自启钩子）、终端 dotfiles |
| `scripts/` | `repack_bootstrap.py`（bootstrap 合并重打包）、`gen_keystore.sh`、`gen_icons.sh`、`AddSplashCredit.java`（开屏制作人行渲染） |
| `design-assets/` | 图标/开屏母版与字形素材 |
| `runtime/mimo-runtime/` | 容器内运行 glibc 版 mimocode 的方案件：`dns_shim.c`/`getaddrinfo_shim.so`（glibc getaddrinfo 拦截器）——背景见 `docs/历史构建报告` |
| `docs/` | 使用文档（README 指向）：二次打包指南、历史构建/任务报告、上游 README 备份 |
| `.gitignore` | 排除构建产物 / bootstrap zip / 发布密钥（见 README「大文件与密钥」） |

## 三、删除的文件

| 文件 | 原因 |
|---|---|
| `README.md`（上游） | 移至 `docs/UPSTREAM-README.md` 留档，根 README 改为本工程使用说明 |
| `app/src/main/res/mipmap-anydpi-v26/` | 见上表——自适应图标渲染异常，回退 legacy |

## 四、不入库的大文件/密钥（设计决定）

| 内容 | 位置 | 不入库原因 | 获取方式 |
|---|---|---|---|
| `bootstrap-*.zip`（官方 ×3 + 定制 aarch64 ~124MB） | `app/src/main/cpp/` | 超出平台单文件限制 | 官方按 build.gradle 中 URL 下载；定制包用 `scripts/repack_bootstrap.py` 重打（README 有完整步骤） |
| `keystore/release.jks`（发布签名私钥） | `keystore/` | **私钥绝不进远端** | `scripts/gen_keystore.sh` 本地生成；口令走环境变量 `KS_PASS` |
| `build/`、`.gradle/`、`local.properties` | 各处 | 构建产物 / 本机路径 | `assembleRelease` 自动生成 |

> 注：`app/testkey_untrusted.jks` 为**官方源码自带**的 debug 测试密钥（上游公开件），保留不影响发布安全。
