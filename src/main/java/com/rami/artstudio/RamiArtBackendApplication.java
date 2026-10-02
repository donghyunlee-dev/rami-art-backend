package com.rami.artstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.rami.artstudio", "com.ramiart.admin"})
public class RamiArtBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(RamiArtBackendApplication.class, args);
	}

}
