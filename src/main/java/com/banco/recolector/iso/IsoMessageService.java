package com.banco.recolector.iso;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.IsoValue;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * Servicio de parseo, codificación y extracción de Data Elements para ISO 8583 (j8583).
 * Es inmutable tras su inicialización y seguro para uso concurrente desde Virtual Threads.
 */
@Service
public class IsoMessageService {

    private static final Logger log = LoggerFactory.getLogger(IsoMessageService.class);

    private static final int LONGITUD_HEADER_ISO = 12;
    private static final Set<Integer> MTI_SOLICITUD = Set.of(0x0100, 0x0200, 0x0420, 0x0800);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("MMdd");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("MMddHHmmss");

    private final String monedaPorDefecto;
    private GuiasMessageFactory messageFactory;

    public IsoMessageService() {
        this("320");
    }

    @Inject
    public IsoMessageService(@Value("${recolector.iso.moneda:320}") String monedaPorDefecto) {
        this.monedaPorDefecto = monedaPorDefecto;
    }

    /** MessageFactory que expone los campos declarados en cada guía de j8583.xml. */
    static final class GuiasMessageFactory extends MessageFactory<IsoMessage> {
        Set<Integer> camposDeGuia(int tipo) {
            Map<Integer, ?> guia = parseMap.get(tipo);
            return guia != null ? guia.keySet() : Set.of();
        }
    }

    @PostConstruct
    public void init() {
        GuiasMessageFactory factory = new GuiasMessageFactory();
        try {
            ConfigParser.configureFromClasspathConfig(factory, "j8583.xml");
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar la configuración ISO 8583 'j8583.xml'", e);
        }
        factory.setCharacterEncoding(StandardCharsets.US_ASCII.name());
        factory.setAssignDate(true);
        this.messageFactory = factory;
        log.info("MessageFactory j8583 cargado desde 'j8583.xml'.");
    }

