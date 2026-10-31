import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulador de terminal ISO 8583 para RED NEXUS.
 *
 * <p>Arma una trama (0200 retiro, 0100 consulta, 0420 reversa u 0800 eco), la envía al Recolector por TCP con
 * el prefijo de 2 bytes y muestra la respuesta campo por campo. No tiene dependencias:
 * {@code java SimuladorIso.java [operación] [opciones]} con Java 21 o superior.
 */
public class SimuladorIso {

    private static final String CABECERA = "ISO015000050";

    /** Cuentas de demostración de init_sqlserver.sql: número de cuenta -> tarjeta (PAN). */
    private static final Map<String, String> TARJETAS = Map.of(
            "1001-2002-3001", "4532000000000001",
            "1001-2002-3002", "5412000000000002",
            "1001-2002-3003", "4000000000000003",
            "1001-2002-3004", "4000000000000004",
            "1001-2002-3005", "4000000000000005");

    /** Formato de cada campo (como en j8583.xml): mayor que 0 = largo fijo, -2 = LLVAR, -3 = LLLVAR. */
    private static final Map<Integer, Integer> FORMATO = Map.ofEntries(
            Map.entry(2, -2), Map.entry(3, 6), Map.entry(4, 12), Map.entry(7, 10), Map.entry(11, 6),
            Map.entry(12, 6), Map.entry(13, 4), Map.entry(14, 4), Map.entry(22, 3), Map.entry(25, 2),
            Map.entry(37, 12), Map.entry(38, 6), Map.entry(39, 2), Map.entry(41, 8), Map.entry(42, 15),
            Map.entry(49, 3), Map.entry(52, 16), Map.entry(54, -3), Map.entry(70, 3), Map.entry(90, 42),
            Map.entry(102, -2), Map.entry(103, -2));

    private static final Map<Integer, String> NOMBRES = Map.ofEntries(
            Map.entry(2, "Tarjeta (PAN)"), Map.entry(3, "Codigo de proceso"), Map.entry(4, "Monto"),
            Map.entry(7, "Fecha y hora"), Map.entry(11, "STAN"), Map.entry(12, "Hora local"),
            Map.entry(13, "Fecha local"), Map.entry(37, "RRN"), Map.entry(38, "Autorizacion"),
            Map.entry(39, "Codigo de respuesta"), Map.entry(41, "Terminal"), Map.entry(42, "Comercio"),
            Map.entry(49, "Moneda"), Map.entry(52, "PIN block"), Map.entry(54, "Saldos"),
            Map.entry(70, "Gestion de red"), Map.entry(90, "Datos originales"), Map.entry(102, "Cuenta"),
            Map.entry(103, "Cuenta destino"));

    private static final Map<String, String> CODIGOS = Map.ofEntries(
            Map.entry("00", "APROBADA"), Map.entry("05", "TARJETA BLOQUEADA"),
            Map.entry("12", "TRANSACCION INVALIDA"), Map.entry("13", "MONTO INVALIDO"),
            Map.entry("14", "CUENTA O TARJETA INEXISTENTE"), Map.entry("25", "TRANSACCION ORIGINAL NO ENCONTRADA"),
            Map.entry("30", "ERROR DE FORMATO"), Map.entry("51", "FONDOS INSUFICIENTES"),
            Map.entry("54", "TARJETA EXPIRADA"), Map.entry("55", "PIN INCORRECTO"),
            Map.entry("61", "EXCEDE EL LIMITE DIARIO"), Map.entry("68", "TIEMPO DE ESPERA AGOTADO"),
            Map.entry("75", "INTENTOS DE PIN EXCEDIDOS"), Map.entry("91", "BANCO NO DISPONIBLE"),
            Map.entry("96", "ERROR DEL SISTEMA"));

    /** Tipo de mensaje que arma cada operación y los campos que lleva. */
    private record Operacion(String nombre, int mti, String codigoProceso, List<Integer> campos) {}

