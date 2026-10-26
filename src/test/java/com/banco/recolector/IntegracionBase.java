package com.banco.recolector;

import com.banco.recolector.corebancario.entity.CuentaEntity;
import com.banco.recolector.corebancario.repository.CuentaRepository;
import com.banco.recolector.corebancario.repository.TransaccionRepository;
import com.banco.recolector.corebancario.service.PinHasher;
import com.banco.recolector.iso.IsoMessageService;
import com.solab.iso8583.IsoMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Base de las pruebas de integración: todas comparten un único contexto de Spring con el servidor HTTP
 * y el socket TCP ISO 8583 reales sobre una base H2 nueva (perfil "test").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
public abstract class IntegracionBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong SECUENCIA = new AtomicLong();

    @Autowired
    protected CuentaRepository cuentaRepository;

    @Autowired
    protected TransaccionRepository transaccionRepository;

    @Autowired
    protected IsoMessageService isoService;

    @Value("${recolector.tcp.port}")
    protected int tcpPort;

    @Value("${server.port}")
    protected int httpPort;

    @Value("${recolector.seguridad.api-key}")
    protected String apiKey;

    @Value("${recolector.seguridad.admin-usuario}")
    protected String adminUsuario;

    @Value("${recolector.seguridad.admin-clave}")
    protected String adminClave;

    /** Cuenta de prueba propia (número y PAN únicos) para no depender del estado de otras pruebas. */
    protected CuentaEntity crearCuenta(String saldo, String limiteDiario, String pin) {
        long n = SECUENCIA.incrementAndGet();
        String numero = String.format("T-%08d", n);
        String pan = String.format("4999%012d", n);
        return cuentaRepository.save(new CuentaEntity(numero, pan, "CLIENTE PRUEBA " + n,
                new BigDecimal(saldo), new BigDecimal(limiteDiario), "ACTIVE", PinHasher.hash(numero, pin), "BANCO PRUEBA"));
    }

    protected static String nuevoStan() {
        return String.format("%06d", SECUENCIA.incrementAndGet() % 1_000_000);
    }

    protected static String nuevoRrn() {
        return String.format("9%011d", SECUENCIA.incrementAndGet());
    }

    protected BigDecimal saldo(String numeroCuenta) {
        return cuentaRepository.findByNumeroCuenta(numeroCuenta).orElseThrow().getSaldoDisponible();
    }

    protected CuentaEntity cuenta(String numeroCuenta) {
        return cuentaRepository.findByNumeroCuenta(numeroCuenta).orElseThrow();
    }

    /** Envía una trama con el enmarcado bancario (prefijo de 2 bytes) y devuelve la respuesta parseada. */
    protected IsoMessage enviarTcp(IsoMessage request) throws Exception {
        return isoService.parse(enviarTcpBytes(isoService.encode(request)));
    }

    protected byte[] enviarTcpBytes(byte[] trama) throws Exception {
        try (Socket socket = new Socket("localhost", tcpPort)) {
            socket.setSoTimeout(10_000);
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            dos.writeShort(trama.length);
            dos.write(trama);
            dos.flush();
            byte[] respuesta = new byte[dis.readUnsignedShort()];
            dis.readFully(respuesta);
            return respuesta;
        }
    }

    // ------------------------------------------------------------------ HTTP

    public record Respuesta(int status, String cuerpo) {
        public JsonNode json() {
            return JSON.readTree(cuerpo);
        }
    }

    protected Respuesta llamar(HttpMethod metodo, String ruta, Object cuerpo, Consumer<HttpHeaders> cabeceras) {
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + httpPort)
                .method(metodo).uri(ruta).headers(cabeceras);
        if (cuerpo != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(cuerpo);
        }
        return spec.exchange((req, res) -> new Respuesta(res.getStatusCode().value(), new String(res.getBody().readAllBytes())));
    }

    protected Respuesta llamar(HttpMethod metodo, String ruta, Object cuerpo) {
        return llamar(metodo, ruta, cuerpo, h -> {});
    }

    protected Respuesta comoAdmin(HttpMethod metodo, String ruta, Object cuerpo) {
        return llamar(metodo, ruta, cuerpo, h -> h.setBasicAuth(adminUsuario, adminClave));
    }

    protected Respuesta comoServicio(HttpMethod metodo, String ruta, Object cuerpo) {
        return llamar(metodo, ruta, cuerpo, h -> h.set("X-API-Key", apiKey));
    }
}
