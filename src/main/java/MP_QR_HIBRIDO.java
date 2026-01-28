
import com.google.gson.JsonObject;
import com.hs.bridge.MpOutWriter;
import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
import com.hs.http.MpHttp;
import com.hs.http.MpHttpAdapter;
import com.hs.http.MpHttpClient;
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
 * Puente ISCOBOL -> MercadoPago QR híbrido.
 *
 * CONTRATO argv (33 parámetros): - ENTRADAS: argv[0..25] - SALIDAS:
 * argv[26..32]
 *
 * ACCION (argv[0]): "O" Crear Orden QR "Q" Consultar Orden "C" Cancelar Orden
 * "R" Refund "S" Crear Store "P" Crear POS "LS" Buscar Stores "LP" Buscar POS
 *
 * SALIDAS (siempre): argv[26] resultado (0 OK, !=0 ERROR) argv[27] msg argv[28]
 * id relevante (order_id / store_id / pos_id según acción) argv[29] qr_data (si
 * aplica) argv[30] status (si aplica) argv[31] payment_id (si aplica) argv[32]
 * mp_raw_json
 */
public class MP_QR_HIBRIDO implements IscobolCall {

    private Logger logger;        // logger por llamada
    private FileHandler fh;       // handler por llamada (se cierra siempre)

    // IN
    private static final int I_ACCION = 0;
    private static final int I_EXT_REF = 1;     // según acción: external_ref u order_id
    private static final int I_DESC = 2;
    private static final int I_EXT_POS = 3;
    private static final int I_MODO = 4;
    private static final int I_EXP = 5;
    private static final int I_TOTAL = 6;
    private static final int I_UNIT = 7;
    private static final int I_ITEM = 8;
    private static final int I_EXT_CODE = 9;
    private static final int I_PATH = 10;
    private static final int I_IDEM = 11;

    // STORE
    private static final int I_STORE_NAME = 12;
    private static final int I_STORE_EXTID = 13;
    private static final int I_STORE_STREET = 14;
    private static final int I_STORE_STREETNRO = 15;
    private static final int I_STORE_CITY = 16;
    private static final int I_STORE_STATE = 17;
    private static final int I_STORE_LAT = 18;
    private static final int I_STORE_LON = 19;

    // POS
    private static final int I_POS_NAME = 20;
    private static final int I_POS_EXTID = 21;
    private static final int I_POS_STOREID = 22;

    // SEARCH
    private static final int I_LIMIT = 23;
    private static final int I_OFFSET = 24;
    private static final int I_FILTER_EXTID = 25;

    // OUT
    private static final int O_RES = 26;
    private static final int O_MSG = 27;
    private static final int O_ID = 28;
    private static final int O_QR = 29;
    private static final int O_STATUS = 30;
    private static final int O_PAYID = 31;
    private static final int O_RAW = 32;

    // Alias semánticos (mismo índice, no cambia contrato)
    private static final int I_ORDER_ID = I_EXT_REF;       // Q/C/R usan argv[1]
    private static final int I_IDEMPOTENCY = I_IDEM;

    @Override
    public Object call(Object[] argv) {
        CobolVar[] argv2 = new CobolVar[argv.length];
        for (int i = 0; i < argv.length; i++) {
            argv2[i] = (CobolVar) argv[i];
        }
        return call(argv2);
    }

    public CobolVar call(CobolVar[] argv) {
        int resultado;

        // logger por llamada (evita locks)
        initLoggerPerCall();

        try {
            String accion = nvl(getStr(argv, I_ACCION)).toUpperCase();

            logInputsCompact(argv);

            switch (accion) {
                case "O":
                    logger.info("---> CREATE_ORDER (O)");
                    resultado = createOrder(argv);
                    break;

                case "Q":
                    logger.info("---> GET_ORDER (Q)");
                    resultado = getOrder(argv);
                    break;

                case "C":
                    logger.info("---> CANCEL_ORDER (C)");
                    resultado = cancelOrder(argv);
                    break;

                case "R":
                    logger.info("---> REFUND_ORDER (R)");
                    resultado = refundOrder(argv);
                    break;

                case "S":
                    logger.info("---> CREATE_STORE (S)");
                    resultado = createStore(argv);
                    break;

                case "P":
                    logger.info("---> CREATE_POS (P)");
                    resultado = createPos(argv);
                    break;

                case "LS":
                    logger.info("---> SEARCH_STORES (LS)");
                    resultado = searchStores(argv);
                    break;

                case "LP":
                    logger.info("---> SEARCH_POS (LP)");
                    resultado = searchPos(argv);
                    break;

                default:
                    logger.warning("---> Accion invalida: " + accion);
                    fail(argv, 8, "Acción inválida: " + accion);
                    resultado = 8;
                    break;
            }

        } catch (Exception e) {
            safeLog(Level.SEVERE, "Excepción general", e);
            fail(argv, 9, "Excepción: " + e.getMessage());
        } finally {
            // SIEMPRE loguear salidas + cerrar handler (libera lock)
            try {
                logOutputsCompact(argv);
            } catch (Exception ignored) {
            }
            closeLoggerPerCall();
        }

        // devolver resultado (lo que ya escribiste en argv[26] manda)
        int res = parseIntDef(getStr(argv, O_RES), 9);
        return NumericVar.literal(res, false);
    }

