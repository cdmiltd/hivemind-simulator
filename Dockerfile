# ========================================
# DJI Dock 模拟器 Docker 镜像
# ========================================
# 前置条件:先在本地执行 mvn package -DskipTests 构建 jar
# SDK 依赖(dji-cloud-api-sdk)为 SNAPSHOT 版本,需先 mvn install 到本地仓库
#
# 构建命令:docker build -t dji-dock-simulator .
# ========================================

FROM eclipse-temurin:21-jre

LABEL maintainer="CDMI"
LABEL description="DJI Dock 机场模拟器/监控器"
LABEL version="1.3.0"

WORKDIR /app

# 安装 ffmpeg(直播真实推流用,不需要可注释此行以减小镜像体积)
# 注意:Debian/Ubuntu 仓库的 ffmpeg 默认未启用 --enable-muxer=whip,
#       WebRTC WHIP 推流需自行编译 ffmpeg(--enable-muxer=whip --enable-libwhip)。
#       RTMP 推流(url_type=1)可直接使用仓库版本。
# 同时安装 curl,供 HEALTHCHECK 健康检查使用
# apt 源替换为清华镜像:境内构建环境访问 archive.ubuntu.com 超时,
# 基础镜像为 Ubuntu(deb822 格式,配置位于 /etc/apt/sources.list.d/ubuntu.sources)
RUN sed -i 's|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g; s|security.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update && apt-get install -y --no-install-recommends ffmpeg curl && rm -rf /var/lib/apt/lists/*

# 创建非 root 用户 simulator(UID 1000)与可选挂载目录
# 提升安全性:容器内进程不以 root 运行,降低逃逸风险
# Ubuntu 26.04 基础镜像自带 ubuntu 用户(UID/GID 1000),先删除再创建 simulator;
# 基础镜像无该用户时 userdel/groupdel 失败不中断(|| true 兜底)
# 挂载目录说明:
#   /app/media  - 模拟照片/视频文件(媒体上传流程读取)
#   /app/videos - 直播推流视频文件(ffmpeg 读取)
#   /app/config - 运行时持久化配置(直播/媒体/机场位置,SIMULATOR_CONFIG_DIR 指向此路径)
RUN userdel -r ubuntu 2>/dev/null || true \
    && groupdel ubuntu 2>/dev/null || true \
    && groupadd -r simulator \
    && useradd -r -g simulator -u 1000 -d /app -s /sbin/nologin simulator \
    && mkdir -p /app/media /app/videos /app/config \
    && chown -R simulator:simulator /app

# 复制构建好的 jar(需先执行 mvn package -DskipTests)
# 通配符匹配 target/dji-dock-simulator-*.jar;如 target/ 存在多版本 jar 会导致 COPY 失败,
# 需先执行 mvn clean package 保留单一版本
COPY --chown=simulator:simulator target/dji-dock-simulator-*.jar app.jar

USER simulator

# 暴露 Web 控制台端口
EXPOSE 9090

# JVM 参数(可通过环境变量覆盖)
# preferIPv4Stack:host.docker.internal 同时解析为 IPv4/IPv6,Java 默认可能优先 IPv6,
# 而 Docker Desktop 的 IPv6 端口转发不可靠,导致 MQTT 连接 Connection lost(32109);
# 强制 IPv4 走 192.168.65.254 转发链路,保证容器内 localhost 自动映射宿主机 Broker 可用(TC-MQTT-011)
ENV JAVA_OPTS="-Xms256m -Xmx512m -Djava.net.preferIPv4Stack=true"

# 时区设置(OSD 时间戳使用本地时区)
ENV TZ=Asia/Shanghai

# 运行时持久化配置目录(LiveConfigStore 通过 SIMULATOR_CONFIG_DIR 环境变量读取)
# 此目录应挂载 named volume,确保容器重启后用户配置不丢失
ENV SIMULATOR_CONFIG_DIR=/app/config

# 媒体上传默认目录(对应 simulator.media.media-dir,Spring relaxed binding)
# 任务完成落地即触发媒体上传,无运行时补传机会;指向已挂载的 media volume,
# 启动时自动预置小体积示例照片/视频,保证 STS→S3→callback 全链路开箱可验证(TC-MEDIA-016/018)
ENV SIMULATOR_MEDIA_MEDIADIR=/app/media

# 健康检查:调用应用统一健康检查接口 /api/health
# 返回 {"status":"UP",...} 即视为健康(HTTP 200),MQTT 未连接不影响 liveness
# start-period=30s 给 Spring Boot 启动留足时间,避免启动期间误报不健康
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -f -s http://localhost:9090/api/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
