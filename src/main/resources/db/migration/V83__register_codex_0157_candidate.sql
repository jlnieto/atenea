DO $$
BEGIN
    IF current_database() = 'atenea_prod' THEN
        IF NOT EXISTS (
            SELECT 1 FROM worker_codex_release_inventory
             WHERE worker_id = 'ax42-01'
               AND codex_version = '0.145.0'
               AND link_state = 'CURRENT'
               AND catalog_revision = '125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187'
        ) THEN
            RAISE EXCEPTION 'AX42 managed Codex current release differs from reviewed 0.145.0 state';
        END IF;

        INSERT INTO worker_codex_release_inventory (
            inventory_id, worker_id, codex_version, release_digest_sha256,
            installation_state, link_state, compatibility_state,
            catalog_revision, observed_at
        ) VALUES (
            '1d586e4a-0409-453a-9ea9-762e99d1438a',
            'ax42-01',
            '0.157.0',
            'c30a04c5791c19534ba5d2586a63b3766272951e559ea0056cc5192b03d3abc9',
            'DISCOVERED', 'NONE', 'COMPATIBLE',
            '1372647bd09888c3305147b9a7cf6889b5b4526e04d332971f7e3a43ccb7efc7',
            CURRENT_TIMESTAMP
        );
    END IF;
END $$;
