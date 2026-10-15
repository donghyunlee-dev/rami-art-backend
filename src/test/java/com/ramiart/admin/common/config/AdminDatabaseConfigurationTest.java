package com.ramiart.admin.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

class AdminDatabaseConfigurationTest {

    private final YamlPropertySourceLoader yaml = new YamlPropertySourceLoader();

    @Test
    void baseAndDevProfilesUseOneRamiSchemaDatasourceConfiguration() throws Exception {
        var base = yaml.load("application", new FileSystemResource(new File("src/main/resources/application.yml"))).getFirst();
        var dev = yaml.load("application-dev", new FileSystemResource(new File("src/main/resources/application-dev.yml"))).getFirst();

        for (var properties : new org.springframework.core.env.PropertySource<?>[] {base, dev}) {
            assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${DATASOURCE_URL}");
            assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${DATASOURCE_USERNAME}");
            assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${DATASOURCE_PASSWORD}");
            assertThat(properties.getProperty("spring.datasource.hikari.connection-init-sql"))
                    .isEqualTo("SET search_path TO rami_art_studio, extensions");
            assertThat(properties.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("rami_art_studio");
            assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
            assertThat(properties.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto")).isEqualTo("none");
            assertThat(properties.getProperty("spring.flyway.default-schema")).isEqualTo("rami_art_studio");
            assertThat(properties.getProperty("spring.flyway.schemas")).isEqualTo("rami_art_studio");
            assertThat(properties.getProperty("app.security.legacy-bootstrap-enabled")).isEqualTo(false);
        }
    }
}
