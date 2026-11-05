FROM maven:3.9.11-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# 阿里云 Maven 镜像 + 本地仓库缓存挂载
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# apk 走阿里云镜像（ffmpeg 及其依赖约 100MB）
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
    && apk add --no-cache ffmpeg
WORKDIR /app
COPY --from=builder /workspace/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
