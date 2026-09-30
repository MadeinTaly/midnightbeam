#!/usr/bin/env python3
"""Converts dayrhythm conformance vectors (test/vectors/*.json of the library) to the line format of
ScheduleMathTest. Cases with time zones / ISO dates and weeks with saved-day refs are skipped."""
import json, sys
W = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
def mins(t):
    if isinstance(t, (int, float)): return t
    p = t.split(":"); return int(p[0]) * 60 + int(p[1]) + (int(p[2]) / 60 if len(p) > 2 else 0)
out, skipped = [], 0
daily, weekly = sys.argv[1], sys.argv[2]
for s in json.load(open(daily))["suites"]:
    slots = s.get("slots") or []
    starts = ",".join(str(mins(x["time"])) for x in slots)
    for c in s["cases"]:
        at = c["at"]
        if isinstance(at, dict): skipped += 1; continue
        e = c["expect"]; ids = [x.get("id") for x in slots]
        want = -1 if e is None else ids.index(e)
        out.append("D %s | %s | %d" % (starts, mins(at), want))
for s in json.load(open(weekly))["suites"]:
    wk = s.get("week") or {}
    if any(isinstance(v, dict) for v in wk.values()) or "days" in s: skipped += len(s["cases"]); continue
    days = [wk.get(d, []) for d in W]
    starts = ";".join(",".join(str(mins(x["time"])) for x in d) for d in days)
    for c in s["cases"]:
        at = c["at"]
        if not (isinstance(at, dict) and "day" in at): skipped += 1; continue
        e = c["expect"]
        if e is None: want = "null"
        else:
            d = W.index(e["day"]); want = "%d %d" % (d, [x.get("id") for x in days[d]].index(e["id"]))
        out.append("W %s | %d %s | %s" % (starts, W.index(at["day"]), mins(at["time"]), want))
print("\n".join(out))
print("converted %d cases, skipped %d (time zones / refs)" % (len(out), skipped), file=sys.stderr)
