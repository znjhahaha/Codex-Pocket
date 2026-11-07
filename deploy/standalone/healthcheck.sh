#!/bin/bash
set -euo pipefail
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r status <&3
[[ "$status" == $'HTTP/1.1 200 OK\r' ]]
