package com.hs;

import com.hs.http.MpHttpClient;
import com.hs.config.MpConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.logging.Logger;

public class MpQrService {

    private static final Gson GSON = new Gson();
    private final Logger logger;
    private final MpConfig config = MpConfig.load();
    private final MpHttpClient http = new MpHttpClient(config);

    public MpQrService(Logger logger) {
        this.logger = logger;
    }

    public String dispatch(String accion, String jsonEntrada) throws Exception {
        if (accion == null) {
            accion = "";
        }
        accion = accion.trim().toUpperCase();

        JsonObject in = safeParse(jsonEntrada);

        switch (accion) {
            case "CREATE_STORE":
                return createStore(in);
            case "CREATE_POS":
                return createPos(in);
            case "CREATE_ORDER":
                return createOrder(in);
            case "CANCEL_ORDER":
                return cancelOrder(in);
            case "REFUND_ORDER":
                return refundOrder(in);
            case "GET_ORDER":
                return getOrder(in);
            default:
                return MpBridgeResponse.fail("Acción no soportada: " + accion);
        }
    }

    private JsonObject safeParse(String json) {
        try {
            if (json == null || json.trim().isEmpty()) {
                return new JsonObject();
            }
            return GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            return new JsonObject(); // si viene roto, devolvemos error abajo en cada acción si faltan campos
        }
    }

    private String createStore(JsonObject in) throws Exception {
        // TODO validar campos
        // TODO armar endpoint + body según MP
        return MpBridgeResponse.ok("createStore pendiente", new JsonObject());
    }

    private String createPos(JsonObject in) throws Exception {
        return MpBridgeResponse.ok("createPos pendiente", new JsonObject());
    }

    private String createOrder(JsonObject in) throws Exception {

        String endpoint = config.get("mp.endpoint.createOrder", "/v1/orders");

        logger.info("POST " + endpoint);

        JsonObject body = extractBody(in);

        // Si no viene type, lo ponemos (es requerido para QR)
        if (!body.has("type")) {
            body.addProperty("type", "qr");
        }

        // Validaciones mínimas
        String externalRef = getAsString(body, "external_reference");
        String totalAmount = getAsString(body, "total_amount");
        if (isBlank(externalRef)) {
            return MpBridgeResponse.fail("CREATE_ORDER: falta 'external_reference'.");
        }
        if (isBlank(totalAmount)) {
            return MpBridgeResponse.fail("CREATE_ORDER: falta 'total_amount'.");
        }

        // Idempotency
        String idemKey = getAsString(in, "idempotency_key");
        if (isBlank(idemKey)) {
            idemKey = externalRef;
        }
        logger.info("BODY " + GSON.toJson(body));
        MpHttpClient.MpHttpResponse r = http.postJson(endpoint, GSON.toJson(body), idemKey);
        logger.info("HTTP " + r.statusCode + " RESP " + r.body);
        if (r.is2xx() || r.statusCode == 201) {
            JsonObject mp = safeParseObj(r.body);
            if (mp == null) {
                mp = new JsonObject();
            }

            // Extraer campos útiles
            JsonObject data = new JsonObject();
            data.addProperty("httpStatus", r.statusCode);
            data.add("mp", mp);

            data.addProperty("order_id", getAsString(mp, "id"));
            data.addProperty("external_reference", getAsString(mp, "external_reference"));
            data.addProperty("status", getAsString(mp, "status"));
            data.addProperty("status_detail", getAsString(mp, "status_detail"));

            // payment_id: transactions.payments[0].id
            String paymentId = null;
            try {
                if (mp.has("transactions") && mp.get("transactions").isJsonObject()) {
                    JsonObject tr = mp.getAsJsonObject("transactions");
                    if (tr.has("payments") && tr.get("payments").isJsonArray() && tr.getAsJsonArray("payments").size() > 0) {
                        JsonObject p0 = tr.getAsJsonArray("payments").get(0).getAsJsonObject();
                        paymentId = getAsString(p0, "id");
                    }
                }
            } catch (Exception ignored) {
            }
            if (paymentId != null) {
                data.addProperty("payment_id", paymentId);
            }

            // qr_data: type_response.qr_data
            String qrData = null;
            try {
                if (mp.has("type_response") && mp.get("type_response").isJsonObject()) {
                    JsonObject tr = mp.getAsJsonObject("type_response");
                    qrData = getAsString(tr, "qr_data");
                }
            } catch (Exception ignored) {
            }
            if (qrData != null) {
                data.addProperty("qr_data", qrData);
            }

            return MpBridgeResponse.ok("Orden creada OK", data);
        }

        return MpBridgeResponse.fail(buildHttpError("CREATE_ORDER", r));
    }

    private String cancelOrder(JsonObject in) throws Exception {
        return MpBridgeResponse.ok("cancelOrder pendiente", new JsonObject());
    }

    private String refundOrder(JsonObject in) throws Exception {
        return MpBridgeResponse.ok("refundOrder pendiente", new JsonObject());
    }

    private String getOrder(JsonObject in) throws Exception {
        return MpBridgeResponse.ok("getOrder pendiente", new JsonObject());
    }

    private JsonObject extractBody(JsonObject in) {
        if (in == null) {
            return new JsonObject();
        }
        JsonElement e = in.get("body");
        if (e != null && e.isJsonObject()) {
            return e.getAsJsonObject();
        }
        return in;
    }

    private String buildHttpError(String action, MpHttpClient.MpHttpResponse r) {
        // Ojo: r.body puede traer JSON con detalle (lo devolvemos completo)
        return action + ": HTTP " + r.statusCode + " - " + (r.body == null ? "" : r.body);
    }

    private JsonObject safeParseObj(String json) {
        try {
            return GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String getAsString(JsonObject o, String k) {
        try {
            if (o == null || k == null) {
                return null;
            }
            JsonElement e = o.get(k);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            return e.getAsString();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
