# Termux 二次打包与二次开发完全指南

> **主题**：如何对 Termux 进行二次打包 —— 集成初始化脚本、更换应用图标、定制启动界面、预装软件包，以及更深层的二次开发（改包名、自建源、签名发布）。
>
> **事实依据**：本指南所有关键机制均直接核对于 `termux/termux-app` 主分支源码（versionName `0.118.0`，versionCode `118`）与 `termux/termux-packages` 构建脚本，2026-09-30 核对。文中标注了具体文件路径，路径以 GitHub 上 `termux/termux-app`、`termux/termux-packages` 仓库为准。
>
> **配套交付物**（同目录）：
> - `scripts/repack_bootstrap.py` —— 定制 bootstrap zip（已测试）
> - `scripts/gen_icons.sh` —— 一套命令生成全部密度启动图标（已测试）
> - `scripts/gen_keystore.sh` —— 生成发布签名密钥库
> - `overlay/` —— 初始化脚本与 dotfiles 模板（已测试，幂等）
> - `任务报告.md` —— 交付说明与验证记录

---

## 目录

- [§0 先选路线：三种二次打包方案](#0-先选路线三种二次打包方案)
- [§1 构建环境与首次跑通](#1-构建环境与首次跑通)
- [§2 改应用名称与版本号](#2-改应用名称与版本号)
- [§3 改应用图标](#3-改应用图标)
- [§4 定制启动界面（Splash）](#4-定制启动界面splash)
- [§5 核心机制：bootstrap 与初始化脚本](#5-核心机制bootstrap-与初始化脚本)
- [§6 预装软件包](#6-预装软件包)
- [§7 改 applicationId（完全去 Termux 化 / 与官方共存）](#7-改-applicationid完全去-termux-化--与官方共存)
- [§8 签名与发布](#8-签名与发布)
- [§9 端到端实战：一个完整的品牌包 Checklist](#9-端到端实战一个完整的品牌包-checklist)
- [§10 常见坑 QA](#10-常见坑-qa)
- [附录 A：apktool 快速改皮路线（不编译源码）](#附录-aapktool-快速改皮路线不编译源码)
- [附录 B：参考资料](#附录-b参考资料)

---

## §0 先选路线：三种二次打包方案

| | 路线 A：源码构建（**推荐**） | 路线 B：apktool 改皮 | 路线 C：纯配置（不算打包） |
|---|---|---|---|
| 改图标 / 名称 | ✅ | ✅ | ❌ |
| 改启动界面 | ✅（主题级，彻底） | ✅（改 theme/drawable） | ❌（仅终端内观感） |
| 初始化脚本打进安装包 | ✅（bootstrap 方式，**离线首启即生效**） | ⚠️ 只能改 APK 资源，动不了 bootstrap 内容 | ⚠️ 装完后手动放进 `~` |
| 预装软件包 | ✅ | ❌ | ❌（只能在线 `pkg install`） |
| 改包名、自建源、持续更新 | ✅ | ⚠️ 改名可行，其余不可 | ❌ |
| 难度 / 耗时 | 中（环境 ~0.5 天，其后增量很快） | 低（~30 分钟） | 极低 |
| 风险 | 低，跟随官方源码升级 | 中（重签名、对齐、签名校验） | 无 |

**结论**：
- 要做「**带初始化脚本、预装环境的产品化分发**」→ 用 **路线 A**，本文主体即路线 A。
- 只是想换个图标名字、快速试水 → [附录 A](#附录-aapktool-快速改皮路线不编译源码)。

---

## §1 构建环境与首次跑通

### 1.1 环境要求

| 项 | 版本（以源码为准） | 说明 |
|---|---|---|
| 构建机 | Linux x86_64 / macOS（推荐） | Windows 可行但坑多 |
| JDK | **17 或 21** | AGP 8.13.2 要求 JDK 17+ |
| Gradle | **9.2.1**（wrapper 自动下载） | 见 `gradle/wrapper/gradle-wrapper.properties`，不要自装其它版本 |
| Android Gradle Plugin | **8.13.2** | 见根目录 `build.gradle` |
| Android SDK | compileSdk **36** | 见 `gradle.properties` |
| NDK | **29.0.14206865** | 见 `gradle.properties` 的 `ndkVersion`，需用 sdkmanager 装同版本 |
| 网络 | 可访问 google()/mavenCentral()/jitpack.io 及 GitHub Releases | 首次构建会下载 bootstrap zip |

安装 NDK（示例）：

```bash
sdkmanager "ndk;29.0.14206865" "platforms;android-36" "build-tools;36.0.0"
```

### 1.2 获取源码并首次构建

```bash
git clone https://github.com/termux/termux-app.git
cd termux-app

# 调试包（自带 testkey 签名，可直接装）
./gradlew assembleDebug

# 发布包（默认无签名，产物是 app-release-unsigned.apk，见 §8）
./gradlew assembleRelease
```

产物路径：

```
app/build/outputs/apk/debug/app-universal-debug.apk    # 或按 ABI 拆分的多个 apk
app/build/outputs/apk/release/app-universal-release-unsigned.apk
```

> `app/build.gradle` 中 `splitAPKsForReleaseBuilds` 默认 `0`，所以 **release 默认只出一个 universal APK**（全架构，含 bootstrap 约 180MB）；debug 默认 `splitAPKsForDebugBuilds=1`，会按 ABI 拆分并带 `universalApk`。

### 1.3 关键构建参数（`app/build.gradle`）

```groovy
ext {
    // bootstrap 变体：决定打进 APK 的 bootstrap zip 种类
    // 可选: "apt-android-7"（默认，Android 7+） / "apt-android-5"
    packageVariant = System.getenv("TERMUX_PACKAGE_VARIANT") ?: "apt-android-7"
}
android {
    namespace "com.termux"            // 未显式写 applicationId 时，applicationId 即此值
    defaultConfig {
        versionCode 118
        versionName "0.118.0"         // 必须符合 semver 2.0.0，否则构建报错
        manifestPlaceholders.TERMUX_PACKAGE_NAME = "com.termux"
        manifestPlaceholders.TERMUX_APP_NAME     = "Termux"
        // ... 其余 *_APP_NAME 占位符（API/Boot/Float/Styling/Tasker/Widget）
    }
}
```

版本号也可用环境变量覆盖：`TERMUX_APP_VERSION_NAME=1.0.0 ./gradlew assembleRelease`。

### 1.4 安装验证

```bash
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb shell am start -n com.termux/.app.TermuxActivity
```

⚠️ 自构建包与 F-Droid 官方包**签名不同**，即使包名同为 `com.termux` 也无法覆盖安装，必须先卸载官方版（数据会丢失，`adb backup` 也救不了 Termux 数据，重要数据请先拷出）。

---

## §2 改应用名称与版本号

### 2.1 应用显示名（桌面图标下的名字）

名字不在常规 `strings.xml` 一行里，而是通过 **XML ENTITY 占位符** 集中定义：

**`app/src/main/res/values/strings.xml`**（文件头部）：

```xml
<!DOCTYPE resources [
    <!ENTITY TERMUX_PACKAGE_NAME "com.termux">
    <!ENTITY TERMUX_APP_NAME "Termux">          <!-- ← 改这里：主应用名 -->
    <!ENTITY TERMUX_API_APP_NAME "Termux:API">
    <!ENTITY TERMUX_BOOT_APP_NAME "Termux:Boot">
    ...
]>

<resources>
    <string name="application_name">&TERMUX_APP_NAME;</string>       <!-- 桌面/界面显示名 -->
    <string name="shared_user_label">&TERMUX_APP_NAME; user</string>
    <string name="permission_run_command_label">Run commands within &TERMUX_APP_NAME; environment</string>
    ...
</resources>
```

同时改 **`app/build.gradle`** 中的 manifest 占位符（用于 manifest 里 `${TERMUX_APP_NAME}` 形式引用处）：

```groovy
manifestPlaceholders.TERMUX_APP_NAME = "我的终端"
```

> 官方指引（`TermuxConstants.java` 类注释）：改名需要同步修改
> ① `termux-app` 的 `strings.xml` ENTITY；② `termux-shared` 的 `strings.xml` ENTITY；
> ③ `build.gradle` 的 `manifestPlaceholders`。
> `termux-shared` 里的 ENTITY 用于插件应用/共享库文案，如果你不用插件，至少把 app 模块的改干净。

### 2.2 其它会露出名字的地方

| 位置 | 内容 |
|---|---|
| `app/src/main/res/xml/shortcuts.xml` | 长按图标快捷菜单的 label（`action_new_session` 等，定义在 strings.xml） |
| `app/src/main/res/values/strings.xml` | `bootstrap_installer_body`（首次安装时"Installing bootstrap packages…"进度框文案，**可改成你的品牌文案**）、通知栏、设置页等全部文案 |
| `AndroidManifest.xml` | `android:label="@string/application_name"` 不用动，跟随 ENTITY |

### 2.3 版本号

`app/build.gradle`：

```groovy
versionCode 118        // 每次发布 +1，决定能否覆盖安装
versionName "0.118.0"  // 必须 semver 2.0.0（major.minor.patch[-pre][+build]）
```

`validateVersionName()` 会在构建期强校验格式，写 `1.0` 或 `v1.0` 都会直接失败。

---

## §3 改应用图标

### 3.1 图标资源全景（`app/src/main/res/`）

| 资源 | 作用 |
|---|---|
| `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` | 传统启动图标，5 个密度：48/72/96/144/192 px |
| `mipmap-{...}/ic_launcher_round.png` | 圆形启动图标（部分桌面用），同样 5 个密度 |
| `mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml` | **自适应图标**（Android 8.0+ 桌面实际显示的就是它） |
| `drawable/ic_foreground.xml` | 自适应图标前景层（矢量图，终端 `>_` 图形） |
| `drawable/banner.png` | Android TV 横幅（manifest `android:banner`） |
| `AndroidManifest.xml` | `android:icon="@mipmap/ic_launcher"`、`android:roundIcon=...`、`android:banner=...` |

自适应图标 XML 现状（两个文件内容相同）：

```xml
<adaptive-icon ...>
    <background android:drawable="@android:color/black"/>   <!-- 背景：纯黑 -->
    <foreground android:drawable="@drawable/ic_foreground"/> <!-- 前景：矢量图 -->
    <monochrome  android:drawable="@drawable/ic_foreground"/> <!-- 单色主题图标 -->
</adaptive-icon>
```

### 3.2 用配套脚本一键生成（已测试）

```bash
# master.png 是你的 512x512（或更大）方形主图，带透明背景效果最佳
./scripts/gen_icons.sh master.png ./res-out --adaptive

# 需要 TV 横幅时：
./scripts/gen_icons.sh master.png ./res-out --adaptive --banner banner_src.png
```

产物（15 个文件）：

```
mipmap-mdpi/ic_launcher.png          48x48      mipmap-mdpi/ic_launcher_round.png       48x48（圆形透明遮罩）
mipmap-hdpi/ic_launcher.png          72x72      ...（hdpi/xhdpi/xxhdpi/xxxhdpi 同理 72/96/144/192）
drawable-mdpi/ic_foreground_layer.png  108x108   （自适应前景，108/162/216/324/432）
drawable-hdpi/ic_foreground_layer.png  162x162
...
drawable/banner.png                 640x360（仅 --banner）
```

复制进工程并重建：

```bash
cp -r ./res-out/* termux-app/app/src/main/res/
cd termux-app && ./gradlew clean assembleRelease
```

### 3.3 接入自适应图标（Android 8+ 桌面显示的真正图标）

编辑 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`（`ic_launcher_round.xml` 同）：

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/black"/>          <!-- 或改品牌色: @color/brand_bg -->
    <foreground android:drawable="@drawable/ic_foreground_layer"/> <!-- 指向脚本生成的 PNG -->
    <monochrome  android:drawable="@drawable/ic_foreground_layer"/>
</adaptive-icon>
```

PNG 放入 `drawable-mdpi` ~ `drawable-xxxhdpi` 后，原来矢量的 `drawable/ic_foreground.xml` 若不再被引用即可删除（或保留不引用）。

### 3.4 注意事项

- **只换 5 个传统 PNG、不动 anydpi XML** → Android 8+ 桌面显示的图标不会变（它读自适应 XML）。两处都要改。
- 圆形图标脚本已做透明圆形遮罩（`geq` alpha），不要直接用方形图充当 `ic_launcher_round.png`。
- 桌面可能缓存图标，装完后换桌面主题或重启桌面进程才能看到新图标。
- 想改**通知栏/前台服务小图标**：`drawable/ic_service_notification.xml`（矢量，需主题色适配）。

---

## §4 定制启动界面（Splash）

Termux 的"启动界面"分四层，按用户看到的顺序：

```
① 系统启动画面 (Splash Screen)      → 由主题 windowBackground + 启动器图标 决定
② Activity 打开瞬间的窗口背景        → 同上（values/values-night 的 theme）
③ 终端首屏文字（欢迎语/motd）        → profile.d 初始化脚本（§5）
④ 首次安装的"Installing bootstrap…"框 → strings.xml 文案
```

### 4.1 机制（源码事实）

- 启动 Activity：`TermuxActivity`，manifest 中
  `android:theme="@style/Theme.TermuxActivity.DayNight.NoActionBar"`。
- 该主题定义在 `app/src/main/res/values/themes.xml` 和 `values-night/themes.xml`，关键项：

```xml
<style name="Theme.TermuxActivity.DayNight.NoActionBar" parent="Theme.TermuxApp.DayNight.DarkActionBar">
    <item name="colorPrimary">@color/black</item>
    <item name="android:windowBackground">@color/black</item>  <!-- ← 启动窗口背景 -->
    ...
</style>
```

- 当前工程 `targetSdkVersion=28`（`gradle.properties`），**没有 `values-v31`**。Android 12+ 对 targetSdk<31 的应用按兼容模式生成系统 Splash：**中心图标 = 启动器图标（§3 改的 mipmap），底色 = 启动 Activity 主题的 `windowBackground`**。
  所以：**换图标 = 换 Splash 中心图；换 `windowBackground` = 换 Splash 底色/背景图。**

### 4.2 方案一（兼容面最广）：把 windowBackground 换成品牌启动图

1. 新建 `app/src/main/res/drawable/splash_background.xml`（layer-list：品牌底色 + 居中 logo）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@color/brand_bg"/>           <!-- 底色，在 colors.xml 定义 -->
    <item>
        <bitmap android:src="@mipmap/ic_launcher"        <!-- 居中 logo，复用启动图标 -->
                android:gravity="center"/>
    </item>
</layer-list>
```

2. 修改 `values/themes.xml` **与** `values-night/themes.xml` 中的 `Theme.TermuxActivity.DayNight.NoActionBar`：

```xml
<item name="android:windowBackground">@drawable/splash_background</item>
```

> 记得给 `values-night` 也改（Termux 的主题是 DayNight 双套），否则夜间模式下回退黑底。

### 4.3 方案二（Android 12+ 原生 Splash 属性）

新建 `app/src/main/res/values-v31/themes.xml`（compileSdk 36，属性可用）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.TermuxActivity.DayNight.NoActionBar"
           parent="Theme.TermuxApp.DayNight.DarkActionBar">
        <!-- 启动画面背景与图标（API 31+） -->
        <item name="android:windowSplashScreenBackground">@color/brand_bg</item>
        <item name="android:windowSplashScreenAnimatedIcon">@mipmap/ic_launcher</item>
        <item name="android:windowSplashScreenIconBackgroundColor">@color/brand_bg</item>
        <!-- Splash 结束后使用的主题 -->
        <item name="android:postSplashScreenTheme">@style/Theme.TermuxActivity.DayNight.NoActionBar</item>
        <!-- 其余原 Theme.TermuxActivity 的必要项按需复制（windowTranslucentStatus 等） -->
    </style>
</resources>
```

⚠️ 兼容提示：`values-v31` 方案在部分 ROM + targetSdk 28 组合下可能只走兼容模式；**建议方案一、方案二同时上**，方案一兜底。验证时用冷启动观察（`adb shell am force-stop com.termux && adb shell am start -n com.termux/.app.TermuxActivity`），或直接录屏逐帧看。

### 4.4 方案三：终端首屏欢迎语（"启动界面"的内容层）

由 bootstrap 里的 `$PREFIX/etc/profile.d/90-brand-init.sh` 完成（见 §5，配套 `overlay/` 已实现）：每次打开 Termux 打印品牌欢迎语；首次运行额外打印"初始化完成"并部署 dotfiles。

### 4.5 方案四：首次安装进度文案

`app/src/main/res/values/strings.xml`：

```xml
<string name="bootstrap_installer_body">正在安装我的终端运行环境…</string>
<string name="bootstrap_error_title">安装失败</string>
```

---

## §5 核心机制：bootstrap 与初始化脚本

**这是"二次打包带初始化脚本"最关键的一章。**

### 5.1 bootstrap 是什么、存在 APK 哪里

Termux APK 里不含任何 Linux 用户态，首次启动时解压的那份根文件系统叫 **bootstrap zip**（`bootstrap-aarch64.zip` 等，每个 ABI 一个）。它在构建期的完整链路（`app/build.gradle`）：

```
downloadBootstraps() 任务
  ├─ 校验/使用本地文件: app/src/main/cpp/bootstrap-<arch>.zip
  │     └─ 本地存在但 SHA-256 ≠ build.gradle 里硬编码的 expectedChecksum
  │        → 直接删除本地文件！(见 build.gradle:176-183)  ← 定制时必须处理，见 5.3
  └─ 否则从 GitHub Releases 下载官方 zip
        https://github.com/termux/termux-packages/releases/download/bootstrap-<ver>/bootstrap-<arch>.zip
                ↓
编译期: app/src/main/cpp/termux-bootstrap-zip.S
        .incbin "bootstrap-aarch64.zip"   ← 按 ABI 选择
                ↓
链接成 libtermux-bootstrap.so（NDK ndkBuild，Android.mk）
                ↓
APK 安装后首次启动: TermuxInstaller 读取该 .so 中的 zip 字节 → 解压到 $PREFIX
```

**结论：改 bootstrap = 替换 `app/src/main/cpp/bootstrap-<arch>.zip` + 过掉 SHA-256 校验 + 重新 gradle 构建。** zip 会被原样编进 native so，无需其它打包动作。

### 5.2 首次启动的解压与初始化流程（`TermuxInstaller.java`）

1. 若 `$PREFIX`（`/data/data/<pkg>/files/usr`）已存在且非空 → **直接跳过**（这就是"初始化只跑一次"的根源；开发期想重新触发：`adb shell pm clear com.termux`，会清空全部数据）。
2. 清空 `files/usr-staging`，把 zip 逐条解压到 staging：
   - 条目路径**相对于 `$PREFIX`**（如 `bin/bash`、`etc/profile`、`lib/...`）；
   - 遇到 `SYMLINKS.txt`（格式 `链接目标←zip内路径`）暂存，解压完统一 `symlink`（**zip 里不能放符号链接，必须写进 SYMLINKS.txt，否则安装器抛 "No SYMLINKS.txt encountered"**）；
   - `bin/`、`libexec`、`lib/apt/apt-helper`、`lib/apt/methods` 强制 `chmod 0700`，其余文件按 zip 内权限落盘。
3. staging 整体 rename 为 `$PREFIX`。
4. **second stage**（`termux-packages/scripts/bootstrap/`）：执行 `var/lib/dpkg/info/*.postinst configure`，把包配置收尾；用 `*.lock` 符号链接保证**一生只跑一次**（rootfs 清了才会重跑）。`etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh` 是兜底（app 没跑成时，下次开 shell 补跑）。

### 5.3 定制 bootstrap：三步走（配套脚本已测试）

#### 第 1 步：拿到官方 zip

官方 zip 会在首次 `./gradlew assembleDebug` 时自动下载到
`app/src/main/cpp/bootstrap-<arch>.zip`（4 个 ABI 各一个：`aarch64 / arm / i686 / x86_64`）。
也可以手动从 GitHub Releases 下载（版本见 `app/build.gradle` 的 `downloadBootstraps()`，当前 `bootstrap-2026.02.12-r1+apt.android-7`）。

#### 第 2 步：合并 overlay（初始化脚本 / 预置文件）

```bash
python3 scripts/repack_bootstrap.py \
    --bootstrap app/src/main/cpp/bootstrap-aarch64.zip \
    --overlay  overlay/ \
    --out      app/src/main/cpp/bootstrap-aarch64.zip
```

脚本行为（已用仿真 zip 验证）：
- overlay 中**同名条目覆盖、异名追加**，自动补齐目录条目，保留原 zip 全部内容与 `SYMLINKS.txt`；
- overlay 里的**符号链接自动追加进 `SYMLINKS.txt`**（格式与官方一致：`目标←./zip内路径`）；
- 保留文件的 unix 权限位（可执行脚本的 `+x` 会被带过去）；
- 输出新 zip 的 **SHA-256**，并打印下一步提示。

本交付物的 `overlay/` 内容：

```
overlay/
├── etc/
│   ├── motd                                    # 登录提示（sshd/login 等场景会读）
│   └── profile.d/90-brand-init.sh              # ★ 核心初始化脚本（见 5.4）
└── share/mytermux/dotfiles/                    # 初始化时部署到 ~ 的模板
    ├── bashrc                                  #   -> ~/.bashrc
    └── termux/colors.properties                #   -> ~/.termux/colors.properties（主题）
```

> 4 个 ABI 都要处理：对每个 `bootstrap-<arch>.zip` 重复执行上述命令（同一 overlay）。

#### 第 3 步：绕过/更新 `app/build.gradle` 的 SHA-256 校验（二选一）

**方式 B1（推荐，保留完整性校验）**：把 `downloadBootstraps()` 里对应架构的
`expectedChecksum` 字符串替换为 repack 脚本输出的 SHA-256：

```groovy
downloadBootstrap("aarch64", "<新SHA-256>", version)
```

**方式 B2（省事，跳过本地校验）**：把 `downloadBootstrap()` 里整个 `if (file.exists()) { ... }`
本地校验块（含"算 hash 不符就 delete"的逻辑）替换为直接信任本地文件：

```groovy
if (file.exists()) {
    // 使用本地自定义 bootstrap，跳过校验（自担完整性责任）
    return
}
```

> ⚠️ 若不处理这一步，你放进去的定制 zip 会在构建时被**静默删除并重新下载官方版**——这是最常见的"改了没生效"原因。
> ⚠️ 还要注意 `clean` 任务会删除 `src/main/cpp/bootstrap-*.zip`（`app/build.gradle` 的 `clean.doLast`），clean 后需重新放文件（或重新跑 repack 脚本）。

之后：

```bash
./gradlew clean assembleRelease   # 新 bootstrap 被 .incbin 进 libtermux-bootstrap.so
```

### 5.4 初始化脚本：挂在哪个钩子上（启动链，源码级）

Termux **默认会话是 login shell**：`TermuxSession` 在 `$PREFIX/bin` 里按
`login, bash, zsh, fish, sh`（`UnixShellEnvironment.LOGIN_SHELL_BINARIES`）顺序找可执行文件，
以 `-bash` 这样的 argv[0] 启动。因此每个新会话都会走 `packages/bash` 提供的标准启动链：

```
$PREFIX/etc/profile                         ← login shell 入口
 ├─ ① $PREFIX/etc/profile.pre               （可选，最早，适合改 PATH）
 ├─ ② $PREFIX/etc/profile.d/*.sh            （按文件名排序逐个 source）★ 首选钩子
 │       ├── 01-termux-bootstrap-second-stage-fallback.sh  (官方兜底脚本)
 │       └── 90-brand-init.sh               (你的初始化脚本，排在官方之后)
 ├─ ③ $PREFIX/etc/bash.bashrc               （交互式 bash 的全局配置）
 └─ ④ ~/.bashrc                             （login shell 下也会被 profile 主动 source）
```

（该链路核对于 `termux-packages/packages/bash/etc-profile`；`etc/profile.pre` 用于在不改 `etc/profile` 的前提下最早注入 PATH，可平滑跟随官方包升级。）

**为什么初始化脚本放 `etc/profile.d/` 而不是塞 `~/.bashrc`？**
bootstrap zip 的条目只覆盖 `$PREFIX`（构建脚本 `create_bootstrap_archive()` 是
`cd $ROOTFS/$PREFIX` 后打包），**home 目录不在 zip 里**，安装器也不从 zip 解出 `~` 的文件。
因此规范做法是：把初始化逻辑放进 `$PREFIX/etc/profile.d/`，由它把 dotfiles **部署到 `~`**。

#### 配套初始化脚本做了什么（`overlay/etc/profile.d/90-brand-init.sh`）

- **首次运行**（无 `~/.mytermux_initialized` 标记）：
  1. 把 `share/mytermux/dotfiles/` 下模板复制到 `~`（**目标已存在则绝不覆盖**）；
  2. 写入含时间戳的标记文件 → 保证幂等，后续会话不再执行部署。
- **每次运行**：`BRAND_WELCOME=1`（默认）时打印品牌欢迎语。
- **可配置**：`$PREFIX/etc/mytermux.conf`：

```sh
BRAND_NAME="SuperXiaoAI"
BRAND_WELCOME=0      # 关闭每次会话的欢迎语
```

已做 `sh -n` / `bash -n` 语法校验，并用隔离的假 PREFIX/HOME 验证了：首启部署、
用户已有 `.bashrc` 不被覆盖、二启幂等、conf 关闭欢迎语 —— 全部通过。

#### 在初始化脚本里还能做什么

```sh
# 90-brand-init.sh 内可任意扩展（login shell 环境，$PREFIX/$HOME 可用）
# 例：设置环境变量、注册别名、首次运行时在线装包（需网络）、下发证书等
```

> 在线装包（`pkg install -y openssh`）可以写进首启脚本，但会让**首次打开变慢且依赖网络**；
> 离线可靠的预装请走 §6 的 bootstrap 内预装路线。

---

## §6 预装软件包

三种路线，按"省事程度"排序：

### 路线 1：官方构建脚本 `build-bootstraps.sh`（推荐，官方支持 fork 场景）

`termux-packages` 仓库的 `scripts/build-bootstraps.sh`，其注释原话：
*"allows bootstrap archives to be easily built for (forked) termux apps without having to publish an apt repo first."*

```bash
git clone https://github.com/termux/termux-packages.git
cd termux-packages

# 在 Docker 中构建（官方方式，依赖环境已配好）
./scripts/run-docker.sh ./scripts/build-bootstraps.sh \
    --architectures aarch64 \
    --add openssh,git,python

# 产物: termux-packages/bootstrap-aarch64.zip（含 --add 的全部包及其依赖）
```

要点：
- `--add pkg1,pkg2`：追加预装包（默认 bootstrap 是最小集合）；
- `--architectures`：默认 4 个架构全出，可只出 `aarch64`；
- **换包名时**：包名/前缀由 `scripts/properties.sh` 的 `TERMUX_APP_PACKAGE` 决定
  （默认 `com.termux`），改完需 `./scripts/run-docker.sh ./clean.sh -f` 强制重建
  （因为 Termux 的二进制里**硬编码了 `$PREFIX=/data/data/<pkg>/files/usr`**）；
- 之后把产物放进 `app/src/main/cpp/`，走 §5.3 的校验补丁流程。

需要的包越多，bootstrap zip 越大（直接进 APK，全部 ABI 线性增长）——**只装真正的必需品**。

### 路线 2：自建 apt 仓库（适合长期运维、在线更新）

1. 用 `termux-packages` 常规流程构建你的包并发布成 apt 源（见官方 wiki
   [Building packages](https://github.com/termux/termux-packages/wiki/Building-packages)）；
2. 用 `scripts/generate-bootstraps.sh` 基于**已发布的仓库 deb** 生成 bootstrap；
3. 客户端首次启动后把你的源与 GPG key 写进 `overlay`（或由 init 脚本下发），
   替换 `$PREFIX/etc/apt/sources.list.d/*`，用户可 `pkg update` 持续升级。

适合"产品化、要长期打补丁"的场景；成本是你要维护一台仓库。

### 路线 3：首启在线安装（最省事，可靠性最低）

`overlay/etc/profile.d/90-brand-init.sh` 的首启分支里加：

```sh
if [ ! -f "$BRAND_MARKER" ]; then
    (pkg update -y && pkg install -y openssh git) >/dev/null 2>&1 &
fi
```

后台执行避免卡首屏；但**无网络的首启就等于没预装**，且用户首屏体验差。仅适合内部工具。

---

## §7 改 applicationId（完全去 Termux 化 / 与官方共存）

> 只想换图标、换名字 → **不用走本章**（§2/§3 即可，包名保持 `com.termux`）。
> 想和官方 Termux **同时安装**、或彻底品牌化 → 必须改包名，且本章清单一项都不能漏。

### 7.1 官方给出的改动清单（`TermuxConstants.java` 类注释原文整理）

1. **`termux-shared` → `TermuxConstants.TERMUX_PACKAGE_NAME`**（默认 `com.termux`）。
   它同时决定 `TERMUX_FILES_DIR_PATH`（`/data/data/<pkg>/files`）等全部路径常量。
2. **`app/build.gradle` 的 `applicationId`**：当前工程没显式写（AGP 默认取
   `namespace "com.termux"`），改名时要在 `defaultConfig` 里**显式加**：
   ```groovy
   defaultConfig {
       applicationId "com.example.mytermux"
       ...
   }
   ```
3. **`manifestPlaceholders`**：`TERMUX_PACKAGE_NAME`、`TERMUX_APP_NAME` 等
   （`TERMUX_PACKAGE_NAME` 用于 manifest 的 `android:sharedUserId` 与
   `${TERMUX_PACKAGE_NAME}.permission.RUN_COMMAND` 权限名）。
4. **`strings.xml` 的 ENTITY**：`termux-app` 与 `termux-shared` 两处
   `<!ENTITY TERMUX_PACKAGE_NAME "com.termux">`。
5. **硬编码文件**：`res/xml/shortcuts.xml`（`android:targetPackage="com.termux"`、
   `targetClass="com.termux.app.TermuxActivity"` —— 文件内注释明确说
   `${applicationId}` 变量在其中**不生效**，必须手改）；以及 `*_preferences.xml`。
6. `AndroidManifest.xml` 的 `package` / build.gradle 的 `namespace`（Java 包结构）——
   官方**明确不建议**改，会连锁破坏大量组件引用（组件名用 `.` 相对写法时按此包前缀解析）。
7. `TermuxConstants` 中其它含 `termux` 字样的 `*_PATH` 常量（可选，视需求）。

### 7.2 必须重编译 bootstrap（最大的坑）

`$PREFIX` 硬编码进全部 Linux 二进制（ELF 路径），包名变了，官方 bootstrap **完全不能用**：
shell 会找不到解释器、动态库路径全错。必须按 §6 路线 1，先在
`termux-packages/scripts/properties.sh` 改 `TERMUX_APP_PACKAGE`，重新构建 bootstrap zip，
再走 §5.3 流程。**这一步是很多人改包名后"一打开就闪退/黑屏"的根本原因。**

### 7.3 插件生态与 sharedUserId

- manifest 有 `android:sharedUserId="${TERMUX_PACKAGE_NAME}"`：Termux:API / Boot / Widget /
  Tasker / Float / Styling 全部与主应用**共享这个 sharedUserId 且要求同签名**才能互通。
- 改了包名或签名后，**官方插件全部失联**；要继续用插件能力，需要 fork 插件工程，
  同步改它们的 `TermuxConstants` 引用、包名与签名（官方 README 的 Forking 一节有链接指引）。
- 不用插件可忽略本条，但 `Termux:Boot`（开机自启脚本）这类能力会一并失去，
  开机自启需求可用别的方案（Tasker、系统自动化）替代。

### 7.4 改包名后的回归点

- `RUN_COMMAND` 权限名、`shortcuts.xml`、`TermuxConstants` 路径、bootstrap、插件；
- 首启安装 bootstrap 正常、`pkg update` 正常、`termux-reload`/`termux-setup-storage` 正常；
- 若你分发 `RUN_COMMAND` intent 给宿主 App（`RunCommandService`），权限名与
  包名引用都要用新值。

---

## §8 签名与发布

### 8.1 生成密钥库

```bash
./scripts/gen_keystore.sh keystore     # 需要 JDK 的 keytool，交互输入口令
# 产物: keystore/release.jks，alias = termux-release
```

**口令不要写死进 git**，用环境变量传入。

### 8.2 接入 `app/build.gradle`

```groovy
android {
    signingConfigs {
        release {
            storeFile file("../keystore/release.jks")
            storePassword System.getenv("KS_PASS") ?: ""
            keyAlias     "termux-release"
            keyPassword  System.getenv("KS_PASS") ?: ""
        }
    }
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources false   // 官方为可复现构建而关闭，建议保持
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
            signingConfig signingConfigs.release      // ← 加这一行
        }
    }
}
```

```bash
KS_PASS=你的口令 ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-universal-release.apk
```

> 现状：release 构建默认**没有** signingConfig（只有 debug 用仓库自带的
> `testkey_untrusted.jks`）。不配置就会产出 `-unsigned.apk`，只能
> `apksigner sign` 手工签或走 `zipalign + apksigner` 流水线。

### 8.3 覆盖安装规则（务必记住）

| 想安装的包 vs 手机上的包 | 结果 |
|---|---|
| 同 applicationId + 同签名 + versionCode ≥ 已装 | ✅ 覆盖升级 |
| 同 applicationId + **不同签名** | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先卸载 |
| **不同 applicationId** | ✅ 直接共存安装 |

即：**自己发布的后续版本必须始终用同一个 keystore**；想与官方 Termux 共存就必须改包名（§7）。

### 8.4 发布检查

- `versionName` 过 semver 校验、`versionCode` 递增；
- universal APK 含 4 个 ABI 的 native so（含 bootstrap），体积 ~180MB 属正常；
- 分发渠道如需按架构拆包：`TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=1 ./gradlew assembleRelease`；
- F-Droid 渠道与你无关（官方签名在 F-Droid 手里），你只能走自己的分发渠道。

---

## §9 端到端实战：一个完整的品牌包 Checklist

目标：产品名"我的终端"、新图标、品牌启动图、预置 shell 环境与主题、离线首启初始化。

```bash
# 0) 源码与工具
git clone https://github.com/termux/termux-app.git && cd termux-app
cp -r /path/to/taskRes/73/{scripts,overlay} .

# 1) 首次构建跑通（会自动下载官方 bootstrap 到 app/src/main/cpp/）
./gradlew assembleDebug        # 需要 4 个 ABI 都在时可先只跑一次，产物 ABI 以配置为准
#    （若只下载了部分架构，手动从 GitHub Releases 补齐 bootstrap-{arm,i686,x86_64}.zip）

# 2) 改名（§2）
#    app/src/main/res/values/strings.xml  ENTITY TERMUX_APP_NAME → "我的终端"
#    app/build.gradle  manifestPlaceholders.TERMUX_APP_NAME = "我的终端"

# 3) 换图标（§3）
./scripts/gen_icons.sh brand-512.png ./res-out --adaptive
cp -r ./res-out/* app/src/main/res/
#    编辑 app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
#    → <foreground android:drawable="@drawable/ic_foreground_layer"/>

# 4) 启动界面（§4）
#    新增 app/src/main/res/drawable/splash_background.xml
#    改 values/themes.xml + values-night/themes.xml 的
#    Theme.TermuxActivity.DayNight.NoActionBar → windowBackground
#    新增 values-v31/themes.xml（API31 splash 属性，可选）

# 5) 初始化脚本 + 预置文件（§5）
for arch in aarch64 arm i686 x86_64; do
    python3 scripts/repack_bootstrap.py \
        --bootstrap app/src/main/cpp/bootstrap-$arch.zip \
        --overlay overlay/ \
        --out app/src/main/cpp/bootstrap-$arch.zip
done

# 6) 绕过 bootstrap SHA-256 校验（§5.3 方式 B1 或 B2）

# 7) 预装包（可选，§6 路线 1，重出 bootstrap 后重复步骤 5-6）

# 8) 签名（§8）
./scripts/gen_keystore.sh keystore
#    在 app/build.gradle 接入 signingConfigs.release

# 9) 构建与安装
KS_PASS=xxx ./gradlew clean assembleRelease
adb install -r app/build/outputs/apk/release/app-universal-release.apk

# 10) 验收（见下）
```

**验收清单**：

- [ ] 桌面图标、长按快捷菜单、设置页图标全部换新（自适应 + 传统两套）
- [ ] 冷启动 Splash 底色/图标为品牌资源（深色模式下也检查）
- [ ] 首次打开：进度条文案为品牌文案；进 shell 打印"首次初始化完成" + 品牌欢迎语
- [ ] `ls -a ~` 可见 `.bashrc`、`.mytermux_initialized`、`.termux/colors.properties`
- [ ] 杀进程重开：不再重复部署（幂等），欢迎语仍打印
- [ ] `pkg update` 可用（源未被破坏）
- [ ] （若预装）`which openssh 对应的 ssh` 等预装命令离线可用
- [ ] （若改包名）`echo $PREFIX` 路径为新包名，短授权/快捷方式正常
- [ ] versionCode/versionName 正确，可覆盖升级自己的上一版

---

## §10 常见坑 QA

| # | 现象 | 原因与解法 |
|---|---|---|
| 1 | 改了 bootstrap 重打包，装上后内容还是官方的 | `downloadBootstrap()` 的 SHA-256 校验把本地 zip 删了 → §5.3 B1/B2；另注意 `./gradlew clean` 会删 `src/main/cpp/bootstrap-*.zip` |
| 2 | 首次安装后怎么改初始化脚本都不生效 | bootstrap 只在 `$PREFIX` 不存在时解压 → 开发期 `adb shell pm clear com.termux` 重置（清空数据） |
| 3 | 打包后一打开就闪退/黑屏 | 多为包名改了但 bootstrap 没重编（$PREFIX 硬编码），或 bootstrap 变体与 `TERMUX_PACKAGE_VARIANT` 不符（`apt-android-7/-5`）→ §7.2、§1.3 |
| 4 | `No SYMLINKS.txt encountered` 安装失败 | 自己重打 zip 时弄丢/写坏 `SYMLINKS.txt`；zip 内不能存符号链接，必须全部登记在该文件 → 用 `repack_bootstrap.py` |
| 5 | 只换了传统 PNG，桌面图标没变 | Android 8+ 桌面读 `mipmap-anydpi-v26/ic_launcher.xml` 自适应图标 → §3.3 |
| 6 | 改了 targetSdk / 用 Android 12 Splash 属性没效果 | 本工程 `targetSdk=28`，系统走兼容 Splash；`values-v31` 属性在部分 ROM 不生效 → 以 `windowBackground` 方案兜底，两案并用 → §4 |
| 7 | release 装不上（unsigned / 签名冲突） | 默认 release 无 signingConfig → §8；与官方包同名不同签名必须先卸载 |
| 8 | Termux:API 等插件全部连不上 | `sharedUserId` 或签名/包名变了 → §7.3 |
| 9 | 构建报 `versionName ... is not a valid semantic version` | `versionName` 必须 `x.y.z` 形式，不能 `1.0`/`v1.0` → §2.3 |
| 10 | 首次构建失败在 NDK / bootstrap 下载 | `gradle.properties` 要求 NDK `29.0.14206865`；GitHub Releases 需可达（可手动下载 zip 放入 `src/main/cpp/`） |
| 11 | 图标生成后 round 图标是方的 | 必须圆形透明遮罩 → 用 `gen_icons.sh`（内置 geq 圆形裁切） |
| 12 | 预装了包但 `pkg list-installed` 查不到/卸载异常 | 手工往 `$PREFIX` 拷文件绕过了 dpkg 数据库；预装必须走 bootstrap 的 deb 展开路径（§6 路线 1），postinst 由 second-stage 统一执行 |
| 13 | 想改 `etc/profile` 本身 | 它属于 `bash` 包，`pkg upgrade` 会被覆盖；用 `etc/profile.pre`（最早）或 `etc/profile.d/*.sh`（推荐），官方就是这么设计的 |
| 14 | home 里的文件怎么预置进安装包 | zip 只覆盖 `$PREFIX`；由 profile.d 初始化脚本把 `share/<brand>/dotfiles/` 部署到 `~` → §5.4 |

---

## 附录 A：apktool 快速改皮路线（不编译源码）

适用于只改**图标/名称/部分文案/主题**，不改 bootstrap、不预装、不改包名的场景。

```bash
# 依赖: JDK17+, apktool, zipalign, apksigner (build-tools)
apktool d termux.apk -o termux-src

# 改图标: 覆盖 termux-src/res/mipmap-*/ic_launcher*.png 与 mipmap-anydpi-v26 下引用
# 改名称: termux-src/res/values/strings.xml 中 application_name 的 ENTITY
# 改启动背景: res/values{,-night}/themes.xml 的 Theme.TermuxActivity windowBackground

apktool b termux-src -o termux-modified.apk

# 对齐 + 用你自己的 keystore 签名
zipalign -p 4 termux-modified.apk termux-aligned.apk
apksigner sign --ks release.jks --out termux-final.apk termux-aligned.apk
apksigner verify termux-final.apk
```

**局限**：
- 重签名后与官方签名不同 → 必须卸载官方版才能装（数据清零）；
- 改不了 bootstrap（预装/初始化脚本做不了，`libtermux-bootstrap.so` 是原样的）；
- apktool 对新 AGP 资源（如部分 vector/链接资源）偶发回编译差异，装上后注意回归测试；
- 官方每次发版你都要重新打一遍，无法演进 —— 长期项目请回路线 A。

---

## 附录 B：参考资料

**源码内关键文件（termux/termux-app）**

| 文件 | 内容 |
|---|---|
| `app/build.gradle` | 包变体、版本号、manifestPlaceholders、debug 签名、`downloadBootstraps()` SHA-256、splits |
| `gradle.properties` | minSdk 21 / targetSdk 28 / compileSdk 36 / NDK 29.0.14206865 |
| `app/src/main/AndroidManifest.xml` | icon/label/theme、`sharedUserId`、RUN_COMMAND 权限 |
| `app/src/main/res/values/strings.xml` | ENTITY 应用名、bootstrap 安装文案 |
| `app/src/main/res/values/themes.xml`、`values-night/themes.xml` | 启动窗口 `windowBackground` |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标（背景/前景/单色） |
| `app/src/main/cpp/termux-bootstrap-zip.S`、`Android.mk` | bootstrap `.incbin` 进 `libtermux-bootstrap.so` |
| `app/src/main/java/com/termux/app/TermuxInstaller.java` | 解压/staging/SYMLINKS/chmod 逻辑 |
| `termux-shared/.../TermuxConstants.java` | 改包名官方清单（类注释）、全部路径常量 |
| `termux-shared/.../TermuxSession.java`、`UnixShellEnvironment.java` | 默认 login shell 的确定逻辑 |
| `app/src/main/res/xml/shortcuts.xml` | 硬编码 `com.termux` 的快捷方式 |

**termux-packages**

| 文件 | 内容 |
|---|---|
| `scripts/build-bootstraps.sh` | fork 场景构建 bootstrap（`--add` 预装包），Docker 内运行 |
| `scripts/generate-bootstraps.sh` | 基于已发布 apt 仓库生成 bootstrap |
| `scripts/properties.sh` | `TERMUX_APP_PACKAGE`（换包名的源头） |
| `scripts/bootstrap/termux-bootstrap-second-stage.sh` | 首启 postinst 收尾（一次性 + lock） |
| `packages/bash/etc-profile`、`etc-bash.bashrc` | 登录/交互启动链权威定义 |

**链接**

- https://github.com/termux/termux-app （README 的 *Forking* 一节）
- https://github.com/termux/termux-packages/wiki/Building-packages
- https://github.com/termux/termux-packages/wiki/For-maintainers#build-bootstrap-archives
- https://github.com/termux/termux-app/issues/1983 、 /2081 （改包名实战讨论）
- https://developer.android.com/training/splash-screen （Android 12+ 启动画面）

---

*本指南由配套脚本实测支撑：`repack_bootstrap.py`（仿真 bootstrap 往返验证）、
`gen_icons.sh`（15 个资源尺寸逐一校验）、`overlay/etc/profile.d/90-brand-init.sh`
（幂等/不覆盖/配置开关三轮实测）。详见 `任务报告.md`。*
