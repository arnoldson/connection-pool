#!/usr/bin/env python3
"""Draws the README charts as SVG from results/<scenario>/, with no dependencies.

    python3 scripts/plot.py            # writes docs/charts/*.svg

Plain SVG rather than matplotlib: nothing to install, crisp at any size, and
GitHub renders it inline. Each SVG carries light and dark colors and follows
the viewer's OS setting via prefers-color-scheme.

Colors: the categorical slots and the blue ordinal ramp were checked with a
palette validator (colorblind separation, contrast). Two light-mode slots sit
below 3:1 on the surface, so every chart also carries direct labels and the
README carries the full numbers in a table.
"""
import csv
import json
import math
from html import escape
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
OUT = ROOT / "docs" / "charts"
PORTS = 28232
DURATION = 150

# Scenario -> categorical slot. Fixed, so a scenario keeps its color in every chart.
SCENARIOS = [("before", 1), ("pool", 2), ("jetty", 3), ("mitigation", 4)]

STYLE = """
.root { --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e; --muted:#898781; --grid:#e1e0d9; --axis:#c3c2b7;
  --s1:#2a78d6; --s2:#eb6834; --s3:#1baf7a; --s4:#eda100; --o1:#86b6ef; --o2:#2a78d6; --o3:#104281; }
@media (prefers-color-scheme: dark) {
  .root { --surface:#1a1a19; --ink:#ffffff; --ink2:#c3c2b7; --muted:#898781; --grid:#2c2c2a; --axis:#383835;
    --s1:#3987e5; --s2:#d95926; --s3:#199e70; --s4:#c98500; --o1:#184f95; --o2:#3987e5; --o3:#9ec5f4; } }
text { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; fill: var(--ink2); font-size: 12px; }
.title { fill: var(--ink); font-size: 15px; font-weight: 600; }
.sub { font-size: 12px; }
.tick { fill: var(--muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.label { fill: var(--ink); font-size: 12px; }
.grid { stroke: var(--grid); stroke-width: 1; }
.axis { stroke: var(--axis); stroke-width: 1; }
.line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
"""

W, H = 720, 380
M = {"left": 64, "right": 112, "top": 92, "bottom": 44}


# ---------- data ----------

def load_start(scenario):
    meta = (RESULTS / scenario / "meta.txt").read_text()
    return int(next(l.split("=")[1] for l in meta.splitlines() if l.startswith("load_start_epoch")))


def rows(scenario, name):
    with open(RESULTS / scenario / name) as f:
        return list(csv.DictReader(f))


def time_wait_series(scenario):
    start = load_start(scenario)
    return [(int(r["epoch"]) - start, int(r["a_time_wait"])) for r in rows(scenario, "sockets.csv")
            if 0 <= int(r["epoch"]) - start <= DURATION]


def calls_series(scenario):
    start = load_start(scenario)
    ok, err = [], []
    for r in rows(scenario, "a-stats.csv"):
        t = int(r["window_end_epoch"]) - start
        if 0 < t <= DURATION:
            ok.append((t, int(r["ok"])))
            err.append((t, int(r["errors"])))
    return ok, err


def avg_cpu(scenario):
    start = load_start(scenario)
    a, b = [], []
    for r in rows(scenario, "cpu.csv"):
        if int(r["epoch"]) >= start and r["a_cpu_pct"] and r["b_cpu_pct"]:
            a.append(float(r["a_cpu_pct"]))
            b.append(float(r["b_cpu_pct"]))
    return sum(a) / len(a), sum(b) / len(b)


def latency_ms(scenario):
    p = json.loads((RESULTS / scenario / "oha.json").read_text())["latencyPercentiles"]
    return {k: p[k] * 1000 for k in ("p50", "p90", "p99")}


# ---------- svg helpers ----------

def nice_ticks(top, count=5):
    raw = top / count
    mag = 10 ** math.floor(math.log10(raw))
    step = next(m * mag for m in (1, 2, 2.5, 5, 10) if m * mag >= raw)
    return [i * step for i in range(int(math.ceil(top / step)) + 1)]


def fmt(v, unit=""):
    if unit == "ms":
        return f"{v:g} ms"
    if unit == "%":
        return f"{v:.0f}%"
    return f"{v:,.0f}"


def frame(title, subtitle, desc, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
            f'role="img" aria-labelledby="t d">\n<title id="t">{escape(title)}</title>\n'
            f'<desc id="d">{escape(desc)}</desc>\n<style>{STYLE}</style>\n<g class="root">\n'
            f'<rect width="{W}" height="{H}" rx="8" fill="var(--surface)"/>\n'
            f'<text class="title" x="{M["left"]}" y="28">{escape(title)}</text>\n'
            f'<text class="sub" x="{M["left"]}" y="48">{escape(subtitle)}</text>\n'
            f'{body}</g>\n</svg>\n')


