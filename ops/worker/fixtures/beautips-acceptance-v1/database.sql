\set ON_ERROR_STOP on

BEGIN;

INSERT INTO customers (
    tenant_id, first_name, last_name, display_name, email, phone,
    birth_day, birth_month, status, created_at, updated_at
)
SELECT
    t.id, 'Lina', 'Prueba', 'Lina Prueba',
    'lina.prueba@aurora-acceptance.invalid', '+00000000001',
    15, 1, 'ACTIVE',
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:00+00'
FROM tenants t
WHERE t.slug = 'aurora-acceptance'
  AND NOT EXISTS (
      SELECT 1
      FROM customers c
      WHERE c.tenant_id = t.id
        AND c.email = 'lina.prueba@aurora-acceptance.invalid'
  );

INSERT INTO customer_consents (
    customer_id, terms_accepted_at, privacy_accepted_at,
    marketing_opt_in, whatsapp_opt_in, push_opt_in,
    created_at, updated_at
)
SELECT
    c.id,
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    FALSE, FALSE, FALSE,
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:00+00'
FROM customers c
JOIN tenants t ON t.id = c.tenant_id
WHERE t.slug = 'aurora-acceptance'
  AND c.email = 'lina.prueba@aurora-acceptance.invalid'
  AND NOT EXISTS (
      SELECT 1 FROM customer_consents cc WHERE cc.customer_id = c.id
  );

INSERT INTO tenant_modules (
    tenant_id, module_key, status, activation_source, activated_at,
    created_at, updated_at
)
SELECT
    t.id, 'LOYALTY', 'ACTIVE', 'SYNTHETIC_ACCEPTANCE',
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:00+00'
FROM tenants t
WHERE t.slug = 'aurora-acceptance'
ON CONFLICT (tenant_id, module_key) DO NOTHING;

INSERT INTO loyalty_programs (
    tenant_id, name, description, program_type, status, stamp_goal,
    points_conversion_amount, wallet_conversion_rate, seal_code,
    display_order, expiration_policy, expires_on, legacy_placeholder,
    created_at, updated_at
)
SELECT
    t.id, 'Tarjeta Aurora', 'Programa sintético de aceptación',
    'STAMP_CARD', 'ACTIVE', 8,
    NULL, NULL, NULL,
    0, 'NEVER', NULL, FALSE,
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:00+00'
FROM tenants t
WHERE t.slug = 'aurora-acceptance'
  AND NOT EXISTS (
      SELECT 1
      FROM loyalty_programs lp
      WHERE lp.tenant_id = t.id
        AND lp.name = 'Tarjeta Aurora'
        AND lp.program_type = 'STAMP_CARD'
  );

INSERT INTO loyalty_accounts (
    tenant_id, customer_id, loyalty_program_id,
    current_stamps, current_points, current_euros,
    total_stamps_granted, total_points_granted, total_euros_granted,
    last_movement_at, version, first_accumulation_at, expires_at, expired_at,
    created_at, updated_at
)
SELECT
    t.id, c.id, lp.id,
    3, 0, 0,
    3, 0, 0,
    TIMESTAMPTZ '2026-01-15 10:05:00+00', 0,
    TIMESTAMPTZ '2026-01-15 10:05:00+00', NULL, NULL,
    TIMESTAMPTZ '2026-01-15 10:05:00+00',
    TIMESTAMPTZ '2026-01-15 10:05:00+00'
FROM tenants t
JOIN customers c
  ON c.tenant_id = t.id
 AND c.email = 'lina.prueba@aurora-acceptance.invalid'
JOIN loyalty_programs lp
  ON lp.tenant_id = t.id
 AND lp.name = 'Tarjeta Aurora'
 AND lp.program_type = 'STAMP_CARD'
WHERE t.slug = 'aurora-acceptance'
ON CONFLICT (tenant_id, customer_id, loyalty_program_id) DO NOTHING;

