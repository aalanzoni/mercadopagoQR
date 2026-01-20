
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hs.bridge.MpOutWriter;
import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
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
 * ACCION (argv[0]): "O" = Crear Orden QR (POST /v1/orders)
 *
 * ENTRADAS (accion "O"): argv[1] external_reference argv[2] description argv[3]
 * external_pos_id argv[4] mode (dynamic/static) argv[5] expiration_time (ej:
 * PT23M) argv[6] total_amount (string: "54.00") argv[7] unit_measure (ej:
 * "unidad") argv[8] item_title argv[9] external_code argv[10] path_properties
 * (ruta al mercadopago.properties) argv[11] idempotency_key (opcional; si vacío
 * usa external_reference)
 *
 * SALIDAS: argv[12] resultado (0 OK, !=0 ERROR) argv[13] msg argv[14] order_id
 * argv[15] qr_data (recomendado en COBOL: X(1024)) argv[16] status argv[17]
 * payment_id argv[18] mp_raw_json (recomendado COBOL: X(20000) o X(32767) si
 * da)
 */
public class MP_QR_HIBRIDO implements IscobolCall {

    private static final Gson GSON = new Gson();
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

        // Entradas
        String externalReference = getStr(argv, 1);
        String description = getStr(argv, 2);
        String externalPosId = getStr(argv, 3);
        String mode = getStr(argv, 4);
        String expirationTime = getStr(argv, 5);
        String totalAmount = getStr(argv, 6); // STRING
        String unitMeasure = getStr(argv, 7);
        String itemTitle = getStr(argv, 8);
        String externalCode = getStr(argv, 9);
        String pathProperties = getStr(argv, 10);
        String idempotencyKey = getStr(argv, 11);

        // Validaciones mínimas
        if (isBlank(externalReference)) {
            return fail(argv, 1, "Falta external_reference (argv[1])");
        }
        if (isBlank(externalPosId)) {
            return fail(argv, 2, "Falta external_pos_id (argv[3])");
        }
        if (isBlank(totalAmount)) {
            return fail(argv, 3, "Falta total_amount (argv[6])");
        }
        if (isBlank(itemTitle)) {
            itemTitle = "Item";
        }
        if (isBlank(unitMeasure)) {
            unitMeasure = "unidad";
        }
        if (isBlank(mode)) {
            mode = "dynamic";
        }
        if (isBlank(idempotencyKey)) {
            idempotencyKey = externalReference;
        }

        logger.info("external_reference=" + externalReference);
        logger.info("external_pos_id=" + externalPosId);
        logger.info("total_amount=" + totalAmount);
        logger.info("idempotency_key=" + idempotencyKey);
        logger.info("path_properties=" + pathProperties);

        // Setear properties como en tu otro puente
        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient http = new MpHttpClient(cfg);

        String endpoint = cfg.get("mp.endpoint.createOrder", "/v1/orders");

        // Armar body EXACTO (el que te funcionó)
        JsonObject body = new JsonObject();
        body.addProperty("type", "qr");
        body.addProperty("total_amount", totalAmount);
        body.addProperty("description", description);
        body.addProperty("external_reference", externalReference);
        body.addProperty("expiration_time", expirationTime);

        JsonObject cfgNode = new JsonObject();
        JsonObject qr = new JsonObject();
        qr.addProperty("external_pos_id", externalPosId);
        qr.addProperty("mode", mode);
        cfgNode.add("qr", qr);
        body.add("config", cfgNode);

        JsonObject transactions = new JsonObject();
        JsonArray payments = new JsonArray();
        JsonObject p0 = new JsonObject();
        p0.addProperty("amount", totalAmount);
        payments.add(p0);
        transactions.add("payments", payments);
        body.add("transactions", transactions);

        JsonArray items = new JsonArray();
        JsonObject it = new JsonObject();
        it.addProperty("title", itemTitle);
        it.addProperty("unit_price", totalAmount);
        it.addProperty("quantity", 1);
        it.addProperty("unit_measure", unitMeasure);
        it.addProperty("external_code", externalCode);
        items.add(it);
        body.add("items", items);

        String jsonBody = GSON.toJson(body);

        logger.info("POST " + endpoint);
        logger.info("BODY " + jsonBody);

