## MODIFIED Requirements

### Requirement: Durable artifact continuity

Artifacts retained by policy and immutable turn attachment bindings SHALL
remain accessible by exact WorkSession identity after client disconnect,
backend/attachment/worker restart, retry and preview shutdown. Restart
reconciliation SHALL use persisted metadata, ordered turn bindings, AgentRun
manifest and opaque storage identity without inventing ownership, re-uploading
bytes, dropping an image or starting a duplicate Codex execution.

#### Scenario: Operator returns after preview teardown

- **WHEN** the operator reconnects after the preview and attachment service have
  stopped and the attachment service is restarted
- **THEN** retained screenshots, reports and historical turn bindings remain
  byte-identical and available from the originating WorkSession even though
  ephemeral preview resources are gone

#### Scenario: Client disconnects after image-bearing turn acceptance

- **WHEN** Atenea committed the turn, bindings and AgentRun before the web
  client disconnected
- **THEN** the same worker executes or reconciles the exact attachment manifest
  and publishes no duplicate turn, binding, run or result

#### Scenario: Failed image-bearing run is retried

- **WHEN** safe recovery creates one linked retry for a failed remote run
- **THEN** it inherits the original turn, ordered image manifest and effective
  Codex profile without re-uploading or rebinding content

#### Scenario: Restart occurs during image materialization

- **WHEN** backend or worker state is reconstructed while an image-bearing run
  is non-terminal
- **THEN** reconciliation follows the existing dispatch identity and never
  creates a replacement execution merely because temporary image state is
  being verified

