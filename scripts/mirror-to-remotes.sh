#!/usr/bin/env bash
# 镜像推送脚本：将稳定版本 tag 同步到 GitHub 和 Gitee 镜像仓库
#
# 公开镜像策略：
#   - Codeup 是主仓库，保留完整文件（含 .codeup/、AGENTS.md、docs/ 等内部文件）
#   - GitHub/Gitee 是公开镜像，推送前会创建 orphan commit，去除排除清单
#     （.github/public-exclude.txt）中列出的文件，仅保留构建和运行所需文件
#   - orphan commit 无 parent，tag 和 master 分支均指向它，避免泄漏内部文件
#
# 使用方式：
#   本地（SSH 认证）：./scripts/mirror-to-remotes.sh v1.0.0
#   CI（Token 认证）：GITHUB_TOKEN=xxx GITEE_TOKEN=xxx ./scripts/mirror-to-remotes.sh v1.0.0
#
# 前置条件：
#   - 本地已配置 github / gitee remote（git remote add github <url>；git remote add gitee <url>）
#   - CI 环境需设置 GITHUB_TOKEN 和 GITEE_TOKEN 环境变量
#     * GITHUB_TOKEN：GitHub Personal Access Token，需 repo 权限
#     * GITEE_TOKEN：Gitee 私人令牌，需 projects 权限（可选，未配置则跳过 Gitee 推送）

set -euo pipefail

GITHUB_SSH_URL="git@github.com:cdmiltd/hivemind-simulator.git"
GITHUB_HTTPS_URL="https://github.com/cdmiltd/hivemind-simulator.git"
GITEE_HTTPS_URL="https://gitee.com/alpeai/hivemind-simulator.git"

# 公开镜像排除清单（相对于仓库根目录）
EXCLUDE_LIST_FILE=".github/public-exclude.txt"

# 自动检测 tag：优先使用参数，其次从 CI 环境变量，最后从 git 自动检测
TAG="${1:-}"
if [[ -z "$TAG" ]]; then
    TAG="${CI_COMMIT_REF_NAME:-}"
    # 防御：部分 CI 系统的 CI_COMMIT_REF_NAME 可能是完整 ref 路径（refs/tags/v1.3.0），剥离前缀
    TAG="${TAG#refs/tags/}"
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

# 验证 tag 存在
if ! git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "错误: tag $TAG 不存在"
    echo "请先执行: git tag -a $TAG -m 'release message'"
    exit 1
fi

COMMIT=$(git rev-list -n 1 "$TAG")
echo "=== 镜像推送 tag $TAG (commit ${COMMIT:0:8}) ==="

# 重试函数：codeup 访问外部仓库（尤其 GitHub）网络偶发不稳定，自动重试
# 通过环境变量可配置重试次数和间隔，默认 5 次，间隔 30 秒
# 环境变量：
#   RETRY_MAX：重试次数，默认 5
#   RETRY_DELAY：重试间隔（秒），默认 30
retry() {
    local max="${RETRY_MAX:-5}" delay="${RETRY_DELAY:-30}" i=1
    while ! "$@"; do
        if (( i >= max )); then
            echo "错误: 重试 $i 次后仍失败"
            return 1
        fi
        echo "第 $i/$((max-1)) 次失败，${delay}s 后重试..."
        sleep $delay
        ((i++))
    done
}

