#!/usr/bin/env bash
set -euo pipefail
umask 077

state="$(cd -- "$(dirname -- "$0")" && pwd)/.runtime/shared-test/tunnel"
container=xiaozhi-shared-test-tunnel

case "${1:-}" in
  stop)
    docker stop "$container"
    docker rm "$container"
    exit
    ;;
  configure|start) ;;
  *)
    printf 'Usage: bash dev/shared-test-tunnel.sh {configure|start|stop}\n' >&2
    exit 2
    ;;
esac

mkdir -p "$state"
if [[ ! -x "$state/ali-instance-cli" ]]; then
  case "$(docker info --format '{{.Architecture}}')" in
    arm64|aarch64) download=https://aliyun-client-assist.oss-cn-beijing.aliyuncs.com/session-manager/linux_arm/ali-instance-cli ;;
    amd64|x86_64) download=https://aliyun-client-assist.oss-accelerate.aliyuncs.com/session-manager/linux/ali-instance-cli ;;
    *) printf 'Unsupported Docker architecture\n' >&2; exit 1 ;;
  esac
  temporary="$(mktemp "$state/ali-instance-cli.XXXXXX")"
  trap 'rm -f -- "$temporary"' EXIT
  curl --fail --silent --show-error --location --proto '=https' --max-time 60 "$download" -o "$temporary"
  chmod 700 "$temporary"
  mv "$temporary" "$state/ali-instance-cli"
fi

runtime=(--user "$(id -u):$(id -g)" --env HOME=/tmp
  --mount "type=bind,src=$state,dst=/state" --workdir /tmp --entrypoint /state/ali-instance-cli)
image=redis:7.4-alpine
if [[ "$1" == configure ]]; then
  docker run --rm -it "${runtime[@]}" "$image" configure --mode AK --config-path /state/credentials.json
  chmod 600 "$state/credentials.json"
else
  if [[ ! -s "$state/credentials.json" ]]; then
    printf 'Run bash dev/shared-test-tunnel.sh configure first\n' >&2
    exit 1
  fi
  # Keep the CLI's wildcard listener inside the container.
  docker run --detach "${runtime[@]}" --name "$container" --publish 127.0.0.1:13306:13306 "$image" \
    portforward --config-path /state/credentials.json --region cn-shanghai \
    --instance i-uf682p8219hdonw3ggc4 --local-port 13306 \
    --remote-port rm-uf6p64yt1d74mnjo4125010.mysql.rds.aliyuncs.com:3306
fi
