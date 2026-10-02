# Background: ephemeral port exhaustion over localhost

This project reproduces and fixes a production incident: a Java/Jetty service
(**A**) calling another service (**B**) on the same host over `localhost` ran out
of ephemeral ports under load, and its outbound calls started failing.

The incident happened some time ago. Details below marked **(assumed)** are
reconstructed from memory or chosen as reasonable defaults, not recovered from
the original system.

## The incident environment

| Item | Value |
|---|---|
| OS | Ubuntu 18.04 **(assumed)**, stock kernel 4.15 |
| Topology | A → B over loopback (`127.0.0.1` → `127.0.0.1:<B port>`), one destination |
| Connection pattern | A opens a new TCP connection per call to B and closes it itself (A does the active close) |
| `net.ipv4.ip_local_port_range` | `32768 60999` (Linux default, 28,232 ports) |
| `net.ipv4.tcp_tw_reuse` | `0` (the 4.15 default) |
| `net.ipv4.tcp_timestamps` | `1` (default) |
| Symptoms | Outbound calls from A to B fail with `Cannot assign requested address`, and connects become slow |

## How it happens

### 1. A connection needs a unique 4-tuple

The kernel identifies a TCP connection by four values:

```
(source IP, source port, destination IP, destination port)
```

When A calls B over localhost, three of them are fixed: `127.0.0.1`, `127.0.0.1`
and B's port. The only one that varies is the **source port**. The kernel picks
it from the ephemeral range (`ip_local_port_range`) when A calls `connect()`
without binding first.

So each new connection from A to B needs a source port that isn't already tied
to that same destination. The limit applies per destination: A calling one B on
one port is the worst case.

### 2. Closing a connection doesn't free its port

Whichever side closes first (the *active closer*) moves the socket into
**TIME_WAIT**, and it stays there for **60 seconds**. That value is the
compile-time constant `TCP_TIMEWAIT_LEN`, so no sysctl changes it.
(`tcp_fin_timeout` is a common misconception: it controls FIN_WAIT_2, not
TIME_WAIT.)

TIME_WAIT exists for two reasons:

1. **Delayed duplicates.** A packet from the old connection that arrives late
   must not be accepted into a new connection that reuses the same 4-tuple.
2. **A lost final ACK.** If the closer's last ACK is lost, the peer resends its
   FIN, and the closer must still be able to answer it.

While the socket sits in TIME_WAIT, its 4-tuple, and therefore its source port
for that destination, can't be used again.

**Where TIME_WAIT lands matters.** If B closed first, B would hold TIME_WAIT on
the tuple `(B:port → A:ephemeral)`. That doesn't block A from picking the same
ephemeral port for a new connection. The ports only run out because **A** is
the active closer.

### 3. The math

Each connection holds its source port for its whole lifetime plus 60 seconds of
TIME_WAIT. By Little's Law:

```
ports in use ≈ new connections/s × (connection lifetime + 60 s)
```

For short requests the lifetime is a few milliseconds, so the 60 seconds
dominates:

```
max sustained new connections/s ≈ ports / 60 = 28,232 / 60 ≈ 470/s
```

- **The limit is a rolling window, not a per-second cap.** Short bursts above
  470/s are fine. It breaks when any 60-second window holds more than about
  28k new connections.
- **Time to exhaustion** at a steady rate `L` above the limit is about
  `28,232 / L` seconds. At 1,000/s that's about 28 s.
- **After exhaustion,** new connections succeed only as fast as old TIME_WAIT
  sockets expire. Everything above that fails.
- **Fan-out makes it easier to hit.** If A calls B three times per inbound
  request, 160 inbound requests/s is already over the limit.
- **More CPU doesn't help.** The limit comes from the port range and
  TIME_WAIT, not from hardware. A bigger machine just reaches it sooner. All
  processes in the same network namespace share the one pool of ports.

### 4. What the failure looks like

