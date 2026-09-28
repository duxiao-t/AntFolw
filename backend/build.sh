#!/usr/bin/env bash
# 宿主无 JDK/Maven，用容器编译；Maven 仓库挂载到 /www/antflow/.m2 持久化，避免每次全量下载依赖。
# 用法: ./build.sh package | ./build.sh clean package | ./build.sh test
#
# 集成测试 PostgresTransactionalIntegrityIntegrationTest 需要一个真的 PostgreSQL：
# 要么给 ANTFLOW_TEST_POSTGRES_URL（外部库），要么让容器能访问宿主 docker socket 起 Testcontainers。
set -e
mkdir -p /www/antflow/.m2

RUN_ARGS=(-v /www/antflow/backend:/app -w /app -v /www/antflow/.m2:/root/.m2)
# 外部测试库要显式传进去——只在宿主机 export 的话，容器里的 System.getenv 读到的是空值，
# 照样会去起 Testcontainers。
if [ -n "${ANTFLOW_TEST_POSTGRES_URL:-}" ]; then
  RUN_ARGS+=(-e ANTFLOW_TEST_POSTGRES_URL -e ANTFLOW_TEST_POSTGRES_USERNAME
             -e ANTFLOW_TEST_POSTGRES_PASSWORD)
fi

MVN_ARGS=("$@")
if [ -z "${ANTFLOW_TEST_POSTGRES_URL:-}" ]; then
  if [ -S /var/run/docker.sock ]; then
    RUN_ARGS+=(-v /var/run/docker.sock:/var/run/docker.sock)
  else
    case " $* " in
      *" test "*)
        echo "build.sh: 未挂载 /var/run/docker.sock 且未设置 ANTFLOW_TEST_POSTGRES_URL，" >&2
        echo "build.sh: 集成测试无法运行。请挂载 socket 或指向一个可用的库。" >&2
        exit 1 ;;
      *)
        # 只排除需要数据库的那一个集成测试：其余单元测试（鉴权、草稿、工作流…）没有任何外部依赖，
        # 一起跳过等于"打包成功但什么都没验证"。
        echo "build.sh: 无 docker socket 且未设置 ANTFLOW_TEST_POSTGRES_URL，" >&2
        echo "build.sh: 本次构建跳过 PostgresTransactionalIntegrityIntegrationTest，其余测试照跑。" >&2
        MVN_ARGS+=(-Dtest=!PostgresTransactionalIntegrityIntegrationTest) ;;
    esac
  fi
fi

docker run --rm \
  "${RUN_ARGS[@]}" \
  maven:3.9-eclipse-temurin-17 \
  mvn -B "${MVN_ARGS[@]}"
