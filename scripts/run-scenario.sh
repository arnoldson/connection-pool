#!/usr/bin/env bash
# Runs one load scenario and collects its evidence into results/<name>/.
#
#   scripts/run-scenario.sh <name> <fresh|pool|jetty> <tw_reuse 0|1> [rate] [duration]
#
# Steps: start B + A + sampler -> warm up -> wait for TIME_WAIT to drain ->
#        oha at a fixed rate -> collect sockets.csv, a-stats.csv, cpu.csv, oha.json, a.log -> stop.
set -euo pipefail

name=${1:?name}; client=${2:?client}; tw_reuse=${3:?tw_reuse}
rate=${4:-1000}; duration=${5:-150s}

cd "$(dirname "$0")/.."
compose="docker compose -f docker/compose.yaml"
out=results/_current
target=http://b:8081/call

rm -rf "$out" && mkdir -p "$out"
export CLIENT=$client TW_REUSE=$tw_reuse

echo "== $name: CLIENT=$client tcp_tw_reuse=$tw_reuse rate=$rate/s duration=$duration"
$compose up -d --build --quiet-pull b a sampler >/dev/null 2>&1

printf "waiting for A"
for i in $(seq 1 60); do
  curl -sf -o /dev/null http://localhost:8081/call && break
  [ "$i" -eq 60 ] && { echo " A did not come up"; $compose logs a | tail -20; exit 1; }
  printf "."; sleep 1
done; echo

# Record what the kernel actually had, not what we asked for.
{
  echo "name=$name client=$client rate=$rate duration=$duration"
  echo "kernel=$($compose exec -T b uname -r)"
  echo "tcp_tw_reuse=$($compose exec -T b cat /proc/sys/net/ipv4/tcp_tw_reuse)"
  echo "ip_local_port_range=$($compose exec -T b cat /proc/sys/net/ipv4/ip_local_port_range | tr '\t' ' ')"
} > "$out/meta.txt"
cat "$out/meta.txt"

# Warm-up lets the JIT compile the hot paths so the first seconds aren't unrepresentative.
echo "warm-up: 200/s for 10s"
$compose run --rm -T oha -q 200 -z 10s --no-tui "$target" >/dev/null

# Start from a clean slate: wait until the warm-up's TIME_WAIT sockets expire (60s max).
printf "draining TIME_WAIT"
for _ in $(seq 1 75); do
  tw=$(tail -1 "$out/sockets.csv" | cut -d, -f2)
  [ "${tw:-0}" -lt 50 ] 2>/dev/null && break
  printf "."; sleep 1
done; echo " (a_time_wait=$tw)"

echo "load: oha -q $rate -z $duration --latency-correction"
# CPU of A and B during the load, as % of one core (docker stats takes ~1-2s per sample).
( echo "epoch,a_cpu_pct,b_cpu_pct"
  while true; do
    echo "$(date +%s),$(docker stats --no-stream --format '{{.CPUPerc}}' connpool-a-1 connpool-b-1 </dev/null | tr -d '%' | paste -sd, -)"
  done ) > "$out/cpu.csv" 2>/dev/null &
cpu_pid=$!
echo "load_start_epoch=$(date +%s)" >> "$out/meta.txt"
$compose run --rm -T oha -q "$rate" -z "$duration" --latency-correction --no-tui \
  --output-format json "$target" > "$out/oha.json"
echo "load_end_epoch=$(date +%s)" >> "$out/meta.txt"
kill $cpu_pid 2>/dev/null || true

sleep 2  # let the last stats line and socket sample land
$compose logs --no-log-prefix a > "$out/a.log"
grep '^stats,' "$out/a.log" | cut -d, -f2- > "$out/a-stats.csv"
$compose down >/dev/null 2>&1

rm -rf "results/$name" && mv "$out" "results/$name"

# Quick summary so a run can be sanity-checked without plotting.
dir=results/$name
start=$(grep load_start_epoch "$dir/meta.txt" | cut -d= -f2)
max_tw=$(tail -n +2 "$dir/sockets.csv" | cut -d, -f2 | sort -n | tail -1)
first_err=$(awk -F, -v s="$start" 'NR>1 && $1>=s && $3>0 {print $1-s; exit}' "$dir/a-stats.csv")
if [ -n "$first_err" ]; then first_err_text="t=${first_err}s"; else first_err_text=none; fi
totals=$(awk -F, -v s="$start" 'NR>1 && $1>=s {ok+=$2; err+=$3} END {print ok" ok, "err" errors"}' "$dir/a-stats.csv")
a_cpu=$(awk -F, -v s="$start" 'NR>1 && $1>=s && $2!="" {sum+=$2; n++} END {if (n) printf "%.0f%%", sum/n}' "$dir/cpu.csv")
echo "== $name done: $totals; peak A-side TIME_WAIT=$max_tw; first error: $first_err_text; A avg CPU=${a_cpu:-n/a} of one core"
grep '^error-first,' "$dir/a.log" | cut -d, -f2- | sed 's/^/   error type: /' || true
