import com.hs.bridge.MpOutWriter;
import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
import com.hs.dto.PointIn;
import com.iscobol.rts.IscobolCall;
import com.iscobol.types.CobolVar;
import com.iscobol.types.NumericVar;
import java.io.File;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Puente ISCOBOL -> MercadoPago Point.
 *
 * CONTRATO argv (11 parámetros):
 *
 * ENTRADAS:
 *   argv[ 0]  accion          PIC X(02)   "O"=Crear Intent  "Q"=Consultar  "X"=Cancelar
 *   argv[ 1]  ext_ref         PIC X(40)   referencia externa (entrada O; salida Q como identificador)
 *   argv[ 2]  device_id       PIC X(40)   ID del dispositivo Point (O y X)
 *   argv[ 3]  total_amount    PIC X(15)   importe "99999.99" (solo O)
 *   argv[ 4]  description     PIC X(100)  descripción visible en el device (solo O)
 *   argv[ 5]  idempotency_key PIC X(40)   si vacío usa ext_ref (solo O)
 *   argv[ 6]  intent_id       PIC X(200)  payment_intent_id: entrada Q/X, salida O
 *   argv[ 7]  path_props      PIC X(260)  path al .properties de MP
 *
 * SALIDAS:
 *   argv[ 8]  resultado       PIC 9(02)   00=OK  02=negocio  >=4=error
 *   argv[ 9]  msg             PIC X(200)  descripción del resultado
 *   argv[10]  status          PIC X(20)   estado MP: OPEN / FINISHED / CANCELED / ERROR
 *   argv[11]  payment_id      PIC X(40)   ID del pago si status=FINISHED
 *   argv[12]  raw_json        PIC X(20000) respuesta cruda de MP
 *
 * FLUJO TÍPICO:
 *   1. Acción 'O' con device_id + ext_ref + importe -> obtiene intent_id
 *   2. Cajero espera que el cliente pase la tarjeta en el Point
 *   3. Acción 'Q' con intent_id -> chequea status
 *      OPEN     -> todavía no pagó, reintentar
 *      FINISHED -> pago OK, payment_id disponible
 *      CANCELED -> cancelado por el cliente o timeout
 *      ERROR    -> error en el pago
 *   4. Si hay que cancelar: acción 'X' con device_id
 *
 * ESTADOS MP POINT (state):
 *   OPEN     -> intent creado, esperando tarjeta
 *   FINISHED -> pago aprobado
 *   CANCELED -> cancelado
 *   ERROR    -> error
 *
 * PREREQUISITOS:
 *   - Parámetro PA-000: MP-POINT-PROPERTIES con path al .properties
 *   - El .properties puede ser el mismo mercadopagoQR.properties si comparten token
 *   - El dispositivo Point debe estar registrado y online en MP
 */
public class MP_POINT implements IscobolCall {

    private Logger logger;
    private FileHandler fh;

    // IN
    private static final int I_ACCION   = 0;
    private static final int I_EXT_REF  = 1;
    private static final int I_DEVICE   = 2;
    private static final int I_TOTAL    = 3;
    private static final int I_DESC     = 4;
    private static final int I_IDEM     = 5;
    private static final int I_INTENT   = 6;  // entrada Q/X, salida O
    private static final int I_PATH     = 7;

    // OUT
    private static final int O_RES      = 8;
    private static final int O_MSG      = 9;
    private static final int O_STATUS   = 10;
    private static final int O_PAYID    = 11;
    private static final int O_RAW      = 12;

    @Override
    public Object call(Object[] argv) {
        CobolVar[] argv2 = new CobolVar[argv.length];
        for (int i = 0; i < argv.length; i++) {
            argv2[i] = (CobolVar) argv[i];
        }
        return call(argv2);
    }

    public CobolVar call(CobolVar[] argv) {
        initLoggerPerCall();

        try {
            String accion = nvl(getStr(argv, I_ACCION)).toUpperCase().trim();

            logInputs(argv);

            switch (accion) {
                case "O":
                    logger.info("---> CREATE_POINT_INTENT (O)");
                    createIntent(argv);
                    break;

                case "Q":
                    logger.info("---> GET_POINT_INTENT (Q)");
                    getIntent(argv);
                    break;

                case "X":
                    logger.info("---> CANCEL_POINT_INTENT (X)");
                    cancelIntent(argv);
                    break;

                default:
                    logger.warning("---> Accion invalida: " + accion);
                    fail(argv, 8, "Acción inválida: " + accion);
                    break;
            }

        } catch (Exception e) {
            safeLog(Level.SEVERE, "Excepción general", e);
            fail(argv, 9, "Excepción: " + e.getMessage());
        } finally {
            try { logOutputs(argv); } catch (Exception ignored) { }
            closeLoggerPerCall();
        }

        int res = parseIntDef(getStr(argv, O_RES), 9);
        return NumericVar.literal(res, false);
    }

    // ======= ACCIONES =======

    private void createIntent(CobolVar[] argv) {
        PointIn in = new PointIn();
        in.externalReference = getStr(argv, I_EXT_REF);
        in.deviceId          = getStr(argv, I_DEVICE);
        in.totalAmount       = getStr(argv, I_TOTAL);
        in.description       = getStr(argv, I_DESC);
        in.idempotencyKey    = getStr(argv, I_IDEM);

        MpResult r = core(argv).createPointIntent(in);

        setOut(argv, O_RES,    String.valueOf(r.res));
        setOut(argv, O_MSG,    nvl(r.msg));
        setOut(argv, O_STATUS, nvl(r.status));
        setOut(argv, O_PAYID,  nvl(r.paymentId));
        setOut(argv, O_RAW,    nvl(r.rawJson));
        // intent_id en argv[I_INTENT] (salida para que el COBOL lo persista)
        setOut(argv, I_INTENT, nvl(r.id));
    }

