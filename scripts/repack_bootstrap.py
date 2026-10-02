#!/usr/bin/env python3
"""repack_bootstrap.py — 定制 Termux bootstrap zip（仅用 Python3 标准库）。

Termux 官方 bootstrap zip（bootstrap-<arch>.zip）的条目路径相对于 $PREFIX
（即 data/data/<pkg>/files/usr），符号链接不直接存放在 zip 中，而是集中记录在
SYMLINKS.txt（格式:  <链接目标>←<zip内相对路径>）。

本脚本流程：
  1. 读取官方 bootstrap zip 的全部条目（保留原有属性与 SYMLINKS.txt）；
  2. 把 overlay 目录下的文件合并进 zip：同名覆盖，异名追加；
     overlay 中的符号链接不作为文件存储，而是追加到 SYMLINKS.txt；
  3. 输出定制后的 zip，并打印 SHA-256 与 app/build.gradle 打补丁的说明。

用法:
  python3 repack_bootstrap.py --bootstrap app/src/main/cpp/bootstrap-aarch64.zip \
      --overlay overlay/ --out bootstrap-aarch64.zip
"""

import argparse
import fnmatch
import hashlib
import io
import os
import stat
import sys
import zipfile


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def collect_overlay(overlay_root: str):
    """遍历 overlay，返回 (regular_files, symlink_entries)。

    regular_files: list[(zip_path, abs_path, mode)]
    symlink_entries: list[(zip_path, link_target)]
    """
    regular_files = []
    symlink_entries = []
    overlay_root = os.path.abspath(overlay_root)
    for dirpath, dirnames, filenames in os.walk(overlay_root, followlinks=False):
        # 目录本身是符号链接时 os.walk 不会深入（followlinks=False），单独处理
        rel_dir = os.path.relpath(dirpath, overlay_root)
        for name in sorted(dirnames):
            abs_path = os.path.join(dirpath, name)
            zip_path = name if rel_dir == "." else f"{rel_dir}/{name}"
            if os.path.islink(abs_path):
                symlink_entries.append((zip_path, os.readlink(abs_path)))
                dirnames.remove(name)  # 不再深入
        for name in sorted(filenames):
            abs_path = os.path.join(dirpath, name)
            zip_path = name if rel_dir == "." else f"{rel_dir}/{name}"
            if os.path.islink(abs_path):
                symlink_entries.append((zip_path, os.readlink(abs_path)))
            else:
                mode = stat.S_IMODE(os.stat(abs_path).st_mode)
                regular_files.append((zip_path, abs_path, mode))
    return regular_files, symlink_entries


def ensure_dir_entries(zf_entries: dict, zip_path: str):
    """为新增文件补齐目录条目（zip 中目录名以 / 结尾）。"""
    parts = zip_path.split("/")[:-1]
    cur = ""
    for p in parts:
        cur = f"{cur}{p}/"
        if cur not in zf_entries:
            di = zipfile.ZipInfo(cur)
            di.create_system = 3  # unix
            di.external_attr = (stat.S_IFDIR | 0o755) << 16 | 0x10
            di.compress_type = zipfile.ZIP_STORED
            zf_entries[cur] = (di, b"")