    /**
     * Parsea un arreglo de bytes en un objeto IsoMessage (con o sin header "ISO" de 12 bytes).
     */
    public IsoMessage parse(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("La trama ISO 8583 no puede ser nula o vacía");
        }
        return messageFactory.parseMessage(data, tieneHeaderIso(data) ? LONGITUD_HEADER_ISO : 0);
    }

    /**
     * Parsea una cadena de texto (ASCII / String) en un IsoMessage.
     */
    public IsoMessage parse(String asciiData) throws Exception {
        if (asciiData == null || asciiData.isBlank()) {
            throw new IllegalArgumentException("La trama ISO 8583 no puede ser vacía");
        }
        return parse(asciiData.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Serializa un IsoMessage a su representación en bytes.
     */
    public byte[] encode(IsoMessage message) {
        return message.writeData();
    }

    /**
     * Serializa un IsoMessage a String ASCII.
     */
    public String encodeToString(IsoMessage message) {
        return new String(encode(message), StandardCharsets.US_ASCII);
    }

    /**
     * Crea un mensaje de respuesta (MTI 0110, 0210, 0430, 0810) a partir de la solicitud.
     * Solo conserva los campos declarados en la guía de la respuesta (nunca el PIN block DE 52).
     */
    public IsoMessage createResponse(IsoMessage request, String responseCode, String authCode) {
        IsoMessage response = messageFactory.createResponse(request);

        if (responseCode != null) {
            response.setValue(39, responseCode, IsoType.ALPHA, 2);
        }
        if (authCode != null && !authCode.isBlank()) {
            response.setValue(38, authCode, IsoType.ALPHA, 6);
        }

        LocalDateTime now = LocalDateTime.now();
        if (!response.hasField(12)) {
            response.setValue(12, now.format(HORA), IsoType.TIME, 6);
        }
        if (!response.hasField(13)) {
            response.setValue(13, now.format(FECHA), IsoType.DATE4, 4);
        }

        // El PIN block nunca viaja de regreso; el resto se limita a la guía de la respuesta en j8583.xml
        response.removeFields(52);
        Set<Integer> permitidos = messageFactory.camposDeGuia(response.getType());
        if (!permitidos.isEmpty()) {
            for (int campo = 2; campo <= 128; campo++) {
                if (response.hasField(campo) && !permitidos.contains(campo)) {
                    response.removeFields(campo);
                }
            }
        }
        return response;
    }

    /**
     * Construye una respuesta con DE 39 = 30 (Error de Formato) para una trama que no se pudo parsear.
     * Si el MTI es legible se responde con su MTI de respuesta; si no, con 0210.
     */
    public IsoMessage crearRespuestaErrorFormato(byte[] trama) {
        int offset = trama != null && tieneHeaderIso(trama) ? LONGITUD_HEADER_ISO : 0;
        int tipoRespuesta = 0x0210;
        if (trama != null && trama.length >= offset + 4) {
            String mti = new String(trama, offset, 4, StandardCharsets.US_ASCII);
            if (mti.matches("\\d{4}") && MTI_SOLICITUD.contains(Integer.parseInt(mti, 16))) {
                tipoRespuesta = Integer.parseInt(mti, 16) + 0x10;
            }
        }
        IsoMessage respuesta = messageFactory.newMessage(tipoRespuesta);
        LocalDateTime now = LocalDateTime.now();
        respuesta.setValue(12, now.format(HORA), IsoType.TIME, 6);
        respuesta.setValue(13, now.format(FECHA), IsoType.DATE4, 4);
        respuesta.setValue(39, IsoResponseCode.ERROR_FORMATO, IsoType.ALPHA, 2);
        return respuesta;
    }

    /**
     * Extrae un DTO normalizado con los campos más importantes de la trama.
     */
    public IsoTransactionDto extractTransaction(IsoMessage msg) {
        String pan = getFieldValue(msg, 2);
        String cuenta = getFieldValue(msg, 102);
        // Si DE 102 no viene, la cuenta se localiza por el PAN (DE 2)
        if ((cuenta == null || cuenta.isBlank()) && pan != null) {
            cuenta = pan;
        }
        return new IsoTransactionDto(
                tipoMti(msg),
                pan,
                getFieldValue(msg, 3),
                getAmountValue(msg),
                getFieldValue(msg, 11),
                getFieldValue(msg, 37),
                getFieldValue(msg, 41),
                getFieldValue(msg, 42),
                cuenta,
                getFieldValue(msg, 103),
                getFieldValue(msg, 49),
                getFieldValue(msg, 52),
                getFieldValue(msg, 39),
                getFieldValue(msg, 38)
        );
    }

    /**
     * Crea una solicitud financiera MTI 0200 programáticamente para pruebas o simulación.
     */
    public IsoMessage createFinancialRequest(
            String pan,
            String procCode,
            BigDecimal amount,
            String stan,
            String rrn,
            String terminalId,
            String cuentaOrigen
    ) {
        return crearSolicitud(0x0200, pan, procCode, amount, stan, rrn, terminalId, cuentaOrigen);
    }

    /**
     * Crea una solicitud ISO 8583 (0100, 0200 o 0420) con los Data Elements comunes.
     */
    public IsoMessage crearSolicitud(
            int mti,
            String pan,
            String procCode,
            BigDecimal amount,
            String stan,
            String rrn,
            String terminalId,
            String cuentaOrigen
    ) {
        IsoMessage msg = messageFactory.newMessage(mti);
        LocalDateTime now = LocalDateTime.now();

        if (pan != null) msg.setValue(2, pan, IsoType.LLVAR, 0);
        if (procCode != null) msg.setValue(3, procCode, IsoType.NUMERIC, 6);
        if (amount != null) msg.setValue(4, amount, IsoType.AMOUNT, 0);
        msg.setValue(7, now.format(FECHA_HORA), IsoType.DATE10, 10);
        if (stan != null) msg.setValue(11, stan, IsoType.NUMERIC, 6);
        msg.setValue(12, now.format(HORA), IsoType.TIME, 6);
        msg.setValue(13, now.format(FECHA), IsoType.DATE4, 4);
        if (rrn != null) msg.setValue(37, rrn, IsoType.ALPHA, 12);
        if (terminalId != null) msg.setValue(41, terminalId, IsoType.ALPHA, 8);
        msg.setValue(49, monedaPorDefecto, IsoType.NUMERIC, 3);
        if (cuentaOrigen != null) msg.setValue(102, cuentaOrigen, IsoType.LLVAR, 0);

        return msg;
    }

    /**
     * Agrega el PIN Block ISO-0 (DE 52) calculado a partir del PIN y el PAN.
     */
    public void agregarPinBlock(IsoMessage msg, String pin, String pan) {
        msg.setValue(52, PinBlock.formato0(pin, pan), IsoType.ALPHA, 16);
    }

    /**
     * Obtiene el valor textual de un campo específico.
     */
    public String getFieldValue(IsoMessage msg, int fieldNumber) {
        if (!msg.hasField(fieldNumber)) return null;
        IsoValue<?> val = msg.getField(fieldNumber);
        return val != null ? String.valueOf(val.getValue()) : null;
    }

    /**
     * Extrae el monto como BigDecimal (DE 4); cero si no viene o no es numérico.
     */
    public BigDecimal getAmountValue(IsoMessage msg) {
        if (!msg.hasField(4)) return BigDecimal.ZERO;
        Object val = msg.getObjectValue(4);
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return new BigDecimal(n.toString());
        if (val instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                // Monto no numérico: se trata como cero y el Core lo rechaza con DE 39 = 13
            }
        }
        return BigDecimal.ZERO;
    }

    /** MTI en formato de 4 dígitos (ej. "0200"). */
    public String tipoMti(IsoMessage msg) {
        return String.format("%04x", msg.getType());
    }

    public String getMonedaPorDefecto() {
        return monedaPorDefecto;
    }

    public MessageFactory<IsoMessage> getMessageFactory() {
        return messageFactory;
    }

    private static boolean tieneHeaderIso(byte[] data) {
        return data.length >= LONGITUD_HEADER_ISO && data[0] == 'I' && data[1] == 'S' && data[2] == 'O';
    }
}
