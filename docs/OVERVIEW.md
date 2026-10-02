# Overview: how a service runs out of ports without running out of anything else

## What happened

Service A called service B on the same machine (`localhost`) once per incoming
request. Every call opened a brand-new TCP connection and closed it when done.
Under enough traffic, A's calls to B started failing with
`Cannot assign requested address`, even though CPU, memory and B itself were
all healthy.

## Why, in one picture

Think of A's outgoing connections as cars in a parking lot:

- **The lot has about 28,000 spaces.** Each space is an *ephemeral port*,
  the temporary port number the OS gives each outgoing connection.
- **Every call to B parks a car.**
- **When a car leaves, its space stays roped off for 60 seconds** (the TCP
  state `TIME_WAIT`). It's a safety measure, so stray packets from the old
  connection can't get mixed into a new one.

So the lot can take about **28,000 cars per 60 seconds, roughly 470 new
connections per second**, no matter how powerful the machine is. Go faster
than that for long enough and every space is roped off. New calls fail until
spaces free up.

This is a limit on **rate**, not load. A tiny request still takes up a space
for 60 seconds.

## Why this happens in real systems

- **One connection per request is easy to create by accident.** Creating a new
  HTTP client for every call, sending `Connection: close`, or turning off
  keep-alive to work around some other bug all lead here.
- **Calls to localhost feel free.** No network, no latency, so nobody thinks
  about connection cost.
- **Nothing breaks until traffic crosses the line.** At 400 requests/s
  everything is fine. At 500 requests/s, calls start failing about a minute
  later. Tests and staging rarely run that hot.
- **Fan-out multiplies it.** If A calls B three times per request, 160
  incoming requests/s is already enough.
- **The symptoms point the wrong way.** "Cannot assign requested address"
  sounds like a configuration or DNS problem. Dashboards show plenty of CPU
  and memory. A bigger machine just reaches the limit sooner.

## Why modern Linux usually doesn't show this on localhost

Newer kernels have a shortcut for localhost: a roped-off space **can be reused
after 1 second instead of 60**. That raises the limit from about 470 to about
28,000 new connections per second, far more than most services ever make.

The kernel can do this safely because of **TCP timestamps**. Every packet
carries a timestamp, and a new connection's timestamps are always higher than
the old one's. So if a stray packet from the old connection turns up, the
kernel can see it's stale and drop it. The 60-second wait isn't needed for
that anymore.

It only applies to localhost by default because there, both ends of the
connection are the same trusted machine. Across a real network, NAT devices
and load balancers can mix up timestamps from different machines, so the
kernel keeps the full 60-second wait there.

The machine in this incident ran an older kernel (Ubuntu 18.04 era) that
predates this shortcut, so localhost got the full 60-second wait and the bug
showed up. This project re-creates that by turning the shortcut off
(`net.ipv4.tcp_tw_reuse=0`).

## The fix

Stop opening a new connection for every request. A **connection pool** keeps
a few connections to B open and reuses them. The number of connections then
depends on the pool size (say 20), not the request rate (say 1,000/s). The
parking lot never fills, and each request also skips the cost of setting up
a TCP connection, so latency drops too.

Kernel tweaks (a wider port range, turning reuse on) raise the limit but keep
paying that setup cost on every request. They're mitigations, not the fix.

That cost isn't just latency. It's CPU, on both ends. Every new connection
means a handshake and a teardown for the caller *and* the server. In our tests
the server needed about three times the CPU when every request opened a new
connection, even with the kernel's reuse shortcut turned on. And when the
parking lot is nearly full, finding a free space gets expensive too: A's CPU
more than doubled while it searched for ports that weren't there. A pool pays
for each connection once and then reuses it.

For the full technical detail, see [BACKGROUND.md](BACKGROUND.md).
