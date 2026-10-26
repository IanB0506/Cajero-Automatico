package com.banco.recolector.config;

import com.banco.recolector.service.RecolectorTransaccionalService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.ip.dsl.Tcp;
import org.springframework.integration.ip.tcp.connection.TcpNetServerConnectionFactory;
import org.springframework.integration.ip.tcp.serializer.ByteArrayLengthHeaderSerializer;

/**
 * Servidor TCP ISO 8583 con Spring Integration IP.
 * <ul>
 *   <li>Enmarcado bancario: prefijo de longitud de 2 bytes (unsigned short) en solicitud y respuesta.</li>
 *   <li>Cada conexión de terminal se atiende en su propio Virtual Thread (Project Loom).</li>
 *   <li>Gateway de entrada: cada trama recibida se responde por la misma conexión (keep-alive).</li>
 * </ul>
 */
@Configuration
public class TcpIntegrationConfig {

    @Bean
    public TcpNetServerConnectionFactory isoServerConnectionFactory(
            TcpServerProperties props,
            @Qualifier("isoVirtualTaskExecutor") AsyncTaskExecutor virtualThreads) {
        ByteArrayLengthHeaderSerializer enmarcado =
                new ByteArrayLengthHeaderSerializer(ByteArrayLengthHeaderSerializer.HEADER_SIZE_UNSIGNED_SHORT);
        enmarcado.setMaxMessageSize(props.maxFrameBytes());

        TcpNetServerConnectionFactory factory = new TcpNetServerConnectionFactory(props.port());
        factory.setSerializer(enmarcado);
        factory.setDeserializer(enmarcado);
        factory.setTaskExecutor(virtualThreads);
        factory.setSoTimeout(props.timeoutMs());
        factory.setSoTcpNoDelay(true); // sin algoritmo de Nagle: menor latencia por transacción
        factory.setBacklog(props.backlog());
        return factory;
    }

    @Bean
    public IntegrationFlow isoTcpInboundFlow(TcpNetServerConnectionFactory isoServerConnectionFactory,
                                             RecolectorTransaccionalService recolector) {
        return IntegrationFlow.from(Tcp.inboundGateway(isoServerConnectionFactory))
                .handle(byte[].class, (trama, headers) -> recolector.procesarTrama(trama))
                .get();
    }
}
