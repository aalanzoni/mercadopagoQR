package com.hs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

// Ajustá estos imports según tu runtime ISCOBOL (igual que en MRP-001)
import com.iscobol.rts.IscobolCall;
import com.iscobol.rts.*;
import com.iscobol.types.*;

/**
 * Puente ISCOBOL -> MercadoPago QR híbrido.
 *
 * ACCION (argv[0]):
 *   "O" = Crear Orden QR (POST /v1/orders)
 *
 * ENTRADAS (accion "O"):
 *   argv[1]  external_reference
 *   argv[2]  description
 *   argv[3]  external_pos_id
 *   argv[4]  mode (dynamic/static)
 *   argv[5]  expiration_time (ej: PT23M)
 *   argv[6]  total_amount (string: "54.00")
 *   argv[7]  unit_measure (ej: "unidad")
 *   argv[8]  item_title
 *   argv[9]  external_code
 *   argv[10] path_properties (ruta al mercadopago.properties)
 *   argv[11] idempotency_key (opcional; si vacío usa external_reference)
 *
 * SALIDAS:
 *   argv[12] resultado (0 OK, !=0 ERROR)
 *   argv[13] msg
 *   argv[14] order_id
 *   argv[15] qr_data (recomendado en COBOL: X(1024))
 *   argv[16] status
 *   argv[17] payment_id
 *   argv[18] mp_raw_json (recomendado COBOL: X(20000) o X(32767) si da)
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
            if (argv2[i] != null) logger.info("argv[" + i + "]=" + argv2[i].toString());
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
        String description       = getStr(argv, 2);
        String externalPosId      = getStr(argv, 3);
        String mode              = getStr(argv, 4);
        String expirationTime    = getStr(argv, 5);
        String totalAmount       = getStr(argv, 6); // STRING
        String unitMeasure       = getStr(argv, 7);
        String itemTitle         = getStr(argv, 8);
        String externalCode      = getStr(argv, 9);
        String pathProperties    = getStr(argv, 10);
        String idempotencyKey    = getStr(argv, 11);

        // Validaciones mínimas
        if (isBlank(externalReference)) return fail(argv, 1, "Falta external_reference (argv[1])");
        if (isBlank(externalPosId))     return fail(argv, 2, "Falta external_pos_id (argv[3])");
        if (isBlank(totalAmount))       return fail(argv, 3, "Falta total_amount (argv[6])");
        if (isBlank(itemTitle))         itemTitle = "Item";
        if (isBlank(unitMeasure))       unitMeasure = "unidad";
        if (isBlank(mode))              mode = "dynamic";
        if (isBlank(idempotencyKey))    idempotencyKey = externalReference;

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

            String orderId   = getJsonStr(mp, "id");
            String status    = getJsonStr(mp, "status");
            String paymentId = null;
            String qrData    = null;

            try {
                if (mp.has("transactions") && mp.get("transactions").isJsonObject()) {
                    JsonObject tr = mp.getAsJsonObject("transactions");
                    if (tr.has("payments") && tr.get("payments").isJsonArray() && tr.getAsJsonArray("payments").size() > 0) {
                        JsonObject pay0 = tr.getAsJsonArray("payments").get(0).getAsJsonObject();
                        paymentId = getJsonStr(pay0, "id");
                    }
                }
            } catch (Exception ignored) {}

            try {
                if (mp.has("type_response") && mp.get("type_response").isJsonObject()) {
                    JsonObject tr = mp.getAsJsonObject("type_response");
                    qrData = getJsonStr(tr, "qr_data");
                }
            } catch (Exception ignored) {}

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

    // ========= helpers =========

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
            try { logger.removeHandler(fh); } catch (Exception ignored) {}
            try { fh.close(); } catch (Exception ignored) {}
        }
    }

    private String getStr(CobolVar[] argv, int idx) {
        try {
            if (argv == null || idx < 0 || idx >= argv.length) return "";
            if (argv[idx] == null) return "";
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
        } catch (Exception ignored) {}
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
            if (o == null || key == null) return "";
            if (!o.has(key) || o.get(key).isJsonNull()) return "";
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