def legend(items, y=72):
    """items: (label, css color, kind) with kind 'line' or 'box'. Ink text beside a colored key."""
    out, x = [], M["left"]
    for label, color, kind in items:
        if kind == "line":
            out.append(f'<line x1="{x}" y1="{y - 4}" x2="{x + 16}" y2="{y - 4}" stroke="{color}" '
                       f'stroke-width="2" stroke-linecap="round"/>')
        else:
            out.append(f'<rect x="{x}" y="{y - 10}" width="12" height="12" rx="3" fill="{color}"/>')
        out.append(f'<text x="{x + 22}" y="{y}">{escape(label)}</text>')
        x += 22 + 7.2 * len(label) + 22
    return "\n".join(out) + "\n"


def y_axis(ticks, ymax, unit=""):
    plot_w, plot_h = W - M["left"] - M["right"], H - M["top"] - M["bottom"]
    out = []
    for v in ticks:
        y = M["top"] + plot_h - v / ymax * plot_h
        cls = "axis" if v == 0 else "grid"
        out.append(f'<line class="{cls}" x1="{M["left"]}" x2="{M["left"] + plot_w}" y1="{y:.1f}" y2="{y:.1f}"/>')
        out.append(f'<text class="tick" x="{M["left"] - 8}" y="{y + 4:.1f}" text-anchor="end">{fmt(v, unit)}</text>')
    return "\n".join(out) + "\n"


def line_chart(title, subtitle, desc, series, ymax, reference=None, unit=""):
    """series: (label, slot, [(t, v)]). End labels are skipped if they'd collide; the legend always names every line."""
    plot_w, plot_h = W - M["left"] - M["right"], H - M["top"] - M["bottom"]
    sx = lambda t: M["left"] + t / DURATION * plot_w
    sy = lambda v: M["top"] + plot_h - v / ymax * plot_h
    body = y_axis(nice_ticks(ymax), ymax, unit)
    for t in range(0, DURATION + 1, 30):
        body += f'<text class="tick" x="{sx(t):.1f}" y="{H - M["bottom"] + 18}" text-anchor="middle">{t}s</text>\n'
    body += (f'<text class="tick" x="{M["left"] + plot_w / 2:.1f}" y="{H - 8}" text-anchor="middle">'
             f'seconds into the load</text>\n')
    if reference:
        value, label = reference
        y = sy(value)
        body += f'<line class="axis" x1="{M["left"]}" x2="{M["left"] + plot_w}" y1="{y:.1f}" y2="{y:.1f}"/>\n'
        body += f'<text class="tick" x="{M["left"] + 6}" y="{y - 6:.1f}">{escape(label)}</text>\n'
    placed = []
    for label, slot, pts in series:
        d = " ".join(f'{"M" if i == 0 else "L"}{sx(t):.1f},{sy(v):.1f}' for i, (t, v) in enumerate(pts))
        body += f'<path class="line" d="{d}" stroke="var(--s{slot})"/>\n'
    for label, slot, pts in series:  # labels after lines, so lines never cover text
        t, v = pts[-1]
        y = sy(v)
        if all(abs(y - p) >= 14 for p in placed):
            placed.append(y)
            body += (f'<circle cx="{sx(t):.1f}" cy="{y:.1f}" r="4" fill="var(--s{slot})" '
                     f'stroke="var(--surface)" stroke-width="2"/>\n')
            body += f'<text class="label" x="{sx(t) + 10:.1f}" y="{y + 4:.1f}">{escape(label)}</text>\n'
    body += legend([(label, f"var(--s{slot})", "line") for label, slot, _ in series])
    return frame(title, subtitle, desc, body)


def rounded_top(x, y, w, h, r=4):
    """A column with a 4px rounded data-end and a square base."""
    r = min(r, h / 2, w / 2)
    if h <= 0:
        return ""
    return (f'M{x:.1f},{y + h:.1f} L{x:.1f},{y + r:.1f} Q{x:.1f},{y:.1f} {x + r:.1f},{y:.1f} '
            f'L{x + w - r:.1f},{y:.1f} Q{x + w:.1f},{y:.1f} {x + w:.1f},{y + r:.1f} L{x + w:.1f},{y + h:.1f} Z')


