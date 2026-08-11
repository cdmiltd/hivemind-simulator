#!/usr/bin/env bash
# 镜像推送脚本：将稳定版本 tag 同步到 GitHub 开源仓库
#
# 使用方式：
#   本地（SSH 认证）：./scripts/mirror-to-github.sh v1.0.0
#   CI（Token 认证）：GITHUB_TOKEN=xxx ./scripts/mirror-to-github.sh v1.0.0
#
# 前置条件：
#   - 本地已配置 github remote（git remote add github <url>）
#   - CI 环境需设置 GITHUB_TOKEN 环境变量（GitHub Personal Access Token，需 repo 权限）
#   - 已安装 gh CLI（可选，用于自动创建 GitHub Release）

set -euo pipefail

GITHUB_SSH_URL="git@github.com:cdmiltd/hivemind-simulator.git"
GITHUB_HTTPS_URL="https://github.com/cdmiltd/hivemind-simulator.git"

# 自动检测 tag：优先使用参数，其次从 CI 环境变量，最后从 git 自动检测
TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    TAG="${CI_COMMIT_REF_NAME:-}"
fi
if [[ -z "$TAG" ]]; then
    TAG=$(git describe --tags --exact-match HEAD 2>/dev/null || true)
fi
if [[ -z "$TAG" ]]; then
    echo "错误: 无法确定 tag，请传入参数或确保在 tag commit 上运行"
    echo "用法: $0 <tag>，例如: $0 v1.0.0"
    exit 1
fi
echo "=== 检测到 tag: $TAG ==="

# 验证 tag 格式（以 v 开头），非版本 tag 则跳过（可能是分支推送触发流水线）
if [[ ! "$TAG" =~ ^v ]]; then
    echo "跳过: '$TAG' 不是版本 tag（应以 v 开头），可能是分支推送触发，无需镜像"
    exit 0
fi

# CI 环境：使用 Token 认证
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    GITHUB_REMOTE="https://${GITHUB_TOKEN}@github.com/cdmiltd/hivemind-simulator.git"
    echo "[CI] 使用 GITHUB_TOKEN 认证"
elif git remote get-url github >/dev/null 2>&1; then
    GITHUB_REMOTE="github"
    echo "[本地] 使用 github remote（SSH/HTTPS）"
else
    echo "错误: 未配置 github remote，且未设置 GITHUB_TOKEN"
    echo "请执行: git remote add github $GITHUB_SSH_URL"
    exit 1
fi

# 验证 tag 存在
if ! git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "错误: tag $TAG 不存在"
    echo "请先执行: git tag -a $TAG -m 'release message'"
    exit 1
fi

COMMIT=$(git rev-list -n 1 "$TAG")
echo "=== 镜像推送 tag $TAG (commit ${COMMIT:0:8}) 到 GitHub ==="

# 重试函数：codeup 访问 GitHub 网络偶发不稳定，自动重试
retry() {
    local max=3 delay=10 i=1
    while ! "$@"; do
        if (( i >= max )); then
            echo "错误: 重试 $i 次后仍失败"
            return 1
        fi
        echo "第 $i 次失败，${delay}s 后重试..."
        sleep $delay
        ((i++))
    done
}

# 1. 推送 tag
retry git push "$GITHUB_REMOTE" "refs/tags/$TAG"

# 2. 将 tag 对应的 commit 推送到 GitHub master 分支（force，因为是镜像同步）
retry git push --force "$GITHUB_REMOTE" "$COMMIT:refs/heads/master"

echo "=== 推送完成 ==="

# 3. 创建 GitHub Release（如果 gh CLI 可用且已认证）
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    echo "=== 创建 GitHub Release ==="
    gh release create "$TAG" \
        --repo cdmiltd/hivemind-simulator \
        --title "$TAG" \
        --generate-notes \
        --target master \
        || echo "[警告] GitHub Release 创建失败，可手动创建: gh release create $TAG"
else
    echo "[提示] gh CLI 未安装或未认证，跳过 GitHub Release 创建"
    echo "       安装 gh CLI 后可手动执行: gh release create $TAG"
fi

echo "=== 完成: $TAG 已同步到 GitHub ==="
echo "    仓库地址: $GITHUB_HTTPS_URL"
