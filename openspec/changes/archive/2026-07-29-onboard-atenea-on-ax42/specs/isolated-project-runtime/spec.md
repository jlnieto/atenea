## MODIFIED Requirements

### Requirement: Project onboarding gate

Atenea SHALL be treated as AX42-resident for administrative development only
after its relocation gate, and SHALL become schedulable for real AgentRuns only
after `onboard-atenea-on-ax42` proves canonical GitHub source, exact project
allowlisting, real prompt/thread continuity, schema-valid manifest, isolated
empty development data, build, tests, runtime health, private preview,
desktop/mobile browser evidence, normal delivery/close, restart
reconciliation, rollback and production non-impact.

Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol SHALL become schedulable
only after their own independent build, runtime, health, preview, browser,
delivery and cleanup evidence passes. No cohort-wide enablement is permitted.

#### Scenario: Atenea real-project onboarding fails

- **WHEN** Atenea cannot prove any required source, execution, delivery,
  runtime, visual, rollback or production non-impact check
- **THEN** administrative development remains available as its fallback and no
  real Atenea AgentRun routing is enabled

#### Scenario: One project fails compatibility validation

- **WHEN** a project's independent compatibility gate fails
- **THEN** only that project remains disabled while already accepted projects
  remain available