# 读取排除清单（忽略注释和空行）
read_exclude_list() {
    EXCLUDE_PATHS=()
    if [[ ! -f "$EXCLUDE_LIST_FILE" ]]; then
        echo "提示: 排除清单 $EXCLUDE_LIST_FILE 不存在，将推送完整内容"
        return 0
    fi
    local line trimmed
    while IFS= read -r line || [[ -n "$line" ]]; do
        # 去除行首行尾空白
        trimmed="${line#"${line%%[![:space:]]*}"}"
        trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
        # 跳过空行和注释
        [[ -z "$trimmed" || "$trimmed" == \#* ]] && continue
        EXCLUDE_PATHS+=("$trimmed")
    done < "$EXCLUDE_LIST_FILE"
}

# 创建公开镜像 commit（orphan commit，无 parent，去除排除清单中的文件）
# 使用 git plumbing 命令直接操作 index，不污染主工作树
create_mirror_commit() {
    local source_commit="$1"

    # 使用临时 index 文件，避免污染主工作树
    local tmp_index
    tmp_index=$(mktemp)
    export GIT_INDEX_FILE="$tmp_index"

    # CI 环境通常没有全局 git 身份配置，commit-tree 会失败
    # 通过环境变量为本次 commit-tree 设置身份（不污染全局/仓库配置）
    export GIT_AUTHOR_NAME="mirror-bot"
    export GIT_AUTHOR_EMAIL="mirror-bot@codeup-ci"
    export GIT_COMMITTER_NAME="mirror-bot"
    export GIT_COMMITTER_EMAIL="mirror-bot@codeup-ci"

    # 从 tag commit 读取 tree 到 index
    git read-tree "$source_commit"

    # 从 index 中移除排除的文件/目录
    local path
    for path in "${EXCLUDE_PATHS[@]}"; do
        git rm -r --cached --ignore-unmatch "$path" >/dev/null 2>&1 || true
    done
    # 排除清单文件本身也从公开镜像中移除（公开仓库不需要此清单）
    git rm --cached --ignore-unmatch "$EXCLUDE_LIST_FILE" >/dev/null 2>&1 || true

    # 写入 tree 并创建 orphan commit（无 parent）
    local tree orphan_commit
    tree=$(git write-tree)
    orphan_commit=$(git commit-tree "$tree" -m "mirror: $TAG (public view)")

    # 清理临时 index 和身份环境变量
    rm -f "$tmp_index"
    unset GIT_INDEX_FILE
    unset GIT_AUTHOR_NAME GIT_AUTHOR_EMAIL GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL

    echo "$orphan_commit"
}

# 读取排除清单并创建公开镜像 commit
read_exclude_list
if [[ ${#EXCLUDE_PATHS[@]} -gt 0 ]]; then
    echo "=== 创建公开镜像 commit（排除内部文件）==="
    echo "排除清单："
    printf '  - %s\n' "${EXCLUDE_PATHS[@]}"
    MIRROR_COMMIT=$(create_mirror_commit "$COMMIT")
    if [[ -z "$MIRROR_COMMIT" ]]; then
        echo "错误: 创建公开镜像 commit 失败（MIRROR_COMMIT 为空），停止推送以避免删除远程分支/tag"
        exit 1
    fi
    echo "原始 commit: ${COMMIT:0:8}"
    echo "公开 commit: ${MIRROR_COMMIT:0:8}"
else
    echo "提示: 排除清单为空，使用原 commit 推送"
    MIRROR_COMMIT="$COMMIT"
fi

# 推送到指定 remote（tag + master 分支，force 镜像同步）
# tag 和 master 均指向公开镜像 commit，确保 GitHub/Gitee 上不泄漏内部文件
push_to_remote() {
    local remote_name="$1" remote_url="$2"
    # 防御：MIRROR_COMMIT 为空时不应到达此处（前面已 fail-fast），双保险避免删除远程分支/tag
    if [[ -z "$MIRROR_COMMIT" ]]; then
        echo "错误: MIRROR_COMMIT 为空，跳过 $remote_name 推送（避免删除远程分支/tag）"
        return 1
    fi
    echo "--- 推送到 $remote_name ---"
    # 推送 tag（force）指向公开镜像 commit
    retry git push --force "$remote_url" "$MIRROR_COMMIT:refs/tags/$TAG"
    # 推送公开镜像 commit 到 master 分支（force，镜像同步）
    retry git push --force "$remote_url" "$MIRROR_COMMIT:refs/heads/master"
    echo "--- $remote_name 推送完成 ---"
}

# 1. 推送到 GitHub
# 注意：codeup CI 访问 GitHub 可能因网络问题失败，GitHub 推送失败不阻止 Gitee 推送
GITHUB_PUSH_OK=false
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    GITHUB_REMOTE="https://${GITHUB_TOKEN}@github.com/cdmiltd/hivemind-simulator.git"
    echo "[CI] GitHub 使用 GITHUB_TOKEN 认证"
    if push_to_remote "GitHub" "$GITHUB_REMOTE"; then
        GITHUB_PUSH_OK=true
    else
        echo "[警告] GitHub 推送失败，可能原因：网络超时、Token 权限不足、workflow scope 缺失"
        echo "       不阻止后续 Gitee 推送，Gitee 推送和发行版创建会继续执行"
        echo "       GitHub tag 可稍后手动推送：git push --force github $MIRROR_COMMIT:refs/tags/$TAG"
    fi
elif git remote get-url github >/dev/null 2>&1; then
    echo "[本地] GitHub 使用 github remote（SSH/HTTPS）"
    if push_to_remote "GitHub" "github"; then
        GITHUB_PUSH_OK=true
    fi
else
    echo "警告: 未配置 github remote，且未设置 GITHUB_TOKEN，跳过 GitHub 推送"
    echo "       本地配置: git remote add github $GITHUB_SSH_URL"
fi

# 2. 推送到 Gitee（可选，GITEE_TOKEN 未配置则跳过）
# Gitee 推送失败不阻止发行版创建（发行版只需 tag 存在于远程，可能之前推送过）
GITEE_PUSH_OK=false
if [[ -n "${GITEE_TOKEN:-}" ]]; then
    GITEE_REMOTE="https://oauth2:${GITEE_TOKEN}@gitee.com/alpeai/hivemind-simulator.git"
    echo "[CI] Gitee 使用 GITEE_TOKEN 认证"
    if push_to_remote "Gitee" "$GITEE_REMOTE"; then
        GITEE_PUSH_OK=true
    else
        echo "[警告] Gitee 推送失败，仍尝试创建发行版（如果 tag 已存在则可能成功）"
    fi
elif git remote get-url gitee >/dev/null 2>&1; then
    echo "[本地] Gitee 使用 gitee remote（SSH/HTTPS）"
    if push_to_remote "Gitee" "gitee"; then
        GITEE_PUSH_OK=true
    fi
else
    echo "提示: 未配置 gitee remote，且未设置 GITEE_TOKEN，跳过 Gitee 推送"
    echo "      本地配置: git remote add gitee $GITEE_HTTPS_URL"
fi

# 3. 创建 Gitee 发行版（通过 Gitee REST API，需要 GITEE_TOKEN）
# 注意：GitHub Release 由 GitHub Actions 的 release.yml 负责创建（包含桌面应用安装包），
#       此处只创建 Gitee 发行版，避免与 GitHub Actions 重复创建。
if [[ -n "${GITEE_TOKEN:-}" ]]; then
    echo "=== 创建 Gitee 发行版 ==="
    # 调用 Gitee API 创建发行版：https://gitee.com/api/v5/swagger#/postV5ReposOwnerReposReleases
    # body 使用 \n 分行（Gitee API 要求的格式）
    RELEASE_BODY="本发行版由 codeup 流水线自动创建，对应 tag $TAG。"$'\n'"完整变更记录与桌面安装包请查看 GitHub Release："$'\n'"https://github.com/cdmiltd/hivemind-simulator/releases/tag/$TAG"
    HTTP_CODE=$(curl -s -o /tmp/gitee-release-response.json -w "%{http_code}" \
        -X POST "https://gitee.com/api/v5/repos/alpeai/hivemind-simulator/releases" \
        -H "Content-Type: application/json;charset=UTF-8" \
        -d "{\"access_token\":\"${GITEE_TOKEN}\",\"tag_name\":\"${TAG}\",\"name\":\"${TAG}\",\"body\":\"${RELEASE_BODY}\",\"target_commitish\":\"master\"}" \
        || echo "[警告] Gitee API 调用失败")
    if [[ "$HTTP_CODE" == "201" ]]; then
        echo "Gitee 发行版创建成功（HTTP 201）"
    else
        echo "[警告] Gitee 发行版创建失败（HTTP $HTTP_CODE），响应："
        cat /tmp/gitee-release-response.json 2>/dev/null | head -c 500
        echo ""
        echo "       可手动创建: https://gitee.com/alpeai/hivemind-simulator/releases/new?tag=$TAG"
    fi
    rm -f /tmp/gitee-release-response.json
else
    echo "[提示] 未配置 GITEE_TOKEN，跳过 Gitee 发行版创建"
    echo "       可手动创建: https://gitee.com/alpeai/hivemind-simulator/releases/new?tag=$TAG"
fi

echo "=== 完成: $TAG 已同步 ==="
echo "    GitHub: $GITHUB_HTTPS_URL"
echo "    Gitee:  $GITEE_HTTPS_URL"