def column_chart(title, subtitle, desc, groups, series, values, ymax, unit, stacked=False, total_label=True):
    """groups: x categories. series: (label, css color). values[g][s]: number."""
    plot_w, plot_h = W - M["left"] - M["right"], H - M["top"] - M["bottom"]
    sy = lambda v: v / ymax * plot_h
    base = M["top"] + plot_h
    body = y_axis(nice_ticks(ymax), ymax, unit)
    bar, gap = 24, 2
    band = plot_w / len(groups)
    for g, group in enumerate(groups):
        cx = M["left"] + band * (g + 0.5)
        n = 1 if stacked else len(series)
        x0 = cx - (n * bar + (n - 1) * gap) / 2
        top = base
        for s, (label, color) in enumerate(series):
            v = values[g][s]
            if stacked:
                h = sy(v)
                y = top - h
                is_top = s == len(series) - 1
                path = rounded_top(x0, y, bar, h) if is_top else (
                    f'M{x0:.1f},{y + h:.1f} L{x0:.1f},{y:.1f} L{x0 + bar:.1f},{y:.1f} L{x0 + bar:.1f},{y + h:.1f} Z')
                body += f'<path d="{path}" fill="{color}"/>\n'
                top = y - gap  # 2px surface gap between stacked segments
            else:
                x = x0 + s * (bar + gap)
                h = sy(v)
                body += f'<path d="{rounded_top(x, base - h, bar, h)}" fill="{color}"/>\n'
                body += (f'<text class="tick" x="{x + bar / 2:.1f}" y="{base - h - 6:.1f}" '
                         f'text-anchor="middle">{v:.2f}</text>\n')
        if stacked and total_label:
            total = sum(values[g])
            body += (f'<text class="label" x="{cx:.1f}" y="{top - 4:.1f}" text-anchor="middle">'
                     f'{fmt(total, unit)}</text>\n')
        body += (f'<text class="label" x="{cx:.1f}" y="{base + 20}" text-anchor="middle">'
                 f'{escape(group)}</text>\n')
    body += legend([(label, color, "box") for label, color in series])
    return frame(title, subtitle, desc, body)


# ---------- the charts ----------

def main():
    OUT.mkdir(parents=True, exist_ok=True)

    tw = [(name, slot, time_wait_series(name)) for name, slot in SCENARIOS]
    ends = {name: pts[-1][1] for name, _, pts in tw}
    (OUT / "time-wait.svg").write_text(line_chart(
        "A-side TIME_WAIT sockets during the load",
        "1,000 req/s for 150 s. Pool and jetty stay near 0, so their lines overlap at the bottom.",
        f"TIME_WAIT over time. before climbs to the {PORTS:,}-port limit by about 30 s and stays near it; "
        f"mitigation levels off around {ends['mitigation']:,}; pool and jetty stay near zero.",
        tw, ymax=30000, reference=(PORTS, f"limit {PORTS:,}")))

    ok, err = calls_series("before")
    (OUT / "before-calls.svg").write_text(line_chart(
        "before: successful vs failed calls per second",
        "Fails when the ports run out, recovers as 60 s TIME_WAITs expire, then fails again.",
        "Per-second successes and errors for the before scenario: about 1,000 ok/s until 30 s, "
        "then errors until about 65 s, ok again until about 95 s, then errors again.",
        [("ok", 1, ok), ("errors", 2, err)], ymax=1200))

    contenders = ["pool", "jetty", "mitigation"]
    lat = [latency_ms(s) for s in contenders]
    (OUT / "latency.svg").write_text(column_chart(
        "Latency percentiles at 1,000 req/s",
        "Measured by oha with coordinated-omission correction. Lower is better.",
        "; ".join(f"{s}: p50 {l['p50']:.2f} ms, p90 {l['p90']:.2f} ms, p99 {l['p99']:.2f} ms"
                  for s, l in zip(contenders, lat)),
        contenders, [("p50", "var(--o1)"), ("p90", "var(--o2)"), ("p99", "var(--o3)")],
        [[l["p50"], l["p90"], l["p99"]] for l in lat],
        ymax=max(nice_ticks(max(l["p99"] for l in lat) * 1.15)), unit="ms"))

    cpu = [avg_cpu(s) for s in contenders]
    (OUT / "cpu.svg").write_text(column_chart(
        "CPU used by A and B at 1,000 req/s",
        "Average % of one core during the load. A new connection per request costs the server most.",
        "; ".join(f"{s}: A {a:.0f}%, B {b:.0f}%, total {a + b:.0f}%" for s, (a, b) in zip(contenders, cpu)),
        contenders, [("A (caller)", "var(--s1)"), ("B (server)", "var(--s2)")],
        [list(c) for c in cpu], ymax=max(nice_ticks(max(a + b for a, b in cpu) * 1.15)), unit="%",
        stacked=True))

    for f in sorted(OUT.glob("*.svg")):
        print(f"wrote {f.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
