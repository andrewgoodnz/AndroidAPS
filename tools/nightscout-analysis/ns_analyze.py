#!/usr/bin/env python3
"""Offline analysis of an AndroidAPS/Nightscout dataset: glycemic patterns,
loop behaviour, and settings-optimisation signals.

    python3 ns_analyze.py --data data/ --tz Pacific/Auckland --units mmol \
        --report report.md --json summary.json

Reads the files written by ns_fetch.py: entries.json, treatments.json,
devicestatus.json, profile.json. devicestatus is optional but everything under
"loop behaviour" depends on it.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import timedelta
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

MGDL_PER_MMOL = 18.0182

# CGM bands, mg/dL (ADA/ATTD consensus)
VERY_LOW, LOW, HIGH, VERY_HIGH = 54, 70, 180, 250


# ----------------------------------------------------------------------------- loading

def _read(data_dir: str, name: str):
    path = os.path.join(data_dir, f"{name}.json")
    if not os.path.exists(path):
        return None
    with open(path) as fh:
        return json.load(fh)


def load_entries(data_dir: str, tz: ZoneInfo) -> pd.DataFrame:
    raw = _read(data_dir, "entries") or []
    rows = [e for e in raw if e.get("sgv") is not None and e.get("date")]
    if not rows:
        return pd.DataFrame(columns=["ts", "sgv", "direction"])
    df = pd.DataFrame(rows)
    df["ts"] = pd.to_datetime(df["date"], unit="ms", utc=True).dt.tz_convert(tz).dt.as_unit("ns")
    df["sgv"] = pd.to_numeric(df["sgv"], errors="coerce")
    df = df.dropna(subset=["sgv"]).drop_duplicates(subset=["ts"]).sort_values("ts")
    # Occasional calibration/backfill artefacts sit outside the sensor range.
    df = df[(df.sgv >= 20) & (df.sgv <= 500)]
    df["hour"] = df.ts.dt.hour
    df["date_local"] = df.ts.dt.date
    df["dow"] = df.ts.dt.dayofweek
    return df[["ts", "sgv", "hour", "date_local", "dow"]].reset_index(drop=True)


def empty_treatments(tz: ZoneInfo) -> pd.DataFrame:
    """Same columns and dtypes as a populated frame, so every caller downstream
    works on an account with no treatments in the window."""
    df = pd.DataFrame({
        "ts": pd.Series(dtype="datetime64[ns, UTC]"),
        "eventType": pd.Series(dtype=object),
        "insulin": pd.Series(dtype=float), "carbs": pd.Series(dtype=float),
        "absolute": pd.Series(dtype=float), "rate": pd.Series(dtype=float),
        "duration": pd.Series(dtype=float), "percent": pd.Series(dtype=float),
        "is_smb": pd.Series(dtype=bool), "hour": pd.Series(dtype=int),
        "date_local": pd.Series(dtype=object),
    })
    df["ts"] = df.ts.dt.tz_convert(tz)
    return df


def load_treatments(data_dir: str, tz: ZoneInfo) -> pd.DataFrame:
    raw = _read(data_dir, "treatments") or []
    if not raw:
        return empty_treatments(tz)
    df = pd.DataFrame(raw)
    stamp = df.get("created_at")
    if stamp is None:
        return empty_treatments(tz)
    df["ts"] = pd.to_datetime(stamp, errors="coerce", utc=True,
                              format="mixed").dt.tz_convert(tz).dt.as_unit("ns")
    df = df.dropna(subset=["ts"]).sort_values("ts")
    for col in ("insulin", "carbs", "absolute", "rate", "duration", "percent"):
        df[col] = pd.to_numeric(df.get(col), errors="coerce")
    df["eventType"] = df.get("eventType", pd.Series(index=df.index, dtype=object)).fillna("")
    # AAPS marks micro-boluses either with isSMB (v1 uploads) or type=SMB (v3).
    is_smb = df.get("isSMB", pd.Series(False, index=df.index)).fillna(False).astype(bool)
    if "type" in df:
        is_smb = is_smb | (df["type"].astype(str).str.upper() == "SMB")
    df["is_smb"] = is_smb
    df["hour"] = df.ts.dt.hour
    df["date_local"] = df.ts.dt.date
    return df.reset_index(drop=True)


REASON_PATTERNS = {
    "dev": r"Dev:\s*(-?[\d.]+)",
    "bgi": r"BGI:\s*(-?[\d.]+)",
    "isf_reason": r"ISF:\s*(-?[\d.]+)",
    "cr_reason": r"CR:\s*(-?[\d.]+)",
    "target_reason": r"Target:\s*(-?[\d.]+)",
    "min_pred_bg": r"minPredBG[:\s]*(-?[\d.]+)",
    "min_guard_bg": r"minGuardBG[:\s]*(-?[\d.]+)",
    "insulin_req_reason": r"insulinReq[:\s]*(-?[\d.]+)",
}


def load_devicestatus(data_dir: str, tz: ZoneInfo) -> pd.DataFrame:
    raw = _read(data_dir, "devicestatus") or []
    rows = []
    for d in raw:
        oaps = d.get("openaps") or {}
        sug = oaps.get("suggested") or {}
        ena = oaps.get("enacted") or {}
        if not sug and not ena:
            continue
        src = {**sug, **{k: v for k, v in ena.items() if v is not None}}
        reason = str(src.get("reason") or "")
        row = {
            "created_at": d.get("created_at") or src.get("timestamp"),
            "bg": src.get("bg"),
            "eventual_bg": src.get("eventualBG"),
            "target_bg": src.get("targetBG"),
            "iob": src.get("IOB"),
            "cob": src.get("COB"),
            "insulin_req": src.get("insulinReq"),
            "smb_units": src.get("units"),
            "rate": src.get("rate"),
            "duration": src.get("duration"),
            "sensitivity_ratio": src.get("sensitivityRatio"),
            "variable_sens": src.get("variable_sens"),
            "carbs_req": src.get("carbsReq"),
            "enacted": bool(ena),
            "reason": reason,
        }
        for key, pattern in REASON_PATTERNS.items():
            m = re.search(pattern, reason)
            row[key] = float(m.group(1)) if m else np.nan
        # Safety caps the loop reports hitting.
        row["capped_maxiob"] = "maxIOB" in reason
        row["capped_maxbolus"] = "maxBolus" in reason or "max_bolus" in reason
        row["capped_maxbasal"] = "maxSafeBasal" in reason or "max_basal" in reason
        rows.append(row)

    if not rows:
        return pd.DataFrame()
    df = pd.DataFrame(rows)
    df["ts"] = pd.to_datetime(df["created_at"], errors="coerce", utc=True,
                              format="mixed").dt.tz_convert(tz).dt.as_unit("ns")
    df = df.dropna(subset=["ts"]).drop_duplicates(subset=["ts"]).sort_values("ts")
    for col in ("bg", "eventual_bg", "target_bg", "iob", "cob", "insulin_req",
                "smb_units", "rate", "duration", "sensitivity_ratio", "variable_sens"):
        df[col] = pd.to_numeric(df[col], errors="coerce")
    df["hour"] = df.ts.dt.hour
    df["date_local"] = df.ts.dt.date
    return df.reset_index(drop=True)


def load_profile(data_dir: str) -> dict:
    raw = _read(data_dir, "profile")
    if not raw:
        return {}
    doc = raw[0] if isinstance(raw, list) else raw
    store = doc.get("store") or {}
    name = doc.get("defaultProfile") or (next(iter(store)) if store else None)
    prof = store.get(name, {}) if name else {}

    def schedule(key):
        out = []
        for item in prof.get(key, []) or []:
            try:
                secs = int(item.get("timeAsSeconds", 0) or 0)
                if not secs and item.get("time"):
                    h, m = str(item["time"]).split(":")[:2]
                    secs = int(h) * 3600 + int(m) * 60
                out.append((secs, float(item["value"])))
            except (ValueError, TypeError, KeyError):
                continue
        return sorted(out)

    return {
        "name": name,
        "units": prof.get("units", doc.get("units", "mg/dl")),
        "basal": schedule("basal"),
        "sens": schedule("sens"),
        "carbratio": schedule("carbratio"),
        "target_low": schedule("target_low"),
        "target_high": schedule("target_high"),
        "dia": prof.get("dia"),
        "timezone": prof.get("timezone"),
    }


def schedule_value(sched: list[tuple[int, float]], seconds: int) -> float:
    """Value in force at `seconds` past local midnight."""
    if not sched:
        return np.nan
    value = sched[-1][1]
    for start, val in sched:
        if seconds >= start:
            value = val
        else:
            break
    return value


# ----------------------------------------------------------------------------- helpers

class Units:
    def __init__(self, mode: str):
        self.mmol = mode == "mmol"

    def bg(self, mgdl, digits=1) -> str:
        if mgdl is None or (isinstance(mgdl, float) and np.isnan(mgdl)):
            return "n/a"
        return f"{mgdl / MGDL_PER_MMOL:.{digits}f}" if self.mmol else f"{mgdl:.0f}"

    @property
    def label(self) -> str:
        return "mmol/L" if self.mmol else "mg/dL"

    def to_mgdl(self, value: float) -> float:
        return value * MGDL_PER_MMOL if self.mmol else value


def detect_reason_scale(ds: pd.DataFrame) -> float:
    """AAPS renders the `reason` string in the user's *display* units, so Dev/BGI/
    ISF there may be mmol/L. Decide from `Target:`, which is unambiguous (a mg/dL
    target is never below 70, an mmol one never above 20) — never from Dev itself,
    whose values sit near zero in both units."""
    for col in ("target_reason", "isf_reason"):
        if col not in ds:
            continue
        vals = ds[col].dropna()
        if len(vals) > 20:
            return MGDL_PER_MMOL if vals.median() < 45 else 1.0
    return 1.0


def harmonise_units(ds: pd.DataFrame, entries: pd.DataFrame) -> pd.DataFrame:
    """Put every devicestatus number on the mg/dL scale used internally here.

    The numeric fields (bg/eventualBG/targetBG) are mg/dL in AAPS, but the check
    is cheap: compare against the CGM the same records were computed from."""
    if ds.empty:
        return ds
    ds = ds.copy()
    ref = entries.sgv.median() if len(entries) else 100.0
    bg_vals = ds.bg.dropna()
    if len(bg_vals) > 20 and ref > 45 and bg_vals.median() < 45:
        for col in ("bg", "eventual_bg", "target_bg"):
            ds[col] = ds[col] * MGDL_PER_MMOL

    scale = detect_reason_scale(ds)
    if scale != 1.0:
        for col in ("dev", "bgi", "isf_reason", "target_reason",
                    "min_pred_bg", "min_guard_bg"):
            if col in ds:
                ds[col] = ds[col] * scale
    ds.attrs["reason_scale"] = scale
    return ds


def merge_hour_blocks(hours: list[int]) -> list[tuple[int, int]]:
    """[1,2,3,7,23,0] -> [(23,3),(7,7)] — consecutive runs, wrapping past midnight."""
    hs = sorted(set(int(h) for h in hours))
    if not hs:
        return []
    runs, run = [], [hs[0]]
    for h in hs[1:]:
        if h == run[-1] + 1:
            run.append(h)
        else:
            runs.append(run)
            run = [h]
    runs.append(run)
    if len(runs) > 1 and runs[0][0] == 0 and runs[-1][-1] == 23:
        runs[0] = runs[-1] + runs[0]
        runs.pop()
    return [(r[0], r[-1]) for r in runs]


def fmt_blocks(hours: list[int]) -> str:
    parts = []
    for a, b in merge_hour_blocks(hours):
        parts.append(f"{a:02d}:00" if a == b else f"{a:02d}:00-{(b + 1) % 24:02d}:00")
    return ", ".join(parts)


def table(headers: list[str], rows: list[list], align_right_from: int = 1) -> str:
    sep = ["---" if i < align_right_from else "---:" for i in range(len(headers))]
    out = ["| " + " | ".join(headers) + " |", "| " + " | ".join(sep) + " |"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def pct(x: float) -> str:
    return f"{100 * x:.1f}%"


# ----------------------------------------------------------------------------- sections

def section_coverage(entries, ds, treatments, u: Units, summary: dict) -> str:
    start, end = entries.ts.min(), entries.ts.max()
    days = max((end - start).total_seconds() / 86400, 1e-9)
    expected = days * 288  # 5-minute CGM
    coverage = len(entries) / expected
    summary["period"] = {"start": str(start), "end": str(end), "days": round(days, 1),
                         "cgm_coverage": round(coverage, 3), "readings": len(entries)}

    lines = [
        f"**Window:** {start:%Y-%m-%d %H:%M} to {end:%Y-%m-%d %H:%M} ({days:.1f} days, local time)",
        f"**CGM readings:** {len(entries):,} — {pct(coverage)} of the expected 5-minute cadence",
        f"**Treatments:** {len(treatments):,}",
        f"**Loop cycles (devicestatus):** {len(ds):,}" if len(ds) else
        "**Loop cycles (devicestatus):** none — loop analysis unavailable",
    ]
    if coverage < 0.7:
        lines.append(f"\n> CGM coverage is only {pct(coverage)}. Percentages below are of "
                     f"*recorded* time, so sensor gaps bias every distribution here.")
    return "## 1. Dataset\n\n" + "\n\n".join(lines)


def section_glycemia(entries, u: Units, summary: dict) -> str:
    sgv = entries.sgv
    n = len(sgv)
    bands = {
        "Very low (<3.0)": (sgv < VERY_LOW).sum(),
        "Low (3.0-3.8)": ((sgv >= VERY_LOW) & (sgv < LOW)).sum(),
        "In range (3.9-10.0)": ((sgv >= LOW) & (sgv <= HIGH)).sum(),
        "High (10.1-13.9)": ((sgv > HIGH) & (sgv <= VERY_HIGH)).sum(),
        "Very high (>13.9)": (sgv > VERY_HIGH).sum(),
    }
    mean, sd = sgv.mean(), sgv.std()
    cv = sd / mean
    gmi = 3.31 + 0.02392 * mean  # Bergenstal 2018, %
    tir = bands["In range (3.9-10.0)"] / n

    summary["glycemia"] = {
        "mean_mgdl": round(mean, 1), "sd_mgdl": round(sd, 1), "cv": round(cv, 3),
        "gmi_percent": round(gmi, 2), "tir": round(tir, 3),
        "tbr_70": round((sgv < LOW).sum() / n, 4),
        "tbr_54": round((sgv < VERY_LOW).sum() / n, 4),
        "tar_180": round((sgv > HIGH).sum() / n, 4),
    }

    rows = [[k, f"{100 * v / n:.1f}%", f"{v * 5 / 60:.1f} h/period",
             f"{v * 5 / 60 / max(entries.date_local.nunique(), 1) * 60:.0f} min/day"]
            for k, v in bands.items()]

    targets = [
        ("Time in range 3.9-10.0", pct(tir), ">70%", tir >= 0.70),
        ("Time below 3.9", pct((sgv < LOW).sum() / n), "<4%", (sgv < LOW).sum() / n < 0.04),
        ("Time below 3.0", pct((sgv < VERY_LOW).sum() / n), "<1%", (sgv < VERY_LOW).sum() / n < 0.01),
        ("Time above 10.0", pct((sgv > HIGH).sum() / n), "<25%", (sgv > HIGH).sum() / n < 0.25),
        ("Coefficient of variation", pct(cv), "<36%", cv < 0.36),
    ]

    return "\n\n".join([
        "## 2. Glycemic overview",
        table(["Metric", "Value"], [
            [f"Mean glucose", f"{u.bg(mean)} {u.label}"],
            ["GMI (estimated A1c)", f"{gmi:.2f}%"],
            [f"SD", f"{u.bg(sd)} {u.label}"],
            ["CV", pct(cv)],
        ]),
        table(["Range", "% of time", "Total", "Per day"], rows),
        "**Against the consensus targets:**",
        table(["Target", "Yours", "Goal", "Met"],
              [[t[0], t[1], t[2], "yes" if t[3] else "**no**"] for t in targets]),
    ])


def section_time_of_day(entries, u: Units, summary: dict) -> str:
    g = entries.groupby("hour").sgv
    stats = pd.DataFrame({
        "median": g.median(), "p25": g.quantile(0.25), "p75": g.quantile(0.75),
        "mean": g.mean(), "n": g.size(),
        "tir": entries.assign(ir=entries.sgv.between(LOW, HIGH)).groupby("hour").ir.mean(),
        "low": entries.assign(lo=entries.sgv < LOW).groupby("hour").lo.mean(),
        "high": entries.assign(hi=entries.sgv > HIGH).groupby("hour").hi.mean(),
    })
    summary["hourly"] = {int(h): {"median_mgdl": round(r["median"], 1),
                                  "tir": round(r["tir"], 3), "below": round(r["low"], 4)}
                         for h, r in stats.iterrows()}

    rows = []
    for h, r in stats.iterrows():
        bar_lo = "▁" if r["low"] < 0.02 else ("▄" if r["low"] < 0.05 else "█")
        rows.append([f"{h:02d}:00", u.bg(r['median']), f"{u.bg(r['p25'])}-{u.bg(r['p75'])}",
                     pct(r["tir"]), pct(r["low"]) + " " + bar_lo, pct(r["high"])])

    worst_high = stats.high.idxmax()
    worst_low = stats.low.idxmax()
    best = stats.tir.idxmax()
    notes = [
        f"- Most hyperglycemia at **{worst_high:02d}:00** ({pct(stats.high[worst_high])} above 10.0)",
        f"- Most hypoglycemia at **{worst_low:02d}:00** ({pct(stats.low[worst_low])} below 3.9)",
        f"- Best-controlled hour: **{best:02d}:00** ({pct(stats.tir[best])} in range)",
    ]

    # Dawn phenomenon: median rise from the 03:00 nadir to 08:00.
    night = stats.loc[[0, 1, 2, 3], "median"].mean()
    dawn = stats.loc[[7, 8, 9], "median"].mean()
    if dawn - night > 20:
        notes.append(f"- **Dawn rise:** median {u.bg(night)} overnight to {u.bg(dawn)} by 07:00-09:00 "
                     f"(+{u.bg(dawn - night)} {u.label}) — a basal/ISF shape issue, not a bolus one")

    return "\n\n".join([
        "## 3. Pattern by time of day",
        table(["Hour", "Median", "IQR", "In range", "Below 3.9", "Above 10.0"], rows),
        "\n".join(notes),
    ])


def section_day_of_week(entries, u: Units) -> str:
    names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    g = entries.groupby("dow")
    rows = []
    for d, sub in g:
        rows.append([names[d], u.bg(sub.sgv.median()),
                     pct(sub.sgv.between(LOW, HIGH).mean()),
                     pct((sub.sgv < LOW).mean()), pct((sub.sgv > HIGH).mean())])
    return "## 4. Pattern by day of week\n\n" + table(
        ["Day", "Median", "In range", "Below 3.9", "Above 10.0"], rows)


def find_hypo_episodes(entries: pd.DataFrame, threshold=LOW, min_minutes=15) -> pd.DataFrame:
    """Consecutive readings below threshold lasting >= min_minutes, ending after
    15 minutes back at or above it (ADA episode definition)."""
    e = entries.reset_index(drop=True)
    below = (e.sgv < threshold).values
    episodes, i, n = [], 0, len(e)
    while i < n:
        if not below[i]:
            i += 1
            continue
        j = i
        recovered_at = None
        while j < n:
            if below[j]:
                recovered_at = None
            else:
                if recovered_at is None:
                    recovered_at = j
                elif (e.ts[j] - e.ts[recovered_at]).total_seconds() >= 15 * 60:
                    break
            j += 1
        end = min(j, n - 1)
        window = e.iloc[i:end + 1]
        low_part = window[window.sgv < threshold]
        if len(low_part):
            duration = (low_part.ts.max() - low_part.ts.min()).total_seconds() / 60 + 5
            if duration >= min_minutes:
                episodes.append({
                    "start": low_part.ts.min(), "duration_min": duration,
                    "nadir": low_part.sgv.min(), "hour": low_part.ts.min().hour,
                })
        i = end + 1
    return pd.DataFrame(episodes)


def section_hypos(entries, treatments, ds, u: Units, summary: dict) -> str:
    eps = find_hypo_episodes(entries)
    days = max(entries.date_local.nunique(), 1)
    if eps.empty:
        summary["hypos"] = {"episodes": 0}
        return "## 5. Hypoglycemia episodes\n\nNo episodes below 3.9 lasting 15+ minutes. "

    summary["hypos"] = {
        "episodes": int(len(eps)), "per_day": round(len(eps) / days, 2),
        "median_duration_min": float(eps.duration_min.median()),
        "severe": int((eps.nadir < VERY_LOW).sum()),
    }

    by_block = eps.assign(block=pd.cut(eps.hour, [-1, 5, 11, 17, 23],
                                       labels=["00-06", "06-12", "12-18", "18-24"]))
    block_rows = [[str(b), int(c), f"{c / days:.2f}"]
                  for b, c in by_block.block.value_counts().sort_index().items()]

    # What preceded each episode: bolus/carbs in the 3 hours before onset.
    ctx = {"after_bolus": 0, "after_carbs": 0, "unprovoked": 0}
    boluses = treatments[(treatments.insulin > 0) & (~treatments.is_smb)]
    carbs = treatments[treatments.carbs > 0]
    for _, ep in eps.iterrows():
        w0 = ep.start - timedelta(hours=3)
        had_bolus = ((boluses.ts >= w0) & (boluses.ts < ep.start)).any()
        had_carbs = ((carbs.ts >= w0) & (carbs.ts < ep.start)).any()
        if had_bolus:
            ctx["after_bolus"] += 1
        if had_carbs:
            ctx["after_carbs"] += 1
        if not had_bolus and not had_carbs:
            ctx["unprovoked"] += 1

    lines = [
        f"**{len(eps)} episodes** over {days} days ({len(eps) / days:.2f}/day), "
        f"median duration {eps.duration_min.median():.0f} min, "
        f"median nadir {u.bg(eps.nadir.median())} {u.label}.",
        f"**{int((eps.nadir < VERY_LOW).sum())}** episodes went below 3.0.",
        table(["Time block", "Episodes", "Per day"], block_rows),
        f"**Context (3 h before onset):** {ctx['after_bolus']} followed a manual bolus, "
        f"{ctx['after_carbs']} followed carb entry, "
        f"**{ctx['unprovoked']} had neither** — those point at basal or ISF, not meal dosing.",
    ]
    if ctx["unprovoked"] > len(eps) * 0.4:
        lines.append("> The unprovoked share is high. Fasting/overnight lows with no meal "
                     "insulin in play are the classic signature of an over-strong basal "
                     "profile in those hours (see section 9).")
    return "## 5. Hypoglycemia episodes\n\n" + "\n\n".join(lines)


def section_loop_behaviour(ds, entries, u: Units, summary: dict) -> str:
    if ds.empty:
        return "## 6. Loop behaviour\n\n_No devicestatus records; skipped._"

    span_min = (ds.ts.max() - ds.ts.min()).total_seconds() / 60
    expected = span_min / 5
    uptime = len(ds) / expected if expected else np.nan
    gaps = ds.ts.diff().dt.total_seconds().div(60)
    long_gaps = gaps[gaps > 20]

    enacted_rate = ds.enacted.mean()
    zero_temp = (ds.rate.fillna(-1) == 0).mean()
    smbs = ds[ds.smb_units.fillna(0) > 0]
    carbs_req = ds[ds.carbs_req.fillna(0) > 0]

    summary["loop"] = {
        "cycles": int(len(ds)), "uptime": round(float(uptime), 3),
        "long_gaps": int(len(long_gaps)),
        "gap_hours": round(float(long_gaps.sum() / 60), 1) if len(long_gaps) else 0.0,
        "zero_temp_share": round(float(zero_temp), 3),
        "smb_cycles": int(len(smbs)),
        "capped_maxiob": round(float(ds.capped_maxiob.mean()), 3),
    }

    rows = [
        ["Loop cycles recorded", f"{len(ds):,}"],
        ["Cycle uptime", pct(uptime)],
        ["Gaps > 20 min", f"{len(long_gaps)} ({long_gaps.sum() / 60:.1f} h total)"],
        ["Cycles that enacted a change", pct(enacted_rate)],
        ["Cycles at zero temp (low-suspend)", pct(zero_temp)],
        ["Cycles delivering an SMB", pct(len(smbs) / len(ds))],
        ["Cycles hitting maxIOB", pct(ds.capped_maxiob.mean())],
        ["Cycles hitting a max-bolus cap", pct(ds.capped_maxbolus.mean())],
        ["Cycles hitting maxSafeBasal", pct(ds.capped_maxbasal.mean())],
        ["Carb-required warnings", f"{len(carbs_req)}"],
    ]

    lines = ["## 6. Loop behaviour", table(["Metric", "Value"], rows)]

    if uptime < 0.9:
        lines.append(f"> Uptime is {pct(uptime)} with {long_gaps.sum() / 60:.1f} h of gaps — "
                     f"the loop was not running for part of this window. Every "
                     f"'time in range' number below includes open-loop time.")
    if ds.capped_maxiob.mean() > 0.05:
        lines.append(f"> maxIOB was the binding constraint on {pct(ds.capped_maxiob.mean())} of "
                     f"cycles. If those cluster around meals, the loop is being held back "
                     f"from correcting rather than choosing not to.")
    if zero_temp > 0.20:
        lines.append(f"> {pct(zero_temp)} of cycles at zero temp is a lot of low-suspend. "
                     f"That is the loop defending against too much insulin somewhere upstream.")

    # Where the caps bite, by hour.
    if ds.capped_maxiob.any():
        by_hour = ds.groupby("hour").capped_maxiob.mean()
        top = by_hour.nlargest(3)
        lines.append("**maxIOB cap by hour (top 3):** " +
                     ", ".join(f"{h:02d}:00 {pct(v)}" for h, v in top.items()))
    return "\n\n".join(lines)


def build_basal_timeline(treatments: pd.DataFrame, profile: dict,
                         start, end, tz) -> pd.DataFrame:
    """5-minute grid of delivered basal vs profile basal."""
    grid = pd.date_range(start.ceil("5min"), end.floor("5min"), freq="5min", tz=tz)
    if len(grid) == 0:
        return pd.DataFrame()
    delivered = pd.Series(np.nan, index=grid)

    tbrs = treatments[treatments.eventType.astype(str).str.startswith("Temp Basal")].copy()
    tbrs = tbrs.dropna(subset=["ts"]).sort_values("ts")
    if len(tbrs):
        # A new TBR supersedes whatever was running.
        next_start = tbrs.ts.shift(-1)
        for (_, t), nxt in zip(tbrs.iterrows(), next_start):
            dur = t.duration if pd.notna(t.duration) else 30
            stop = t.ts + timedelta(minutes=float(dur))
            if pd.notna(nxt):
                stop = min(stop, nxt)
            rate = t.absolute
            if pd.isna(rate) and pd.notna(t.get("percent")) and profile.get("basal"):
                secs = t.ts.hour * 3600 + t.ts.minute * 60
                rate = schedule_value(profile["basal"], secs) * (1 + t["percent"] / 100)
            if pd.isna(rate):
                continue
            delivered.loc[(delivered.index >= t.ts) & (delivered.index < stop)] = float(rate)

    secs = grid.hour * 3600 + grid.minute * 60
    prof_basal = pd.Series([schedule_value(profile.get("basal", []), int(s)) for s in secs],
                           index=grid)
    delivered = delivered.fillna(prof_basal)  # no TBR running -> profile rate
    return pd.DataFrame({"delivered": delivered, "profile": prof_basal,
                         "hour": grid.hour}, index=grid)


def section_insulin(treatments, ds, profile, basal_tl, u: Units, summary: dict, days: int) -> str:
    boluses = treatments[(treatments.insulin > 0)]
    smb = boluses[boluses.is_smb]
    manual = boluses[~boluses.is_smb]

    basal_units = float(basal_tl.delivered.sum() / 12) if len(basal_tl) else np.nan  # U per 5 min
    profile_units = float(basal_tl.profile.sum() / 12) if len(basal_tl) else np.nan
    smb_units, manual_units = smb.insulin.sum(), manual.insulin.sum()
    tdd = np.nansum([basal_units, smb_units, manual_units])

    summary["insulin"] = {
        "tdd_per_day": round(tdd / days, 2),
        "basal_per_day": round(basal_units / days, 2) if basal_units == basal_units else None,
        "smb_per_day": round(smb_units / days, 2),
        "manual_bolus_per_day": round(manual_units / days, 2),
        "basal_share": round(basal_units / tdd, 3) if tdd else None,
        "delivered_vs_profile_basal": round(basal_units / profile_units, 3) if profile_units else None,
    }

    rows = [
        ["Total daily dose", f"{tdd / days:.1f} U/day", "100%"],
        ["Basal (incl. temp basals)", f"{basal_units / days:.1f} U/day", pct(basal_units / tdd)],
        ["SMB", f"{smb_units / days:.1f} U/day", pct(smb_units / tdd)],
        ["Manual/meal bolus", f"{manual_units / days:.1f} U/day", pct(manual_units / tdd)],
    ]
    lines = ["## 7. Insulin delivery", table(["Component", "Amount", "Share"], rows)]

    if profile_units and profile_units == profile_units:
        ratio = basal_units / profile_units
        lines.append(
            f"**Delivered basal is {ratio:.2f}x the profile basal** "
            f"({basal_units / days:.1f} vs {profile_units / days:.1f} U/day). "
            + ("The loop is systematically adding basal on top of the profile — the profile is "
               "under-set." if ratio > 1.10 else
               "The loop is systematically holding basal back — the profile is over-set."
               if ratio < 0.90 else
               "That is close to neutral, which is what a well-tuned profile looks like.")
        )

        # The per-hour version is the actionable one.
        by_hour = basal_tl.groupby("hour")[["delivered", "profile"]].mean()
        by_hour["ratio"] = by_hour.delivered / by_hour.profile
        flagged = by_hour[(by_hour.ratio > 1.20) | (by_hour.ratio < 0.80)]
        if len(flagged):
            # Report contiguous blocks, not 9 near-identical hourly rows.
            frows, blocks = [], []
            for lo in (True, False):
                subset = flagged[flagged.ratio < 1] if lo else flagged[flagged.ratio > 1]
                for a, b in merge_hour_blocks(subset.index.tolist()):
                    hours = [h % 24 for h in range(a, a + ((b - a) % 24) + 1)]
                    seg = by_hour.loc[hours]
                    ratio = seg.delivered.sum() / seg.profile.sum()
                    label = f"{a:02d}:00-{(b + 1) % 24:02d}:00"
                    frows.append([label, f"{seg.profile.mean():.3f}", f"{seg.delivered.mean():.3f}",
                                  f"{ratio:.2f}x",
                                  "profile too low" if ratio > 1 else "profile too high"])
                    blocks.append({"from": a, "to": (b + 1) % 24, "ratio": round(float(ratio), 2),
                                   "profile_u_h": round(float(seg.profile.mean()), 3),
                                   "delivered_u_h": round(float(seg.delivered.mean()), 3)})
            lines.append("**Blocks where the loop persistently disagrees with the profile basal "
                         "(>20%):**\n\n" +
                         table(["Block", "Profile U/h", "Delivered U/h", "Ratio", "Reading"], frows))
            summary["basal_disagreement"] = blocks

    if len(smb):
        lines.append(f"**SMB profile:** {len(smb) / days:.0f} per day, mean "
                     f"{smb.insulin.mean():.2f} U, max {smb.insulin.max():.2f} U.")
    return "\n\n".join(lines)


def section_sensitivity(ds, profile, u: Units, summary: dict) -> str:
    if ds.empty:
        return "## 8. Autosens / ISF behaviour\n\n_No devicestatus records; skipped._"

    lines = ["## 8. Autosens and ISF behaviour"]
    sr = ds.sensitivity_ratio.dropna()
    if len(sr):
        by_hour = ds.dropna(subset=["sensitivity_ratio"]).groupby("hour").sensitivity_ratio.median()
        summary["autosens"] = {"median": round(float(sr.median()), 3),
                               "p10": round(float(sr.quantile(0.1)), 3),
                               "p90": round(float(sr.quantile(0.9)), 3),
                               "share_above_1": round(float((sr > 1).mean()), 3)}
        lines.append(table(["Autosens ratio", "Value"], [
            ["Median", f"{sr.median():.2f}"],
            ["10th-90th percentile", f"{sr.quantile(0.1):.2f} - {sr.quantile(0.9):.2f}"],
            ["Share of time > 1.0 (resistant)", pct((sr > 1).mean())],
            ["Share of time < 1.0 (sensitive)", pct((sr < 1).mean())],
            ["Share pinned at observed max", pct((sr >= sr.max() - 1e-9).mean())],
        ]))
        skew = by_hour.sub(1).abs().nlargest(4)
        lines.append("**Hours with the most persistent autosens correction:** " +
                     ", ".join(f"{h:02d}:00 ({by_hour[h]:.2f}x)" for h in skew.index))
        if sr.median() > 1.15:
            lines.append("> Autosens sits above 1.15 most of the time: the whole profile "
                         "(basal *and* ISF) is weaker than reality. Autosens is meant to correct "
                         "day-to-day drift, not a permanent offset — fold this into the profile.")
        elif sr.median() < 0.85:
            lines.append("> Autosens sits below 0.85 most of the time: the profile is stronger "
                         "than reality and the loop is permanently compensating downward.")

    vs = ds.variable_sens.dropna()
    if len(vs):
        # Profile units are declared, so use them rather than guessing from magnitude.
        prof_is_mmol = str(profile.get("units", "")).lower().startswith("mmol")
        prof_isf_mgdl = None
        if profile.get("sens"):
            prof_isf = float(np.mean([v for _, v in profile["sens"]]))
            prof_isf_mgdl = prof_isf * (MGDL_PER_MMOL if prof_is_mmol else 1)
        # variable_sens should already be mg/dL; if it is not, whichever reading
        # sits closer to the profile's own ISF is the right one.
        vs_mgdl = vs.copy()
        if prof_isf_mgdl:
            med = max(float(vs.median()), 1e-9)
            if abs(np.log(med * MGDL_PER_MMOL / prof_isf_mgdl)) < abs(np.log(med / prof_isf_mgdl)):
                vs_mgdl = vs * MGDL_PER_MMOL
        lines.append(f"**Dynamic ISF is active.** Variable sensitivity ranged "
                     f"{u.bg(vs_mgdl.quantile(0.1))} - {u.bg(vs_mgdl.quantile(0.9))} {u.label}/U "
                     f"(median {u.bg(vs_mgdl.median())}).")
        if prof_isf_mgdl:
            lines.append(f"Profile ISF averages {u.bg(prof_isf_mgdl)} {u.label}/U, so dynamic ISF "
                         f"runs {vs_mgdl.median() / prof_isf_mgdl:.2f}x the static value on "
                         f"average (below 1.0 = dosing more aggressively than the profile).")
    return "\n\n".join(lines)


def section_deviations(ds, u: Units, summary: dict) -> str:
    """Dev is observed BG change minus insulin-predicted change: the residual the
    loop cannot explain. Its sign by hour is the cleanest tuning signal available."""
    if ds.empty or ds.dev.dropna().empty:
        return "## 9. Unexplained deviations\n\n_No parseable `Dev:` values; skipped._"

    d = ds.dropna(subset=["dev"]).copy()
    zero_cob = d[d.cob.fillna(0) == 0]
    base = zero_cob if len(zero_cob) > 200 else d
    filtered = len(zero_cob) > 200

    by_hour = base.groupby("hour").dev.agg(["median", "mean", "count"])
    by_hour = by_hour[by_hour["count"] >= 20]  # an hour needs real support to be read
    if by_hour.empty:
        return "## 9. Unexplained deviations\n\n_Too few cycles per hour to read; skipped._"
    summary["deviations"] = {int(h): round(float(r["median"]), 1) for h, r in by_hour.iterrows()}

    rows = []
    for h, r in by_hour.iterrows():
        bias = "rising" if r["median"] > 3 else ("falling" if r["median"] < -3 else "neutral")
        rows.append([f"{h:02d}:00", f"{r['median']:+.1f}", f"{r['mean']:+.1f}",
                     int(r["count"]), bias])

    hot = by_hour[by_hour["median"] > 3].index.tolist()
    cold = by_hour[by_hour["median"] < -3].index.tolist()
    missing = sorted(set(range(24)) - set(by_hour.index))
    lines = [
        "## 9. Unexplained deviations (the tuning signal)",
        "`Dev` is the gap between the BG change the loop predicted from insulin alone and what "
        "actually happened, in mg/dL per 5 min cycle. A persistently positive Dev in an hour "
        "means insulin needs there are higher than the profile says; persistently negative means "
        "lower. This is the same residual autotune works from."
        + (" Carb-affected cycles (COB > 0) are excluded, so digestion is not counted as a "
           "sensitivity change." if filtered else
           " **Not** filtered by COB — too few zero-COB cycles — so meal hours are inflated."),
        table(["Hour", "Median Dev", "Mean Dev", "Cycles", "Bias"], rows),
    ]
    if missing:
        lines.append(f"_Hours {fmt_blocks(missing)} had too few zero-carb cycles to include._")
    if cold:
        lines.append(f"**Over-insulinised: {fmt_blocks(cold)}** — BG drifts down here with no "
                     f"carbs on board. This is where fasting lows come from, and it is the "
                     f"highest-value thing to fix.")
    if hot:
        lines.append(f"**Under-insulinised: {fmt_blocks(hot)}** — BG drifts up here even with no "
                     f"carbs on board.")
    return "\n\n".join(lines)


def section_prediction(ds, entries, u: Units, summary: dict) -> str:
    if ds.empty or ds.eventual_bg.dropna().empty:
        return "## 10. Prediction accuracy\n\n_No eventualBG values; skipped._"

    d = ds.dropna(subset=["eventual_bg"]).copy()
    d["eventual_mgdl"] = d.eventual_bg
    d["target_60"] = d.ts + timedelta(minutes=60)

    actual = pd.merge_asof(
        d.sort_values("target_60")[["target_60", "eventual_mgdl", "hour"]],
        entries[["ts", "sgv"]].sort_values("ts"),
        left_on="target_60", right_on="ts", direction="nearest",
        tolerance=pd.Timedelta("7min"),
    ).dropna(subset=["sgv"])

    if actual.empty:
        return "## 10. Prediction accuracy\n\n_Could not align predictions with CGM; skipped._"

    actual["error"] = actual.eventual_mgdl - actual.sgv
    bias, mae = actual.error.mean(), actual.error.abs().mean()
    summary["prediction"] = {"n": int(len(actual)), "bias_mgdl": round(float(bias), 1),
                             "mae_mgdl": round(float(mae), 1)}

    by_hour = actual.groupby("hour").error.median()
    worst = by_hour.abs().nlargest(3)

    lines = [
        "## 10. Prediction accuracy (eventualBG vs actual +60 min)",
        table(["Metric", "Value"], [
            ["Predictions compared", f"{len(actual):,}"],
            ["Bias (predicted - actual)", f"{u.bg(abs(bias))} {u.label} "
                                          f"{'high' if bias > 0 else 'low'}"],
            ["Mean absolute error", f"{u.bg(mae)} {u.label}"],
        ]),
        "**Largest hourly bias:** " + ", ".join(
            f"{h:02d}:00 ({'+' if by_hour[h] > 0 else '-'}{u.bg(abs(by_hour[h]))})"
            for h in worst.index),
    ]
    if abs(bias) > 15:
        direction = "over" if bias > 0 else "under"
        lines.append(f"> The loop {direction}-predicts eventual BG by {u.bg(abs(bias))} "
                     f"{u.label} on average. A one-sided bias of that size usually traces to ISF "
                     f"rather than to the prediction maths.")
    return "\n\n".join(lines)


def section_meals(treatments, entries, ds, profile, u: Units, summary: dict) -> str:
    meals = treatments[(treatments.carbs.fillna(0) >= 15)].copy()
    if meals.empty or entries.empty:
        return "## 11. Meal responses\n\n_No carb entries of 15 g or more; skipped._"

    e = entries.set_index("ts").sgv.sort_index()
    records = []
    for _, m in meals.iterrows():
        window = e[(e.index >= m.ts - timedelta(minutes=15)) &
                   (e.index <= m.ts + timedelta(hours=5))]
        if len(window) < 24:  # need most of the 5 h covered
            continue
        pre = window[window.index <= m.ts + timedelta(minutes=5)]
        if pre.empty:
            continue
        start_bg = pre.iloc[-1]
        post = window[window.index > m.ts]
        if post.empty:
            continue
        peak_ts = post.idxmax()
        at_4h = post[post.index <= m.ts + timedelta(hours=4)]
        end_bg = at_4h.iloc[-1] if len(at_4h) else np.nan
        # Insulin given with the meal (+/- 20 min), SMBs excluded: that is what
        # the user's carb ratio actually produced.
        near = treatments[(treatments.ts >= m.ts - timedelta(minutes=20)) &
                          (treatments.ts <= m.ts + timedelta(minutes=20)) &
                          (treatments.insulin > 0) & (~treatments.is_smb)]
        records.append({
            "ts": m.ts, "hour": m.ts.hour, "carbs": m.carbs,
            "bolus": near.insulin.sum(),
            "start": start_bg, "peak": post.max(),
            "rise": post.max() - start_bg,
            "tt_peak": (peak_ts - m.ts).total_seconds() / 60,
            "end_4h": end_bg, "delta_4h": end_bg - start_bg,
            "hypo_5h": bool((post < LOW).any()),
        })

    if not records:
        return "## 11. Meal responses\n\n_No meals with enough surrounding CGM data; skipped._"

    r = pd.DataFrame(records)
    r["ratio"] = np.where(r.bolus > 0, r.carbs / r.bolus, np.nan)
    summary["meals"] = {
        "n": int(len(r)), "median_rise_mgdl": round(float(r.rise.median()), 1),
        "median_tt_peak_min": round(float(r.tt_peak.median()), 0),
        "median_delta_4h_mgdl": round(float(r.delta_4h.median()), 1),
        "hypo_share": round(float(r.hypo_5h.mean()), 3),
        "median_observed_cr": round(float(r.ratio.median()), 1) if r.ratio.notna().any() else None,
    }

    blocks = pd.cut(r.hour, [-1, 10, 15, 21, 23], labels=["Breakfast", "Lunch", "Dinner", "Late"])
    brows = []
    for b, sub in r.groupby(blocks, observed=True):
        if sub.empty:
            continue
        brows.append([str(b), len(sub), f"{sub.carbs.median():.0f} g",
                      f"{sub.ratio.median():.1f}" if sub.ratio.notna().any() else "n/a",
                      f"+{u.bg(sub.rise.median())}", f"{sub.tt_peak.median():.0f} min",
                      f"{'+' if sub.delta_4h.median() >= 0 else '-'}{u.bg(abs(sub.delta_4h.median()))}",
                      pct(sub.hypo_5h.mean())])

    lines = [
        "## 11. Meal responses",
        f"{len(r)} meals of 15 g or more with usable CGM coverage.",
        table(["Meal", "n", "Median carbs", "CR used", "Peak rise", "Time to peak",
               "Net at 4 h", "Hypo within 5 h"], brows),
        "'Net at 4 h' is the carb-ratio test: a well-matched ratio lands back near where it "
        "started. Consistently positive means the ratio is too weak; consistently negative (or a "
        "hypo) means too strong. **Caveat:** it also absorbs whatever the basal is doing across "
        "those 4 hours, so read it only after the basal blocks in section 9 are settled — a meal "
        "sitting on top of an over-strong basal looks like an over-strong carb ratio.",
    ]
    for b, sub in r.groupby(blocks, observed=True):
        if len(sub) < 3:
            continue
        med, cr = sub.delta_4h.median(), sub.ratio.median()
        if med > 30 and pd.notna(cr):
            lines.append(f"- **{b}: ratio looks too weak.** Net +{u.bg(med)} {u.label} at 4 h "
                         f"across {len(sub)} meals on CR {cr:.1f} g/U.")
        elif med < -30 and pd.notna(cr):
            lines.append(f"- **{b}: ratio looks too strong.** Net {u.bg(med)} {u.label} at 4 h "
                         f"across {len(sub)} meals on CR {cr:.1f} g/U.")
    if r.tt_peak.median() > 90:
        lines.append(f"- Median time to peak is {r.tt_peak.median():.0f} min. Late peaks usually "
                     f"mean the bolus is going in too late relative to the carbs rather than "
                     f"being the wrong size.")
    return "\n\n".join(lines)


def section_recommendations(summary: dict, profile: dict, u: Units) -> str:
    """Synthesise the sections above into ranked, concrete changes."""
    recs = []
    gly = summary.get("glycemia", {})
    hypo = summary.get("hypos", {})
    loop = summary.get("loop", {})
    devs = summary.get("deviations", {})
    basal_dis = summary.get("basal_disagreement", [])
    autosens = summary.get("autosens", {})
    meals = summary.get("meals", {})
    pred = summary.get("prediction", {})

    if gly.get("tbr_54", 0) > 0.01:
        recs.append(("Safety first", "high",
                     f"Time below 3.0 is {pct(gly['tbr_54'])} (target <1%). Fix the hypo drivers "
                     f"below before chasing any in-range number."))
    if gly.get("tbr_70", 0) > 0.04:
        recs.append(("Reduce lows", "high",
                     f"Time below 3.9 is {pct(gly['tbr_70'])} (target <4%)."))

    over = sorted([h for h, v in devs.items() if v < -3])
    under = sorted([h for h, v in devs.items() if v > 3])
    if over:
        recs.append(("Basal: reduce", "high",
                     f"{fmt_blocks(over)} runs a negative deviation with no carbs on board — BG "
                     f"falls faster than insulin alone explains. Cut basal ~10-20%, starting the "
                     f"reduction 1-2 h *before* the block (insulin lags the rate change)."))
    if under:
        recs.append(("Basal: increase", "medium",
                     f"{fmt_blocks(under)} runs a positive deviation with no carbs on board. "
                     f"Raise basal ~10%, again starting 1-2 h before the block."))
    for blk in basal_dis:
        direction = "Raise" if blk["ratio"] > 1 else "Lower"
        recs.append((f"Basal {blk['from']:02d}:00-{blk['to']:02d}:00", "medium",
                     f"The loop ran {blk['ratio']:.2f}x profile basal across this block for the "
                     f"whole period ({blk['delivered_u_h']:.2f} vs {blk['profile_u_h']:.2f} U/h). "
                     f"{direction} the profile toward what the loop keeps choosing so it has "
                     f"headroom to react, instead of spending its range correcting a known "
                     f"offset."))

    med_as = autosens.get("median")
    if med_as and med_as > 1.15:
        recs.append(("Whole profile is weak", "high",
                     f"Median autosens {med_as:.2f}. Strengthen basal and ISF together by roughly "
                     f"{(med_as - 1) * 100:.0f}% rather than leaving autosens to do it every day."))
    elif med_as and med_as < 0.85:
        recs.append(("Whole profile is strong", "high",
                     f"Median autosens {med_as:.2f}. Weaken basal and ISF together by roughly "
                     f"{(1 - med_as) * 100:.0f}%."))

    # Only worth raising once basal is not obviously confounding the 4 h window.
    basal_noisy = bool(over or under or basal_dis)
    delta_4h = meals.get("median_delta_4h_mgdl", 0) or 0
    if abs(delta_4h) > 40:
        strong = delta_4h < 0
        recs.append(("Carb ratio", "medium" if basal_noisy else ("high" if strong else "medium"),
                     f"Meals land {'+' if delta_4h > 0 else '-'}{u.bg(abs(delta_4h))} {u.label} "
                     f"{'above' if delta_4h > 0 else 'below'} baseline at 4 h, so the ratio looks "
                     f"{'too strong' if strong else 'too weak'} by roughly 10%."
                     + (" Re-check this after the basal changes above land — right now it may "
                        "just be the basal error showing through the meal window."
                        if basal_noisy else "")))
    if meals.get("median_tt_peak_min", 0) > 90:
        recs.append(("Bolus timing", "medium",
                     f"Median time to peak {meals['median_tt_peak_min']:.0f} min. Pre-bolus "
                     f"15-20 min earlier before changing any ratio."))

    if pred.get("bias_mgdl") and abs(pred["bias_mgdl"]) > 15:
        recs.append(("ISF", "medium",
                     f"eventualBG is biased {u.bg(abs(pred['bias_mgdl']))} {u.label} "
                     f"{'high' if pred['bias_mgdl'] > 0 else 'low'} at the 60-minute horizon, "
                     f"which is an ISF-strength signal in the same direction."))

    if loop.get("uptime", 1) < 0.9:
        recs.append(("Loop uptime", "high",
                     f"Only {pct(loop['uptime'])} cycle uptime ({loop.get('gap_hours', 0)} h of "
                     f"gaps). Connectivity is capping what any settings change can achieve."))
    if loop.get("capped_maxiob", 0) > 0.05:
        recs.append(("maxIOB", "medium",
                     f"maxIOB bound {pct(loop['capped_maxiob'])} of cycles. If TIR is limited by "
                     f"post-meal highs and lows are already rare, this is the ceiling to raise "
                     f"— but only after the basal/ISF work above."))
    if hypo.get("episodes", 0) and hypo.get("per_day", 0) > 1:
        recs.append(("Hypo frequency", "high",
                     f"{hypo['per_day']:.1f} episodes/day is high; see the time-block breakdown "
                     f"in section 5 for where to aim the basal cut."))

    if not recs:
        return ("## 12. What to change\n\nNothing crossed the thresholds this tool checks. "
                "At that point the remaining gains are in meal timing and consistency rather "
                "than in settings.")

    order = {"high": 0, "medium": 1, "low": 2}
    recs.sort(key=lambda r: order[r[1]])
    rows = [[i + 1, r[0], r[1], r[2]] for i, r in enumerate(recs)]
    return "\n\n".join([
        "## 12. What to change, in order",
        table(["#", "Area", "Priority", "Change"], rows),
        "_Change one thing at a time and give it 3-4 days before judging it. Basal first, then "
        "ISF, then carb ratio — tuning a ratio on top of a wrong basal just moves the error._",
    ])


# ----------------------------------------------------------------------------- main

def build_report(data_dir: str, tz_name: str, units: str) -> tuple[str, dict]:
    tz = ZoneInfo(tz_name)
    u = Units(units)
    entries = load_entries(data_dir, tz)
    if entries.empty:
        raise SystemExit(f"No CGM entries found in {data_dir}/entries.json")
    treatments = load_treatments(data_dir, tz)
    ds = harmonise_units(load_devicestatus(data_dir, tz), entries)
    profile = load_profile(data_dir)
    basal_tl = (build_basal_timeline(treatments, profile, entries.ts.min(), entries.ts.max(), tz)
                if profile.get("basal") else pd.DataFrame())

    summary: dict = {"timezone": tz_name, "units": u.label, "profile": profile.get("name")}
    parts = [
        f"# Nightscout / AndroidAPS analysis\n\n_Generated from {data_dir}, times in {tz_name}, "
        f"glucose in {u.label}._",
        section_coverage(entries, ds, treatments, u, summary),
        section_glycemia(entries, u, summary),
        section_time_of_day(entries, u, summary),
        section_day_of_week(entries, u),
        section_hypos(entries, treatments, ds, u, summary),
        section_loop_behaviour(ds, entries, u, summary),
        section_insulin(treatments, ds, profile, basal_tl, u, summary,
                        max(entries.date_local.nunique(), 1)),
        section_sensitivity(ds, profile, u, summary),
        section_deviations(ds, u, summary),
        section_prediction(ds, entries, u, summary),
        section_meals(treatments, entries, ds, profile, u, summary),
        section_recommendations(summary, profile, u),
        "---\n\n_This is a descriptive analysis of recorded data, not medical advice. "
        "Discuss settings changes with your diabetes team before making them._",
    ]
    return "\n\n".join(parts) + "\n", summary


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data")
    ap.add_argument("--tz", default=os.environ.get("TZ") or "Pacific/Auckland")
    ap.add_argument("--units", choices=["mmol", "mgdl"], default="mmol")
    ap.add_argument("--report", default="report.md")
    ap.add_argument("--json", dest="json_out", default="summary.json")
    args = ap.parse_args()

    report, summary = build_report(args.data, args.tz, args.units)
    with open(args.report, "w") as fh:
        fh.write(report)
    with open(args.json_out, "w") as fh:
        json.dump(summary, fh, indent=2, default=str)
    print(f"wrote {args.report} and {args.json_out}", file=sys.stderr)
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
