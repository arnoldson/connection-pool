#!/usr/bin/env bash
# Reproduces ephemeral port exhaustion with no Java at all: socat in Alpine containers.
# Proves the problem is in the kernel's TCP behavior, not in Jetty or any library.
#
# Each case narrows the port range to 1,000 ports so it fails within seconds, then
# hammers one destination with short connections that the client closes first.
# Takes about a minute. See docs/BACKGROUND.md ("Evidence gathered so far").
set -euo pipefail

client_script=$(mktemp)
trap 'rm -f "$client_script"; docker rm -f smoke-b >/dev/null 2>&1; docker network rm smoke-net >/dev/null 2>&1 || true' EXIT

# Runs inside the client container: connect as fast as possible for $DUR seconds,
# then report successes, failures and the most common error.
cat > "$client_script" <<'EOF'
apk add -q socat iproute2 >/dev/null
[ "${SERVER:-}" = local ] && { socat TCP-LISTEN:8080,fork,reuseaddr SYSTEM:cat & sleep 1; }
end=$(( $(date +%s) + DUR ))
ok=0; fail=0
while [ "$(date +%s)" -lt "$end" ]; do
  # /dev/null sends EOF at once, so the client sends the first FIN and holds TIME_WAIT.
  if socat -T2 /dev/null "TCP:$TARGET:8080${OPTS:-}" 2>>/tmp/err; then ok=$((ok+1)); else fail=$((fail+1)); fi
done
err=$(sed -n 's/.*W \(connect\|bind\)(.*): \(.*\)/\1(): \2/p' /tmp/err | sort | uniq -c | sort -rn | head -1 | sed 's/^ *[0-9]* //')
printf "ok=%-6s fail=%-6s time_wait=%-5s %s\n" "$ok" "$fail" "$(ss -Htan state time-wait | wc -l)" "${err:-}"
EOF

run_case() { # label, extra docker args...
  local label=$1; shift
  printf "%-56s " "$label"
  docker run --rm -e DUR="${DUR:-4}" -v "$client_script:/client.sh:ro" \
    --sysctl net.ipv4.ip_local_port_range="32768 33767" "$@" alpine sh /client.sh
}

echo "Kernel: $(docker run --rm alpine uname -r); 1,000 ephemeral ports per case"
echo

# Two containers over a bridge network: the modern loopback-only reuse default doesn't apply.
docker network create smoke-net >/dev/null
docker run -d --rm --name smoke-b --network smoke-net alpine \
  sh -c 'apk add -q socat && socat TCP-LISTEN:8080,fork,reuseaddr SYSTEM:cat' >/dev/null
sleep 4
run_case "bridge, tw_reuse=2 (default)                -> exhausts" --network smoke-net -e TARGET=smoke-b

# Loopback, same container: the incident's topology.
local_args=(-e TARGET=127.0.0.1 -e SERVER=local)
run_case "loopback, tw_reuse=2 (default)              -> hidden" "${local_args[@]}"
run_case "loopback, tw_reuse=0 (Ubuntu 18.04)         -> exhausts" "${local_args[@]}" --sysctl net.ipv4.tcp_tw_reuse=0
run_case "loopback, tw_reuse=2, timestamps=0          -> exhausts" "${local_args[@]}" --sysctl net.ipv4.tcp_timestamps=0
run_case "loopback, tw_reuse=2, bind() before connect -> exhausts" "${local_args[@]}" -e OPTS=,bind=127.0.0.1:0
