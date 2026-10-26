package com.banco.recolector.iso;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.IsoValue;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Servicio de parseo, codificación y extracción de Data Elements para ISO 8583.
 * Basado en la librería j8583 de alto rendimiento con Virtual Threads.
 */
@Service
public class IsoMessageService {

    private static final Logger log = LoggerFactory.getLogger(IsoMessageService.class);

    private MessageFactory<IsoMessage> messageFactory;

    @PostConstruct
    public void init() {
        try {
            log.info("Inicializando MessageFactory de j8583 desde 'j8583.xml'...");
            messageFactory = ConfigParser.createFromClasspathConfig("j8583.xml");
            messageFactory.setCharacterEncoding(StandardCharsets.US_ASCII.name());
            messageFactory.setAssignDate(true);
            log.info("MessageFactory j8583 cargado exitosamente.");
        } catch (IOException e) {
            log.warn("No se pudo cargar 'j8583.xml', inicializando MessageFactory por defecto: {}", e.getMessage());
            messageFactory = new MessageFactory<>();
            messageFactory.setCharacterEncoding(StandardCharsets.US_ASCII.name());
            messageFactory.setAssignDate(true);
        }
    }

    /**
     * Parsea un arreglo de bytes en un objeto IsoMessage.
     */
    public IsoMessage parse(byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("La trama ISO 8583 no puede ser nula o vacía");
        }
        int offset = 0;
        // Detección inteligente de encabezado ISO estándar (12 bytes, ej. ISO015000050 o ISO026000050)
        if (data.length >= 12 && data[0] == 'I' && data[1] == 'S' && data[2] == 'O') {
            offset = 12;
        }
        return messageFactory.parseMessage(data, offset);
    }

    /**
     * Parsea una cadena de texto (ASCII / String) en un IsoMessage.
     */
    public IsoMessage parse(String asciiData) throws Exception {
        if (asciiData == null || asciiData.isBlank()) {
            throw new IllegalArgumentException("La trama ISO 8583 no puede ser vacía");
        }
        byte[] bytes = asciiData.getBytes(StandardCharsets.US_ASCII);
        return parse(bytes);
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
     * Crea un mensaje de respuesta (MTI 0210, 0110, 0430, 0810) a partir de la solicitud.
     */
    public IsoMessage createResponse(IsoMessage request, String responseCode, String authCode) {
        IsoMessage response = messageFactory.createResponse(request);
        
        // Asignar Código de Respuesta (DE 39)
        if (responseCode != null) {
            response.setValue(39, responseCode, IsoType.ALPHA, 2);
        }
        
        // Asignar Código de Autorización (DE 38) si fue aprobada
        if (authCode != null && !authCode.isBlank()) {
            response.setValue(38, authCode, IsoType.ALPHA, 6);
        }
        
        // Garantizar que la hora/fecha de transmisión (DE 7, DE 12, DE 13) estén presentes
        LocalDateTime now = LocalDateTime.now();
        if (!response.hasField(12)) {
            response.setValue(12, now.format(DateTimeFormatter.ofPattern("HHmmss")), IsoType.TIME, 6);
        }
        if (!response.hasField(13)) {
            response.setValue(13, now.format(DateTimeFormatter.ofPattern("MMdd")), IsoType.DATE4, 4);
        }

        return response;
    }

    /**
     * Extrae un DTO normalizado con los campos más importantes de la trama.
     */
    public IsoTransactionDto extractTransaction(IsoMessage msg) {
        String mti = String.format("%04x", msg.getType());
        String pan = getFieldValue(msg, 2);
        String procCode = getFieldValue(msg, 3);
        BigDecimal amount = getAmountValue(msg);
        String stan = getFieldValue(msg, 11);
        String rrn = getFieldValue(msg, 37);
        String tid = getFieldValue(msg, 41);
        String mid = getFieldValue(msg, 42);
        String cuenta = getFieldValue(msg, 102);
        String rc = getFieldValue(msg, 39);
        String auth = getFieldValue(msg, 38);

        // Si DE 102 no viene, usar PAN o generar ID de cuenta
        if ((cuenta == null || cuenta.isBlank()) && pan != null) {
            cuenta = pan;
        }

        return new IsoTransactionDto(mti, pan, procCode, amount, stan, rrn, tid, mid, cuenta, rc, auth);
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
        IsoMessage msg = messageFactory.newMessage(0x0200);
        LocalDateTime now = LocalDateTime.now();

        if (pan != null) msg.setValue(2, pan, IsoType.LLVAR, 0);
        if (procCode != null) msg.setValue(3, procCode, IsoType.NUMERIC, 6);
        if (amount != null) msg.setValue(4, amount, IsoType.AMOUNT, 0);
        msg.setValue(7, now.format(DateTimeFormatter.ofPattern("MMddHHmmss")), IsoType.DATE10, 10);
        if (stan != null) msg.setValue(11, stan, IsoType.NUMERIC, 6);
        msg.setValue(12, now.format(DateTimeFormatter.ofPattern("HHmmss")), IsoType.TIME, 6);
        msg.setValue(13, now.format(DateTimeFormatter.ofPattern("MMdd")), IsoType.DATE4, 4);
        if (rrn != null) msg.setValue(37, rrn, IsoType.ALPHA, 12);
        if (terminalId != null) msg.setValue(41, terminalId, IsoType.ALPHA, 8);
        msg.setValue(49, "840", IsoType.NUMERIC, 3);
        if (cuentaOrigen != null) msg.setValue(102, cuentaOrigen, IsoType.LLVAR, 0);

        return msg;
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
     * Extrae el monto como BigDecimal (DE 4).
     */
    public BigDecimal getAmountValue(IsoMessage msg) {
        if (!msg.hasField(4)) return BigDecimal.ZERO;
        Object val = msg.getObjectValue(4);
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (val instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (Exception ignored) {}
        }
        return BigDecimal.ZERO;
    }

    public MessageFactory<IsoMessage> getMessageFactory() {
        return messageFactory;
    }
}
