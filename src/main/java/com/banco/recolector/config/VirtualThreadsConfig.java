package com.banco.recolector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Configuración de Virtual Threads (Project Loom).
 * Tomcat, @Scheduled y el resto de Spring usan Virtual Threads vía {@code spring.threads.virtual.enabled=true};
 * este ejecutor dedicado atiende cada conexión TCP ISO 8583 en su propio Virtual Thread.
 */
@Configuration
public class VirtualThreadsConfig {

    @Bean(name = "isoVirtualTaskExecutor")
    public AsyncTaskExecutor isoVirtualTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("iso-tcp-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