    // ======= CORE FACTORY =======
    private MpBridgeCore core(CobolVar[] argv) {
        String pathProperties = getStr(argv, I_PATH);
        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient httpClient = new MpHttpClient(cfg);
        MpHttp http = new MpHttpAdapter(httpClient);

        return new MpBridgeCore(cfg, http);
    }

    // ======= ACCIONES (todas delegadas al core) =======
    private int createOrder(CobolVar[] argv) {
        com.hs.dto.OrderIn in = new com.hs.dto.OrderIn();
        in.externalReference = getStr(argv, I_EXT_REF);
        in.description = getStr(argv, I_DESC);
        in.externalPosId = getStr(argv, I_EXT_POS);
        in.mode = getStr(argv, I_MODO);
        in.expirationTime = getStr(argv, I_EXP);
        in.totalAmount = getStr(argv, I_TOTAL);
        in.unitMeasure = getStr(argv, I_UNIT);
        in.itemTitle = getStr(argv, I_ITEM);
        in.externalCode = getStr(argv, I_EXT_CODE);
        in.idempotencyKey = getStr(argv, I_IDEMPOTENCY);

        MpResult r = core(argv).createOrder(in);
        MpOutWriter.write(argv, r);
        return 0;
    }

    private int getOrder(CobolVar[] argv) {
        String orderId = getStr(argv, I_ORDER_ID);

        MpResult r = core(argv).getOrder(orderId);
        if (r != null && isBlank(r.id)) {
            r.id = orderId;
        }
        MpOutWriter.write(argv, r);
        return 0;
    }

    private int cancelOrder(CobolVar[] argv) {
        String orderId = getStr(argv, I_ORDER_ID);
        String idem = getStr(argv, I_IDEMPOTENCY);

        MpResult r = core(argv).cancelOrder(orderId, idem);
        MpOutWriter.write(argv, r);
        return 0;
    }

    private int refundOrder(CobolVar[] argv) {
        String orderId = getStr(argv, I_ORDER_ID);
        String idem = getStr(argv, I_IDEMPOTENCY);

        MpResult r = core(argv).refundOrder(orderId, idem);
        MpOutWriter.write(argv, r);
        return 0;
    }

    private int createStore(CobolVar[] argv) {
        com.hs.dto.StoreIn in = new com.hs.dto.StoreIn();
        in.name = getStr(argv, I_STORE_NAME);
        in.externalId = getStr(argv, I_STORE_EXTID);
        in.street = getStr(argv, I_STORE_STREET);
        in.streetNumber = getStr(argv, I_STORE_STREETNRO);
        in.city = getStr(argv, I_STORE_CITY);
        in.state = getStr(argv, I_STORE_STATE);
        in.latitude = getStr(argv, I_STORE_LAT);
        in.longitude = getStr(argv, I_STORE_LON);
        in.idempotencyKey = getStr(argv, I_IDEMPOTENCY);

        MpResult r = core(argv).createStore(in);
        MpOutWriter.write(argv, r);
        return 0;
    }

    private int createPos(CobolVar[] argv) {
        com.hs.dto.PosIn in = new com.hs.dto.PosIn();
        in.name = getStr(argv, I_POS_NAME);
        in.externalId = getStr(argv, I_POS_EXTID);

        String storeIdStr = getStr(argv, I_POS_STOREID);
        try {
            in.storeId = Long.parseLong(nvl(storeIdStr).trim());
        } catch (Exception e) {
            MpOutWriter.write(argv, MpResult.error(4, "store_id debe ser numérico"));
            return 0;
        }

        in.idempotencyKey = getStr(argv, I_IDEMPOTENCY);

        MpResult r = core(argv).createPos(in);
        MpOutWriter.write(argv, r);
        return 0;
    }

    private int searchStores(CobolVar[] argv) {
        com.hs.dto.SearchIn in = new com.hs.dto.SearchIn();
        in.limit = parseIntDef(getStr(argv, I_LIMIT), 50);
        in.offset = parseIntDef(getStr(argv, I_OFFSET), 0);
        in.filterExternalId = getStr(argv, I_FILTER_EXTID);

        MpResult r = core(argv).searchStores(in);
        MpOutWriter.write(argv, r);
        return 0;
    }

    private int searchPos(CobolVar[] argv) {
        com.hs.dto.SearchIn in = new com.hs.dto.SearchIn();
        in.limit = parseIntDef(getStr(argv, I_LIMIT), 50);
        in.offset = parseIntDef(getStr(argv, I_OFFSET), 0);
        in.filterExternalId = getStr(argv, I_FILTER_EXTID);

        MpResult r = core(argv).searchPos(in);
        MpOutWriter.write(argv, r);
        return 0;
    }

