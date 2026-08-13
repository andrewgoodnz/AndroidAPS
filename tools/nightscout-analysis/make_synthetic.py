#!/usr/bin/env python3
"""Generate a synthetic Nightscout dataset with known, deliberately planted
problems, so the analyser can be validated before it ever sees real data.

Planted faults:
  * over-strong basal 01:00-05:00  -> negative Dev + overnight lows
  * under-strong basal 06:00-09:00 -> dawn rise + positive Dev
  * weak dinner carb ratio         -> meals land high at 4 h
  * a 6-hour loop outage
"""
from __future__ import annotations

import json
import os
import random
import sys
from datetime import datetime, timedelta, timezone

random.seed(7)
OUT = sys.argv[1] if len(sys.argv) > 1 else "synth"
DAYS = 21
NZ = timezone(timedelta(hours=12))  # fixed offset stand-in for Pacific/Auckland


def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def target_bg(local_hour, minute):
    """Underlying BG shape in mg/dL with the planted faults baked in."""
    t = local_hour + minute / 60
    bg = 130.0
    if 1 <= t < 5:                      # over-basal overnight
        bg -= 45 * (1 - abs(t - 3) / 2)
    if 5.5 <= t < 10:                   # dawn: under-basal
        bg += 55 * (1 - abs(t - 7.75) / 2.25)
    if 7 <= t < 10:                     # breakfast excursion
        bg += 40 * (1 - abs(t - 8.5) / 1.5)
    if 12 <= t < 16:                    # lunch, reasonably matched
        bg += 45 * (1 - abs(t - 13.5) / 2)
    if 18 <= t < 24:                    # dinner, weak ratio -> stays high
        bg += 70 * (1 - abs(t - 20.5) / 3.5)
    return max(45.0, bg)


end = datetime.now(timezone.utc).replace(second=0, microsecond=0)
start = end - timedelta(days=DAYS)

entries, treatments, devicestatus = [], [], []
outage_start = start + timedelta(days=9, hours=4)
outage_end = outage_start + timedelta(hours=6)

t = start
prev_bg = 130.0
while t < end:
    local = t.astimezone(NZ)
    base = target_bg(local.hour, local.minute)
    noise = random.gauss(0, 7) + (prev_bg - base) * 0.55  # autocorrelated wobble
    bg = max(40, min(400, base + noise))
    prev_bg = bg

    entries.append({
        "type": "sgv", "sgv": round(bg), "date": int(t.timestamp() * 1000),
        "dateString": iso(t), "device": "xdrip",
        "direction": "Flat",
    })

    in_outage = outage_start <= t < outage_end
    if not in_outage:
        # Loop cycle. Dev carries the planted sensitivity error.
        hour = local.hour + local.minute / 60
        dev = 0.0
        if 1 <= hour < 5:
            dev = random.gauss(-9, 3)
        elif 5.5 <= hour < 10:
            dev = random.gauss(9, 3)
        else:
            dev = random.gauss(0.5, 4)
        cob = 30 if (7.5 <= hour < 10 or 12.5 <= hour < 15 or 19 <= hour < 21.5) else 0
        iob = round(max(0.0, random.gauss(1.2 if cob else 0.5, 0.5)), 2)
        sens_ratio = round(min(1.4, max(0.7, 1.0 + dev / 60)), 2)
        profile_basal = 0.85
        rate = round(max(0.0, profile_basal * (1 + dev / 25)), 2)
        eventual = round(bg + dev * 6 + random.gauss(0, 12))
        smb = round(max(0.0, random.gauss(0.25, 0.2)), 2) if (dev > 4 and bg > 150) else 0.0
        reason = (f"COB: {cob}, Dev: {dev:.1f}, BGI: {-iob * 2:.2f}, ISF: 45, CR: 9, "
                  f"Target: 100, minPredBG {max(50, bg - 30):.0f}; "
                  f"Eventual BG {eventual} >= 100, insulinReq {max(0, dev / 20):.2f}; "
                  f"setting {rate}U/hr")
        if iob > 4.5:
            reason += ", maxIOB reached"
        suggested = {
            "temp": "absolute", "bg": round(bg), "tick": "+2",
            "eventualBG": eventual, "targetBG": 100, "insulinReq": round(max(0, dev / 20), 2),
            "sensitivityRatio": sens_ratio, "COB": cob, "IOB": iob,
            "reason": reason, "rate": rate, "duration": 30,
            "timestamp": iso(t),
        }
        if smb:
            suggested["units"] = smb
        # dynamic ISF, mg/dL per U, tracking the planted sensitivity swing
        suggested["variable_sens"] = round(45 / sens_ratio, 1)
        devicestatus.append({
            "created_at": iso(t), "device": "openaps://AndroidAPS",
            "openaps": {"suggested": suggested, "enacted": {**suggested, "recieved": True}},
            "pump": {"battery": {"percent": 70}, "reservoir": 120.0},
        })
        treatments.append({
            "eventType": "Temp Basal", "created_at": iso(t), "duration": 30,
            "absolute": rate, "rate": rate, "isAbsolute": True, "enteredBy": "AndroidAPS",
        })
        if smb:
            treatments.append({
                "eventType": "Correction Bolus", "created_at": iso(t), "insulin": smb,
                "isSMB": True, "type": "SMB", "enteredBy": "AndroidAPS",
            })
    t += timedelta(minutes=5)

# Meals: breakfast/lunch/dinner, with dinner deliberately under-bolused.
day = start
while day < end:
    local_midnight = day.astimezone(NZ).replace(hour=0, minute=0, second=0, microsecond=0)
    for hour, carbs, cr in ((7.5, 45, 9.0), (12.5, 60, 9.0), (19.0, 75, 14.0)):
        when = local_midnight + timedelta(hours=hour + random.uniform(-0.4, 0.4))
        if not (start <= when.astimezone(timezone.utc) < end):
            continue
        treatments.append({
            "eventType": "Meal Bolus", "created_at": iso(when),
            "carbs": carbs, "insulin": round(carbs / cr, 2),
            "isSMB": False, "type": "NORMAL", "enteredBy": "andrew",
        })
    day += timedelta(days=1)

profile = [{
    "defaultProfile": "AAPS",
    "startDate": iso(start),
    "store": {"AAPS": {
        "dia": 6,
        "units": "mmol",
        "timezone": "Pacific/Auckland",
        "basal": [{"time": "00:00", "timeAsSeconds": 0, "value": 0.85},
                  {"time": "01:00", "timeAsSeconds": 3600, "value": 0.95},
                  {"time": "05:00", "timeAsSeconds": 18000, "value": 0.80},
                  {"time": "10:00", "timeAsSeconds": 36000, "value": 0.85}],
        "sens": [{"time": "00:00", "timeAsSeconds": 0, "value": 2.5}],
        "carbratio": [{"time": "00:00", "timeAsSeconds": 0, "value": 9.0},
                      {"time": "17:00", "timeAsSeconds": 61200, "value": 14.0}],
        "target_low": [{"time": "00:00", "timeAsSeconds": 0, "value": 5.0}],
        "target_high": [{"time": "00:00", "timeAsSeconds": 0, "value": 6.5}],
    }},
}]

os.makedirs(OUT, exist_ok=True)
for name, data in (("entries", entries), ("treatments", treatments),
                   ("devicestatus", devicestatus), ("profile", profile),
                   ("status", {"settings": {"units": "mmol"}})):
    with open(os.path.join(OUT, f"{name}.json"), "w") as fh:
        json.dump(data, fh)
    n = len(data) if isinstance(data, list) else 1
    print(f"{name}: {n}")
