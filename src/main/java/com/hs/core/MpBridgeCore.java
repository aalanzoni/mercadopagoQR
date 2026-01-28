package com.hs.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hs.config.MpConfig;
import com.hs.dto.*;
import com.hs.http.MpHttp;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Capa de negocio/armado de JSON para Mercado Pago.
 *
 * Esta clase NO conoce ISCOBOL. Es testeable (mockeando MpHttp).
 */
public class MpBridgeCore {

    private final MpConfig cfg;
    private final MpHttp http;
    private final Gson gson = new Gson();

    public MpBridgeCore(MpConfig cfg, MpHttp http) {
        this.cfg = cfg;
        this.http = http;
    }

    // --------------------------
    // Ordenes (O/Q/C/R)
    // --------------------------
    public MpResult createOrder(OrderIn in) {
        if (in == null) {
            return MpResult.error(4, "Falta input");
        }
        if (isBlank(in.externalReference)) {
            return MpResult.error(4, "Falta external_reference");
        }
        if (isBlank(in.externalPosId)) {
            return MpResult.error(4, "Falta external_pos_id");
        }
        if (isBlank(in.totalAmount)) {
            return MpResult.error(4, "Falta total_amount");
        }

        String mode = isBlank(in.mode) ? "dynamic" : in.mode.trim();
        String unitMeasure = isBlank(in.unitMeasure) ? "unidad" : in.unitMeasure.trim();
        String itemTitle = isBlank(in.itemTitle) ? "Item" : in.itemTitle.trim();
        String idem = isBlank(in.idempotencyKey) ? in.externalReference.trim() : in.idempotencyKey.trim();

        String endpoint = cfg.get("mp.endpoint.createOrder", "/v1/orders");

        JsonObject body = new JsonObject();
        body.addProperty("type", "qr");
        body.addProperty("total_amount", in.totalAmount.trim());
        body.addProperty("description", nvl(in.description));
        body.addProperty("external_reference", in.externalReference.trim());
        if (!isBlank(in.expirationTime)) {
            body.addProperty("expiration_time", in.expirationTime.trim());
        }

        JsonObject cfgNode = new JsonObject();
        JsonObject qr = new JsonObject();
        qr.addProperty("external_pos_id", in.externalPosId.trim());
        qr.addProperty("mode", mode);
        cfgNode.add("qr", qr);
        body.add("config", cfgNode);

        JsonObject transactions = new JsonObject();
        JsonArray payments = new JsonArray();
        JsonObject p0 = new JsonObject();
        p0.addProperty("amount", in.totalAmount.trim());
        payments.add(p0);
        transactions.add("payments", payments);
        body.add("transactions", transactions);

        JsonArray items = new JsonArray();
        JsonObject it = new JsonObject();
        it.addProperty("title", itemTitle);
        it.addProperty("unit_price", in.totalAmount.trim());
        it.addProperty("quantity", 1);
        it.addProperty("unit_measure", unitMeasure);
        if (!isBlank(in.externalCode)) {
            it.addProperty("external_code", in.externalCode.trim());
        }
        items.add(it);
        body.add("items", items);

        try {
            MpHttp.MpHttpResponse r = http.postJson(endpoint, gson.toJson(body), idem);

            MpResult out = MpResult.ok();
            out.rawJson = r.body;

            if (r.httpCode < 200 || r.httpCode >= 300) {
                // create suele devolver 201; si llegamos aquí es error
                out.res = 5;
                out.msg = "MP createOrder HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);
            out.id = getJsonStr(mp, "id");
            out.status = getJsonStr(mp, "status");
            out.paymentId = extractPaymentId(mp);
            out.qrData = extractQrData(mp);
            out.msg = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico createOrder: " + ex.getMessage());
        }
    }

    public MpResult getOrder(String orderId) {
        if (isBlank(orderId)) {
            return MpResult.error(4, "Falta order_id");
        }

        String endpointFmt = cfg.get("mp.endpoint.getOrder", "/v1/orders/%s");
        String endpoint = String.format(endpointFmt, orderId.trim());

        try {
            MpHttp.MpHttpResponse r = http.get(endpoint);

            MpResult out = MpResult.ok();
            out.id = orderId.trim();
            out.rawJson = r.body;

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP getOrder HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);
            out.status = getJsonStr(mp, "status");
            out.paymentId = extractPaymentId(mp);
            out.qrData = extractQrData(mp);
            out.msg = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico getOrder: " + ex.getMessage());
        }
    }

