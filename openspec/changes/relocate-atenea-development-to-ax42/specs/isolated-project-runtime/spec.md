## MODIFIED Requirements

### Requirement: Project onboarding gate
Atenea SHALL be treated as AX42-resident for development only after its
dedicated relocation gate proves canonical GitHub source, a schema-valid
manifest, isolated empty development data, build, tests, runtime health,
desktop/mobile browser evidence, restart reconciliation, rollback and
production non-impact.

Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol SHALL become schedulable
only after their own build, runtime, health, preview, browser and cleanup
evidence passes.

#### Scenario: Atenea development relocation fails
- **WHEN** Atenea cannot prove any required relocation or production non-impact check
- **THEN** its normal development location remains the existing control-plane fallback and no AgentRun routing to AX42 is enabled

#### Scenario: One project fails compatibility validation
- **WHEN** a project's representative runtime or browser check fails
- **THEN** that project remains disabled on the worker while already accepted projects remain available