    private static final Map<String, Operacion> OPERACIONES = Map.of(
            "retiro", new Operacion("RETIRO", 0x0200, "010000", List.of(2, 3, 4, 7, 11, 12, 13, 37, 41, 49, 52, 102)),
            "consulta", new Operacion("CONSULTA DE SALDO", 0x0100, "300000", List.of(2, 3, 4, 7, 11, 12, 13, 37, 41, 49, 52, 102)),
            "reversa", new Operacion("REVERSA", 0x0420, "010000", List.of(2, 3, 4, 7, 11, 12, 13, 37, 41, 49, 102)),
            "eco", new Operacion("ECHO TEST", 0x0800, null, List.of(7, 11, 12, 13, 70)));

    private record Opciones(String operacion, String host, int puerto, String cuenta, String tarjeta, BigDecimal monto,
                            String pin, String rrn, String stan, String terminal, int repetir) {}

    public static void main(String[] args) {
        Opciones opciones;
        try {
            opciones = leerOpciones(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Use --ayuda para ver las opciones.");
            System.exit(2);
            return;
        }
        if (opciones == null) {
            System.out.println(AYUDA);
            return;
        }
        Operacion op = OPERACIONES.get(opciones.operacion());
        Map<Integer, String> campos = armarCampos(op, opciones);
        String trama = CABECERA + String.format("%04x", op.mti()) + bitmap(campos.keySet()) + String.join("", campos.values());

        System.out.println("RED NEXUS - Simulador ISO 8583 por TCP");
        System.out.printf("Destino: %s:%d%n%n", opciones.host(), opciones.puerto());
        System.out.printf(">> %04x  %s%s%n", op.mti(), op.nombre(), descripcion(op, opciones));
        System.out.printf("   Trama enviada (%d bytes, mas 2 bytes de longitud):%n   %s%n", trama.length(), trama);
        imprimirCampos(campos);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(opciones.host(), opciones.puerto()), 5000);
            socket.setSoTimeout(30000);
            socket.setTcpNoDelay(true);
            DataOutputStream salida = new DataOutputStream(socket.getOutputStream());
            DataInputStream entrada = new DataInputStream(socket.getInputStream());
            byte[] bytes = trama.getBytes(StandardCharsets.US_ASCII);
            for (int envio = 1; envio <= opciones.repetir(); envio++) {
                long inicio = System.nanoTime();
                salida.writeShort(bytes.length);
                salida.write(bytes);
                salida.flush();
                byte[] respuesta = new byte[entrada.readUnsignedShort()];
                entrada.readFully(respuesta);
                long milisegundos = (System.nanoTime() - inicio) / 1_000_000;
                if (opciones.repetir() > 1) {
                    System.out.printf("%n-- Envio %d de %d (misma trama: mismo RRN y STAN)%n", envio, opciones.repetir());
                }
                imprimirRespuesta(new String(respuesta, StandardCharsets.US_ASCII), milisegundos);
            }
        } catch (IOException e) {
            System.err.printf("%nNo se pudo comunicar con %s:%d: %s%n", opciones.host(), opciones.puerto(), e.getMessage());
            System.err.println("Verifique que la aplicacion este arriba (docker compose ps).");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------------------------------ armado de la trama

    private static Map<Integer, String> armarCampos(Operacion op, Opciones o) {
        LocalDateTime ahora = LocalDateTime.now();
        Map<Integer, String> campos = new TreeMap<>();
        for (int campo : op.campos()) {
            String valor = switch (campo) {
                case 2 -> o.tarjeta();
                case 3 -> op.codigoProceso();
                case 4 -> String.format("%012d", o.monto().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
                case 7 -> ahora.format(DateTimeFormatter.ofPattern("MMddHHmmss"));
                case 11 -> o.stan();
                case 12 -> ahora.format(DateTimeFormatter.ofPattern("HHmmss"));
                case 13 -> ahora.format(DateTimeFormatter.ofPattern("MMdd"));
                case 37 -> o.rrn();
                case 41 -> o.terminal();
                case 49 -> "320";
                case 52 -> pinBlock(o.pin(), o.tarjeta());
                case 70 -> "301";
                case 102 -> o.cuenta();
                default -> throw new IllegalStateException("Campo no soportado: " + campo);
            };
            campos.put(campo, codificar(campo, valor));
        }
        return campos;
    }

    private static String codificar(int campo, String valor) {
        int formato = FORMATO.get(campo);
        return switch (formato) {
            case -2 -> String.format("%02d", valor.length()) + valor;
            case -3 -> String.format("%03d", valor.length()) + valor;
            default -> {
                if (valor.length() > formato) {
                    throw new IllegalArgumentException("El DE " + campo + " admite " + formato + " caracteres: " + valor);
                }
                yield String.format("%-" + formato + "s", valor);
            }
        };
    }

    /** Bitmap primario (y secundario si hay campos mayores que 64) en hexadecimal. */
    private static String bitmap(Iterable<Integer> campos) {
        boolean secundario = false;
        for (int campo : campos) {
            secundario |= campo > 64;
        }
        byte[] bits = new byte[secundario ? 16 : 8];
        if (secundario) {
            bits[0] |= (byte) 0x80;
        }
        for (int campo : campos) {
            bits[(campo - 1) / 8] |= (byte) (0x80 >>> ((campo - 1) % 8));
        }
        return HexFormat.of().withUpperCase().formatHex(bits);
    }

    /** PIN block ISO 9564 formato 0: (0 | largo | PIN | F...) XOR (0000 | 12 dígitos del PAN sin el verificador). */
    private static String pinBlock(String pin, String pan) {
        String bloquePin = ("0" + Integer.toHexString(pin.length()).toUpperCase() + pin + "F".repeat(14)).substring(0, 16);
        String bloquePan = "0000" + pan.substring(pan.length() - 13, pan.length() - 1);
        StringBuilder resultado = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            int xor = Character.digit(bloquePin.charAt(i), 16) ^ Character.digit(bloquePan.charAt(i), 16);
            resultado.append(Character.toUpperCase(Character.forDigit(xor, 16)));
        }
        return resultado.toString();
    }

    // ------------------------------------------------------------------------------------------ lectura de la respuesta

    private static void imprimirRespuesta(String respuesta, long milisegundos) {
        int pos = respuesta.startsWith("ISO") ? CABECERA.length() : 0;
        String mti = respuesta.substring(pos, pos + 4);
        pos += 4;
        boolean[] presentes = new boolean[129];
        int largoBitmap = Character.digit(respuesta.charAt(pos), 16) >= 8 ? 32 : 16;
        String bitmap = respuesta.substring(pos, pos + largoBitmap);
        for (int i = 0; i < bitmap.length(); i++) {
            int nibble = Character.digit(bitmap.charAt(i), 16);
            for (int b = 0; b < 4; b++) {
                presentes[i * 4 + b + 1] = (nibble & (8 >> b)) != 0;
            }
        }
        pos += largoBitmap;

        Map<Integer, String> campos = new LinkedHashMap<>();
        for (int campo = 2; campo <= 128; campo++) {
            if (!presentes[campo]) {
                continue;
            }
            Integer formato = FORMATO.get(campo);
            if (formato == null) {
                System.out.printf("   (campo %d sin formato conocido; se detiene la lectura)%n", campo);
                break;
            }
            int largo = formato > 0 ? formato : Integer.parseInt(respuesta.substring(pos, pos - formato));
            if (formato < 0) {
                pos += -formato;
            }
            campos.put(campo, respuesta.substring(pos, pos + largo));
            pos += largo;
        }

        System.out.printf("%n<< %s recibida en %d ms (%d bytes):%n   %s%n", mti, milisegundos, respuesta.length(), respuesta);
        String codigo = campos.getOrDefault(39, "--");
        System.out.printf("   DE 39  %-20s %s  %s%n", NOMBRES.get(39), codigo, CODIGOS.getOrDefault(codigo, ""));
        if (campos.containsKey(38)) {
            System.out.printf("   DE 38  %-20s %s%n", NOMBRES.get(38), campos.get(38));
        }
        if (campos.containsKey(54)) {
            System.out.printf("   DE 54  %-20s %s%n", NOMBRES.get(54), describirSaldos(campos.get(54)));
        }
        if (campos.containsKey(37)) {
            System.out.printf("   DE 37  %-20s %s%n", NOMBRES.get(37), campos.get(37));
        }
    }

    private static String describirSaldos(String valor) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i + 20 <= valor.length(); i += 20) {
            String tipo = "01".equals(valor.substring(i + 2, i + 4)) ? "contable" : "disponible";
            BigDecimal monto = new BigDecimal(valor.substring(i + 8, i + 20)).movePointLeft(2);
            if (valor.charAt(i + 7) == 'D') {
                monto = monto.negate();
            }
            texto.append(texto.isEmpty() ? "" : " - ").append(tipo).append(' ').append(quetzales(monto));
        }
        return texto.toString();
    }

    // ------------------------------------------------------------------------------------------ presentación

    private static void imprimirCampos(Map<Integer, String> campos) {
        System.out.println("   Campos enviados:");
        for (var campo : campos.entrySet()) {
            int numero = campo.getKey();
            String valor = FORMATO.get(numero) < 0 ? campo.getValue().substring(-FORMATO.get(numero)) : campo.getValue().trim();
            if (numero == 2) {
                valor = valor.substring(0, 6) + "*".repeat(valor.length() - 10) + valor.substring(valor.length() - 4);
            }
            String detalle = switch (numero) {
                case 3 -> "  (" + switch (valor.substring(0, 2)) {
                    case "01" -> "retiro";
                    case "30" -> "consulta";
                    default -> "operacion " + valor.substring(0, 2);
                } + ")";
                case 4 -> "  (" + quetzales(new BigDecimal(valor).movePointLeft(2)) + ")";
                case 49 -> "  (quetzales)";
                case 70 -> "  (echo test)";
                default -> "";
            };
            System.out.printf("     DE %-3d %-20s %s%s%n", numero, NOMBRES.get(numero), valor, detalle);
        }
    }

    private static String descripcion(Operacion op, Opciones o) {
        if (op.mti() == 0x0800) {
            return "";
        }
        String monto = op.mti() == 0x0100 ? "" : "  " + quetzales(o.monto());
        String tarjeta = o.tarjeta().substring(0, 4) + " **** " + o.tarjeta().substring(o.tarjeta().length() - 4);
        return monto + "  cuenta " + o.cuenta() + "  tarjeta " + tarjeta;
    }

    private static String quetzales(BigDecimal monto) {
        return String.format(Locale.US, "Q%,.2f", monto);
    }

    // ------------------------------------------------------------------------------------------ opciones

    private static Opciones leerOpciones(String[] args) {
        Map<String, String> valores = new LinkedHashMap<>();
        String operacion = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--ayuda".equals(arg) || "-h".equals(arg) || "--help".equals(arg)) {
                return null;
            }
            if (arg.startsWith("--")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Falta el valor de " + arg);
                }
                valores.put(arg.substring(2), args[++i]);
            } else if (operacion == null) {
                operacion = arg.toLowerCase(Locale.ROOT);
            } else {
                throw new IllegalArgumentException("Argumento inesperado: " + arg);
            }
        }
        if (operacion == null) {
            operacion = "retiro";
        }
        if (!OPERACIONES.containsKey(operacion)) {
            throw new IllegalArgumentException("Operacion desconocida: " + operacion + " (use retiro, consulta, reversa o eco)");
        }
        for (String clave : valores.keySet()) {
            if (!List.of("host", "puerto", "cuenta", "tarjeta", "monto", "pin", "rrn", "stan", "terminal", "repetir").contains(clave)) {
                throw new IllegalArgumentException("Opcion desconocida: --" + clave);
            }
        }

        String cuenta = valores.getOrDefault("cuenta", "1001-2002-3001");
        String tarjeta = valores.getOrDefault("tarjeta", TARJETAS.get(cuenta));
        if (tarjeta == null || !tarjeta.matches("\\d{13,19}")) {
            throw new IllegalArgumentException("Indique la tarjeta de la cuenta " + cuenta + " con --tarjeta (13 a 19 digitos)");
        }
        String pin = valores.getOrDefault("pin", "1234");
        if (!pin.matches("\\d{4,12}")) {
            throw new IllegalArgumentException("El PIN debe tener entre 4 y 12 digitos");
        }
        BigDecimal monto = new BigDecimal(valores.getOrDefault("monto", "consulta".equals(operacion) ? "0" : "100"));

        String rrn = valores.get("rrn");
        if ("reversa".equals(operacion) && rrn == null) {
            throw new IllegalArgumentException("La reversa necesita el RRN del retiro original: --rrn <RRN>");
        }
        boolean rrnIndicado = rrn != null;
        if (rrn == null) {
            rrn = String.format("%012d", System.currentTimeMillis() % 1_000_000_000_000L);
        } else if (rrn.length() < 12) {
            rrn = "0".repeat(12 - rrn.length()) + rrn;
        } else if (rrn.length() > 12) {
            throw new IllegalArgumentException("El RRN admite 12 caracteres");
        }
        // Con un RRN indicado (y que no sea reversa) el STAN se deriva del RRN: repetir el mismo comando
        // es una retransmisión de la misma transacción. La reversa siempre lleva un STAN propio.
        String stan = valores.get("stan");
        if (stan == null) {
            stan = rrnIndicado && !"reversa".equals(operacion) && rrn.substring(6).matches("\\d{6}")
                    ? rrn.substring(6)
                    : String.format("%06d", ThreadLocalRandom.current().nextInt(1, 1_000_000));
        }
        if (!stan.matches("\\d{1,6}")) {
            throw new IllegalArgumentException("El STAN debe tener hasta 6 digitos");
        }
        stan = String.format("%06d", Integer.parseInt(stan));

        String terminal = valores.getOrDefault("terminal", "SIMUL001");
        int repetir = Integer.parseInt(valores.getOrDefault("repetir", "1"));
        if (repetir < 1 || repetir > 10) {
            throw new IllegalArgumentException("--repetir admite de 1 a 10 envios");
        }
        return new Opciones(operacion, valores.getOrDefault("host", "localhost"), Integer.parseInt(valores.getOrDefault("puerto", "15000")),
                cuenta, tarjeta, monto, pin, rrn, stan, terminal, repetir);
    }

    private static final String AYUDA = """
            RED NEXUS - Simulador ISO 8583 por TCP

            Uso: java SimuladorIso.java [operacion] [opciones]
                 docker compose --profile demo run --rm simulador [operacion] [opciones]

            Operaciones:
              retiro     0200 retiro (DE 3 = 010000), con PIN block en el DE 52   (por defecto)
              consulta   0100 consulta de saldo (DE 3 = 300000), con PIN
              reversa    0420 reversa del retiro con el RRN indicado en --rrn
              eco        0800 echo test de red (DE 70 = 301)

            Opciones:
              --cuenta   numero de cuenta (DE 102)          por defecto 1001-2002-3001
              --tarjeta  tarjeta (DE 2); para las cuentas de demostracion se toma sola
              --monto    monto en quetzales (DE 4)          por defecto 100
              --pin      PIN de la tarjeta                  por defecto 1234
              --rrn      RRN (DE 37); repetir el comando con el mismo RRN es una retransmision
              --stan     STAN (DE 11)
              --repetir  envia la misma trama N veces por la misma conexion (1 a 10)
              --host     servidor del Recolector            por defecto localhost
              --puerto   puerto TCP del Recolector          por defecto 15000

            Ejemplos:
              retiro --monto 100 --rrn 100000000001
              retiro --monto 100 --rrn 100000000001 --repetir 2     (retransmision: un solo debito)
              reversa --monto 100 --rrn 100000000001
              retiro --pin 0000
              consulta --cuenta 1001-2002-3002 --pin 4321
              eco
            """;
}