| Where it fails | errno | Typical Java surface |
|---|---|---|
| `connect()` with an automatically chosen port (our case) | `EADDRNOTAVAIL` | `java.net.BindException: Cannot assign requested address`, thrown from `Socket.connect` (confirmed in Phase 1) |
| `bind()` before `connect()` (e.g. Jetty `HttpClient.setBindAddress`) | `EADDRINUSE` | `java.net.BindException: Address already in use` |

Before the hard failure, `connect()` slows down: the kernel has to search a
nearly full range for a free port. That shows up as higher latency and connect
timeouts.

## Why localhost didn't protect us

Modern kernels would have hidden this bug. `net.ipv4.tcp_tw_reuse` has three
modes:

| Value | Meaning |
|---|---|
| `0` | Never reuse a TIME_WAIT port for a new outbound connection |
| `1` | Reuse it once it's about 1 s old (1–2 s on 4.15, because of whole-second granularity), for all traffic |
| `2` | Like `1`, but only for loopback traffic. **Default on newer kernels** (added after 4.15) |

Reuse is safe because of TCP timestamps: the new connection's timestamps are
higher, so delayed packets from the old connection get recognized and
dropped. That's why `tcp_timestamps=0` also turns reuse off.

**Why there's still a delay of about 1 s.** The timestamp check (PAWS) only
works if every packet on the new connection carries a timestamp *strictly
higher* than every leftover packet from the old one. So the peer's timestamp
clock has to tick at least once. The reusing side can't see how fast the
peer's clock runs, and RFC 7323 allows clocks as slow as 1 tick per second, so
the kernel waits more than 1 s after the last packet it received. On loopback
the peer is the same kernel with a 1 ms clock, so a few milliseconds would be
enough. Recent kernels expose the delay as `tcp_tw_reuse_delay` (in ms), but
the default stays at a conservative 1000 ms.

With reuse, the limit becomes `ports / reuse delay`: about 14k–28k new
connections/s instead of about 470/s. Ubuntu 18.04's stock kernel predates mode
`2` and defaults to `0`, so loopback had no protection.

**Reusing a port after about 1 second can't cut off an in-flight request.**
Only ports in TIME_WAIT are eligible, which means connections that have
*already fully closed*. A connection that's still sending a large body is
ESTABLISHED, and its port is off-limits for as long as it's open. The actual
risk of reuse is a lost final ACK colliding with the new SYN, which causes a
retry of about 1 s, not corruption. On loopback, packets essentially never get
lost.

## Mitigations vs. the fix

| Option | Effect | Why it's not the fix |
|---|---|---|
| Widen `ip_local_port_range` | ~2× more headroom at most | The limit is still a rate: `ports / 60` |
| `tcp_tw_reuse=1` | Raises the limit ~30–60× | Every request still pays for a TCP handshake plus teardown; across a real network, NAT can mix up timestamps |
| `tcp_tw_recycle` | — | Broke clients behind NAT; **removed in 4.12**. Often confused with `tw_reuse` |
| More source IPs (`127.0.0.2`, …) | Multiplies the number of 4-tuples | Hides the problem; config spread across services |
| `SO_LINGER` with timeout 0 | Closes with RST, which skips TIME_WAIT | Throws away TIME_WAIT's protections; risks data loss |
| **Reuse connections (pool / keep-alive)** | New connections ≈ pool size, not request rate | **This is the fix** |

The root cause is **the rate of new connections outpacing TIME_WAIT
reclamation**, not a missing library. Keep-alive pooling fixes it because the
number of connections follows the pool size, not the request rate.

### Opening connections costs CPU, not just ports

Every kernel-side mitigation above keeps a new connection per request, and each
new connection costs CPU in two ways:

1. **Fixed cost per request.** A TCP handshake and teardown on both A and B:
   socket setup, three packets to open and four to close, and TIME_WAIT
   bookkeeping. That cost grows with the request rate.
