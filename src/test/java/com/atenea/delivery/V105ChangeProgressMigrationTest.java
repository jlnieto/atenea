package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V105ChangeProgressMigrationTest {
    @Test void expandsFrom104WithoutChangingPriorMigrationHistoryAndReplaysWithoutChanges() throws Exception {
        String schema="change_progress_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("SPRING_DATASOURCE_URL"),user=System.getenv("SPRING_DATASOURCE_USERNAME"),password=System.getenv("SPRING_DATASOURCE_PASSWORD");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test"));
        try {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("104").load().migrate();
            try(var db=DriverManager.getConnection(url,user,password)) {
                db.setSchema(schema);
                String before;
                try(var s=db.createStatement();var r=s.executeQuery("SELECT md5(string_agg(version||':'||checksum::text,',' ORDER BY installed_rank)) FROM flyway_schema_history WHERE success AND version IS NOT NULL")) { r.next();before=r.getString(1); }
                var upgrade=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("105").load();
                assertEquals(1,upgrade.migrate().migrationsExecuted);assertEquals(0,upgrade.migrate().migrationsExecuted);upgrade.validate();
                try(var s=db.createStatement();var r=s.executeQuery("SELECT md5(string_agg(version||':'||checksum::text,',' ORDER BY installed_rank)) FROM flyway_schema_history WHERE success AND version IS NOT NULL AND version<>'105'")) { r.next();assertEquals(before,r.getString(1)); }
                try(var s=db.createStatement();var r=s.executeQuery("SELECT count(*) FROM mobile_change_progress_notice")) { r.next();assertEquals(0,r.getLong(1)); }
                try(var s=db.createStatement();var r=s.executeQuery("SELECT count(*) FROM mobile_change_progress_delivery")) { r.next();assertEquals(0,r.getLong(1)); }
            }
        } finally {
            try(var db=DriverManager.getConnection(url,user,password);var s=db.createStatement()) { s.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE"); }
        }
    }
}
