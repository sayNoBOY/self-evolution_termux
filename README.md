# SelfEvolution_termux（智衍 · SelfEvolution）

基于 [termux/termux-app](https://github.com/termux/termux-app) 的**二次打包工程**：
官方 Termux 容器 → 改造为「智衍 / SelfEvolution」应用——品牌图标与双屏开屏、
内嵌浏览器首屏（服务就绪自动进前端）、容器内预置项目运行时（node + McpServer + vite 双服务）
的定制 bootstrap，以及完整的可复现打包脚本与文档。

- 包名保持 `com.termux`（容器路径、生态兼容性所依赖）
- 发布签名：自建 keystore（**不入库**，口令经环境变量注入）
- 完整改动清单见 **[CHANGES.md](CHANGES.md)**；深度打包原理见 **[docs/Termux二次打包与二次开发指南.md](docs/Termux二次打包与二次开发指南.md)**

## 仓库结构

```
SelfEvolution_termux/
├── app/                    # 智衍主模块（品牌化 + WebView 首屏 + 签名/版本配置）
├── termux-shared/          # 官方共享库（应用名等品牌常量已改）
├── terminal-emulator/      # 官方终端模拟器（仅固定 buildTools 版本）
├── terminal-view/          # 官方终端视图（同上）
├── overlay/                # 自定义 overlay 小件（品牌 motd / 初始化守护 / dotfiles）
├── scripts/                # 打包工具脚本
│   ├── repack_bootstrap.py #   bootstrap zip 合并重打包（overlay → zip）
│   ├── gen_keystore.sh     #   发布签名密钥生成
│   ├── gen_icons.sh        #   品牌图标生成
│   └── AddSplashCredit.java#   开屏「制作人」行渲染（JDK 无头 AWT）
├── design-assets/          # 图标 / 开屏母版与字形素材
├── runtime/mimo-runtime/   # 容器内 glibc mimocode 运行方案件（dns shim 等）
├── docs/                   # 使用指南、历史构建报告、上游 README 备份
├── CHANGES.md              # 相对官方 termux-app 的改动对照（文件级）
└── .gitignore              # 排除：build 产物 / bootstrap zip / 发布密钥
```

## 环境要求

| 组件 | 版本（实测可用） |
|---|---|
| JDK | 21（17+ 理论可行） |
| Android SDK | platform-36、build-tools 36.0.0 |
| Android NDK | r29（`ndkVersion` 见 gradle.properties） |
| Gradle | 9.2.1（或用仓库自带 wrapper） |
| Python | 3.x（仅标准库，用于 repack 脚本） |
| 网络 | 构建依赖走阿里云 maven 镜像（已配置） |

## 快速开始：打出第一个 APK

```bash
# 0) 准备签名密钥（已有人跳过；口令后文记为 $KS_PASS）
./scripts/gen_keystore.sh keystore          # 交互输入口令，产出 keystore/release.jks

# 1) 准备 bootstrap zip（不入库，见下方「bootstrap 从哪来」）
#    把 bootstrap-aarch64.zip 等放到 app/src/main/cpp/

# 2) 构建（release 需要 KS_PASS）
ANDROID_HOME=/path/to/android-sdk KS_PASS=<你的KS_PASS> \
  gradle assembleRelease --no-daemon

# 3) 产物
#    app/build/outputs/apk/release/termux-app_apt-android-7-release_universal.apk
```

安装到设备（同签名覆盖升级）：

```bash
adb install -r app/build/outputs/apk/release/termux-app_apt-android-7-release_universal.apk
```

### 构建的三条铁律（踩过的坑）

1. **禁止 `gradle clean`** —— `clean` 的 doLast 会**删除 `app/src/main/cpp/bootstrap-*.zip`**，
   删了就要重新下载/重打（任务报告记载的真实事故源）。
2. **换过 bootstrap zip 后必须 `touch app/src/main/cpp/termux-bootstrap-zip.S`**
   —— zip 经 `.incbin` 汇编内嵌进 `libtermux-bootstrap*.so`，make **不跟踪该依赖**，
   不 touch 会**静默打出内嵌旧容器的包**（se4 构建实测踩中，靠哈希比对拦截）。
   验证方法：从 APK 里解出 `lib/arm64-v8a/libtermux-bootstrap.so`，
   在其中定位并剥离内嵌 zip（EOCD），比对 SHA-256 与源 zip 一致。
3. **版本号**：`app/build.gradle` 的 `versionCode`（形如日期 20261002）+ `versionName`
   （形如 `0.118.0-se14`）。每次对外发布递增，`validateVersionName()` 会做 semver 校验。

## bootstrap 从哪来（不入库的大文件）

`app/src/main/cpp/bootstrap-*.zip` 单文件过大（定制 aarch64 约 124MB），**不提交**：

- **官方 zip**：`app/build.gradle → downloadBootstraps()` 中给出的官方 URL
  （`https://github.com/termux/termux-packages/releases/download/bootstrap-<version>/bootstrap-<arch>.zip`）。
  本地存在同名 zip 时，构建直接使用并**跳过校验**（智衍补丁），故可自由替换为定制包。
- **定制 aarch64 zip**（内嵌项目运行时 + overlay）：用仓库自带脚本重打——

```bash
python3 scripts/repack_bootstrap.py \
  --bootstrap app/src/main/cpp/bootstrap-aarch64.zip \   # 官方基线
  --overlay  /path/to/overlay-full/ \                    # 完整 overlay（见下）
  --exclude  'share/selfevolution/*' \                   # 从基线剔除旧项目快照（防残留）
  --out      app/src/main/cpp/bootstrap-aarch64.zip
# 然后：touch app/src/main/cpp/termux-bootstrap-zip.S  ← 铁律 2
```

> 本仓库的 `overlay/` 只收录**自定义小件**（品牌 motd / 初始化守护 / dotfiles）。
> 完整 overlay 还包含 node 运行时、Deb 件解包内容、glibc 运行时与项目快照等大体积内容，
> 组装方法见 `docs/Termux二次打包与二次开发指南.md` §4–§6 与 `docs/历史构建报告-2026-09-30.md`。
> 刷新「容器内项目快照」的完整流程（同步最新项目 → repack → touch → 构建）见历史报告附录。

## 应用运行时链路（首屏都长什么样）

```
冷启动
 ├─ 系统 splash（Android 12+：se_bg 底 + 智衍图标）
 ├─ 原生开屏（windowBackground splash_se）：图标 + 「智衍，无限进化成你想要的模样」
 │    + SelfEvolution + 「制作人:老实人留一手」
 ├─ TermuxActivity.onCreate 立即拉起 SelfWebViewActivity
 │    └─ assets/se_loading.html（智字动画「正在启动服务…」，同样含制作人行）
 │         └─ 每秒轮询 后端:3000 + 前端:5173 → 双服务就绪自动跳 http://localhost:5173
 └─ 后台：bootstrap 解压/初始化（首次）→ 等等 profile.d/90-brand-init.sh 守护
          → restart.sh 起双服务 →（由停到起时）termux-open-url 自启前端
```

- 终端与 WebView 并存：WebView 按**返回键**回终端；终端内可手动 `restart.sh` 管理服务。
- **后台重开自愈（P1）**：进程存活时点桌面图标会触发 singleTask clear-top 销毁 WebView——
  `TermuxActivity.onResume/onNewIntent` 检测到 WebView 不在栈上会自动补拉；
  用户主动返回键退出不会被拉回（`sUserExited` 标记）；30s 探测失败进 `se_error.html`
  （重试/打开终端）；`singleTop` 保证栈内单实例。
- 上传/下载已接系统（`WebChromeClient.onShowFileChooser` + `DownloadManager`）。

## 安装 / 升级语义（重要）

`TermuxInstaller` 的规则：**`$PREFIX` 已存在且非空 → 跳过 bootstrap 解压**（无版本检查）。

| 场景 | 结果 |
|---|---|
| 全新安装 | 解压包内 bootstrap → 容器 = 打包时的项目快照 |
| 已装设备 `adb install -r` 升级 | **只换应用外壳**（图标/开屏/代码），容器内容保持原样，数据零风险 |
| 想让已装设备换新容器 | 同步项目源码进容器后 `restart.sh`（推荐，轻量），或清除应用数据后重装（**销毁性**） |

## 大文件与密钥

| 内容 | 策略 |
|---|---|
| `app/src/main/cpp/bootstrap-*.zip` | gitignore；来源见上文 |
| `keystore/release.jks`（发布私钥） | gitignore；`gen_keystore.sh` 本地生成；口令仅经 `KS_PASS` 环境变量传入 |
| `build/` `.gradle/` `local.properties` `*.apk` | gitignore |
| `app/testkey_untrusted.jks` | 官方自带 debug 测试密钥（上游公开件），**非发布密钥**，保留 |

## 文档索引

- [CHANGES.md](CHANGES.md) —— 相对官方 termux-app 的文件级改动对照
- [docs/Termux二次打包与二次开发指南.md](docs/Termux二次打包与二次开发指南.md) —— 打包原理与全流程（bootstrap 结构、overlay、校验、FAQ）
- [docs/历史构建报告-2026-09-30.md](docs/历史构建报告-2026-09-30.md) —— 首次成包 + 装机验收实录（口令已脱敏）
- [docs/历史任务报告-任务73.md](docs/历史任务报告-任务73.md) —— 打包任务原始报告
- [docs/UPSTREAM-README.md](docs/UPSTREAM-README.md) —— 上游 termux-app README 留档

## 许可与致谢

- 上游 [termux-app](https://github.com/termux/termux-app) 采用 **GPL-3.0**（本仓库保留其
  `LICENSE.md` / `SECURITY.md` / 全部源码版权头）；本工程是其衍生作品，同样遵循 GPL-3.0。
- 「Termux」为 Termux 开源项目名称；本工程仅在其基础上做应用级品牌定制，包名保持 `com.termux`。
- 智衍 / SelfEvolution 品牌素材与文案 © 老实人留一手。
