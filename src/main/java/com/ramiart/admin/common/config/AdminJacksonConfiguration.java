package com.ramiart.admin.common.config;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps the management API's JSON contract independent of the legacy API naming policy. */
@Configuration(proxyBeanMethods = false)
public class AdminJacksonConfiguration {

    private static final String ADMIN_PACKAGE = "com.ramiart.admin";

    @Bean
    Jackson2ObjectMapperBuilderCustomizer adminPackageCamelCaseNaming() {
        return builder -> builder.postConfigurer(mapper -> {
            AnnotationIntrospector serialization = mapper.getSerializationConfig().getAnnotationIntrospector();
            AnnotationIntrospector deserialization = mapper.getDeserializationConfig().getAnnotationIntrospector();
            AnnotationIntrospector adminNaming = new NopAnnotationIntrospector() {
                @Override
                public Object findNamingStrategy(AnnotatedClass annotatedClass) {
                    String packageName = annotatedClass.getRawType().getPackageName();
                    if (packageName.equals(ADMIN_PACKAGE) || packageName.startsWith(ADMIN_PACKAGE + ".")) {
                        return PropertyNamingStrategies.LOWER_CAMEL_CASE;
                    }
                    return null;
                }
            };
            mapper.setAnnotationIntrospectors(
                    AnnotationIntrospector.pair(serialization, adminNaming),
                    AnnotationIntrospector.pair(deserialization, adminNaming));
        });
    }
}