def main() -> int:
    ap = argparse.ArgumentParser(description="把 overlay 合并进官方 Termux bootstrap zip")
    ap.add_argument("--bootstrap", required=True, help="官方 bootstrap-<arch>.zip 路径")
    ap.add_argument("--overlay", required=True, help="overlay 根目录（路径相对于 $PREFIX）")
    ap.add_argument("--out", default=None, help="输出 zip 路径（默认覆盖原文件，另存为 *_custom.zip）")
    ap.add_argument("--exclude", action="append", default=[],
                    help="排除的条目 glob（可重复），如 '.git/*'、'*.playwright-cli/*'。"
                         "注意 repack 是合并语义：从已有 zip 中删除条目必须用它（或换干净基线）")
    args = ap.parse_args()

    if not os.path.isfile(args.bootstrap):
        print(f"[!] 找不到 bootstrap zip: {args.bootstrap}", file=sys.stderr)
        return 1
    if not os.path.isdir(args.overlay):
        print(f"[!] 找不到 overlay 目录: {args.overlay}", file=sys.stderr)
        return 1

    out_path = args.out or args.bootstrap.replace(".zip", "_custom.zip")

    # ---- 1. 读取源 zip ----
    entries = {}  # name -> (ZipInfo, data)
    excluded = 0
    with zipfile.ZipFile(args.bootstrap, "r") as src:
        for info in src.infolist():
            if args.exclude and any(
                fnmatch.fnmatch(info.filename, pat) or fnmatch.fnmatch(info.filename, pat.rstrip("/") + "/*")
                for pat in args.exclude
            ):
                excluded += 1
                continue
            entries[info.filename] = (info, src.read(info.filename))
    if excluded:
        print(f"[*] --exclude 排除旧条目: {excluded}")

    if "SYMLINKS.txt" not in entries:
        print("[!] 警告: 源 zip 中没有 SYMLINKS.txt，可能不是 Termux 官方 bootstrap", file=sys.stderr)

    # ---- 2. 合并 overlay ----
    regular_files, symlink_entries = collect_overlay(args.overlay)
    replaced, added = [], []

    for zip_path, abs_path, mode in regular_files:
        with open(abs_path, "rb") as f:
            data = f.read()
        ensure_dir_entries(entries, zip_path)
        if zip_path in entries:
            replaced.append(zip_path)
        else:
            added.append(zip_path)
        info = zipfile.ZipInfo(zip_path)
        info.create_system = 3  # unix
        # 保留可执行位（TermuxInstaller 会对 bin/ 等强制 0700，其余按 owner 可读即可）
        file_type = stat.S_IFREG
        info.external_attr = (file_type | mode) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        entries[zip_path] = (info, data)

    # ---- 3. 追加符号链接到 SYMLINKS.txt ----
    if symlink_entries:
        if "SYMLINKS.txt" in entries:
            _, sl_data = entries["SYMLINKS.txt"]
            sl_lines = sl_data.decode("utf-8", "replace")
        else:
            sl_lines = ""
        existing = set()
        for l in sl_lines.splitlines():
            if "←" in l:
                existing.add(l.split("←", 1)[1].strip().removeprefix("./"))  # 归一化 ./ 前缀
        appended = []
        for zip_path, target in symlink_entries:
            norm = zip_path.removeprefix("./")
            if norm in existing:
                continue
            sl_lines += f"{target}←./{norm}\n"
            existing.add(norm)
            appended.append(zip_path)
        info = zipfile.ZipInfo("SYMLINKS.txt")
        info.create_system = 3
        info.external_attr = (stat.S_IFREG | 0o644) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        entries["SYMLINKS.txt"] = (info, sl_lines.encode("utf-8"))
        print(f"[*] 追加符号链接 {len(appended)} 条 -> SYMLINKS.txt: {appended}")

    # ---- 4. 写出 ----
    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as dst:
        for name in sorted(entries):
            info, data = entries[name]
            dst.writestr(info, data)

    digest = sha256_file(out_path)
    size = os.path.getsize(out_path)

    print("[*] 覆盖同名条目 (%d):" % len(replaced))
    for n in replaced:
        print("      -", n)
    print("[*] 新增条目 (%d):" % len(added))
    for n in added:
        print("      +", n)
    print(f"[*] 输出: {out_path}  ({size} bytes)")
    print(f"[*] SHA-256: {digest}")
    print()
    print("下一步（把它打进 APK）：")
    print(f"  1) cp {out_path} app/src/main/cpp/bootstrap-<arch>.zip   # 覆盖同名文件")
    print("  2) 打开 app/build.gradle，把 downloadBootstraps() 中该架构的 expectedChecksum")
    print(f"     替换为: {digest}")
    print("     （或直接让 downloadBootstrap() 跳过对本地文件的校验，详见指南 §6.3）")
    print("  3) ./gradlew assembleRelease   # zip 会被 .incbin 编进 libtermux-bootstrap.so")
    return 0


if __name__ == "__main__":
    sys.exit(main())
