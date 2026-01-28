
import com.google.gson.JsonObject;
import com.hs.bridge.MpOutWriter;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
import com.hs.config.MpConfig;
import com.hs.http.MpHttp;
import com.hs.http.MpHttpAdapter;
import com.hs.http.MpHttpClient;
import com.iscobol.rts.IscobolCall;
import com.iscobol.types.*;
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
 * ACCION (argv[0]): "O" = Crear Orden QR (POST /v1/orders) "Q" = Consultar
 * Orden (GET /v1/orders/{id}) "C" = Cancelar Orden (POST
 * /v1/orders/{id}/cancel) "R" = Refund (POST /v1/payments/{id}/refund) "S" =
 * Crear Store "P" = Crear POS "LS" = Buscar Stores "LP" = Buscar POS
 *
 * SALIDAS (siempre): argv[26] resultado (0 OK, !=0 ERROR) argv[27] msg argv[28]
 * order_id / id relevante (según acción) argv[29] qr_data (si aplica) argv[30]
 * status (si aplica) argv[31] payment_id (si aplica) argv[32] mp_raw_json
 * (recomendado COBOL: X(20000) o X(32767))
 */
public class MP_QR_HIBRIDO implements IscobolCall {

    private MpBridgeCore core;
    private static Logger logger;
    private Handler fh;

// IN
    private static final int I_ACCION = 0;
    private static final int I_EXT_REF = 1;
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
    private static final int I_ORDER_ID = I_EXT_REF;   // Q/C/R usan argv[1] como order_id
    private static final int I_IDEMPOTENCY = I_IDEM;

    public Object call(Object[] argv) {

        initLogger();

        CobolVar[] argv2 = new CobolVar[argv.length];
        for (int i = 0; i < argv.length; i++) {
            argv2[i] = (CobolVar) argv[i];
            if (argv2[i] != null) {
                logger.info("argv[" + i + "]=" + argv2[i].toString());
            }
        }

        CobolVar ret = call(argv2);
        return ret;
    }

    public CobolVar call(CobolVar[] argv) {

        int resultado = 0;
        String accion = getStr(argv, 0).toUpperCase();

        try {
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
                    logger.info("---> Accion invalida: " + accion);
                    setOut(argv, O_RES, "8");
                    setOut(argv, O_MSG, "Acción inválida: " + accion);
                    resultado = 8;
                    break;
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Excepción general: " + e.getMessage(), e);
            setOut(argv, O_RES, "9");
            setOut(argv, O_MSG, "Excepción: " + e.getMessage());
            resultado = 9;

        } finally {
            closeLogger();
        }

        return NumericVar.literal(resultado, false);
    }

    private MpBridgeCore core(CobolVar[] argv) {
        // Lazy init por si cambian pathProperties por llamada
        String pathProperties = getStr(argv, I_PATH);
        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient httpClient = new MpHttpClient(cfg);
        MpHttp http = new MpHttpAdapter(httpClient);

        return new MpBridgeCore(cfg, http);
    }

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
        if (r != null && (r.id == null || r.id.trim().isEmpty())) {
            r.id = orderId;
        }
        MpOutWriter.write(argv, r);
        return 0;
    }

    private int cancelOrder(CobolVar[] argv) {
        String orderId = getStr(argv, I_EXT_REF);
        String idem = getStr(argv, I_IDEM);

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
            in.storeId = Long.parseLong((storeIdStr == null ? "" : storeIdStr).trim());
        } catch (Exception e) {
            MpOutWriter.write(argv, com.hs.dto.MpResult.error(4, "store_id debe ser numérico"));
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

    // ========= helpers =========
    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String appendQuery(String base, String queryString) {
        if (isBlank(queryString)) {
            return base;
        }

        String q = queryString.trim();
        if (q.startsWith("?")) {
            q = q.substring(1);
        }

        if (isBlank(q)) {
            return base;
        }

        return base.contains("?") ? (base + "&" + q) : (base + "?" + q);
    }

    private void initLogger() {
        try {
            if (logger == null) {
                logger = Logger.getLogger(MP_QR_HIBRIDO.class.getName());
            }
            fh = new FileHandler("C:/A2JTMP/MP_QR_HIBRIDO.log", true);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
            logger.setLevel(Level.ALL);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void closeLogger() {
        if (fh != null) {
            try {
                logger.removeHandler(fh);
            } catch (Exception ignored) {
            }
            try {
                fh.close();
            } catch (Exception ignored) {
            }
        }
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
        logger.warning("FAIL code=" + code + " msg=" + msg);
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

    private double parseDoubleReq(String s, String campo) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException("Falta " + campo);
        }
        return Double.parseDouble(s.trim().replace(",", "."));
    }

    @Override
    public void perform(int i, int i1) {
    }

    public void finalize() {
    }

}
