package com.ramiart.admin.config;

import org.apache.coyote.http11.Http11Nio2Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("local")
@Configuration(proxyBeanMethods = false)
class LocalTomcatConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> localTomcatProtocolCustomizer() {
        return factory -> factory.setProtocol(Http11Nio2Protocol.class.getName());
    }
}
