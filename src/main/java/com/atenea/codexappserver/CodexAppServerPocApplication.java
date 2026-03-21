package com.atenea.codexappserver;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(
        scanBasePackageClasses = CodexAppServerPocApplication.class,
        exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
        })
@Profile("codex-app-server-poc")
public class CodexAppServerPocApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(CodexAppServerPocApplication.class)
                .profiles("codex-app-server-poc")
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
