#!/usr/bin/env bash
# 宿主无 JDK/Maven，用容器编译；Maven 仓库挂载到 /www/antflow/.m2 持久化，避免每次全量下载依赖。
# 用法: ./build.sh package | ./build.sh clean package | ./build.sh test
set -e
mkdir -p /www/antflow/.m2
docker run --rm \
  -v /www/antflow/backend:/app -w /app \
  -v /www/antflow/.m2:/root/.m2 \
  maven:3.9-eclipse-temurin-17 \
  mvn -B -DskipTests "$@"
