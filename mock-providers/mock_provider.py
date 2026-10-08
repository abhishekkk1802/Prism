#!/usr/bin/env python3
"""
Mock OpenAI-compatible provider for the Prism gateway (plan section 1:
"two supplied mock providers", Alpha and Beta).

Implements just enough of the OpenAI chat-completions contract for Prism's
OpenAIProvider (see src/main/java/.../service/OpenAIProvider.java) to work
against it:

  POST /v1/chat/completions
    - non-streaming: returns a single JSON ChatCompletion object with
      choices[0].message.content and usage.prompt_tokens/completion_tokens
    - streaming (stream: true): returns text/event-stream of
      "data: {...}\n\n" chunk objects, terminated by "data: [DONE]\n\n"

Responses are deterministic (derived from the request) so tests and demos are
repeatable. Failure injection is supported via environment variables so the
retry/fallback demo (Alpha down -> Beta backup) can be driven without
touching the gateway:

  MOCK_PROVIDER_NAME     label embedded in responses, default "mock"
  MOCK_ALWAYS_FAIL        "true" -> every request returns HTTP 503
  MOCK_FAIL_RATE          float 0.0-1.0, fraction of requests that fail (default 0)
  MOCK_LATENCY_MS         artificial per-request delay in milliseconds (default 0)

No third-party dependencies: standard library only, so it runs anywhere
Python 3 runs (and in the slim Docker image used by docker-compose.yml).

Usage:
    python3 mock_provider.py --port 9001 --name alpha
"""

import argparse
import json
import os
import random
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def env_flag(name, default=False):
    val = os.environ.get(name)
    if val is None:
        return default
    return val.strip().lower() in ("1", "true", "yes", "on")


def env_float(name, default=0.0):
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def count_tokens(text):
    """Deterministic, approximate token count: whitespace-separated words."""
    return max(1, len(text.split()))


def build_reply(provider_name, model, messages):
    last_user = ""
    for m in reversed(messages):
        if m.get("role") == "user":
            last_user = m.get("content", "")
            break
    return f"[{provider_name}:{model}] Mock response to: {last_user[:120]}"


class Handler(BaseHTTPRequestHandler):
    provider_name = "mock"

    def log_message(self, fmt, *args):
        # Keep container/dev logs readable.
        print(f"[{self.provider_name}] {self.address_string()} - {fmt % args}")

    def _should_fail(self):
        if env_flag("MOCK_ALWAYS_FAIL", False):
            return True
        rate = env_float("MOCK_FAIL_RATE", 0.0)
        return rate > 0 and random.random() < rate

    def _delay(self):
        latency_ms = env_float("MOCK_LATENCY_MS", 0.0)
        if latency_ms > 0:
            time.sleep(latency_ms / 1000.0)

    def do_POST(self):
        if self.path.rstrip("/") != "/v1/chat/completions":
            self._send_json(404, {"error": {"message": "not found", "type": "not_found_error"}})
            return

        length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(length) if length > 0 else b"{}"

        try:
            payload = json.loads(raw_body.decode("utf-8"))
        except json.JSONDecodeError:
            self._send_json(400, {"error": {"message": "invalid JSON", "type": "invalid_request_error"}})
            return

        self._delay()

        if self._should_fail():
            self._send_json(503, {"error": {"message": "mock provider unavailable", "type": "server_error"}})
            return

        model = payload.get("model", "mock-model")
        messages = payload.get("messages", [])
        stream = bool(payload.get("stream", False))

        if stream:
            self._handle_streaming(model, messages)
        else:
            self._handle_non_streaming(model, messages)

    def _handle_non_streaming(self, model, messages):
        reply_text = build_reply(self.provider_name, model, messages)
        prompt_tokens = sum(count_tokens(m.get("content", "")) for m in messages)
        completion_tokens = count_tokens(reply_text)

        body = {
            "id": f"chatcmpl-mock-{uuid.uuid4().hex[:12]}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": reply_text},
                "finish_reason": "stop",
            }],
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            },
        }
        self._send_json(200, body)

    def _handle_streaming(self, model, messages):
        reply_text = build_reply(self.provider_name, model, messages)
        words = reply_text.split(" ")
        prompt_tokens = sum(count_tokens(m.get("content", "")) for m in messages)
        completion_id = f"chatcmpl-mock-{uuid.uuid4().hex[:12]}"
        created = int(time.time())

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        for i, word in enumerate(words):
            chunk = {
                "id": completion_id,
                "object": "chat.completion.chunk",
                "created": created,
                "model": model,
                "choices": [{
                    "index": 0,
                    "delta": {"content": word + (" " if i < len(words) - 1 else "")},
                    "finish_reason": None,
                }],
            }
            self._write_sse(chunk)

        final_chunk = {
            "id": completion_id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": model,
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": count_tokens(reply_text),
                "total_tokens": prompt_tokens + count_tokens(reply_text),
            },
        }
        self._write_sse(final_chunk)

        try:
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _write_sse(self, obj):
        try:
            self.wfile.write(f"data: {json.dumps(obj)}\n\n".encode("utf-8"))
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _send_json(self, status, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path.rstrip("/") in ("/health", "/"):
            self._send_json(200, {"status": "UP", "provider": self.provider_name})
            return
        self._send_json(404, {"error": {"message": "not found", "type": "not_found_error"}})


def main():
    parser = argparse.ArgumentParser(description="Mock OpenAI-compatible provider for Prism")
    parser.add_argument("--port", type=int, default=9001)
    parser.add_argument("--name", type=str, default=os.environ.get("MOCK_PROVIDER_NAME", "mock"))
    args = parser.parse_args()

    Handler.provider_name = args.name

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"Mock provider '{args.name}' listening on 0.0.0.0:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()


if __name__ == "__main__":
    main()
