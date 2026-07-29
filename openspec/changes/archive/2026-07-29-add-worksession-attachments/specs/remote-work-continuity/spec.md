## MODIFIED Requirements

### Requirement: Durable artifact continuity

Artifacts retained by policy SHALL remain accessible by exact WorkSession
identity after client disconnect, attachment-service restart and preview
shutdown. Restart reconciliation SHALL use persisted metadata and opaque storage
identity without inventing ownership or re-uploading bytes.

#### Scenario: Operator returns after preview teardown

- **WHEN** the operator reconnects after the preview and attachment service have
  stopped and the attachment service is restarted
- **THEN** retained screenshots and reports remain byte-identical and available
  from the originating WorkSession even though ephemeral preview resources are
  gone
