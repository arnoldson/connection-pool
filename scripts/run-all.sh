#!/usr/bin/env bash
# Runs the load scenarios back to back with a live terminal dashboard, then prints
# a comparison table. Evidence for each run lands in results/<scenario>/.
#
#   scripts/run-all.sh                  # before pool jetty mitigation
#   scripts/run-all.sh before pool      # just these
#   DURATION=60s RATE=500 scripts/run-all.sh before
#
# The dashboard only reads files run-scenario.sh already writes (sockets.csv,
# cpu.csv, meta.txt) plus A's per-second stats line from its container log.
# Written for macOS's bash 3.2: no associative arrays.
set -uo pipefail
cd "$(dirname "$0")/.."

scenarios=("$@")
[ ${#scenarios[@]} -eq 0 ] && scenarios=(before pool jetty mitigation)
rate=${RATE:-1000}
duration=${DURATION:-150s}
duration_s=${duration%s}
ports_total=28232
logs=results/.logs
mkdir -p "$logs"

spec() { # scenario -> "client tw_reuse"
  case $1 in
    before) echo "fresh 0" ;;
    pool) echo "pool 0" ;;
    jetty) echo "jetty 0" ;;
    mitigation) echo "fresh 1" ;;
    *) echo "unknown scenario: $1" >&2; exit 2 ;;
  esac
}
for s in "${scenarios[@]}"; do spec "$s" >/dev/null; done

tty=false; [ -t 1 ] && tty=true
cols=72
if $tty; then
  B=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; R=$'\033[0m'; EL=$'\033[K'
else
  B=; DIM=; RED=; GRN=; YEL=; R=; EL=
fi

# bar <value> <max> <width>: a filled/empty horizontal bar
bar() {
  awk -v v="$1" -v m="$2" -v w="$3" 'BEGIN { f = (m > 0) ? int(v / m * w + 0.5) : 0; if (f > w) f = w;
    s = ""; for (i = 0; i < f; i++) s = s "█"; for (; i < w; i++) s = s "░"; print s }'
}