    // ======= LOGGING =======
    private void initLoggerPerCall() {
        try {
            logger = Logger.getLogger("MP_QR_HIBRIDO_CALL");
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.INFO);

            // Por si la JVM reutiliza el logger: limpiar handlers previos
            removeAndCloseHandlers(logger);

            String dir = "C:\\A2JTMP";
            new File(dir).mkdirs();

            String file = dir + "\\MP_QR_HIBRIDO.log";
            fh = new FileHandler(file, true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.INFO);

            logger.addHandler(fh);
        } catch (Exception e) {
            // No romper la operación si falla el log
            logger = Logger.getLogger("MP_QR_HIBRIDO_FALLBACK");
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

    private void safeLog(Level level, String msg, Throwable t) {
        try {
            if (logger != null) {
                logger.log(level, msg, t);
            }
        } catch (Exception ignored) {
        }
    }

    private void logInputsCompact(CobolVar[] argv) {
        try {
            String accion = sanitize(getStr(argv, I_ACCION), 10).toUpperCase();

            logger.info("==== MP_QR_HIBRIDO INPUTS ====");
            logger.info("accion=" + accion
                    + " ext_ref=" + sanitize(getStr(argv, I_EXT_REF), 80)
                    + " ext_pos_id=" + sanitize(getStr(argv, I_EXT_POS), 40)
                    + " total_amount=" + sanitize(getStr(argv, I_TOTAL), 30)
                    + " modo=" + sanitize(getStr(argv, I_MODO), 30)
                    + " expiration=" + sanitize(getStr(argv, I_EXP), 40));

            logger.info("store: name=" + sanitize(getStr(argv, I_STORE_NAME), 80)
                    + " external_id=" + sanitize(getStr(argv, I_STORE_EXTID), 60));

            logger.info("pos: name=" + sanitize(getStr(argv, I_POS_NAME), 80)
                    + " external_id=" + sanitize(getStr(argv, I_POS_EXTID), 60)
                    + " store_id=" + sanitize(getStr(argv, I_POS_STOREID), 40));

            logger.info("search: limit=" + sanitize(getStr(argv, I_LIMIT), 10)
                    + " offset=" + sanitize(getStr(argv, I_OFFSET), 10)
                    + " filter_extid=" + sanitize(getStr(argv, I_FILTER_EXTID), 80));

            logger.info("props: path=" + sanitize(getStr(argv, I_PATH), 200)
                    + " idempotency=" + sanitize(getStr(argv, I_IDEMPOTENCY), 80));

        } catch (Exception e) {
            safeLog(Level.WARNING, "No se pudo loguear inputs", e);
        }
    }

    private void logOutputsCompact(CobolVar[] argv) {
        try {
            String raw = getStr(argv, O_RAW);
            String rawPreview = sanitize(raw, 300);

            logger.info("==== MP_QR_HIBRIDO OUTPUTS ====");
            logger.info("res=" + sanitize(getStr(argv, O_RES), 10)
                    + " msg=" + sanitize(getStr(argv, O_MSG), 200));

            logger.info("id=" + sanitize(getStr(argv, O_ID), 40)
                    + " status=" + sanitize(getStr(argv, O_STATUS), 40)
                    + " payment_id=" + sanitize(getStr(argv, O_PAYID), 40));

            logger.info("qr_data_preview=" + sanitize(getStr(argv, O_QR), 200));

            logger.info("raw_json_len=" + (raw == null ? 0 : raw.length())
                    + " raw_json_preview=" + rawPreview);

        } catch (Exception e) {
            safeLog(Level.WARNING, "No se pudo loguear outputs", e);
        }
    }

    // ======= HELPERS =======
    private String sanitize(String s, int max) {
        if (s == null) {
            return "";
        }
        String x = s.replace('\r', ' ').replace('\n', ' ').trim();
        if (max > 0 && x.length() > max) {
            return x.substring(0, max) + "...";
        }
        return x;
    }

    private String getStr(CobolVar[] argv, int idx) {
        try {
            if (argv == null || idx < 0 || idx >= argv.length) {
                return "";
            }
            if (argv[idx] == null) {
                return "";
            }
            return argv[idx].toString().trim();
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

    private int fail(CobolVar[] argv, int code, String msg) {
        setOut(argv, O_RES, String.valueOf(code));
        setOut(argv, O_MSG, msg);
        try {
            logger.warning("FAIL code=" + code + " msg=" + msg);
        } catch (Exception ignored) {
        }
        return code;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String getJsonStr(JsonObject o, String key) {
        try {
            if (o == null || key == null) {
                return "";
            }
            if (!o.has(key) || o.get(key).isJsonNull()) {
                return "";
            }
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private int parseIntDef(String s, int def) {
        try {
            if (s == null) {
                return def;
            }
            s = s.trim();
            if (s.isEmpty()) {
                return def;
            }
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public void perform(int i, int i1) {
        // no-op
    }

    public void finalize() {
    }
}
