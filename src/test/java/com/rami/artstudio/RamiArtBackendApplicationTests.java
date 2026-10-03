package com.rami.artstudio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.rami.artstudio.support.LegacyEmbeddedPostgres;

@SpringBootTest(properties = "spring.flyway.enabled=true")
class RamiArtBackendApplicationTests {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		LegacyEmbeddedPostgres.configure(registry, "legacy_application_tests");
	}

	@Test
	void contextLoads() {
	}

}
