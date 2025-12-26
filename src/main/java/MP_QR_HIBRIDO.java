
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hs.MpConfig;
import com.hs.MpHttpClient;
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

    private static Logger logger;
    private Handler fh;

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
                    setOut(argv, 12, "8");
                    setOut(argv, 13, "Acción inválida: " + accion);
                    resultado = 8;
                    break;
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Excepción general: " + e.getMessage(), e);
            setOut(argv, 12, "9");
            setOut(argv, 13, "Excepción: " + e.getMessage());
            resultado = 9;

        } finally {
            closeLogger();
        }

        return NumericVar.literal(resultado, false);
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
            setOut(argv, 12, "0");
            setOut(argv, 13, "OK");
            setOut(argv, 14, nvl(orderId));
            setOut(argv, 15, nvl(qrData));
            setOut(argv, 16, nvl(status));
            setOut(argv, 17, nvl(paymentId));
            setOut(argv, 18, r.body); // mp_raw_json

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
            setOut(argv, 12, "0");
            setOut(argv, 13, "OK");
            setOut(argv, 14, orderId);       // order_id
            setOut(argv, 15, nvl(qrData));   // qr_data
            setOut(argv, 16, nvl(status));   // status
            setOut(argv, 17, nvl(paymentId));// payment_id
            setOut(argv, 18, r.body);        // mp_raw_json

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error getOrder: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
    }

    private int cancelOrder(CobolVar[] argv) {

        String orderId = getStr(argv, 1);
        String pathProperties = getStr(argv, 10);
        String idempotencyKey = getStr(argv, 11);

        if (isBlank(orderId)) {
            return fail(argv, 1, "Falta order_id (argv[1])");
        }
        if (isBlank(idempotencyKey)) {
            idempotencyKey = orderId; // fallback
        }
        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient http = new MpHttpClient(cfg);

        String endpointFmt = cfg.get("mp.endpoint.cancelOrder", "/v1/orders/%s/cancel");
        String endpoint = String.format(endpointFmt, orderId);

        logger.info("POST " + endpoint);
        logger.info("X-Idempotency-Key: " + idempotencyKey);

        try {
            // cancel: POST sin body, pero con X-Idempotency-Key
            MpHttpClient.MpHttpResponse r = http.postJson(endpoint, "{}", idempotencyKey);

            logger.info("HTTP " + r.statusCode);
            logger.info("RESP " + r.body);

            if (r.statusCode == 409) {
                String code = "";
                String msg = "";
                String currentStatus = "";

                try {
                    JsonObject err = GSON.fromJson(r.body, JsonObject.class);
                    if (err.has("errors") && err.get("errors").isJsonArray() && err.getAsJsonArray("errors").size() > 0) {
                        JsonObject e0 = err.getAsJsonArray("errors").get(0).getAsJsonObject();
                        code = getJsonStr(e0, "code");
                        msg = getJsonStr(e0, "message");
                    }
                } catch (Exception ignored) {
                }

                // Si el mensaje trae "... current status, 'expired' ..."
                if (msg != null) {
                    int p1 = msg.indexOf("'");
                    int p2 = (p1 >= 0) ? msg.indexOf("'", p1 + 1) : -1;
                    if (p1 >= 0 && p2 > p1) {
                        currentStatus = msg.substring(p1 + 1, p2);
                    }
                }

                // Resultado 2 = negocio: no cancelable
                setOut(argv, 12, "2");
                setOut(argv, 13, "No cancelable (" + nvl(code) + "). Estado=" + nvl(currentStatus));
                setOut(argv, 14, orderId);
                setOut(argv, 16, nvl(currentStatus)); // status
                setOut(argv, 18, r.body);
                return 2;
            }

            if (!r.is2xx()) {
                return fail(argv, 4, "HTTP " + r.statusCode + " - " + r.body);
            }

            JsonObject mp = GSON.fromJson(r.body, JsonObject.class);

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

            setOut(argv, 12, "0");
            setOut(argv, 13, "OK");
            setOut(argv, 14, orderId);
            setOut(argv, 15, nvl(qrData));
            setOut(argv, 16, nvl(status));
            setOut(argv, 17, nvl(paymentId));
            setOut(argv, 18, r.body);

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error cancelOrder: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
    }

    private int refundOrder(CobolVar[] argv) {

        String orderId = getStr(argv, 1);
        String pathProperties = getStr(argv, 10);
        String idempotencyKey = getStr(argv, 11);

        if (isBlank(orderId)) {
            return fail(argv, 1, "Falta order_id (argv[1])");
        }

        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }

        if (isBlank(idempotencyKey)) {
            idempotencyKey = orderId;
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient http = new MpHttpClient(cfg);

        // 1) Primero consultamos la orden (GET) para verificar si está pagada
        String getFmt = cfg.get("mp.endpoint.getOrder", "/v1/orders/%s");
        String getEndpoint = String.format(getFmt, orderId);

        logger.info("GET " + getEndpoint);

        try {
            MpHttpClient.MpHttpResponse g = http.get(getEndpoint, null);

            logger.info("HTTP(GET) " + g.statusCode);
            logger.info("RESP(GET) " + g.body);

            if (!g.is2xx()) {
                return fail(argv, 3, "GET_ORDER previo a REFUND falló: HTTP " + g.statusCode + " - " + g.body);
            }

            JsonObject mpGet = GSON.fromJson(g.body, JsonObject.class);

            String orderStatus = getJsonStr(mpGet, "status");
            String payStatus = "";
            String payId = "";

            try {
                if (mpGet.has("transactions") && mpGet.get("transactions").isJsonObject()) {
                    JsonObject tr = mpGet.getAsJsonObject("transactions");
                    if (tr.has("payments") && tr.get("payments").isJsonArray() && tr.getAsJsonArray("payments").size() > 0) {
                        JsonObject pay0 = tr.getAsJsonArray("payments").get(0).getAsJsonObject();
                        payStatus = getJsonStr(pay0, "status");
                        payId = getJsonStr(pay0, "id");
                    }
                }
            } catch (Exception ignored) {
            }

            boolean pagada
                    = "approved".equalsIgnoreCase(payStatus)
                    || "paid".equalsIgnoreCase(payStatus);

            if (!pagada) {
                // Negocio: no reembolsable porque no está pagada
                setOut(argv, 12, "2"); // código de negocio
                setOut(argv, 13, "No reembolsable: la orden no está pagada. payment.status=" + nvl(payStatus)
                        + " order.status=" + nvl(orderStatus));
                setOut(argv, 14, orderId);
                setOut(argv, 15, "");              // qr_data (no aplica)
                setOut(argv, 16, nvl(orderStatus)); // status
                setOut(argv, 17, nvl(payId));       // payment_id si existía
                setOut(argv, 18, g.body);           // mp_raw_json del GET para auditoría
                return 2;
            }

            // 2) Recién ahora hacemos el REFUND
            String endpointFmt = cfg.get("mp.endpoint.refundOrder", "/v1/orders/%s/refund");
            String endpoint = String.format(endpointFmt, orderId);

            logger.info("POST " + endpoint);
            logger.info("X-Idempotency-Key: " + idempotencyKey);

            MpHttpClient.MpHttpResponse r = http.postJson(endpoint, "{}", idempotencyKey);

            logger.info("HTTP(REFUND) " + r.statusCode);
            logger.info("RESP(REFUND) " + r.body);

            if (!r.is2xx()) {
                return fail(argv, 4, "REFUND: HTTP " + r.statusCode + " - " + r.body);
            }

            JsonObject mp = GSON.fromJson(r.body, JsonObject.class);

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

            setOut(argv, 12, "0");
            setOut(argv, 13, "OK");
            setOut(argv, 14, orderId);
            setOut(argv, 15, nvl(qrData));
            setOut(argv, 16, nvl(status));
            setOut(argv, 17, nvl(paymentId));
            setOut(argv, 18, r.body);

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error refundOrder: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
    }

    private int createStore(CobolVar[] argv) {

        String pathProperties = getStr(argv, 10);
        String reqJson = getStr(argv, 18); // <-- ENTRADA: JSON store
        String idempotencyKey = getStr(argv, 11);

        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }
        if (isBlank(reqJson)) {
            return fail(argv, 1, "Falta JSON de sucursal en argv[18] (AR-MP-RAW-JSON-MPQ)");
        }
        if (isBlank(idempotencyKey)) {
            idempotencyKey = "STORE-" + System.currentTimeMillis();
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient http = new MpHttpClient(cfg);

        String userId = cfg.userId(); // debe resolver test/pro según mp.etapa
        String endpointFmt = cfg.get("mp.endpoint.createStore", "/users/%s/stores");
        String endpoint = String.format(endpointFmt, userId);

        logger.info("POST " + endpoint);
        logger.info("X-Idempotency-Key: " + idempotencyKey);
        logger.info("REQ " + reqJson);

        try {
            MpHttpClient.MpHttpResponse r = http.postJson(endpoint, reqJson, idempotencyKey);

            logger.info("HTTP " + r.statusCode);
            logger.info("RESP " + r.body);

            if (!r.is2xx()) {
                return fail(argv, 4, "CREATE_STORE: HTTP " + r.statusCode + " - " + r.body);
            }

            JsonObject mp = GSON.fromJson(r.body, JsonObject.class);
            String storeId = getJsonStr(mp, "id"); // normalmente el store creado trae id

            setOut(argv, 12, "0");
            setOut(argv, 13, "Sucursal creada OK");
            setOut(argv, 14, nvl(storeId)); // reutilizamos AR-MP-ORDER-ID-MPQ como "id creado"
            setOut(argv, 18, r.body);

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error createStore: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
    }

    private int createPos(CobolVar[] argv) {

        String pathProperties = getStr(argv, 10);
        String reqJson = getStr(argv, 18); // <-- ENTRADA: JSON pos
        String idempotencyKey = getStr(argv, 11);

        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }
        if (isBlank(reqJson)) {
            return fail(argv, 1, "Falta JSON de caja/POS en argv[18] (AR-MP-RAW-JSON-MPQ)");
        }
        if (isBlank(idempotencyKey)) {
            idempotencyKey = "POS-" + System.currentTimeMillis();
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient http = new MpHttpClient(cfg);

        String endpoint = cfg.get("mp.endpoint.createPos", "/pos");

        logger.info("POST " + endpoint);
        logger.info("X-Idempotency-Key: " + idempotencyKey);
        logger.info("REQ " + reqJson);

        try {
            MpHttpClient.MpHttpResponse r = http.postJson(endpoint, reqJson, idempotencyKey);

            logger.info("HTTP " + r.statusCode);
            logger.info("RESP " + r.body);

            if (!r.is2xx()) {
                return fail(argv, 4, "CREATE_POS: HTTP " + r.statusCode + " - " + r.body);
            }

            JsonObject mp = GSON.fromJson(r.body, JsonObject.class);
            String posId = getJsonStr(mp, "id"); // normalmente trae id

            setOut(argv, 12, "0");
            setOut(argv, 13, "Caja/POS creada OK");
            setOut(argv, 14, nvl(posId));
            setOut(argv, 18, r.body);

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error createPos: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
    }

    private int searchStores(CobolVar[] argv) {

        String pathProperties = getStr(argv, 10);
        String query = getStr(argv, 18); // ENTRADA: querystring (ej: "external_id=SUCURSAL001&limit=50&offset=0")

        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient http = new MpHttpClient(cfg);

        String userId = cfg.userId();

        String endpointFmt = cfg.get("mp.endpoint.searchStores", "/users/%s/stores/search");
        String endpoint = String.format(endpointFmt, userId);
        endpoint = appendQuery(endpoint, query);

        logger.info("GET " + endpoint);

        try {
            MpHttpClient.MpHttpResponse r = http.get(endpoint, null);

            logger.info("HTTP " + r.statusCode);
            logger.info("RESP " + r.body);

            if (!r.is2xx()) {
                return fail(argv, 4, "SEARCH_STORES: HTTP " + r.statusCode + " - " + r.body);
            }

            setOut(argv, 12, "0");
            setOut(argv, 13, "OK");
            setOut(argv, 18, r.body); // mp_raw_json con results/paging

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error searchStores: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
    }

    private int searchPos(CobolVar[] argv) {

        String pathProperties = getStr(argv, 10);
        String query = getStr(argv, 18); // ENTRADA: querystring (ej: "external_id=SUCURSAL001PDV001&limit=50&offset=0")

        if (!isBlank(pathProperties)) {
            System.setProperty("mp.config", pathProperties);
        }

        MpConfig cfg = MpConfig.load();
        MpHttpClient http = new MpHttpClient(cfg);

        String endpoint = cfg.get("mp.endpoint.searchPos", "/pos");
        endpoint = appendQuery(endpoint, query);

        logger.info("GET " + endpoint);

        try {
            MpHttpClient.MpHttpResponse r = http.get(endpoint, null);

            logger.info("HTTP " + r.statusCode);
            logger.info("RESP " + r.body);

            if (!r.is2xx()) {
                return fail(argv, 4, "SEARCH_POS: HTTP " + r.statusCode + " - " + r.body);
            }

            setOut(argv, 12, "0");
            setOut(argv, 13, "OK");
            setOut(argv, 18, r.body);

            return 0;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error searchPos: " + e.getMessage(), e);
            return fail(argv, 5, "Excepción: " + e.getMessage());
        }
    }

    // ========= helpers =========
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
        setOut(argv, 12, String.valueOf(code));
        setOut(argv, 13, msg);
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

    @Override
    public void perform(int i, int i1) {
    }

    public void finalize() {
    }

}
