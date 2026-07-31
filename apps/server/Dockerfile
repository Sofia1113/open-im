# OpenIM 服务端镜像
#
# 构建（在仓库根目录执行，因为需要 pom.xml 与源码）：
#   docker build -f apps/server/Dockerfile -t openim-server:local apps/server
#
# 分三个阶段：依赖解析、构建、运行。依赖单独一层，只要 pom.xml 没变，
# 修改源码时就能复用缓存，不必重新下载整个依赖树。

# ---------- 阶段 1：依赖 ----------
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build
COPY pom.xml ./
# go-offline 会预取依赖；插件解析偶尔会因可选插件缺失而失败，不影响后续构建
RUN mvn -B dependency:go-offline -DskipTests || true

# ---------- 阶段 2：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY --from=deps /root/.m2 /root/.m2
COPY pom.xml ./
COPY src ./src
RUN mvn -B package -DskipTests

# 展开可执行 JAR：分层拷贝使依赖层与应用层分离，
# 只改业务代码时推送的增量仅为最后一层
RUN java -Djarmode=tools -jar target/open-im-server-*.jar \
        extract --layers --launcher --destination extracted

# ---------- 阶段 3：运行 ----------
FROM eclipse-temurin:25-jre-alpine AS runtime

# 以非 root 运行：容器逃逸的影响面显著小于 root 进程
RUN addgroup -S openim && adduser -S -G openim openim

WORKDIR /app

# curl 供健康检查使用；JRE 基础镜像不自带
RUN apk add --no-cache curl

COPY --from=build --chown=openim:openim /build/extracted/dependencies/ ./
COPY --from=build --chown=openim:openim /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=openim:openim /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=openim:openim /build/extracted/application/ ./

USER openim

EXPOSE 3001

# 容器内存限制下让 JVM 按 cgroup 配额计算堆大小，而不是按宿主机物理内存
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# 就绪探针：数据库不可用时返回非 200，使流量被摘除而不是继续打进来
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -fsS http://127.0.0.1:3001/actuator/health/readiness || exit 1

# 分层提取产生的是展开目录而非单个 jar，因此用 JarLauncher 而不是 java -jar
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
