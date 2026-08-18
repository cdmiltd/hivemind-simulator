# ========================================
# DJI Dock 模拟器 Docker 镜像
# ========================================
# 前置条件：先在本地执行 mvn package -DskipTests 构建 jar
# SDK 依赖（dji-cloud-api-sdk）为 SNAPSHOT 版本，需先 mvn install 到本地仓库
#
# 构建命令：docker build -t dji-dock-simulator .
# ========================================

FROM eclipse-temurin:21-jre

LABEL maintainer="CDMI"
LABEL description="DJI Dock 机场模拟器/监控器"
LABEL version="1.1.2"

WORKDIR /app

# 安装 ffmpeg（直播真实推流用，不需要可注释此行以减小镜像体积）
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*

# 复制构建好的 jar（需先执行 mvn package -DskipTests）
COPY target/dji-dock-simulator-*.jar app.jar

# 创建可选挂载目录
RUN mkdir -p /app/media /app/videos

# 暴露 Web 控制台端口
EXPOSE 9090

# JVM 参数（可通过环境变量覆盖）
ENV JAVA_OPTS="-Xms256m -Xmx512m"

# 时区设置（OSD 时间戳使用本地时区）
ENV TZ=Asia/Shanghai

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