        try {
            MpHttpClient.MpHttpResponse r = http.postJson(endpoint, jsonBody, idempotencyKey);
            logger.info("HTTP " + r.statusCode);
            logger.info("RESP " + r.body);

            if (!(r.is2xx() || r.statusCode == 201)) {
                return fail(argv, 4, "HTTP " + r.statusCode + " - " + r.body);
            }

            // Parse response
            JsonObject mp = GSON.fromJson(r.body, JsonObject.class);

            String orderId = getJsonStr(mp, "id");
            String status = getJsonStr(mp, "status");
            String paymentId = null;
            String qrData = null;

            try {
                if (mp.has("transactions") && mp.get("transactions").isJsonObject()) {
                    JsonObject tr = mp.getAsJsonObject("transactions");
                    if (tr.has("payments") && tr.get("payments").isJsonArray() && tr.getAsJsonArray("payments").size() > 0) {
                        JsonObject pay0 = tr.getAsJsonArray("payments").get(0).getAsJsonObject();
                        paymentId = getJsonStr(pay0, "id");
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                if (mp.has("type_response") && mp.get("type_response").isJsonObject()) {
                    JsonObject tr = mp.getAsJsonObject("type_response");
                    qrData = getJsonStr(tr, "qr_data");
                }
            } catch (Exception ignored) {
            }

            // Salidas (como tu MRP-001)
            setOut(argv, O_RES, "0");
            setOut(argv, O_MSG, "OK");
            setOut(argv, O_ID, nvl(orderId));
            setOut(argv, O_QR, nvl(qrData));
            setOut(argv, O_STATUS, nvl(status));
            setOut(argv, O_PAYID, nvl(paymentId));
            setOut(argv, O_RAW, r.body); // mp_raw_json

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error createOrder: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
    }

    private int getOrder(CobolVar[] argv) {

        // Entradas mínimas para Q
        String orderId = getStr(argv, 1);   // reutilizamos argv[1]
        String pathProperties = getStr(argv, 10);  // reutilizamos argv[10]

        if (isBlank(orderId)) {
            return fail(argv, 1, "Falta order_id (argv[1])");
        }

        logger.info("order_id=" + orderId);
        logger.info("path_properties=" + pathProperties);

        // Setear properties como en tu otro puente
        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient http = new MpHttpClient(cfg);

        String endpointFmt = cfg.get("mp.endpoint.getOrder", "/v1/orders/%s");
        String endpoint = String.format(endpointFmt, orderId);

        logger.info("GET " + endpoint);

        try {
            MpHttpClient.MpHttpResponse r = http.get(endpoint, null);

            logger.info("HTTP " + r.statusCode);
            logger.info("RESP " + r.body);

            if (!r.is2xx()) {
                return fail(argv, 4, "HTTP " + r.statusCode + " - " + r.body);
            }

            JsonObject mp = GSON.fromJson(r.body, JsonObject.class);

            String status = getJsonStr(mp, "status");
            String paymentId = null;
            String qrData = null;

            // payment_id: transactions.payments[0].id
            try {
                if (mp.has("transactions") && mp.get("transactions").isJsonObject()) {
                    JsonObject tr = mp.getAsJsonObject("transactions");
                    if (tr.has("payments") && tr.get("payments").isJsonArray() && tr.getAsJsonArray("payments").size() > 0) {
                        JsonObject pay0 = tr.getAsJsonArray("payments").get(0).getAsJsonObject();
                        paymentId = getJsonStr(pay0, "id");
                    }
                }
            } catch (Exception ignored) {
            }

            // qr_data: type_response.qr_data (a veces viene siempre, a veces no)
            try {
                if (mp.has("type_response") && mp.get("type_response").isJsonObject()) {
                    JsonObject tr = mp.getAsJsonObject("type_response");
                    qrData = getJsonStr(tr, "qr_data");
                }
            } catch (Exception ignored) {
            }

            // Salidas (mismo contrato)
            setOut(argv, O_RES, "0");
            setOut(argv, O_MSG, "OK");
            setOut(argv, O_ID, orderId);       // order_id
            setOut(argv, O_QR, nvl(qrData));   // qr_data
            setOut(argv, O_STATUS, nvl(status));   // status
            setOut(argv, O_PAYID, nvl(paymentId));// payment_id
            setOut(argv, O_RAW, r.body);        // mp_raw_json

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error getOrder: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
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

        MpResult r = core(argv).searchStores(com.hs.config.MpConfig.load().userId(), in);
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
