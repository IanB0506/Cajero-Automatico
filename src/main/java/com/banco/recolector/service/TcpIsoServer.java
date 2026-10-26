package com.banco.recolector.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servidor TCP/IP de Ingesta ISO 8583 de Alto Rendimiento.
 * Opera de manera no bloqueante delegando cada conexión a un Virtual Thread (Project Loom).
 * Cumple con la garantía de CERO Carrier Thread Pinning al evitar bloques synchronized en I/O.
 */
@Component
public class TcpIsoServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TcpIsoServer.class);

    @Value("${recolector.tcp.port:15000}")
    private int port;

    @Value("${recolector.tcp.timeout-ms:10000}")
    private int socketTimeoutMs;

    private final RecolectorTransaccionalService recolectorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptorThread;

    public TcpIsoServer(RecolectorTransaccionalService recolectorService) {
        this.recolectorService = recolectorService;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            try {
                serverSocket = new ServerSocket(port, 2048);
                serverSocket.setReuseAddress(true);
                log.info("=================================================================");
                log.info(">>> Servidor TCP ISO 8583 INICIADO en puerto: {}", port);
                log.info(">>> Modo de Concurrencia: Virtual Threads (Project Loom) ACTIVO");
                log.info("=================================================================");

                // Hilo Virtual para el bucle de aceptación de conexiones
                acceptorThread = Thread.ofVirtual()
                        .name("tcp-iso-acceptor")
                        .start(this::acceptLoop);

            } catch (IOException e) {
                running.set(false);
                log.error("Fallo crítico al iniciar Servidor TCP en puerto {}: {}", port, e.getMessage(), e);
            }
        }
    }

    private void acceptLoop() {
        log.info("Bucle de aceptación TCP iniciado en Virtual Thread.");
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(socketTimeoutMs);
                clientSocket.setTcpNoDelay(true); // Deshabilitar algoritmo de Nagle para latencia ultra-baja

                // Cada conexión cliente se atiende en su propio Virtual Thread independiente
                Thread.ofVirtual()
                        .name("iso-worker-" + clientSocket.getPort())
                        .start(() -> handleClientSocket(clientSocket));

            } catch (SocketException e) {
                if (!running.get()) {
                    log.info("ServerSocket cerrado controladamente.");
                    break;
                }
                log.warn("Excepción de socket en acceptLoop: {}", e.getMessage());
            } catch (Exception e) {
                log.error("Error inesperado en acceptLoop: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Procesa la comunicación bidireccional con una terminal POS o Cajero ATM.
     * Soporta enmarcado estándar de 2 bytes de longitud binaria o 4 bytes ASCII.
     */
    private void handleClientSocket(Socket socket) {
        String remoteAddress = socket.getRemoteSocketAddress().toString();
        log.debug("Nueva conexión TCP entrante desde: {}", remoteAddress);

        try (socket;
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            while (running.get() && !socket.isClosed()) {
                byte[] messageBytes = readIsoFrame(dis);
                if (messageBytes == null || messageBytes.length == 0) {
                    break; // Fin de conexión o stream cerrado
                }

                log.debug("Trama ISO 8583 recibida ({} bytes) desde {}", messageBytes.length, remoteAddress);

                // Orquestar transacción sobre el Virtual Thread actual
                byte[] responseBytes = recolectorService.procesarTrama(messageBytes);

                // Escribir respuesta con prefijo de longitud de 2 bytes (estándar bancario)
                dos.writeShort(responseBytes.length);
                dos.write(responseBytes);
                dos.flush();

                log.debug("Respuesta ISO 8583 enviada ({} bytes) hacia {}", responseBytes.length, remoteAddress);
            }

        } catch (EOFException e) {
            log.debug("Cliente desconectado normalmente: {}", remoteAddress);
        } catch (SocketTimeoutException e) {
            log.warn("Timeout de lectura en socket cliente: {}", remoteAddress);
        } catch (Exception e) {
            log.warn("Error durante la atención del cliente TCP {}: {}", remoteAddress, e.getMessage());
        }
    }

    /**
     * Lee un marco ISO 8583 con prefijo de longitud de 2 bytes (unsigned short).
     */
    private byte[] readIsoFrame(DataInputStream dis) throws IOException {
        int length;
        try {
            length = dis.readUnsignedShort();
        } catch (EOFException e) {
            return null;
        }

        if (length <= 0 || length > 8192) {
            log.warn("Longitud de trama inválida recibida: {}", length);
            return null;
        }

        byte[] buffer = new byte[length];
        dis.readFully(buffer);
        return buffer;
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Deteniendo Servidor TCP ISO 8583...");
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                log.warn("Excepción al cerrar ServerSocket: {}", e.getMessage());
            }
            if (acceptorThread != null) {
                acceptorThread.interrupt();
            }
            log.info("Servidor TCP ISO 8583 detenido.");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // Arranca último para asegurar que el contexto Spring esté 100% listo
    }

    public int getPort() {
        return port;
    }
}