INSERT INTO loyalty_transactions (
    tenant_id, loyalty_account_id, customer_id, loyalty_program_id,
    reward_id, type, stamps_delta, points_delta, euros_delta,
    source, source_reference, notes, performed_by_type, performed_by_id,
    occurred_at, created_at
)
SELECT
    t.id, la.id, c.id, lp.id,
    NULL, 'STAMP_GRANTED', 3, 0, 0,
    'PANEL', 'acceptance-v1-initial-stamps',
    'Movimiento sintético versionado', 'ADMIN', bu.id,
    TIMESTAMPTZ '2026-01-15 10:05:00+00',
    TIMESTAMPTZ '2026-01-15 10:05:00+00'
FROM tenants t
JOIN customers c
  ON c.tenant_id = t.id
 AND c.email = 'lina.prueba@aurora-acceptance.invalid'
JOIN loyalty_programs lp
  ON lp.tenant_id = t.id
 AND lp.name = 'Tarjeta Aurora'
 AND lp.program_type = 'STAMP_CARD'
JOIN loyalty_accounts la
  ON la.tenant_id = t.id
 AND la.customer_id = c.id
 AND la.loyalty_program_id = lp.id
JOIN LATERAL (
    SELECT id
    FROM business_users
    WHERE tenant_id = t.id
    ORDER BY id
    LIMIT 1
) bu ON TRUE
WHERE t.slug = 'aurora-acceptance'
  AND NOT EXISTS (
      SELECT 1
      FROM loyalty_transactions lt
      WHERE lt.tenant_id = t.id
        AND lt.source_reference = 'acceptance-v1-initial-stamps'
  );

INSERT INTO loyalty_program_events (
    tenant_id, loyalty_program_id, program_type, event_type,
    previous_status, new_status, actor_type, actor_id, reason, metadata_json,
    created_at, updated_at
)
SELECT
    t.id, lp.id, 'STAMP_CARD', 'PROGRAM_CREATED',
    NULL, 'ACTIVE', 'SYSTEM', 'acceptance-v1',
    'Evento sintético versionado',
    '{"fixtureVersion":"beautips-acceptance-v1"}'::jsonb,
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:00+00'
FROM tenants t
JOIN loyalty_programs lp
  ON lp.tenant_id = t.id
 AND lp.name = 'Tarjeta Aurora'
 AND lp.program_type = 'STAMP_CARD'
WHERE t.slug = 'aurora-acceptance'
  AND NOT EXISTS (
      SELECT 1
      FROM loyalty_program_events lpe
      WHERE lpe.tenant_id = t.id
        AND lpe.actor_id = 'acceptance-v1'
        AND lpe.event_type = 'PROGRAM_CREATED'
  );

INSERT INTO service_catalogs (
    tenant_id, name, content, file_key, status, created_at, updated_at
)
SELECT
    t.id, 'Servicios Aurora',
    'Catálogo sintético para validación visual.',
    'acceptance-v1/synthetic-salon-mark.svg',
    'ACTIVE',
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:00+00'
FROM tenants t
WHERE t.slug = 'aurora-acceptance'
  AND NOT EXISTS (
      SELECT 1
      FROM service_catalogs sc
      WHERE sc.tenant_id = t.id
        AND sc.file_key = 'acceptance-v1/synthetic-salon-mark.svg'
  );

INSERT INTO import_jobs (
    tenant_id, source_system, job_type, status, started_at, finished_at,
    summary_json, error_log, created_at, updated_at
)
SELECT
    t.id, 'SYNTHETIC_ACCEPTANCE_V1', 'INVENTED_CUSTOMERS', 'COMPLETED',
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:01+00',
    '{"fixtureVersion":"beautips-acceptance-v1","file":"acceptance-v1/invented-customers.csv","rows":1}'::jsonb,
    NULL,
    TIMESTAMPTZ '2026-01-15 10:00:00+00',
    TIMESTAMPTZ '2026-01-15 10:00:01+00'
FROM tenants t
WHERE t.slug = 'aurora-acceptance'
  AND NOT EXISTS (
      SELECT 1
      FROM import_jobs ij
      WHERE ij.tenant_id = t.id
        AND ij.source_system = 'SYNTHETIC_ACCEPTANCE_V1'
        AND ij.job_type = 'INVENTED_CUSTOMERS'
  );

COMMIT;
