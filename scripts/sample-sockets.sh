#!/bin/sh
# Samples socket states once a second into /out/sockets.csv.
# Over loopback, ss shows both ends of each connection, so split by B's port:
#   dport = :8080  -> A's side (A's ephemeral port -> B)
#   sport = :8080  -> B's side
apk add -q iproute2 >/dev/null
echo "epoch,a_time_wait,b_time_wait,a_established" > /out/sockets.csv
while true; do
  now=$(date +%s)
  a_tw=$(ss -Htan state time-wait '( dport = :8080 )' | wc -l)
  b_tw=$(ss -Htan state time-wait '( sport = :8080 )' | wc -l)
  a_est=$(ss -Htan state established '( dport = :8080 )' | wc -l)
  echo "$now,$a_tw,$b_tw,$a_est" >> /out/sockets.csv
  sleep 1
done
