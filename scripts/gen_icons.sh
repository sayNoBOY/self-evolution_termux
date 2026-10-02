#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# gen_icons.sh — 用一张主图生成 Termux 二次打包所需的全套 Android 图标资源
#
# 依赖: ffmpeg（负责缩放与圆形遮罩）
#
# 用法:
#   ./gen_icons.sh <主图.png|jpg> <输出目录> [--adaptive] [--banner <横幅图>]
#
#   <输出目录>  通常是 termux-app/app/src/main/res
#   --adaptive  额外生成自适应图标(AcitiveIcon)前景层 PNG
#   --banner    用指定图片生成 Android TV 横幅 banner.png (640x360)
#
# 产物:
#   mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png       普通图标
#   mipmap-{...}/ic_launcher_round.png                            圆形图标(圆形透明遮罩)
#   drawable-{...}/ic_foreground_layer.png     (仅 --adaptive) 自适应前景层
#   drawable/banner.png                                        (仅 --banner)
# ---------------------------------------------------------------------------
set -euo pipefail

if [ $# -lt 2 ]; then
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi

MASTER="$1"
OUT="$2"
shift 2

ADAPTIVE=0
BANNER=""
while [ $# -gt 0 ]; do
    case "$1" in
        --adaptive) ADAPTIVE=1; shift ;;
        --banner)   BANNER="${2:?--banner 需要图片路径}"; shift 2 ;;
        *) echo "[!] 未知参数: $1" >&2; exit 1 ;;
    esac
done

if [ ! -f "$MASTER" ]; then echo "[!] 主图不存在: $MASTER" >&2; exit 1; fi
if ! command -v ffmpeg >/dev/null 2>&1; then echo "[!] 需要 ffmpeg" >&2; exit 1; fi

# 密度表: 目录:边长(dp对应的px)
DENSITIES="mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192"
# 自适应前景层边距: 108dp 基准 (safe zone 为 66/108)
FG_DENSITIES="mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432"

make_square() { # $1=size $2=out
    ffmpeg -hide_banner -loglevel error -y -i "$MASTER" \
        -vf "scale=$1:$1:flags=lanczos,format=rgba" -frames:v 1 "$2"
}

make_round() { # $1=size $2=out   （圆形透明遮罩，圆心 (s-1)/2, 半径 s/2）
    local s="$1" out="$2"
    local cx cy r2
    cx=$(awk "BEGIN{printf \"%.1f\", ($s-1)/2}")
    cy="$cx"
    r2=$(awk "BEGIN{printf \"%.1f\", ($s/2)*($s/2)}")
    ffmpeg -hide_banner -loglevel error -y -i "$MASTER" \
        -vf "scale=$s:$s:flags=lanczos,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lte(pow(X-$cx,2)+pow(Y-$cy,2),$r2),alpha(X,Y),0)'" \
        -frames:v 1 "$out"
}

mkdir -p "$OUT"
count=0

# ---------- 1) 传统启动图标 ----------
for entry in $DENSITIES; do
    d="${entry%%:*}"; s="${entry##*:}"
    mkdir -p "$OUT/mipmap-$d"
    make_square "$s" "$OUT/mipmap-$d/ic_launcher.png"
    make_round   "$s" "$OUT/mipmap-$d/ic_launcher_round.png"
    count=$((count+2))
    echo "[+] mipmap-$d/ic_launcher{,_round}.png  (${s}x${s})"
done

# ---------- 2) 自适应图标前景层（可选）----------
if [ "$ADAPTIVE" = "1" ]; then
    for entry in $FG_DENSITIES; do
        d="${entry%%:*}"; s="${entry##*:}"
        mkdir -p "$OUT/drawable-$d"
        make_square "$s" "$OUT/drawable-$d/ic_foreground_layer.png"
        count=$((count+1))
        echo "[+] drawable-$d/ic_foreground_layer.png  (${s}x${s})"
    done
    cat <<'TIP'

  自适应图标接入方法（termux-app）:
    1) 把生成的 drawable-*/ic_foreground_layer.png 放入 app/src/main/res/；
    2) 编辑 app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml 与 ic_launcher_round.xml:
         <foreground android:drawable="@drawable/ic_foreground_layer"/>
       （背景可继续用 @android:color/black，或改为你的品牌色/图片）
    3) 删除原矢量前景 drawable/ic_foreground.xml 引用，避免重名冲突。
TIP
fi

# ---------- 3) Android TV 横幅 banner（可选）----------
if [ -n "$BANNER" ]; then
    mkdir -p "$OUT/drawable"
    ffmpeg -hide_banner -loglevel error -y -i "$BANNER" \
        -vf "scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2:black,format=rgba" \
        -frames:v 1 "$OUT/drawable/banner.png"
    count=$((count+1))
    echo "[+] drawable/banner.png (640x360)"
fi

echo
echo "[*] 共生成 $count 个文件到 $OUT"
echo "[*] 下一步: 将对应文件复制到 termux-app/app/src/main/res/ 覆盖同名资源，"
echo "    清理并重新构建: ./gradlew clean assembleRelease"