    private void getIntent(CobolVar[] argv) {
        String intentId = getStr(argv, I_INTENT);

        MpResult r = core(argv).getPointIntent(intentId);

        setOut(argv, O_RES,    String.valueOf(r.res));
        setOut(argv, O_MSG,    nvl(r.msg));
        setOut(argv, O_STATUS, nvl(r.status));
        setOut(argv, O_PAYID,  nvl(r.paymentId));
        setOut(argv, O_RAW,    nvl(r.rawJson));
    }

    private void cancelIntent(CobolVar[] argv) {
        String deviceId = getStr(argv, I_DEVICE);

        MpResult r = core(argv).cancelPointIntent(deviceId);

        setOut(argv, O_RES,    String.valueOf(r.res));
        setOut(argv, O_MSG,    nvl(r.msg));
        setOut(argv, O_STATUS, nvl(r.status));
        setOut(argv, O_PAYID,  "");
        setOut(argv, O_RAW,    nvl(r.rawJson));
    }

    // ======= CORE FACTORY =======

    private MpBridgeCore core(CobolVar[] argv) {
        String pathProperties = getStr(argv, I_PATH);
        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }
        return new MpBridgeCore(MpConfig.load(), logger);
    }

    // ======= LOGGING =======

    private void initLoggerPerCall() {
        try {
            logger = Logger.getLogger("MP_POINT_CALL");
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.INFO);
            removeAndCloseHandlers(logger);

            String dir = "C:\\A2JTMP";
            new File(dir).mkdirs();

            fh = new FileHandler(dir + "\\MP_POINT.log", true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.INFO);
            logger.addHandler(fh);
        } catch (Exception e) {
            logger = Logger.getLogger("MP_POINT_FALLBACK");
        }
    }

    private void closeLoggerPerCall() {
        try {
            if (fh != null) {
                try { fh.flush();             } catch (Exception ignored) { }
                try { logger.removeHandler(fh); } catch (Exception ignored) { }
                try { fh.close();             } catch (Exception ignored) { }
            }
        } finally {
            fh = null;
        }
    }

    private void removeAndCloseHandlers(Logger log) {
        if (log == null) return;
        for (Handler h : log.getHandlers()) {
            try { h.flush();        } catch (Exception ignored) { }
            try { h.close();        } catch (Exception ignored) { }
            try { log.removeHandler(h); } catch (Exception ignored) { }
        }
    }

    private void safeLog(Level level, String msg, Throwable t) {
        try { if (logger != null) logger.log(level, msg, t); } catch (Exception ignored) { }
    }

    private void logInputs(CobolVar[] argv) {
        try {
            logger.info("==== MP_POINT INPUTS ====");
            logger.info("accion="    + sanitize(getStr(argv, I_ACCION), 10)
                + " ext_ref="   + sanitize(getStr(argv, I_EXT_REF), 80)
                + " device_id=" + sanitize(getStr(argv, I_DEVICE),  40)
                + " total="     + sanitize(getStr(argv, I_TOTAL),   20)
                + " intent_id=" + sanitize(getStr(argv, I_INTENT),  80)
                + " props="     + sanitize(getStr(argv, I_PATH),   200));
        } catch (Exception e) {
            safeLog(Level.WARNING, "No se pudo loguear inputs", e);
        }
    }

    private void logOutputs(CobolVar[] argv) {
        try {
            logger.info("==== MP_POINT OUTPUTS ====");
            logger.info("res="       + sanitize(getStr(argv, O_RES),    10)
                + " status="    + sanitize(getStr(argv, O_STATUS), 40)
                + " payment_id="+ sanitize(getStr(argv, O_PAYID),  40)
                + " intent_id=" + sanitize(getStr(argv, I_INTENT), 80)
                + " msg="       + sanitize(getStr(argv, O_MSG),   200));
        } catch (Exception e) {
            safeLog(Level.WARNING, "No se pudo loguear outputs", e);
        }
    }

    // ======= HELPERS =======

    private String getStr(CobolVar[] argv, int idx) {
        try {
            if (argv == null || idx < 0 || idx >= argv.length || argv[idx] == null) return "";
            return argv[idx].toString().trim();
        } catch (Exception e) { return ""; }
    }

    private void setOut(CobolVar[] argv, int idx, String value) {
        try {
            if (argv != null && idx >= 0 && idx < argv.length && argv[idx] != null) {
                argv[idx].set(value == null ? "" : value);
            }
        } catch (Exception ignored) { }
    }

    private int fail(CobolVar[] argv, int code, String msg) {
        setOut(argv, O_RES, String.valueOf(code));
        setOut(argv, O_MSG, msg);
        try { logger.warning("FAIL code=" + code + " msg=" + msg); } catch (Exception ignored) { }
        return code;
    }

    private String sanitize(String s, int max) {
        if (s == null) return "";
        String x = s.replace('\r', ' ').replace('\n', ' ').trim();
        if (max > 0 && x.length() > max) return x.substring(0, max) + "...";
        return x;
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private String nvl(String s) { return s == null ? "" : s; }

    private int parseIntDef(String s, int def) {
        try {
            if (s == null || s.trim().isEmpty()) return def;
            return Integer.parseInt(s.trim());
        } catch (Exception e) { return def; }
    }

    @Override
    public void perform(int i, int i1) { }

    public void finalize() { }
}
