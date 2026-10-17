package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V101AndroidVersionPreparationMigrationTest {
    @Test void upgradeIsRepeatablePreservesReceiptsAndSerializesVersionPreparationWithPublication() throws Exception {
        assertTrue(System.getenv("SPRING_DATASOURCE_URL").matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test"));
        String schema="android_version_"+UUID.randomUUID().toString().replace("-","");
        try {
            flyway(schema,"100").migrate();
            try (var db=connection(schema)) {
                sql(db,"INSERT INTO project(id,name,repo_path,default_base_branch) VALUES(9001,'Synthetic','/synthetic','main')");
                sql(db,"INSERT INTO operator_account(id,email,display_name,password_hash,created_at,updated_at) VALUES(9001,'synthetic@atenea.test','Test','synthetic',now(),now())");
                sql(db,"INSERT INTO development_change(id,change_key,project_id,title,status,base_ref,base_commit,workspace_branch,workspace_identity,selected_worker_id,project_policy_revision,source_fingerprint_sha256,created_at,updated_at) "
                        +"VALUES(9001,'00000000-0000-4000-8000-000000000101',9001,'Synthetic','COMPLETED','refs/heads/main','"+"1".repeat(40)+"','atenea/change-00000000-0000-4000-8000-000000000101','remote:synthetic:change:00000000-0000-4000-8000-000000000101','synthetic',1,'"+"4".repeat(64)+"',now(),now())");
                sql(db,"INSERT INTO work_session(id,project_id,development_change_id,status,title,base_branch,workspace_branch,workspace_identity,opened_at,closed_at,last_activity_at) "
                        +"VALUES(9001,9001,9001,'CLOSED','Synthetic','main','atenea/change-00000000-0000-4000-8000-000000000101','remote:synthetic:change:00000000-0000-4000-8000-000000000101',now(),now(),now())");
                sql(db,operation("RELEASE","ANDROID_STABLE","BLOCKED"));
            }
            assertEquals(1,flyway(schema,"101").migrate().migrationsExecuted);
            assertEquals(0,flyway(schema,"101").migrate().migrationsExecuted);
            try (var db=connection(schema)) {
                sql(db,operation("ANDROID_VERSION","ANDROID_STABLE","QUEUED"));
                assertEquals("23505",assertThrows(SQLException.class,()->sql(db,operation("RELEASE","APP_PROD","READY"))).getSQLState());
                assertEquals("23514",assertThrows(SQLException.class,()->sql(db,operation("ANDROID_VERSION","APP_PROD","BLOCKED"))).getSQLState());
                assertEquals("23514",assertThrows(SQLException.class,()->sql(db,operation("PUBLISH_PR","ANDROID_STABLE","BLOCKED"))).getSQLState());
                assertEquals("55000",assertThrows(SQLException.class,()->sql(db,"UPDATE mobile_delivery_operation SET source_commit='"+"2".repeat(40)+"' WHERE kind='ANDROID_VERSION'")).getSQLState());
                assertEquals("55000",assertThrows(SQLException.class,()->sql(db,"UPDATE mobile_delivery_operation SET evidence_json='{\"versionCode\":999}' WHERE kind='ANDROID_VERSION'")).getSQLState());
                sql(db,"UPDATE mobile_delivery_operation SET state='SUCCEEDED' WHERE kind='ANDROID_VERSION'");
                sql(db,operation("RELEASE","APP_PROD","READY"));
                try (var stmt=db.createStatement();var rows=stmt.executeQuery("SELECT count(*) FROM mobile_delivery_operation")) { rows.next();assertEquals(3,rows.getInt(1)); }
            }
        } finally { try (var db=connection(schema)) { sql(db,"DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE"); } }
    }
    private String operation(String kind,String target,String state) {
        return "INSERT INTO mobile_delivery_operation(id,session_id,operator_id,kind,target,source_commit,execution_id,state,created_at,updated_at) VALUES('"+UUID.randomUUID()+"',9001,9001,'"+kind+"','"+target+"','"+"1".repeat(40)+"','"+UUID.randomUUID()+"','"+state+"',now(),now())";
    }
    private Flyway flyway(String schema,String target) {
        return Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).createSchemas(true).locations("classpath:db/migration").target(target).load();
    }
    private Connection connection(String schema) throws SQLException {
        var db=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        db.setSchema(schema);return db;
    }
    private void sql(Connection db,String sql) throws SQLException { try (var statement=db.createStatement()) { statement.execute(sql); } }
}