    public MpResult cancelOrder(String orderId, String idempotencyKey) {
        if (isBlank(orderId)) {
            return MpResult.error(4, "Falta order_id");
        }

        try {
            // 1) validar status
            MpResult q = getOrder(orderId);
            if (q.res != 0) {
                return q;
            }

            String status = nvl(q.status);
            if (!"created".equalsIgnoreCase(status)) {
                MpResult out = MpResult.business("No se puede cancelar: status=" + status);
                out.id = orderId.trim();
                out.status = status;
                out.paymentId = q.paymentId;
                out.qrData = q.qrData;
                out.rawJson = q.rawJson;
                return out;
            }

            // 2) cancelar
            String endpointFmt = cfg.get("mp.endpoint.cancelOrder", "/v1/orders/%s/cancel");
            String endpoint = String.format(endpointFmt, orderId.trim());

            String idem = isBlank(idempotencyKey) ? orderId.trim() : idempotencyKey.trim();
            MpHttp.MpHttpResponse r = http.postJson(endpoint, "{}", idem);

            MpResult out = MpResult.ok();
            out.id = orderId.trim();
            out.rawJson = r.body;

            if (r.httpCode == 409) {
                out.res = 2;
                out.msg = "Negocio: cannot_cancel_order/expired";
                return out;
            }

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP cancelOrder HTTP " + r.httpCode;
                return out;
            }

            out.status = "cancelled";
            out.msg = "Cancelada";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico cancelOrder: " + ex.getMessage());
        }
    }

    public MpResult refundOrder(String orderId, String idempotencyKey) {
        if (isBlank(orderId)) {
            return MpResult.error(4, "Falta order_id");
        }

        try {
            // 1) consultar orden y verificar pago
            MpResult q = getOrder(orderId);
            if (q.res != 0) {
                return q;
            }

            JsonObject mp = safeObj(q.rawJson);
            PaymentInfo pi = extractPaymentInfo(mp);

            boolean paid = "approved".equalsIgnoreCase(pi.status) || "paid".equalsIgnoreCase(pi.status);
            if (!paid) {
                MpResult out = MpResult.business("No reembolsable: payment.status=" + nvl(pi.status) + " order.status=" + nvl(q.status));
                out.id = orderId.trim();
                out.status = q.status;
                out.paymentId = pi.id;
                out.rawJson = q.rawJson;
                return out;
            }

            // 2) refund
            String endpointFmt = cfg.get("mp.endpoint.refundOrder", "/v1/orders/%s/refund");
            String endpoint = String.format(endpointFmt, orderId.trim());

            String idem = isBlank(idempotencyKey) ? orderId.trim() : idempotencyKey.trim();
            MpHttp.MpHttpResponse r = http.postJson(endpoint, "{}", idem);

            MpResult out = MpResult.ok();
            out.id = orderId.trim();
            out.paymentId = pi.id;
            out.rawJson = r.body;

            if (r.httpCode == 409) {
                out.res = 2;
                out.msg = "Negocio: cannot_refund_order";
                return out;
            }

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP refundOrder HTTP " + r.httpCode;
                return out;
            }

            out.msg = "Refund OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico refundOrder: " + ex.getMessage());
        }
    }

    // --------------------------
    // Admin: Stores (S / LS)
    // --------------------------
    public MpResult createStore(StoreIn in) {
        if (in == null) {
            return MpResult.error(4, "Falta input");
        }
        if (isBlank(in.name)) {
            return MpResult.error(4, "Falta store_name");
        }
        if (isBlank(in.externalId)) {
            return MpResult.error(4, "Falta store_external_id");
        }

        String userId = cfg.userId();
        if (isBlank(userId)) {
            return MpResult.error(4, "Falta user_id en config");
        }

        String endpointFmt = cfg.get("mp.endpoint.createStore", "/users/%s/stores");
        String endpoint = String.format(endpointFmt, userId.trim());

        JsonObject body = new JsonObject();
        body.addProperty("name", in.name.trim());
        body.addProperty("external_id", in.externalId.trim());

        JsonObject loc = new JsonObject();
        if (!isBlank(in.street)) {
            loc.addProperty("street_name", in.street.trim());
        }
        if (!isBlank(in.streetNumber)) {
            loc.addProperty("street_number", in.streetNumber.trim());
        }
        if (!isBlank(in.city)) {
            loc.addProperty("city_name", in.city.trim());
        }
        if (!isBlank(in.state)) {
            loc.addProperty("state_name", in.state.trim());
        }
        if (!isBlank(in.latitude)) {
            loc.addProperty("latitude", parseDoubleSafe(in.latitude));
        }
        if (!isBlank(in.longitude)) {
            loc.addProperty("longitude", parseDoubleSafe(in.longitude));
        }
        body.add("location", loc);

        String idem = isBlank(in.idempotencyKey) ? in.externalId.trim() : in.idempotencyKey.trim();

        try {
            MpHttp.MpHttpResponse r = http.postJson(endpoint, gson.toJson(body), idem);
            MpResult out = MpResult.ok();
            out.rawJson = r.body;

            if (r.httpCode == 409) {
                out.res = 2;
                out.msg = "Negocio: store ya existe/409";
                return out;
            }
            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP createStore HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);
            out.id = firstNonBlank(getJsonStr(mp, "id"), getJsonStr(mp, "store_id"));
            out.msg = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico createStore: " + ex.getMessage());
        }
    }

    
