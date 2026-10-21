#!/usr/bin/env bash
# 宿主无 JDK/Maven，用容器编译；Maven 仓库默认挂到 /www/antflow/.m2 持久化（服务器约定），
# 可用 ANTFLOW_M2_DIR 覆盖。用法: ./build.sh package | ./build.sh clean package | ./build.sh test
#
# 集成测试与 OpenAPI 契约测试需要一个真的 PostgreSQL：
# 要么给 ANTFLOW_TEST_POSTGRES_URL（外部库），要么让容器能访问宿主 docker socket 起 Testcontainers。
set -e

# 路径一律从脚本自身位置推导。曾经硬编码 /www/antflow/*，在非该布局的机器上会挂到空目录，
# maven 直接 "no POM in this directory"，而报错完全不像"路径错了"（排查了很久）。
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(dirname "$HERE")
M2_DIR=${ANTFLOW_M2_DIR:-/www/antflow/.m2}
mkdir -p "$M2_DIR"

# Git Bash（MSYS）会把裸的 /app 这类参数改写成 Windows 路径，导致
# "the working directory 'D:/.../app' is invalid"。docker 只认容器内路径，必须关掉转换。
export MSYS_NO_PATHCONV=1

# 同一个路径在不同平台要交给 docker 的形态不同：Windows 上 Git Bash 的 /e/... 会被当成
# VM 内路径（挂上去是空的），必须转成 E:/...；Linux 上没有 cygpath，原样返回。
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# 保留仓库相对路径：打包资源、共享 fixture 与 BI 角色集成测试都读取 backend 之外的文件。
RUN_ARGS=(-v "$(host_path "$HERE")":/app -w /app -v "$(host_path "$M2_DIR")":/root/.m2
          -v "$(host_path "$ROOT/frontend/config")":/frontend/config:ro
          -v "$(host_path "$ROOT/fixtures")":/fixtures:ro
          -v "$(host_path "$ROOT/infra")":/infra:ro)
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
        # 仅排除需要 PostgreSQL 的测试，其余单元测试仍须通过。
        echo "build.sh: 无 docker socket 且未设置 ANTFLOW_TEST_POSTGRES_URL，" >&2
        echo "build.sh: 本次构建跳过 PostgresTransactionalIntegrityIntegrationTest 和 ApiContractSnapshotTest，其余测试照跑。" >&2
        MVN_ARGS+=('-Dtest=!PostgresTransactionalIntegrityIntegrationTest,!ApiContractSnapshotTest') ;;
    esac
  fi
fi

docker run --rm \
  "${RUN_ARGS[@]}" \
  maven:3.9-eclipse-temurin-17 \
  mvn -B "${MVN_ARGS[@]}"
