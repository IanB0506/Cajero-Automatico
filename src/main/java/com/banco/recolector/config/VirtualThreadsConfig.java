package com.banco.recolector.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configuración de Virtual Threads (Project Loom) en Java 21+.
 * Provee ejecutores asíncronos que evitan el bloqueo de hilos de la plataforma (Carrier Threads).
 */
@Configuration
public class VirtualThreadsConfig {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadsConfig.class);

    @PostConstruct
    public void logVirtualThreadsStatus() {
        boolean isVirtualSupported = Thread.currentThread().isVirtual() || 
                Runtime.version().feature() >= 21;
        log.info("=================================================================");
        log.info(">>> Project Loom / Virtual Threads Soportado: {}", isVirtualSupported);
        log.info(">>> Java Runtime Version: {}", Runtime.version());
        log.info(">>> Concurrencia de Alto Rendimiento sin Carrier Thread Pinning");
        log.info("=================================================================");
    }

    /**
     * Executor Service dedicado respaldado por Virtual Threads por tarea.
     */
    @Bean(name = "isoVirtualTaskExecutor")
    public AsyncTaskExecutor isoVirtualTaskExecutor() {
        ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        return new TaskExecutorAdapter(virtualExecutor);
    }
}