public MpResult searchStores(SearchIn in) {

    String userId = cfg.userId();
    if (isBlank(userId)) {
        return MpResult.error(4, "Falta user_id en config");
    }

    return searchStores(userId, in);
}

    public MpResult searchStores(String userId, SearchIn in) {

        int limit = (in != null && in.limit > 0) ? in.limit : 50;
        int offset = (in != null && in.offset >= 0) ? in.offset : 0;
        String filter = (in != null && in.filterExternalId != null)
                ? in.filterExternalId
                : "";

        return searchStores(userId, limit, offset, filter);
    }

// ===============================
// SEARCH POS (LP)
// ===============================
    public MpResult searchPos(SearchIn in) {

        int limit = (in != null && in.limit > 0) ? in.limit : 50;
        int offset = (in != null && in.offset >= 0) ? in.offset : 0;
        String filter = (in != null && in.filterExternalId != null)
                ? in.filterExternalId
                : "";

        return searchPos(limit, offset, filter);
    }

    public MpResult searchStores(String userId, int limit, int offset, String externalStoreId) {
        if (isBlank(userId)) {
            return MpResult.error(4, "Falta user_id");
        }
        String endpointFmt = cfg.get("mp.endpoint.searchStores", "/users/%s/stores/search");
        String base = String.format(endpointFmt, userId.trim());

        StringBuilder sb = new StringBuilder(base);
        sb.append("?limit=").append(limit);
        sb.append("&offset=").append(offset);
        if (!isBlank(externalStoreId)) {
            sb.append("&external_id=").append(url(externalStoreId.trim()));
        }

        try {
            MpHttp.MpHttpResponse r = http.get(sb.toString());
            MpResult out = MpResult.ok();
            out.rawJson = r.body;

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP searchStores HTTP " + r.httpCode;
                return out;
            }

            out.msg = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico searchStores: " + ex.getMessage());
        }
    }

    // overload de compatibilidad (por si algún main viejo lo llama con 5 params)
    public MpResult searchStores(String userId, int limit, int offset, String externalStoreId, String ignored) {
        return searchStores(userId, limit, offset, externalStoreId);
    }

    // --------------------------
    // Admin: POS (P / LP)
    // --------------------------
    public MpResult createPos(PosIn in) {
        if (in == null) {
            return MpResult.error(4, "Falta input");
        }
        if (isBlank(in.name)) {
            return MpResult.error(4, "Falta pos_name");
        }
        if (isBlank(in.externalId)) {
            return MpResult.error(4, "Falta pos_external_id");
        }
        if (in.storeId <= 0) {
            return MpResult.error(4, "Falta store_id");
        }

        String endpoint = cfg.get("mp.endpoint.createPos", "/pos");

        JsonObject body = new JsonObject();
        body.addProperty("name", in.name.trim());
        body.addProperty("external_id", in.externalId.trim());
        body.addProperty("store_id", in.storeId);

        String idem = isBlank(in.idempotencyKey) ? in.externalId.trim() : in.idempotencyKey.trim();

        try {
            MpHttp.MpHttpResponse r = http.postJson(endpoint, gson.toJson(body), idem);
            MpResult out = MpResult.ok();
            out.rawJson = r.body;

            if (r.httpCode == 409) {
                out.res = 2;
                out.msg = "Negocio: pos ya existe/409";
                return out;
            }
            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP createPos HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);
            out.id = firstNonBlank(getJsonStr(mp, "id"), getJsonStr(mp, "pos_id"));
            out.msg = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico createPos: " + ex.getMessage());
        }
    }

    public MpResult createPos(String posName, String posExternalId, long storeId, String idempotencyKey) {
        PosIn in = new PosIn();
        in.name = posName;
        in.externalId = posExternalId;
        in.storeId = storeId;
        in.idempotencyKey = idempotencyKey;
        return createPos(in);
    }

    public MpResult searchPos(int limit, int offset, String externalPosId) {
        String base = cfg.get("mp.endpoint.searchPos", "/pos");

        StringBuilder sb = new StringBuilder(base);
        sb.append("?limit=").append(limit);
        sb.append("&offset=").append(offset);
        if (!isBlank(externalPosId)) {
            sb.append("&external_id=").append(url(externalPosId.trim()));
        }

        try {
            MpHttp.MpHttpResponse r = http.get(sb.toString());
            MpResult out = MpResult.ok();
            out.rawJson = r.body;

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP searchPos HTTP " + r.httpCode;
                return out;
            }

            out.msg = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico searchPos: " + ex.getMessage());
        }
    }

    // --------------------------
    // Parsing helpers
    // --------------------------
    private static class PaymentInfo {

        String id;
        String status;
    }

    private PaymentInfo extractPaymentInfo(JsonObject mp) {
        PaymentInfo pi = new PaymentInfo();
        if (mp == null) {
            return pi;
        }
        try {
            JsonObject transactions = obj(mp.get("transactions"));
            JsonArray payments = arr(transactions == null ? null : transactions.get("payments"));
            if (payments != null && payments.size() > 0) {
                JsonObject p0 = obj(payments.get(0));
                pi.id = getJsonStr(p0, "id");
                pi.status = getJsonStr(p0, "status");
            }
        } catch (Exception ignored) {
        }
        return pi;
    }

    private String extractPaymentId(JsonObject mp) {
        return nvl(extractPaymentInfo(mp).id);
    }

    private String extractQrData(JsonObject mp) {
        try {
            JsonObject tr = obj(mp == null ? null : mp.get("type_response"));
            return getJsonStr(tr, "qr_data");
        } catch (Exception ignored) {
            return "";
        }
    }

    private JsonObject safeObj(String json) {
        try {
            if (isBlank(json)) {
                return new JsonObject();
            }
            JsonObject o = gson.fromJson(json, JsonObject.class);
            return o == null ? new JsonObject() : o;
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static JsonObject obj(JsonElement e) {
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonElement e) {
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static String getJsonStr(JsonObject o, String k) {
        try {
            if (o == null || k == null) {
                return "";
            }
            JsonElement e = o.get(k);
            if (e == null || e.isJsonNull()) {
                return "";
            }
            return e.getAsString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static double parseDoubleSafe(String s) {
        String x = s.trim().replace(',', '.');
        return Double.parseDouble(x);
    }

    private static String url(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 siempre existe, esto es solo por contrato del método
            return s;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : (!isBlank(b) ? b : "");
    }
}
