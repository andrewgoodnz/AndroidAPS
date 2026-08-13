# Nightscout / AAPS data analysis

Offline analysis of your own Nightscout data: glycemic patterns, loop behaviour,
and the signals that tell you which setting to change next.

Nothing is uploaded anywhere. `ns_fetch.py` pulls your data down to local JSON,
`ns_analyze.py` reads those files and writes a Markdown report. Both are plain
Python; the only dependencies are `pandas` and `numpy`.

## Use

```bash
pip install pandas numpy

export NS_URL=https://your-site.example.com
export NS_TOKEN=...            # Admin Tools -> Subjects, a 'readable' role token
#   or: export NS_API_SECRET=...   (the plain secret; sent as its sha1 hash)

python3 ns_fetch.py --days 30 --out data/
python3 ns_analyze.py --data data/ --tz Pacific/Auckland --units mmol \
    --report report.md --json summary.json
```

`--days 30` is a good default: long enough for hourly patterns to have ~30
samples per hour, short enough that your settings and your body have not moved
underneath the data. Use `--no-devicestatus` for a much smaller download if you
only want the CGM-side analysis (sections 1-5, 11).

`summary.json` holds the same numbers in machine-readable form, for diffing two
periods against each other after a settings change.

## What the report covers

| Section | Question it answers |
| --- | --- |
| 1-2 | How much data is there, and what are the standard metrics (TIR, GMI, CV)? |
| 3-4 | Which hours and which days are the problem? |
| 5 | When do hypos happen, and was there a bolus or carbs behind them? |
| 6 | Is the loop actually running, and what is constraining it (maxIOB, caps, zero temps)? |
| 7 | Where does the insulin go — basal vs SMB vs manual — and does the loop agree with the profile basal? |
| 8 | Is autosens permanently correcting the same offset? Is dynamic ISF doing what you think? |
| 9 | The `Dev` residual by hour: the cleanest read on which hours are over- or under-insulinised. |
| 10 | Does `eventualBG` actually predict BG 60 minutes later, and is it biased? |
| 11 | Do meals land back at baseline at 4 h, and how late do they peak? |
| 12 | The ranked list of what to change. |

## How to read it

Two independent signals point at basal:

- **Section 9 (`Dev` by hour)** — the residual between the BG change the loop
  predicted from insulin alone and what happened, restricted to zero-carb
  cycles. Persistently negative in an hour means BG falls faster than insulin
  explains: too much basal. Persistently positive: too little.
- **Section 7 (delivered vs profile basal)** — if the loop runs 1.4x profile
  basal at 06:00 every single day, that is not the loop reacting to something,
  it is the loop correcting a profile you could just fix.

When those two agree on an hour, the finding is solid. Fix basal before ISF, and
ISF before carb ratio — section 11's "net at 4 h" absorbs whatever the basal was
doing across the meal window, so tuning a ratio on top of a wrong basal only
moves the error somewhere else.

Change one thing, then wait 3-4 days before judging it.

## Caveats

- Percentages are of *recorded* time. Section 1 reports CGM coverage; if it is
  low, every distribution is biased toward the hours the sensor was working.
- Profile switches and percentage/timeshift profiles are not reconstructed. The
  basal comparison uses the profile currently stored in Nightscout, so a period
  spanning a profile change will read as disagreement that is not real.
- Temp targets (exercise, eating-soon) move autosens and the loop's behaviour;
  they are not separated out here.
- `Dev` is only as good as the carb entries. Uncounted carbs land in `Dev` and
  look like insulin resistance in that hour.

## Validating a change to these scripts

`make_synthetic.py` writes a fake Nightscout dataset with deliberately planted
faults — an over-strong basal 01:00-05:00, an under-strong basal 06:00-09:00, a
weak dinner carb ratio, and a 6-hour loop outage:

```bash
python3 make_synthetic.py synth/
python3 ns_analyze.py --data synth/ --report /dev/null --json /dev/null
```

A correct analyser recovers those blocks in sections 7, 9 and 12. It also
exercises the unit handling: AAPS renders the `reason` string in *display*
units, so `Dev`/`BGI`/`ISF` may arrive in mmol/L, and the analyser has to detect
that from `Target:` rather than from `Dev` itself (whose values sit near zero in
either unit).

## Not medical advice

This is a descriptive summary of data you already have. Discuss settings changes
with your diabetes team.
