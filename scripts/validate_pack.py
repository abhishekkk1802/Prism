#!/usr/bin/env python3
"""
Prism Gateway — gateway-config.json ("model pack") validator.

Validates a gateway-config.json file against the schema and referential
integrity rules actually enforced by the Java config loader/routing classes,
WITHOUT starting the JVM:

  - com.prism.gateway.config.GatewayConfigLoader   (shape: providers / model_aliases / retry)
  - com.prism.gateway.config.model.{ProviderConfig,ModelAliasConfig,RetryConfig}
  - com.prism.gateway.routing.ProviderRegistry      (provider name uniqueness)
  - com.prism.gateway.routing.ProviderResolver      (model name must contain '-',
                                                      prefix before first '-' is the provider)
  - com.prism.gateway.routing.ModelResolver         (model_aliases lookup)

This lets a config pack be checked in CI / before a deploy, catching the exact
same mistakes that would otherwise only surface as a 500 at request time (e.g.
a fallback model pointing at a provider name that doesn't exist, or an "auto"
alias referencing a difficulty bucket that isn't itself a valid alias).

Stdlib only — no dependencies to install.

Usage:
    python3 scripts/validate_pack.py [path/to/gateway-config.json]

    # defaults to src/main/resources/gateway-config.json relative to the repo root
    python3 scripts/validate_pack.py

Exit codes:
    0  pack is valid (warnings may still be printed)
    1  pack has one or more errors
    2  file not found / not valid JSON
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


class Finding:
    def __init__(self, level: str, message: str):
        self.level = level  # "ERROR" or "WARN"
        self.message = message

    def __str__(self) -> str:
        return f"[{self.level}] {self.message}"


def default_pack_path() -> Path:
    repo_root = Path(__file__).resolve().parent.parent
    return repo_root / "src" / "main" / "resources" / "gateway-config.json"


def load_pack(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def validate_provider_config(providers_raw: Any, findings: list[Finding]) -> dict[str, dict]:
    """Mirrors ProviderConfig(name, base_url, api_key) + ProviderRegistry uniqueness."""
    providers: dict[str, dict] = {}

    if not isinstance(providers_raw, list):
        findings.append(Finding("ERROR", "'providers' must be a JSON array"))
        return providers

    if len(providers_raw) == 0:
        findings.append(Finding("ERROR", "'providers' must not be empty"))

    for idx, entry in enumerate(providers_raw):
        label = f"providers[{idx}]"
        if not isinstance(entry, dict):
            findings.append(Finding("ERROR", f"{label} must be an object"))
            continue

        for field in ("name", "base_url", "api_key"):
            value = entry.get(field)
            if not isinstance(value, str) or not value.strip():
                findings.append(Finding("ERROR", f"{label}.{field} must be a non-empty string"))

        name = entry.get("name")
        if isinstance(name, str) and name.strip():
            if name in providers:
                # Mirrors ProviderRegistry's toUnmodifiableMap(ProviderConfig::name, ...):
                # a duplicate key would make Collectors.toMap throw at startup.
                findings.append(Finding("ERROR", f"duplicate provider name '{name}' (ProviderRegistry requires unique names)"))
            else:
                providers[name] = entry

        base_url = entry.get("base_url")
        if isinstance(base_url, str) and base_url and not (base_url.startswith("http://") or base_url.startswith("https://")):
            findings.append(Finding("WARN", f"{label}.base_url '{base_url}' does not start with http:// or https://"))

    return providers


def validate_retry_config(retry_raw: Any, findings: list[Finding]) -> None:
    """Mirrors RetryConfig(max_attempts, initial_backoff_ms, backoff_multiplier)."""
    if retry_raw is None:
        findings.append(Finding("ERROR", "'retry' section is missing"))
        return
    if not isinstance(retry_raw, dict):
        findings.append(Finding("ERROR", "'retry' must be an object"))
        return

    max_attempts = retry_raw.get("max_attempts")
    if not isinstance(max_attempts, int) or isinstance(max_attempts, bool) or max_attempts < 1:
        findings.append(Finding("ERROR", "'retry.max_attempts' must be an integer >= 1"))

    initial_backoff_ms = retry_raw.get("initial_backoff_ms")
    if not isinstance(initial_backoff_ms, (int, float)) or isinstance(initial_backoff_ms, bool) or initial_backoff_ms < 0:
        findings.append(Finding("ERROR", "'retry.initial_backoff_ms' must be a number >= 0"))

    backoff_multiplier = retry_raw.get("backoff_multiplier")
    if not isinstance(backoff_multiplier, (int, float)) or isinstance(backoff_multiplier, bool) or backoff_multiplier < 1:
        findings.append(Finding("ERROR", "'retry.backoff_multiplier' must be a number >= 1"))


def resolve_provider_name(model: str) -> str | None:
    """Mirrors ProviderResolver.resolveProvider: prefix before the first '-'."""
    separator = model.find("-")
    if separator <= 0:
        return None
    return model[:separator]


def validate_model_name_resolves(model: str, label: str, provider_names: set[str], findings: list[Finding]) -> None:
    provider = resolve_provider_name(model)
    if provider is None:
        findings.append(Finding(
            "ERROR",
            f"{label} = '{model}' is not a valid model name "
            "(ProviderResolver requires a '-' separator, e.g. 'alpha-small')"
        ))
        return
    if provider not in provider_names:
        findings.append(Finding(
            "ERROR",
            f"{label} = '{model}' resolves to unknown provider '{provider}' "
            f"(declared providers: {sorted(provider_names) or '[]'})"
        ))


def validate_model_aliases(aliases_raw: Any, provider_names: set[str], findings: list[Finding]) -> dict[str, dict]:
    """Mirrors ModelAliasConfig(primary, fallbacks, route_by_difficulty) + ModelResolver lookups."""
    aliases: dict[str, dict] = {}

    if not isinstance(aliases_raw, dict):
        findings.append(Finding("ERROR", "'model_aliases' must be an object"))
        return aliases

    if len(aliases_raw) == 0:
        findings.append(Finding("ERROR", "'model_aliases' must not be empty"))

    for alias_name, alias_cfg in aliases_raw.items():
        if not isinstance(alias_cfg, dict):
            findings.append(Finding("ERROR", f"model_aliases.{alias_name} must be an object"))
            continue
        aliases[alias_name] = alias_cfg

        primary = alias_cfg.get("primary")
        fallbacks = alias_cfg.get("fallbacks")
        route_by_difficulty = alias_cfg.get("route_by_difficulty")

        has_direct_route = primary is not None
        has_difficulty_route = route_by_difficulty is not None

        if not has_direct_route and not has_difficulty_route:
            findings.append(Finding(
                "ERROR",
                f"model_aliases.{alias_name} must define either 'primary' "
                "(direct routing) or 'route_by_difficulty' (auto routing)"
            ))
            continue

        if has_direct_route:
            if not isinstance(primary, str) or not primary.strip():
                findings.append(Finding("ERROR", f"model_aliases.{alias_name}.primary must be a non-empty string"))
            else:
                validate_model_name_resolves(primary, f"model_aliases.{alias_name}.primary", provider_names, findings)

            if fallbacks is not None:
                if not isinstance(fallbacks, list):
                    findings.append(Finding("ERROR", f"model_aliases.{alias_name}.fallbacks must be an array"))
                else:
                    for i, fb in enumerate(fallbacks):
                        if not isinstance(fb, str) or not fb.strip():
                            findings.append(Finding("ERROR", f"model_aliases.{alias_name}.fallbacks[{i}] must be a non-empty string"))
                        else:
                            validate_model_name_resolves(fb, f"model_aliases.{alias_name}.fallbacks[{i}]", provider_names, findings)
            else:
                findings.append(Finding("WARN", f"model_aliases.{alias_name} has no 'fallbacks' — a primary failure has nowhere to fail over to"))

        if has_difficulty_route:
            if not isinstance(route_by_difficulty, dict) or not route_by_difficulty:
                findings.append(Finding("ERROR", f"model_aliases.{alias_name}.route_by_difficulty must be a non-empty object"))
            else:
                for difficulty, target_alias in route_by_difficulty.items():
                    if not isinstance(target_alias, str) or not target_alias.strip():
                        findings.append(Finding("ERROR", f"model_aliases.{alias_name}.route_by_difficulty.{difficulty} must be a non-empty string"))
                        continue
                    # ModelResolver.resolve() is called again on the routed-to alias
                    # at request time (ChatCompletionService), so it must itself exist
                    # and must NOT be another route_by_difficulty alias (no chained auto).
                    if target_alias == alias_name:
                        findings.append(Finding("ERROR", f"model_aliases.{alias_name}.route_by_difficulty.{difficulty} routes to itself ('{alias_name}')"))
                    elif target_alias not in aliases_raw:
                        findings.append(Finding("ERROR", f"model_aliases.{alias_name}.route_by_difficulty.{difficulty} = '{target_alias}' is not a declared model_aliases entry"))
                    else:
                        referenced = aliases_raw.get(target_alias, {})
                        if isinstance(referenced, dict) and referenced.get("route_by_difficulty") is not None:
                            findings.append(Finding("ERROR", f"model_aliases.{alias_name}.route_by_difficulty.{difficulty} = '{target_alias}' is itself an auto-routing alias (chained auto routing is not supported)"))

                for expected in ("simple", "complex"):
                    if expected not in route_by_difficulty:
                        findings.append(Finding("WARN", f"model_aliases.{alias_name}.route_by_difficulty is missing '{expected}' (DifficultyClassifier only ever returns 'simple' or 'complex')"))

    return aliases


def validate_pack(pack: dict[str, Any]) -> list[Finding]:
    findings: list[Finding] = []

    if "providers" not in pack:
        findings.append(Finding("ERROR", "top-level 'providers' key is missing"))
    providers = validate_provider_config(pack.get("providers", []), findings)

    if "model_aliases" not in pack:
        findings.append(Finding("ERROR", "top-level 'model_aliases' key is missing"))
    validate_model_aliases(pack.get("model_aliases", {}), set(providers.keys()), findings)

    validate_retry_config(pack.get("retry"), findings)

    return findings


def main(argv: list[str]) -> int:
    path = Path(argv[1]) if len(argv) > 1 else default_pack_path()

    if not path.is_file():
        print(f"ERROR: pack file not found: {path}", file=sys.stderr)
        return 2

    try:
        pack = load_pack(path)
    except json.JSONDecodeError as e:
        print(f"ERROR: {path} is not valid JSON: {e}", file=sys.stderr)
        return 2

    findings = validate_pack(pack)
    errors = [f for f in findings if f.level == "ERROR"]
    warnings = [f for f in findings if f.level == "WARN"]

    print(f"Validating pack: {path}")
    print()
    if not findings:
        print("OK — no errors or warnings.")
    else:
        for f in findings:
            print(f"  {f}")
        print()
        print(f"{len(errors)} error(s), {len(warnings)} warning(s)")

    if errors:
        print()
        print("RESULT: INVALID")
        return 1

    print()
    print("RESULT: VALID" + (" (with warnings)" if warnings else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
