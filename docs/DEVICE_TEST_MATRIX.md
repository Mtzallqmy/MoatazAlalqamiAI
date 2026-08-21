# Device and resilience test matrix

| Target | Scenarios | Release gate |
|---|---|---|
| ARM64 API 26/29/34/37 | fresh Full install, offline Runtime Ready, PTY input/resize/cancel | Beta candidate |
| ARM64 upgrade | previous release to Full and Lite, settings/projects/secrets preserved | Beta candidate |
| Lite networking | cancel/resume, ETag change, corrupt hash, DNS loss, captive/offline | Lite Beta |
| Storage pressure | insufficient download/extraction space and cleanup without project loss | Lite Beta |
| Runtime rollback | failed extraction, failed health, interrupted activation journal | Runtime Beta |
| Agent workflow | edit/test/diff/undo, approval denial, cancellation, provider failure | Beta candidate |
| Remote gateway | JWT expiry, tenant isolation, rate limits, WS ordering/backpressure | Remote Beta |

Repository CI currently proves host tests, Debug/Release assembly, APK contents,
ABI, and CI certificate identity. Emulator/device rows must include device model,
API level, ABI, APK/runtime IDs, elapsed times, and JUnit/log artifacts. An absent
device-farm result is reported as not validated, never as a pass.
