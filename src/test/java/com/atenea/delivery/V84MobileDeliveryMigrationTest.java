package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V84MobileDeliveryMigrationTest {
    private String schema;
    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        if (schema != null) connection.setSchema(schema);
        return connection;
    }
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).createSchemas(true).locations("classpath:db/migration")
                .target(target).load();
    }
    private void isolated(Work work) throws Exception {
        schema="delivery_"+UUID.randomUUID().toString().replace("-","");
        try { flyway("83").migrate(); work.run(); }
        finally {
            try (Connection connection=connection(); Statement statement=connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");
            }
        }
    }
    private void fixture(Connection connection) throws Exception {
        sql(connection,"INSERT INTO project(id,name,repo_path,default_base_branch) VALUES(9001,'Isolated delivery','/synthetic','main')");
        sql(connection,"INSERT INTO operator_account(id,email,display_name,password_hash,created_at,updated_at) VALUES(9001,'isolated@atenea.test','Test','synthetic',now(),now())");
        sql(connection,"INSERT INTO work_session(id,project_id,status,title,base_branch,workspace_identity,opened_at,last_activity_at) VALUES(9001,9001,'OPEN','Isolated','main','local:work-session:9001',now(),now())");
        sql(connection,"INSERT INTO session_turn(id,session_id,actor,message_text) VALUES(9001,9001,'OPERATOR','Synthetic; never dispatched')");
    }
    private String release(String state) {
        return "INSERT INTO mobile_delivery_operation(id,session_id,operator_id,kind,target,source_commit,execution_id,state,plan_sha256,created_at,updated_at) VALUES('"
                +UUID.randomUUID()+"',9001,9001,'RELEASE','APP_PROD','"+"1".repeat(40)+"','"+UUID.randomUUID()+"','"+state+"','"+"2".repeat(64)+"',now(),now())";
    }
    private String run() {
        return "INSERT INTO agent_run(session_id,origin_turn_id,status,target_repo_path,workspace_identity,started_at) VALUES(9001,9001,'RUNNING','/synthetic','local:work-session:9001',now())";
    }
    private void sql(Connection connection,String value) throws SQLException {
        try (Statement statement=connection.createStatement()) { statement.execute(value); }
    }
    @Test void upgradeIsExpandOnlyAndIdempotent() throws Exception {
        isolated(() -> {
            try (Connection connection=connection()) { fixture(connection); }
            assertEquals(1,flyway("84").migrate().migrationsExecuted);
            assertEquals(0,flyway("84").migrate().migrationsExecuted);
            try (Connection connection=connection(); Statement statement=connection.createStatement();
                 var result=statement.executeQuery("SELECT count(*) FROM work_session WHERE id=9001")) {
                result.next(); assertEquals(1,result.getInt(1));
            }
        });
    }
    @Test void globalReleaseUniquenessAndTerminalAuditRetention() throws Exception {
        isolated(() -> {
            flyway("84").migrate();
            try (Connection connection=connection()) {
                fixture(connection); sql(connection,release("READY"));
                assertEquals("23505",assertThrows(SQLException.class,()->sql(connection,release("READY"))).getSQLState());
                sql(connection,"UPDATE mobile_delivery_operation SET state='BLOCKED',error_code='TEST_STOP' WHERE session_id=9001");
                sql(connection,release("READY"));
                try (var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT count(*) FROM mobile_delivery_operation")) {
                    rows.next(); assertEquals(2,rows.getInt(1));
                }
            }
        });
    }
    @Test void preparedPlanDoesNotBlockTasksButAcceptedPublicationDoes() throws Exception {
        isolated(() -> {
            flyway("84").migrate();
            try (Connection connection=connection()) {
                fixture(connection); sql(connection,release("READY")); sql(connection,run());
                sql(connection,"UPDATE agent_run SET status='CANCELLED',finished_at=now() WHERE session_id=9001");
                sql(connection,"UPDATE mobile_delivery_operation SET state='CONFIRMED' WHERE session_id=9001");
                SQLException rejected=assertThrows(SQLException.class,()->sql(connection,run()));
                assertEquals("55000",rejected.getSQLState());
                assertTrue(rejected.getMessage().contains("ATENEA_RELEASE_IN_PROGRESS"));
                sql(connection,"UPDATE mobile_delivery_operation SET state='SUCCEEDED' WHERE session_id=9001");
                sql(connection,run());
            }
        });
    }
    @Test void admissionCannotRaceReleaseAcceptanceTransaction() throws Exception {
        isolated(() -> {
            flyway("84").migrate();
            try (var thread=Executors.newSingleThreadExecutor()) {
              try (Connection acceptance=connection()) {
                fixture(acceptance); acceptance.setAutoCommit(false);
                sql(acceptance,"SELECT pg_advisory_xact_lock(814205002)");
                sql(acceptance,release("CONFIRMED"));
                var entered=new java.util.concurrent.CountDownLatch(1);
                var result=thread.submit(() -> {
                    try (Connection candidate=connection()) {
                        entered.countDown(); sql(candidate,run()); return "UNEXPECTED_ADMISSION";
                    } catch (SQLException exception) { return exception.getSQLState(); }
                });
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,()->result.get(100,TimeUnit.MILLISECONDS));
                acceptance.commit(); assertEquals("55000",result.get(3,TimeUnit.SECONDS));
              }
            }
        });
    }
    @Test void failedRollbackKeepsReleaseAndTaskAdmissionClosedIncludingStatusUpdates() throws Exception {
        isolated(() -> {
            flyway("84").migrate();
            try (Connection connection=connection()) {
                fixture(connection); sql(connection,run());
                sql(connection,"UPDATE agent_run SET status='FAILED',finished_at=now() WHERE session_id=9001");
                sql(connection,release("ROLLBACK_FAILED"));
                assertEquals("23505",assertThrows(SQLException.class,()->sql(connection,release("READY"))).getSQLState());
                assertEquals("55000",assertThrows(SQLException.class,()->sql(connection,run())).getSQLState());
                assertEquals("55000",assertThrows(SQLException.class,()->sql(connection,
                        "UPDATE agent_run SET status='RUNNING' WHERE session_id=9001")).getSQLState());
            }
        });
    }
    interface Work { void run() throws Exception; }
}
