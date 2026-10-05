package com.ramiart.admin.financeimport.infrastructure;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class FinancialImportAsyncConfiguration {
    @Bean(name="financialImportExecutor")
    Executor financialImportExecutor(){ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(2);executor.setQueueCapacity(20);executor.setThreadNamePrefix("financial-import-");executor.initialize();return executor;}
}
