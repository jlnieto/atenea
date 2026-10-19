package com.atenea.service.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AteneaUpdatesSnapshotStore {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public AteneaUpdatesSnapshotStore(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public record Snapshot(AteneaUpdatesService.Updates payload,Instant attemptedAt,String errorCode) { }
    public Snapshot find(int page) {
        return jdbc.query("SELECT * FROM atenea_updates_snapshot WHERE page=?",(r,n)->{
            try{return new Snapshot(r.getString("payload_json")==null ? null : mapper.readValue(r.getString("payload_json"),AteneaUpdatesService.Updates.class),
                r.getTimestamp("attempted_at").toInstant(),r.getString("error_code"));}
            catch(java.io.IOException invalid){throw new java.sql.SQLException("Invalid product snapshot",invalid);}
        },page).stream().findFirst().orElse(null);
    }
    public void save(int page,AteneaUpdatesService.Updates payload) {
        try{jdbc.update("""
            INSERT INTO atenea_updates_snapshot(page,payload_json,attempted_at) VALUES(?,?::jsonb,now())
            ON CONFLICT(page) DO UPDATE SET payload_json=excluded.payload_json,attempted_at=now(),error_code=NULL
            """,page,mapper.writeValueAsString(payload));}
        catch(java.io.IOException invalid){throw new IllegalStateException("Invalid product snapshot",invalid);}
    }
    public void failed(int page) {jdbc.update("""
        INSERT INTO atenea_updates_snapshot(page,attempted_at,error_code) VALUES(?,now(),'NEWS_REFRESH_UNAVAILABLE')
        ON CONFLICT(page) DO UPDATE SET attempted_at=now(),error_code=excluded.error_code
        """,page);}
}