2. **Cost that grows as the range fills.** To find a free source port,
   `connect()` searches the ephemeral range. When most of the range is sitting
   in TIME_WAIT, each attempt checks many occupied ports before it finds one,
   or fails. In Phase 1, **A's CPU went from 37% of one core to 79% and then
   88% at the same 1,000 requests/s** as the range filled up, while useful
   work fell to zero after exhaustion. A bigger machine doesn't help: extra
   cores just search the same full range in parallel.

**What Phase 3 measured.** With `tcp_tw_reuse=1` the range never fills, so
cost 2 mostly disappears: A's CPU was 32% of a core vs the pool's 29%. Cost 1
doesn't disappear, and most of it lands on the **server**. B has to accept and
tear down 1,000 connections per second: **25% of a core vs 8% with the pool.**
In total (A + B), that's 56% vs 37%, about 1.5× the pool. Latency was worse
too: p50 +60%, p90 2×, p99 +56%. A pool pays these costs once per pooled
connection, and then never again.

### Where the real "cut-off request" risk lives: the pool

The kernel never reuses a live connection, but a pool can. If a connection goes
back to the pool with unread response bytes, for example because the caller
timed out or stopped reading early, the next borrower reads the *previous*
response. The pool rule: **only check a connection back in after its response
has been read completely. Otherwise close it.**

## Evidence gathered so far

These smoke tests ran on 2026-09-30 on OrbStack (Linux kernel
`7.0.14-orbstack`) using `socat`, with no Java involved. The range was narrowed
to 1,000 ports and the client always closed first.

| Setup | Result |
|---|---|
| Two containers over a bridge network, `tw_reuse=2` (default) | Exhausted: 999 TIME_WAIT, then `connect(): Address not available` |
| One container over loopback, `tw_reuse=2` (default) | **Not exhausted**: 7,800+ connections in 10 s, 0 failures |
| Loopback, `tw_reuse=0` | Exhausted (`EADDRNOTAVAIL` on `connect()`) |
| Loopback, `tw_reuse=2`, `tcp_timestamps=0` | Exhausted (`EADDRNOTAVAIL` on `connect()`) |
| Loopback, `tw_reuse=2`, bind before connect | Exhausted (`EADDRINUSE` on `bind()`) |
| Loopback, `tw_reuse=1`, **200 ports** | Exactly **~200 successful connects/s**, the rest fail: the ceiling is `ports / reuse delay`, and the delay is `tcp_tw_reuse_delay` = 1000 ms on this kernel |

This confirms the problem lives in the kernel, not in Jetty. It also confirms
that a modern kernel's loopback default hides the bug. `scripts/kernel-smoke.sh`
re-runs these cases.

### Phase 1: the Java reproduction

`scripts/run-scenario.sh before fresh 0 1000 90s`: a fresh connection per request,
`tcp_tw_reuse=0`, the default range of 28,232 ports, 1,000 requests/s for 90 s.

- **A-side TIME_WAIT rises about 1,000/s and levels off at 28,231.** Errors
  start at t≈30 s (the prediction was about 28 s), all
  `java.net.BindException: Cannot assign requested address`.
- **B-side TIME_WAIT stays at 0** for the whole run, which confirms A does the
  active close.
- **Distinct source ports per second match successful calls per second**
  (about 1,000), which confirms every request used a new connection.
- **Recovery begins at t≈61–65 s**, 60 s after the first TIME_WAIT sockets were
  created, plus batching. Ports then free up at the rate they were used
  60 s earlier, so the service works for about 30 s and then fails again. Over
  the long run that averages out to the ~470/s limit. It's a sawtooth, not a
  one-time outage.
- **TIME_WAIT expires in batches about every 4 s**, not smoothly. That's the
  granularity of the kernel's timer wheel for 60 s timers, and it's why the
  recovery looks jagged.
- **`connect()` gets more expensive as the range fills.** A's CPU went from
  37% of a core at t=12 s to 79% at t=22 s and 88% after exhaustion, at the
  same request rate. Each attempt searches further through a mostly occupied
  range before finding a free port, or failing.

