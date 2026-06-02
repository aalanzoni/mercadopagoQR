
import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
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
 * MP_LINK_CANCEL — vence (anula) una preferencia/link de Checkout Pro en MP.
 *
 * Contrato (7 argumentos):
 *   IN
 *     0 : path props (mercadopagoQR.properties)
 *     1 : preference_id (ID de la preferencia a vencer)
 *     2 : idempotency (opcional; si viene vacío se usa el preference_id)
 *   OUT
 *     3 : res     (0 OK / >0 error)
 *     4 : msg
 *     5 : status  ("expired" si venció bien)
 *     6 : raw_json (respuesta cruda de MP)
 *
 * No toca el contrato de MP_LINK_PAGO. Reutiliza la capa core
 * (MpBridgeCore.cancelPaymentLink) que hace el PUT a la preferencia.
 */
public class MP_LINK_CANCEL implements IscobolCall {

    private Logger logger;
    private FileHandler fh;

    private static final int MIN_ARGS = 7;

    private static final int I_PATH = 0;
    private static final int I_PREFERENCE_ID = 1;
    private static final int I_IDEMPOTENCY = 2;

    private static final int O_RES = 3;
    private static final int O_MSG = 4;
    private static final int O_STATUS = 5;
    private static final int O_RAW_JSON = 6;

    @Override
    public Object call(Object[] argv) {
        CobolVar[] argv2 = new CobolVar[argv.length];
        for (int i = 0; i < argv.length; i++) {
            argv2[i] = (CobolVar) argv[i];
        }
        return call(argv2);
    }

    public CobolVar call(CobolVar[] argv) {
        int resultado = 9;

        initLoggerPerCall();

        try {
            if (argv == null || argv.length < MIN_ARGS) {
                safeLog(Level.SEVERE,
                        "Cantidad de argumentos inválida: "
                        + (argv == null ? 0 : argv.length), null);
                return NumericVar.literal(9, false);
            }

            String path = getStr(argv, I_PATH);
            String preferenceId = getStr(argv, I_PREFERENCE_ID);
            String idempotency = getStr(argv, I_IDEMPOTENCY);

            safeLog(Level.INFO, "==== MP_LINK_CANCEL IN ==== preference_id="
                    + sanitize(preferenceId, 80)
                    + " idempotency=" + sanitize(idempotency, 80)
                    + " path=" + sanitize(path, 200), null);

            if (isBlank(preferenceId)) {
                resultado = fail(argv, 4, "preference_id es obligatorio");
            } else {
                if (!isBlank(path)) {
                    System.setProperty("mp.config", path);
                }
                MpConfig cfg = MpConfig.load();
                MpBridgeCore core = new MpBridgeCore(cfg, logger);

                MpResult r = core.cancelPaymentLink(preferenceId, idempotency);
                writeOut(argv, r);
                resultado = (r != null) ? r.res : 9;
            }

        } catch (Exception e) {
            safeLog(Level.SEVERE, "Excepción general", e);
            fail(argv, 9, "Excepción: " + safeMsg(e));
            resultado = 9;

        } finally {
            try {
                if (argv != null && argv.length >= MIN_ARGS) {
                    safeLog(Level.INFO, "==== MP_LINK_CANCEL OUT ==== res="
                            + sanitize(getStr(argv, O_RES), 10)
                            + " status=" + sanitize(getStr(argv, O_STATUS), 40)
                            + " msg=" + sanitize(getStr(argv, O_MSG), 200), null);
                }
            } catch (Exception ignored) {
            }
            closeLoggerPerCall();
        }

        int res = parseIntDef(getStr(argv, O_RES), resultado);
        return NumericVar.literal(res, false);
    }

    private void writeOut(CobolVar[] argv, MpResult r) {
        setOut(argv, O_RES, String.valueOf(r != null ? r.res : 9));
        setOut(argv, O_MSG, r != null ? nn(r.msg) : "Respuesta nula");
        setOut(argv, O_STATUS, r != null ? nn(r.status) : "");
        setOut(argv, O_RAW_JSON, r != null ? nn(r.rawJson) : "");
    }

    private int fail(CobolVar[] argv, int code, String msg) {
        setOut(argv, O_RES, String.valueOf(code));
        setOut(argv, O_MSG, msg);
        setOut(argv, O_STATUS, "error");
        setOut(argv, O_RAW_JSON, "");
        return code;
    }

    private String getStr(CobolVar[] argv, int idx) {
        try {
            if (argv == null || idx < 0 || idx >= argv.length || argv[idx] == null) {
                return "";
            }
            String s = argv[idx].toString();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void setOut(CobolVar[] argv, int idx, String value) {
        try {
            if (argv != null && idx >= 0 && idx < argv.length && argv[idx] != null) {
                argv[idx].set(value == null ? "" : value);
            }
        } catch (Exception ignored) {
        }
    }

    private int parseIntDef(String s, int def) {
        try {
            return Integer.parseInt(nn(s).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String nn(String s) {
        return s == null ? "" : s;
    }

    private String safeMsg(Exception e) {
        String m = (e == null || e.getMessage() == null) ? "" : e.getMessage().trim();
        return m.isEmpty() ? (e == null ? "" : e.getClass().getSimpleName()) : m;
    }

    private void initLoggerPerCall() {
        try {
            logger = Logger.getLogger("MP_LINK_CANCEL_CALL");
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.INFO);

            removeAndCloseHandlers(logger);

            String dir = "C:\\A2JTMP";
            new File(dir).mkdirs();

            String file = dir + "\\MP_LINK_CANCEL.log";
            fh = new FileHandler(file, true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.INFO);

            logger.addHandler(fh);
        } catch (Exception e) {
            logger = Logger.getLogger("MP_LINK_CANCEL_FALLBACK");
            logger.setUseParentHandlers(true);
            logger.setLevel(Level.INFO);
        }
    }

    private void closeLoggerPerCall() {
        try {
            if (fh != null) {
                try {
                    fh.flush();
                } catch (Exception ignored) {
                }
                try {
                    logger.removeHandler(fh);
                } catch (Exception ignored) {
                }
                try {
                    fh.close();
                } catch (Exception ignored) {
                }
            }
        } finally {
            fh = null;
        }
    }

    private void removeAndCloseHandlers(Logger log) {
        if (log == null) {
            return;
        }
        for (Handler h : log.getHandlers()) {
            try {
                h.flush();
            } catch (Exception ignored) {
            }
            try {
                h.close();
            } catch (Exception ignored) {
            }
            try {
                log.removeHandler(h);
            } catch (Exception ignored) {
            }
        }
    }

    private String sanitize(String s, int max) {
        String x = nn(s).replace('\r', ' ').replace('\n', ' ').trim();
        if (max > 0 && x.length() > max) {
            return x.substring(0, max) + "...";
        }
        return x;
    }

    private void safeLog(Level level, String msg, Throwable t) {
        try {
            if (logger != null) {
                if (t != null) {
                    logger.log(level, msg, t);
                } else {
                    logger.log(level, msg);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void perform(int i, int i1) {
        // no-op
    }

    public void finalize() {
    }
}