# spark <max> <width> <values...>: the whole series squeezed into <width> columns (max per bucket)
spark() {
  local max=$1 width=$2; shift 2
  [ $# -eq 0 ] && { echo ""; return; }
  printf '%s\n' "$@" | awk -v m="$max" -v w="$width" '
    { v[NR] = $1 } END {
      split("▁ ▂ ▃ ▄ ▅ ▆ ▇ █", ch, " "); per = (NR > w) ? NR / w : 1; s = "";
      for (b = 0; b * per < NR && b < w; b++) {
        hi = -1; for (i = int(b * per) + 1; i <= int((b + 1) * per) && i <= NR; i++) if (v[i] > hi) hi = v[i];
        if (hi <= 0) s = s " "; else { k = int(hi / m * 8 - 1e-9) + 1; if (k > 8) k = 8; s = s ch[k] }
      } print s }'
}

# fmt 28232 -> 28,232 (bash 3.2's printf ignores the %'d grouping flag)
fmt() { echo "$1" | sed -e :a -e 's/\(.*[0-9]\)\([0-9]\{3\}\)/\1,\2/;ta'; }

summary_rows=()
summarize() { # scenario -> one table row from results/<scenario>/
  local d=results/$1 start ok err tw cpu p50 p99
  [ -f "$d/meta.txt" ] || { summary_rows+=("$(printf "%-11s %s" "$1" "${RED}failed - see $logs/$1.log${R}")"); return; }
  start=$(grep load_start_epoch "$d/meta.txt" | cut -d= -f2)
  read -r ok err < <(awk -F, -v s="$start" 'NR>1 && $1>s {o+=$2; e+=$3} END {print o+0, e+0}' "$d/a-stats.csv")
  tw=$(tail -n +2 "$d/sockets.csv" | cut -d, -f2 | sort -n | tail -1)
  cpu=$(awk -F, -v s="$start" 'NR>1 && $1>=s && $2!="" {t+=$2; n++} END {if (n) printf "%.0f%%", t/n; else print "-"}' "$d/cpu.csv")
  p50=$(jq -r '.latencyPercentiles.p50 * 1000 | . * 100 | round / 100' "$d/oha.json" 2>/dev/null)
  p99=$(jq -r '.latencyPercentiles.p99 * 1000 | . * 100 | round / 100' "$d/oha.json" 2>/dev/null)
  local errc=$GRN; [ "$err" -gt 0 ] && errc=$RED
  summary_rows+=("$(printf "%-11s %9s  %s%9s%s  %10s  %6s  %8s  %8s" "$1" "$(fmt "$ok")" "$errc" "$(fmt "$err")" "$R" \
    "$(fmt "${tw:-0}")" "$cpu" "${p50}ms" "${p99}ms")")
}

print_summary() {
  echo "${B}Results${R} (${rate} req/s for ${duration} each)"
  printf "${DIM}%-11s %9s  %9s  %10s  %6s  %8s  %8s${R}\n" scenario ok errors "peak TW" "A CPU" p50 p99
  local row; for row in "${summary_rows[@]}"; do echo "$row"; done
  echo "${DIM}TW = A-side TIME_WAIT (limit $(fmt $ports_total)). A CPU = average % of one core during load.${R}"
  echo "${DIM}'before' latency includes fast-failing 502s, so compare latency across the other runs.${R}"
}

$tty && printf '\033[?25l\033[2J'  # hide cursor, clear screen
trap '$tty && printf "\033[?25h"' EXIT

n=0
for scenario in "${scenarios[@]}"; do
  n=$((n + 1))
  read -r client tw_reuse <<< "$(spec "$scenario")"
  log=$logs/$scenario.log
  scripts/run-scenario.sh "$scenario" "$client" "$tw_reuse" "$rate" "$duration" </dev/null >"$log" 2>&1 &
  pid=$!
  tw_hist=(); ok_hist=(); err_hist=(); last_plain=0

  while kill -0 $pid 2>/dev/null; do
    cur=results/_current
    phase=starting; t=0
    if grep -q '^load:' "$log" 2>/dev/null; then
      phase=load
      start=$(grep load_start_epoch "$cur/meta.txt" 2>/dev/null | cut -d= -f2)
      [ -n "$start" ] && t=$(( $(date +%s) - start ))
      [ $t -gt $duration_s ] && { phase=collecting; t=$duration_s; }
    elif grep -q '^draining' "$log" 2>/dev/null; then phase="draining TIME_WAIT"
    elif grep -q '^warm-up' "$log" 2>/dev/null; then phase=warm-up
    fi

    IFS=, read -r _ tw _ est < <(tail -1 "$cur/sockets.csv" 2>/dev/null | grep -E '^[0-9]') || true
    IFS=, read -r _ cpu _ < <(tail -1 "$cur/cpu.csv" 2>/dev/null | grep -E '^[0-9]') || true
    IFS=, read -r _ _ ok err _ < <(docker logs --tail 5 connpool-a-1 2>/dev/null | grep '^stats,' | tail -1) || true
    tw=${tw:-0}; est=${est:-0}; ok=${ok:-0}; err=${err:-0}; cpu=${cpu:--}
    if [ "$phase" = load ]; then tw_hist+=("$tw"); ok_hist+=("$ok"); err_hist+=("$err"); fi

    if $tty; then
      errc=$GRN; [ "$err" -gt 0 ] && errc=$RED
      twc=$GRN; [ "$tw" -gt $((ports_total / 2)) ] && twc=$YEL; [ "$tw" -ge $((ports_total - 10)) ] && twc=$RED
      {
        printf '\033[H'
        echo "${B}connection-pool${R} · full test run · scenario $n/${#scenarios[@]}$EL"
        printf '─%.0s' $(seq 1 $cols); echo  # tr can't do this: '─' is 3 bytes in UTF-8
        echo "${B}▶ $scenario${R}  CLIENT=$client  tcp_tw_reuse=$tw_reuse  ${DIM}$rate req/s$R  phase: ${YEL}$phase$R$EL"
        echo "  progress   $(bar $t $duration_s 40)  ${t}s / ${duration_s}s$EL"
        echo "$EL"
        echo "  ${B}A-side TIME_WAIT${R}   ${twc}$(fmt "$tw")${R} / $(fmt $ports_total)$EL"
        echo "    now        $(bar "$tw" $ports_total 40)$EL"
        echo "    over run   $(spark $ports_total 60 "${tw_hist[@]+"${tw_hist[@]}"}")$EL"
        echo "  ${B}ESTABLISHED to B${R}   $est$EL"
        echo "  ${B}calls / s${R}          ${GRN}$ok ok${R}   ${errc}$err errors${R}$EL"
        echo "    ok         ${GRN}$(spark "$rate" 60 "${ok_hist[@]+"${ok_hist[@]}"}")${R}$EL"
        echo "    errors     ${RED}$(spark "$rate" 60 "${err_hist[@]+"${err_hist[@]}"}")${R}$EL"
        echo "  ${B}A CPU${R}              $cpu% of one core$EL"
        echo "$EL"
        if [ ${#summary_rows[@]} -gt 0 ]; then print_summary | sed "s/\$/$EL/"; fi
        printf '\033[J'
      }
    elif [ $(( $(date +%s) - last_plain )) -ge 10 ]; then
      last_plain=$(date +%s)
      echo "[$n/${#scenarios[@]} $scenario] $phase t=${t}s time_wait=$tw established=$est ok/s=$ok err/s=$err cpu=$cpu%"
    fi
    sleep 1
  done

  wait $pid || echo "$scenario: run-scenario.sh failed, see $log"
  summarize "$scenario"
done

if $tty; then printf '\033[H\033[J'; fi
print_summary
echo
echo "Per-run evidence: results/<scenario>/ · logs: $logs/"
