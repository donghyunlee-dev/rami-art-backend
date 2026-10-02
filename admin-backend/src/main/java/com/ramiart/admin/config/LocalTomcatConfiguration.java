package com.ramiart.admin.config;

import org.apache.coyote.http11.Http11Nio2Protocol;
import org.springframework.boot.tomcat.TomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("local")
@Configuration(proxyBeanMethods = false)
class LocalTomcatConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatWebServerFactory> localTomcatProtocolCustomizer() {
        return factory -> factory.setProtocol(Http11Nio2Protocol.class.getName());
    }
}
