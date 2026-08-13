#!/usr/bin/env python3
"""Pull a Nightscout dataset to local JSON for offline analysis.

Auth (pick one, via environment):
    NS_TOKEN       Nightscout access token  (Admin Tools -> Subjects; role 'readable')
    NS_API_SECRET  the plain API secret; sent as sha1 hex in the api-secret header

Usage:
    export NS_URL=https://andrew.nightscoutpro.com
    export NS_TOKEN=...            # or NS_API_SECRET=...
    python3 ns_fetch.py --days 30 --out data/
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

MS = 1000


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


class Nightscout:
    def __init__(self, base: str, token: str | None, api_secret: str | None, verbose: bool = True):
        self.base = base.rstrip("/")
        self.token = token
        self.secret_hash = hashlib.sha1(api_secret.encode()).hexdigest() if api_secret else None
        self.verbose = verbose

    def get(self, path: str, params: dict | None = None, retries: int = 4):
        params = dict(params or {})
        if self.token:
            params["token"] = self.token
        url = f"{self.base}{path}"
        if params:
            url += "?" + urllib.parse.urlencode(params)
        headers = {"Accept": "application/json", "User-Agent": "aaps-analysis/1.0"}
        if self.secret_hash:
            headers["api-secret"] = self.secret_hash

        delay = 2
        for attempt in range(retries + 1):
            try:
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=120) as resp:
                    return json.loads(resp.read().decode())
            except urllib.error.HTTPError as e:
                # 401/403 are auth problems - retrying will not help.
                if e.code in (401, 403):
                    raise SystemExit(
                        f"HTTP {e.code} on {path}. Check NS_TOKEN / NS_API_SECRET "
                        f"(the token needs at least the 'readable' role)."
                    )
                if attempt == retries:
                    raise
            except (urllib.error.URLError, TimeoutError):
                if attempt == retries:
                    raise
            if self.verbose:
                print(f"  retry in {delay}s ({path})", file=sys.stderr)
            time.sleep(delay)
            delay *= 2

    def entries(self, start: datetime, end: datetime) -> list:
        """CGM readings. Paged backwards on the numeric `date` field (epoch ms)."""
        out, cursor, page = [], int(end.timestamp() * MS), 0
        floor = int(start.timestamp() * MS)
        while cursor > floor:
            batch = self.get(
                "/api/v1/entries.json",
                {"count": 5000, "find[date][$gte]": floor, "find[date][$lte]": cursor},
            )
            if not batch:
                break
            out.extend(batch)
            oldest = min(int(e["date"]) for e in batch if e.get("date"))
            if oldest >= cursor:
                break
            cursor = oldest - 1
            page += 1
            if self.verbose:
                print(f"  entries page {page}: {len(out)} rows", file=sys.stderr)
        return out

    def by_created_at(self, path: str, start: datetime, end: datetime, chunk_days: int = 3) -> list:
        """treatments/devicestatus page on the ISO `created_at` field, chunked by day."""
        out, window_start = [], start
        while window_start < end:
            window_end = min(window_start + timedelta(days=chunk_days), end)
            batch = self.get(
                path,
                {
                    "count": 50000,
                    "find[created_at][$gte]": iso(window_start),
                    "find[created_at][$lt]": iso(window_end),
                },
            )
            out.extend(batch or [])
            if self.verbose:
                print(f"  {path} {window_start:%Y-%m-%d}: +{len(batch or [])} ({len(out)} total)", file=sys.stderr)
            window_start = window_end
        return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=os.environ.get("NS_URL"))
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--out", default="data")
    ap.add_argument("--no-devicestatus", action="store_true",
                    help="skip devicestatus (much smaller download, but no loop analysis)")
    args = ap.parse_args()

    if not args.url:
        raise SystemExit("Set --url or NS_URL (e.g. https://andrew.nightscoutpro.com)")

    token, secret = os.environ.get("NS_TOKEN"), os.environ.get("NS_API_SECRET")
    if not token and not secret:
        print("warning: no NS_TOKEN / NS_API_SECRET set; only public data will be returned",
              file=sys.stderr)

    ns = Nightscout(args.url, token, secret)
    end = datetime.now(timezone.utc)
    start = end - timedelta(days=args.days)
    os.makedirs(args.out, exist_ok=True)

    print(f"Nightscout {args.url}: {start:%Y-%m-%d} .. {end:%Y-%m-%d}", file=sys.stderr)

    datasets = {
        "status": lambda: ns.get("/api/v1/status.json"),
        "profile": lambda: ns.get("/api/v1/profile.json"),
        "entries": lambda: ns.entries(start, end),
        "treatments": lambda: ns.by_created_at("/api/v1/treatments.json", start, end),
    }
    if not args.no_devicestatus:
        datasets["devicestatus"] = lambda: ns.by_created_at(
            "/api/v1/devicestatus.json", start, end, chunk_days=1
        )

    for name, fn in datasets.items():
        print(f"fetching {name}...", file=sys.stderr)
        data = fn()
        path = os.path.join(args.out, f"{name}.json")
        with open(path, "w") as fh:
            json.dump(data, fh)
        n = len(data) if isinstance(data, list) else 1
        print(f"  -> {path} ({n} records, {os.path.getsize(path) / 1e6:.1f} MB)", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
