#!/usr/bin/env bash
#
# Phase 11 — lightweight concurrency/load runner for the Prism Gateway.
#
# Fires TOTAL_REQUESTS across CONCURRENCY parallel workers against a running
# gateway and reports latency percentiles, throughput, HTTP status distribution
# and cache hit/miss counts (from the x-prism-cache response header).
#
# No frameworks, no build changes — just bash + curl.
#
# Configuration (all via environment variables, with safe defaults):
#   BASE_URL        default http://localhost:8080
#   API_KEY         Bearer token (REQUIRED; never printed)
#   CONCURRENCY     parallel workers        default 20
#   TOTAL_REQUESTS  total requests to send  default 100
#   MODEL           model/alias             default fast
#   PROMPT          user prompt             default "Explain Redis in one sentence."
#   TIMEOUT         per-request seconds     default 30
#   STREAM          true|false              default false
#
# SAFETY: by default this is intended for a gateway backed by mock providers.
# If your gateway routes to a REAL paid provider, you must explicitly opt in:
#   PRISM_REAL_PROVIDER_TEST=true
# Otherwise the script refuses to run to avoid generating real LLM bills.
# The script keeps request counts modest; raise TOTAL_REQUESTS deliberately.
#
# Usage:
#   API_KEY=prism_test_key CONCURRENCY=20 TOTAL_REQUESTS=100 ./scripts/load-test.sh
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-}"
CONCURRENCY="${CONCURRENCY:-20}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-100}"
MODEL="${MODEL:-fast}"
PROMPT="${PROMPT:-Explain Redis in one sentence.}"
TIMEOUT="${TIMEOUT:-30}"
STREAM="${STREAM:-false}"

if [[ -z "$API_KEY" ]]; then
  echo "ERROR: API_KEY env var is required (not printed)." >&2
  exit 1
fi

# Safety guard: avoid accidental real-provider spend.
if [[ "${PRISM_REAL_PROVIDER_TEST:-false}" != "true" ]]; then
  if [[ "$TOTAL_REQUESTS" -gt 200 ]]; then
    echo "ERROR: TOTAL_REQUESTS=$TOTAL_REQUESTS is large and PRISM_REAL_PROVIDER_TEST!=true." >&2
    echo "       Set PRISM_REAL_PROVIDER_TEST=true to confirm you are NOT hitting a paid provider." >&2
    exit 1
  fi
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

ENDPOINT="$BASE_URL/v1/chat/completions"
if [[ "$STREAM" == "true" ]]; then
  ENDPOINT="$BASE_URL/v1/chat/completions/stream"
fi

# JSON body (prompt is escaped minimally; keep prompts simple).
BODY=$(cat <<JSON
{"model":"$MODEL","stream":$STREAM,"messages":[{"role":"user","content":"$PROMPT"}]}
JSON
)

echo "PRISM load test"
echo "  endpoint     : $ENDPOINT"
echo "  model        : $MODEL"
echo "  concurrency  : $CONCURRENCY"
echo "  total        : $TOTAL_REQUESTS"
echo "  stream       : $STREAM"
echo "  (API key is not printed)"
echo

# One request -> prints "HTTP_STATUS LATENCY_MS CACHE" to a per-request file.
do_request() {
  local idx="$1"
  local out="$WORKDIR/r_$idx"
  # -w writes status + total time; dump headers to capture x-prism-cache.
  local hdr="$WORKDIR/h_$idx"
  local metrics
  metrics=$(curl -sS -o /dev/null -D "$hdr" \
      --max-time "$TIMEOUT" \
      -w '%{http_code} %{time_total}' \
      -X POST "$ENDPOINT" \
      -H "Authorization: Bearer $API_KEY" \
      -H "Content-Type: application/json" \
      -d "$BODY" 2>/dev/null) || metrics="000 0"
  local status="${metrics%% *}"
  local tsec="${metrics##* }"
  # ms as integer
  local tms
  tms=$(awk -v t="$tsec" 'BEGIN{printf "%d", t*1000}')
  local cache
  cache=$(grep -i '^x-prism-cache:' "$hdr" 2>/dev/null | tr -d '\r' | awk '{print $2}')
  [[ -z "$cache" ]] && cache="-"
  echo "$status $tms $cache" > "$out"
}
export -f do_request
export WORKDIR ENDPOINT API_KEY BODY TIMEOUT

START=$(date +%s.%N)

# Drive CONCURRENCY workers; xargs -P gives a simple bounded parallel pool.
seq 1 "$TOTAL_REQUESTS" | xargs -P "$CONCURRENCY" -I{} bash -c 'do_request "$@"' _ {}

END=$(date +%s.%N)
DURATION=$(awk -v a="$START" -v b="$END" 'BEGIN{printf "%.3f", b-a}')

# Aggregate.
cat "$WORKDIR"/r_* > "$WORKDIR/all.txt"

awk -v dur="$DURATION" -v total="$TOTAL_REQUESTS" '
{
  status[$1]++
  lat[NR]=$2
  sum+=$2
  if ($1 ~ /^2/) ok++
  else if ($1 == "429") rejected++
  else failed++
  if ($3 == "hit") hits++
  else if ($3 == "miss") misses++
}
END {
  n=NR
  # sort latencies
  for (i=1;i<=n;i++) a[i]=lat[i]
  for (i=1;i<=n;i++) for (j=i+1;j<=n;j++) if (a[j]<a[i]){t=a[i];a[i]=a[j];a[j]=t}
  function pct(p){ if(n==0) return 0; idx=int((p/100.0)*n); if(idx<1)idx=1; if(idx>n)idx=n; return a[idx] }
  printf "Total requests   : %d\n", total
  printf "Completed        : %d\n", n
  printf "Successful (2xx) : %d\n", ok+0
  printf "Rejected (429)   : %d\n", rejected+0
  printf "Failed (other)   : %d\n", failed+0
  printf "Duration (s)     : %s\n", dur
  if (dur+0 > 0) printf "Requests/sec     : %.2f\n", n/dur
  if (n>0) printf "Avg latency (ms) : %.1f\n", sum/n
  printf "Min latency (ms) : %d\n", a[1]+0
  printf "Max latency (ms) : %d\n", a[n]+0
  printf "p50 (ms)         : %d\n", pct(50)
  printf "p95 (ms)         : %d\n", pct(95)
  printf "p99 (ms)         : %d\n", pct(99)
  printf "Cache hits       : %d\n", hits+0
  printf "Cache misses     : %d\n", misses+0
  print  "HTTP status distribution:"
  for (s in status) printf "  %s : %d\n", s, status[s]
}
' "$WORKDIR/all.txt"
