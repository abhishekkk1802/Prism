#!/usr/bin/env python3
"""
Prism Gateway — Python load test runner.

Fires TOTAL_REQUESTS across CONCURRENCY parallel workers against a running
gateway and reports latency percentiles, throughput, HTTP status
distribution, and cache hit/miss counts (from the x-prism-cache response
header).

This is a Python counterpart to scripts/load-test.sh — same configuration
knobs and the same real-provider safety guard, reusing the exact approach
(env-var config, cache header sniffing, nearest-rank percentiles) rather than
inventing a different reporting shape. Prefer this version when you want
JSON output (--json) for feeding into other tooling, or when bash/awk isn't
available in the target environment.

Stdlib only (urllib + concurrent.futures) — no dependencies to install.

Usage:
    API_KEY=prism_test_key CONCURRENCY=20 TOTAL_REQUESTS=100 \\
        python3 scripts/load_test.py

    # machine-readable summary
    python3 scripts/load_test.py --json

Environment variables (all optional except API_KEY):
    BASE_URL          default http://localhost:8080
    API_KEY           Bearer token (REQUIRED; never printed)
    CONCURRENCY       parallel workers        default 20
    TOTAL_REQUESTS    total requests to send  default 100
    MODEL             model/alias             default fast
    PROMPT            user prompt             default "Explain Redis in one sentence."
    TIMEOUT           per-request seconds     default 30

SAFETY: by default this targets a gateway backed by mock providers. If your
gateway routes to a REAL paid provider, you must explicitly opt in:
    PRISM_REAL_PROVIDER_TEST=true
Otherwise the script refuses to run more than 200 requests, to avoid
generating real LLM bills by accident.
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass


@dataclass
class RequestOutcome:
    status: int
    latency_ms: float
    cache: str  # "hit" | "miss" | "-"


def percentile(sorted_values: list[float], pct: float) -> float:
    if not sorted_values:
        return 0.0
    idx = max(0, min(len(sorted_values) - 1, int((pct / 100.0) * len(sorted_values)) - 1))
    return sorted_values[idx]


def send_one(base_url: str, api_key: str, model: str, prompt: str, timeout: float) -> RequestOutcome:
    url = f"{base_url}/v1/chat/completions"
    body = json.dumps({
        "model": model,
        "stream": False,
        "messages": [{"role": "user", "content": prompt}],
    }).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            latency_ms = (time.monotonic() - start) * 1000
            cache = resp.headers.get("x-prism-cache", "-")
            return RequestOutcome(resp.status, latency_ms, cache)
    except urllib.error.HTTPError as e:
        e.read()
        latency_ms = (time.monotonic() - start) * 1000
        cache = e.headers.get("x-prism-cache", "-") if e.headers else "-"
        return RequestOutcome(e.code, latency_ms, cache)
    except urllib.error.URLError:
        latency_ms = (time.monotonic() - start) * 1000
        return RequestOutcome(0, latency_ms, "-")


def run_load_test(
    base_url: str,
    api_key: str,
    concurrency: int,
    total_requests: int,
    model: str,
    prompt: str,
    timeout: float,
) -> dict:
    outcomes: list[RequestOutcome] = []
    start = time.monotonic()

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [
            pool.submit(send_one, base_url, api_key, model, prompt, timeout)
            for _ in range(total_requests)
        ]
        for future in as_completed(futures):
            outcomes.append(future.result())

    duration = time.monotonic() - start

    latencies = sorted(o.latency_ms for o in outcomes)
    status_counts: dict[str, int] = {}
    ok = rejected = failed = hits = misses = 0

    for o in outcomes:
        key = str(o.status) if o.status else "000"
        status_counts[key] = status_counts.get(key, 0) + 1
        if 200 <= o.status < 300:
            ok += 1
        elif o.status == 429:
            rejected += 1
        else:
            failed += 1
        if o.cache == "hit":
            hits += 1
        elif o.cache == "miss":
            misses += 1

    n = len(outcomes)
    return {
        "endpoint": f"{base_url}/v1/chat/completions",
        "model": model,
        "concurrency": concurrency,
        "total_requests": total_requests,
        "completed": n,
        "successful_2xx": ok,
        "rejected_429": rejected,
        "failed_other": failed,
        "duration_s": round(duration, 3),
        "requests_per_sec": round(n / duration, 2) if duration > 0 else 0.0,
        "avg_latency_ms": round(sum(latencies) / n, 1) if n else 0.0,
        "min_latency_ms": round(latencies[0], 1) if n else 0.0,
        "max_latency_ms": round(latencies[-1], 1) if n else 0.0,
        "p50_latency_ms": round(percentile(latencies, 50), 1),
        "p95_latency_ms": round(percentile(latencies, 95), 1),
        "p99_latency_ms": round(percentile(latencies, 99), 1),
        "cache_hits": hits,
        "cache_misses": misses,
        "status_distribution": dict(sorted(status_counts.items())),
    }


def print_human(summary: dict) -> None:
    print("PRISM load test (python)")
    print(f"  endpoint     : {summary['endpoint']}")
    print(f"  model        : {summary['model']}")
    print(f"  concurrency  : {summary['concurrency']}")
    print(f"  total        : {summary['total_requests']}")
    print("  (API key is not printed)")
    print()
    print(f"Total requests   : {summary['total_requests']}")
    print(f"Completed        : {summary['completed']}")
    print(f"Successful (2xx) : {summary['successful_2xx']}")
    print(f"Rejected (429)   : {summary['rejected_429']}")
    print(f"Failed (other)   : {summary['failed_other']}")
    print(f"Duration (s)     : {summary['duration_s']}")
    print(f"Requests/sec     : {summary['requests_per_sec']}")
    print(f"Avg latency (ms) : {summary['avg_latency_ms']}")
    print(f"Min latency (ms) : {summary['min_latency_ms']}")
    print(f"Max latency (ms) : {summary['max_latency_ms']}")
    print(f"p50 (ms)         : {summary['p50_latency_ms']}")
    print(f"p95 (ms)         : {summary['p95_latency_ms']}")
    print(f"p99 (ms)         : {summary['p99_latency_ms']}")
    print(f"Cache hits       : {summary['cache_hits']}")
    print(f"Cache misses     : {summary['cache_misses']}")
    print("HTTP status distribution:")
    for status, count in summary["status_distribution"].items():
        print(f"  {status} : {count}")


def main() -> int:
    base_url = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
    api_key = os.environ.get("API_KEY", "")
    concurrency = int(os.environ.get("CONCURRENCY", "20"))
    total_requests = int(os.environ.get("TOTAL_REQUESTS", "100"))
    model = os.environ.get("MODEL", "fast")
    prompt = os.environ.get("PROMPT", "Explain Redis in one sentence.")
    timeout = float(os.environ.get("TIMEOUT", "30"))
    as_json = "--json" in sys.argv[1:]

    if not api_key:
        print("ERROR: API_KEY env var is required (not printed).", file=sys.stderr)
        return 1

    real_provider_test = os.environ.get("PRISM_REAL_PROVIDER_TEST", "false").lower() == "true"
    if not real_provider_test and total_requests > 200:
        print(
            f"ERROR: TOTAL_REQUESTS={total_requests} is large and "
            "PRISM_REAL_PROVIDER_TEST != true.",
            file=sys.stderr,
        )
        print(
            "       Set PRISM_REAL_PROVIDER_TEST=true to confirm you are NOT "
            "hitting a paid provider.",
            file=sys.stderr,
        )
        return 1

    summary = run_load_test(base_url, api_key, concurrency, total_requests, model, prompt, timeout)

    if as_json:
        print(json.dumps(summary, indent=2))
    else:
        print_human(summary)

    return 0


if __name__ == "__main__":
    sys.exit(main())
