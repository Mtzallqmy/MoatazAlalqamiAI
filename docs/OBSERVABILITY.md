# Privacy-preserving observability

Crash reporting and usage telemetry are separate opt-ins and default off.
Disabled exporters must issue zero network requests. Local diagnostics remain
available without consent.

Allowed metrics are aggregate operation status, duration, storage, terminal
startup, agent completion/cancellation/repair, provider latency, token count,
and user-approved cost. Prompts, responses, source code, command stdin/stdout,
file paths, API tokens, SSH material, and conversation text are prohibited.

Exporters must apply structured redaction, bounded queues, exponential backoff,
retention, and deletion controls. Runtime diagnostics expose stage, exit code,
duration, redacted stderr tail, and cause. Audit export is an independent opt-in;
local audit history must not be described as durable or tamper-evident unless it
is persisted with sequence and hash-chain verification.

Backend alert targets for a future deployment include authentication failures,
tenant-boundary denial, 429 rate, sandbox-create failure, command latency,
orphan processes, storage exhaustion, and backup/restore age. No production SRE
claim exists until dashboards and alerts are exercised against a deployed stack.
