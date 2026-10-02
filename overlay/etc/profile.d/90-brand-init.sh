#!/data/data/com.termux/files/usr/bin/sh
# ---------------------------------------------------------------------------
# 智衍 SelfEvolution —— 容器初始化与双服务守护（bootstrap overlay）
#
# 由 $PREFIX/etc/profile 在每个 login shell 会话 source（默认会话即 login shell）。
# 职责：
#   1) 首次运行：部署 ~ 下 dotfiles（不覆盖已有文件），写幂等标记；
#   2) 每次运行：异步守护两个服务 —— 后端 :3000 (McpServer) / 前端 :5173 (HtmlView)：
#        node 兜底安装 → 前端依赖 npm install（仅首次）→ restart.sh all 拉起 → 健康自检；
#   3) 打印品牌欢迎语与服务状态。
# 项目位置：$PREFIX/share/selfevolution（随 bootstrap 解压预置，无需拷贝）
# ---------------------------------------------------------------------------

if [ -z "${PREFIX:-}" ] || [ -z "${HOME:-}" ]; then
	return 0 2>/dev/null || exit 0
fi

BRAND_NAME="智衍"
BRAND_EN="SelfEvolution"
BRAND_TAGLINE="智衍，无限进化成你想要的模样"
BRAND_SHARE="$PREFIX/share/mytermux"
BRAND_DOTFILES="$BRAND_SHARE/dotfiles"
BRAND_MARKER="$HOME/.selfevolution_initialized"
SE_ROOT="$PREFIX/share/selfevolution"
SE_LOG="${TMPDIR:-/tmp}/se-services.log"
SE_LOCK="${TMPDIR:-/tmp}/.se_svc_lock"
SE_READY="${TMPDIR:-/tmp}/se_services_ready.tmp"

# 可选配置：$PREFIX/etc/selfevolution.conf
BRAND_WELCOME="1"
if [ -r "$PREFIX/etc/selfevolution.conf" ]; then
	# shellcheck disable=SC1091
	. "$PREFIX/etc/selfevolution.conf"
fi

# --------------------------- 首次运行：dotfiles 部署 ---------------------------
if [ ! -f "$BRAND_MARKER" ]; then
	if [ -d "$BRAND_DOTFILES" ]; then
		if [ -f "$BRAND_DOTFILES/bashrc" ] && [ ! -e "$HOME/.bashrc" ]; then
			cp "$BRAND_DOTFILES/bashrc" "$HOME/.bashrc"
		fi
		if [ -d "$BRAND_DOTFILES/termux" ]; then
			mkdir -p "$HOME/.termux"
			for f in "$BRAND_DOTFILES/termux"/*; do
				[ -e "$f" ] || continue
				base="$(basename "$f")"
				[ -e "$HOME/.termux/$base" ] || cp -R "$f" "$HOME/.termux/$base"
			done
		fi
	fi
	{
		echo "initialized_at=$(date '+%Y-%m-%d %H:%M:%S')"
		echo "brand=$BRAND_NAME ($BRAND_EN)"
	} > "$BRAND_MARKER"

	# （按需求）大模型配置 auth.json 已从打包中移除：不在首启部署凭据，
	# 任务系统调用 mimo 前需在设备侧自行完成 mimo auth login / 放入 auth.json。

	echo ""
	echo "  [$BRAND_NAME] 首次初始化完成：环境与项目已就绪"
	echo ""
fi

# --------------------------- 双服务异步守护 ---------------------------
services_up() {
	# 后端3000 与 前端5173 都在 LISTEN 即视为就绪（iproute2 的 ss 已随 bootstrap 预置）
	ss -ltn 2>/dev/null | grep -q ':3000 ' || return 1
	ss -ltn 2>/dev/null | grep -q ':5173 ' || return 1
	return 0
}

ensure_services() {
	# 已就绪则秒退；否则拿锁异步执行（mkdir 原子锁，防并发会话重复拉起）
	services_up && return 0
	mkdir "$SE_LOCK" 2>/dev/null || return 0
	(
		set +e
		{
			echo "[$(date '+%H:%M:%S')] se ensure start"
			# 1) node/npm 兜底（正常情况下已随 bootstrap 预置，无需联网）
			if ! command -v node >/dev/null 2>&1; then
				echo "node missing -> pkg install nodejs npm iproute2"
				pkg install -y nodejs npm iproute2
			fi
			# 1.5) 补执行位（zip 解压可能丢执行位的兜底，幂等快速）
			chmod u+rx "$SE_ROOT/restart.sh" 2>/dev/null
			if [ -d "$PREFIX/lib/node_modules" ]; then
				find "$PREFIX/lib/node_modules" -type f -exec chmod u+x {} + 2>/dev/null
			fi

			# 2) 前端依赖：仅首次（平台相关原生件必须在设备上安装）
			if [ -d "$SE_ROOT/App/HtmlView" ] && [ ! -d "$SE_ROOT/App/HtmlView/node_modules" ]; then
				echo "frontend deps -> npm install (npmmirror)"
				(cd "$SE_ROOT/App/HtmlView" && npm install --no-audit --no-fund)
			fi
			# 3) 拉起后端:3000 + 前端:5173（restart.sh 自带停止/启动/HTTP冒烟）
			if ! services_up; then
				echo "starting services via restart.sh"
				chmod +x "$SE_ROOT/restart.sh" 2>/dev/null
				# restart.sh 全部✅（后端+前端均就绪）时退出码才为 0
				if "$SE_ROOT/restart.sh" all; then
					echo "both services up -> opening frontend in in-app browser"
					# 首选：应用内嵌浏览器（SelfWebViewActivity）；失败回退系统浏览器
					if am start -n com.termux/.app.SelfWebViewActivity >/dev/null 2>&1; then
						echo "opened: com.termux/.app.SelfWebViewActivity (http://localhost:5173)"
					elif command -v termux-open-url >/dev/null 2>&1; then
						termux-open-url "http://localhost:5173" || true
						echo "fallback: external browser"
					else
						termux-am start -a android.intent.action.VIEW -d "http://localhost:5173" || true
						echo "fallback: termux-am VIEW"
					fi
				fi
			fi
			echo "[$(date '+%H:%M:%S')] se ensure done"
		} >> "$SE_LOG" 2>&1
		touch "$SE_READY"
		rmdir "$SE_LOCK" 2>/dev/null
	) >/dev/null 2>&1 &
}

ensure_services

# --------------------------- 欢迎语 + 服务状态 ---------------------------
if [ "$BRAND_WELCOME" = "1" ]; then
	printf '\n  \033[1;36m%s\033[0m \033[0;90m%s\033[0m\n' "$BRAND_NAME" "$BRAND_EN"
	printf '  \033[0;90m%s\033[0m\n\n' "$BRAND_TAGLINE"
	if services_up; then
		printf '  \033[0;90m服务 \033[0mbackend :3000 ✅   frontend :5173 ✅   \033[0;90m(打开浏览器访问 http://localhost:5173)\033[0m\n\n'
	elif [ -d "$SE_LOCK" ]; then
		printf '  \033[0;90m服务 \033[0m⏳ 初始化中（首次需安装前端依赖，约1-3分钟）→ 查看: \033[0mtail -f %s\033[0m\n\n' "$SE_LOG"
	else
		printf '  \033[0;90m服务 \033[0m⏳ 正在拉起 backend:3000 / frontend:5173 …\n\n'
	fi
fi

true
