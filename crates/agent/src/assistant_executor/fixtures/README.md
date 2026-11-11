# Native schema fixture provenance

Copied without editing from `.local/assistant-schema-20261110/`, the existing app-server schema extraction requested for this task. These eight schemas are embedded only in tests so verification does not depend on an untracked `.local` directory or generate models. Runtime protocol verification remains the authenticated Host's responsibility; a passing local fixture is not evidence for a different installed protocol generation.

- ThreadStartParams SHA256: see final executor report hash inventory.
- TurnStartParams SHA256: see final executor report hash inventory.

Fixtures include real function dynamicTools, sandbox/approval fields and native dynamic-tool/human response shapes. The test validator checks the schema features exercised by these packets, not arbitrary JSON Schema implementations.
