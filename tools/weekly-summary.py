"""One-page summary of Moyna's events log for the last 7 days.

    sh tools/pull-logs.sh
    python tools/weekly-summary.py            # prints the summary and saves private/logs/summary-<date>.txt
"""
import collections, datetime as dt, glob, io, json, os, re, sys

days = int(sys.argv[1]) if len(sys.argv) > 1 else 7
since = (dt.date.today() - dt.timedelta(days=days - 1)).isoformat()
events = []
for f in sorted(glob.glob("private/logs/events/*.jsonl")):
    if os.path.basename(f)[:10] < since: continue
    for line in io.open(f, encoding="utf-8"):
        try: events.append(json.loads(line))
        except ValueError: pass

def of(kind): return [e for e in events if e.get("type") == kind]
out = []
def p(s=""): out.append(s)

p("MOYNA: last %d days (%s to %s)" % (days, since, dt.date.today()))
p("=" * 60)
if not events:
    p("No events found. Run: sh tools/pull-logs.sh")
else:
    wakes = of("wake"); turns = of("turn"); tiles = of("tile")
    by_voice = [w for w in wakes if str(w.get("how", "")).startswith("wake:")]
    empty = len(wakes) - len({(t["t"][:16]) for t in turns})  # rough: wakes that led to no question
    p("Wake-ups: %d (%d by voice, %d by tap)   Questions answered: %d   Tile taps: %d" %
      (len(wakes), len(by_voice), len(wakes) - len(by_voice), len(turns), len(tiles)))
    p("Wake-ups with nothing asked (possible false wakes): about %d" % max(empty, 0))
    ans = [e["ms"] for e in of("answer_start") if not e.get("tile")]
    tile_ans = [e["ms"] for e in of("answer_start") if e.get("tile")]
    if ans: p("Average time to start answering: %.1f s (slowest %.1f s)" % (sum(ans) / len(ans) / 1000, max(ans) / 1000))
    if tile_ans: p("Average tile answer time: %.1f s" % (sum(tile_ans) / len(tile_ans) / 1000))
    lk = of("look_up")
    if lk: p("Look-ups: %d, average %.1f s, failed %d" % (len(lk), sum(e["ms"] for e in lk) / len(lk) / 1000, sum(1 for e in lk if not e.get("ok"))))
    p("Azans played: %d (stopped by hand: %d)   Stops: %d   Quiet/sleep: %d" % (len(of("azan")), len(of("azan_stopped")), len(of("stop")), len(of("sleep"))))
    p()
    p("PER DAY")
    for day in sorted({e["t"][:10] for e in events}):
        d = [e for e in events if e["t"].startswith(day)]
        c = collections.Counter(e["type"] for e in d)
        p("  %s  wakes %2d  questions %2d  tiles %2d  azans %d  errors %d" % (day, c["wake"], c["turn"], c["tile"], c["azan"], c["error"]))
    p()
    p("BUSIEST HOURS")
    hours = collections.Counter(int(e["t"][11:13]) for e in wakes + tiles)
    for h, n in sorted(hours.items(), key=lambda x: -x[1])[:5]:
        p("  %2d:00  %s %d" % (h, "#" * n, n))
    p()
    p("TILES USED")
    for k, n in collections.Counter(e.get("id") for e in tiles).most_common(): p("  %-8s %d" % (k, n))
    p()
    p("MOST ASKED ABOUT (look-up questions and tools)")
    topics = collections.Counter()
    for e in lk:
        for w in re.findall(r"[a-zA-Z]{4,}", e.get("q", "").lower()):
            if w not in ("today", "what", "current", "latest", "news", "time", "with", "from", "about"): topics[w] += 1
    for e in of("tool"): topics["[" + e.get("name", "") + "]"] += 1
    for w, n in topics.most_common(10): p("  %-22s %d" % (w, n))
    p()
    p("PROBLEMS")
    errs = collections.Counter(e.get("what", "?") for e in of("error"))
    if not errs: p("  none")
    for w, n in errs.most_common(8): p("  %3d x %s" % (n, w))
    p("  app restarts: %d" % len(of("app_start")))
    p()
    p("LAST 10 QUESTIONS")
    for e in turns[-10:]:
        p("  %s  she: %s" % (e["t"][5:16].replace("T", " "), e.get("heard", "")[:70]))
        p("               Moyna: %s" % e.get("said", "")[:90])

text = "\n".join(out)
os.makedirs("private/logs", exist_ok=True)
io.open("private/logs/summary-%s.txt" % dt.date.today(), "w", encoding="utf-8").write(text + "\n")
sys.stdout.buffer.write((text + "\n").encode("utf-8"))
