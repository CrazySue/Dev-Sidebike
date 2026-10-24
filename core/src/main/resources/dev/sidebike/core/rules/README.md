# Bundled rules snapshot — provenance

`rules.json` in this directory is an **offline snapshot** of the official Dev-Sidecar rule set,
generated for Dev-Sidebike's `:core` module (T6).

## Sources (read-only, generated 2026-10-25)

| Item | Source |
|---|---|
| Intercepts / preSetIpList / whiteList / excludeIpList / dns providers+mapping+familyMapping | `C:\Users\CrazySue\.dev-sidecar\running.json` — the live Dev-Sidecar v2.3.1 runtime config (official config `metaInfo.version=202610161530`) |
| Domestic allow-list | `C:\Users\CrazySue\.dev-sidecar\domestic-domain-allowlist.txt` (pluwen/china-domain-allowlist, Update Date 2025/07/05) |

The top-level `_meta` object inside `rules.json` carries the same provenance plus generation stats.

## Transformation rules

- Only MVP-supported intercept action fields are kept:
  `sni`, `proxy`, `success`, `script`, `tampermonkeyScript`, `requestReplace`, `responseReplace`,
  `cacheDays` (converted from cacheSeconds/Minutes/Hours/Days/Weeks/Months/Years/cacheMaxAge/cache),
  `status`, `desc`, `remark`.
- Dropped: `abort`, `redirect`, `backup`, `verifyHost`, `unVerifySsl`, `options`, `exclusions`,
  `order`, `replace`, `baiduOcr`, and `null`-valued (disabled) path rules.
- Upstream DNS provider `local` (127.0.0.1) is removed to avoid VPN DNS self-recursion.
- `bootstrapIps` are embedded for DoH/DoT providers resolved on 2026-10-25, because the app must
  NEVER resolve provider hostnames via the system resolver (VPN loop / re-poisoning).
- Do not edit by hand; regenerate with the T6 generator logic and keep `_meta` accurate.