## Reproduction target

Emulate the incident on a modern kernel:

- A and B talk over **loopback**, in a shared network namespace.
- `tcp_tw_reuse=0`, to emulate Ubuntu 18.04's default.
- The default port range (28,232 ports), so the limit is about 470 new
  connections/s.
- Drive A at about 1,000 requests/s with [`oha`](https://github.com/hatoo/oha).
  Expect exhaustion about 28 s in.

Phase 3 runs all four clients at the same load (1,000 requests/s, default
range):

1. **Before:** a fresh connection per request, `tw_reuse=0`. Expected to fail.
2. **Pool:** the hand-rolled pool, `tw_reuse=0`.
3. **Jetty `HttpClient`:** Jetty's built-in pooled client, `tw_reuse=0`.
4. **Mitigation:** a fresh connection per request, `tw_reuse=1`. Expected: no
   errors, but worse latency than the pool.

The ceiling of the mitigation itself (`ports / 1 s`) isn't re-run in Java.
The socat measurement above already shows it.

## Open questions

- How did the original client end up with one connection per request? (A new
  client per call, `Connection: close`, keep-alive switched off?) Unknown. The
  reproduction uses an explicit one-connection-per-request client.
- The exact Java and Jetty versions at the time. Unknown; they don't affect
  the kernel behavior.

### Phases 2–3: one run, four scenarios

`scripts/run-all.sh`: all four scenarios back to back in one run, at 1,000
requests/s for 150 s each, with the default port range. Running them together
puts every scenario under the same machine conditions.

| Scenario | OK | Errors | Peak A-side TIME_WAIT | Max ESTABLISHED | A CPU | B CPU | p50 | p90 | p99 |
|---|---|---|---|---|---|---|---|---|---|
| before (fresh, `tw_reuse=0`) | 78,269 | 71,731 | 28,231 | – | 66% | – | 0.86 ms* | – | 3.56 ms* |
| **pool** (ours, max 32) | 149,998 | **0** | 7 | 12 | **29%** | **8%** | **0.55 ms** | **1.34 ms** | **3.17 ms** |
| jetty (`HttpClient`, max 32) | 150,057 | **0** | 24 | 20 | 37% | 8% | 0.71 ms | 1.57 ms | 3.57 ms |
| mitigation (fresh, `tw_reuse=1`) | 149,997 | **0** | 14,045 | 3 | 32% | 25% | 0.88 ms | 2.84 ms | 4.94 ms |

CPU is the average % of one core during the load. \* `before` includes
fast-failing 502s, which pull its percentiles down.

- **`before` fails and recovers in cycles.** It fails at about 30 s, recovers
  at about 65 s, fails again at about 95 s and recovers at about 125 s. That
  averages roughly 520 successful calls/s, close to the ~470/s limit.
- **The pool fixes it.** Zero errors, A-side TIME_WAIT near 0 during the load
  (the 7 come from shutdown and idle expiry), and at most 12 connections in
  use out of 32.
- **Our pool is comparable to Jetty's `HttpClient`.** Both have zero errors and
  near-zero TIME_WAIT, with similar CPU and latency. Ours came out slightly
  lighter, but it supports only a sliver of what Jetty's does (no redirects,
  HTTP/2, chunked bodies or async API), so "comparable" is the fair reading.
- **The mitigation avoids errors but not the cost.** Ports recycle after about
  1 s, so nothing fails, but TIME_WAIT settles around 14,000 sockets (half the
  range). Every request still pays a handshake, which costs B CPU and adds
  latency (see "Opening connections costs CPU, not just ports").

**Measurement noise.** These runs happen on a laptop shared with other
containers. Between the Phase 2 run and this one, the pool's A CPU moved from
17% to 29% and its p99 from 1.36 ms to 3.17 ms, under an identical
configuration. Compare scenarios *within* one run; absolute numbers across runs
can vary by roughly ±50%. Repeating each run several times and reporting
medians would tighten this; we didn't, to keep scope lean.
