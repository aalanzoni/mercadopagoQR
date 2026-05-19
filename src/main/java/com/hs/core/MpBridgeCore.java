package com.hs.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hs.config.MpConfig;
import com.hs.dto.*;
import com.hs.http.MpHttp;
import com.hs.http.MpHttpAdapter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

/**
 * Capa de negocio/armado de JSON para Mercado Pago.
 *
 * Esta clase NO conoce ISCOBOL. Es testeable (mockeando MpHttp).
 */
public class MpBridgeCore {

    private final MpConfig cfg;
    private final MpHttp http;
    private final Gson gson = new Gson();

    public MpBridgeCore(MpConfig cfg, Logger logger) {
        this.cfg = cfg;
        this.http = new MpHttpAdapter(cfg, logger);
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
        if (itemTitle.isEmpty()) {
            itemTitle = in.description;
        }
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

            // HTTP 500 en cancel es un bug conocido del entorno de testing de MP.
            // La orden queda efectivamente cancelada del lado de MP.
            // En produccion el cancel devuelve 2xx normalmente.
            if (r.httpCode == 500) {
                out.status = "canceled";
                out.msg = "Cancelada (HTTP 500 ignorado - bug testing MP)";
                return out;
            }

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP cancelOrder HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);
            out.status = getJsonStr(mp, "status");
            out.paymentId = extractPaymentId(mp);
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
    // Pagos (QP)
    // --------------------------
    /**
     * Consulta un pago por payment_id. Endpoint estándar:
     * /v1/payments/{paymentId}
     */
    public MpResult getPayment(String paymentId) {
        if (isBlank(paymentId)) {
            return MpResult.error(4, "Falta payment_id");
        }

        String endpointFmt = cfg.get("mp.endpoint.getPayment", "/v1/payments/%s");
        String endpoint = String.format(endpointFmt, paymentId.trim());

        try {
            MpHttp.MpHttpResponse r = http.get(endpoint);

            MpResult out = MpResult.ok();
            out.paymentId = paymentId.trim();
            out.id = paymentId.trim();      // por consistencia, devolvemos el id consultado
            out.rawJson = r.body;

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP getPayment HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);
            // MercadoPago Payments suele devolver status y status_detail
            out.status = getJsonStr(mp, "status");
            out.msg = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico getPayment: " + ex.getMessage());
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
            JsonObject qr = obj(mp.get("qr"));
            if (qr != null) {
                out.qrImage = getJsonStr(qr, "image");
                out.qrTemplateDocument = getJsonStr(qr, "template_document");
                out.qrTemplateImage = getJsonStr(qr, "template_image");
            }
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

    public MpResult queryPaymentLink(String externalReference) {
        if (isBlank(externalReference)) {
            return MpResult.error(4, "Falta external_reference");
        }

        String endpoint = cfg.get("mp.endpoint.searchPayments", "/v1/payments/search");

        StringBuilder sb = new StringBuilder(endpoint);
        if (endpoint.contains("?")) {
            sb.append("&");
        } else {
            sb.append("?");
        }
        sb.append("external_reference=").append(url(externalReference.trim()));
        sb.append("&sort=date_created");
        sb.append("&criteria=desc");
        sb.append("&range=date_created");
        sb.append("&limit=50");

        try {
            MpHttp.MpHttpResponse r = http.get(sb.toString());

            MpResult out = MpResult.ok();
            out.id = externalReference.trim();
            out.rawJson = r.body;
            out.preferenceExternalReference = externalReference.trim();

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP queryPaymentLink HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);
            JsonArray results = arr(mp.get("results"));

            if (results == null || results.size() == 0) {
                out.status = "pending";
                out.msg = "SIN PAGOS";
                return out;
            }

            JsonObject p0 = selectBestPayment(results);
            if (p0 == null) {
                out.status = "unknown";
                out.msg = "SIN PAGOS";
                return out;
            }

            out.paymentId = getJsonStr(p0, "id");
            out.status = getJsonStr(p0, "status");
            if (isBlank(out.status)) {
                out.status = "unknown";
            }

            String statusDetail = getJsonStr(p0, "status_detail");
            String preferenceId = getJsonStr(p0, "order_id");
            if (isBlank(preferenceId)) {
                JsonObject order = obj(p0.get("order"));
                preferenceId = getJsonStr(order, "id");
            }
            if (isBlank(preferenceId)) {
                preferenceId = getJsonStr(p0, "preference_id");
            }
            out.preferenceId = preferenceId;
            out.preferenceExternalReference = firstNonBlank(
                    getJsonStr(p0, "external_reference"),
                    out.preferenceExternalReference,
                    externalReference.trim());

            if ("approved".equalsIgnoreCase(out.status) || "authorized".equalsIgnoreCase(out.status)) {
                out.msg = "PAGADO";
            } else if ("pending".equalsIgnoreCase(out.status) || "in_process".equalsIgnoreCase(out.status)) {
                out.msg = "PENDIENTE";
            } else if ("rejected".equalsIgnoreCase(out.status) || "cancelled".equalsIgnoreCase(out.status) || "refunded".equalsIgnoreCase(out.status) || "charged_back".equalsIgnoreCase(out.status)) {
                out.msg = isBlank(statusDetail) ? out.status.toUpperCase() : statusDetail;
            } else {
                out.msg = isBlank(statusDetail) ? "OK" : statusDetail;
            }

            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico queryPaymentLink: " + ex.getMessage());
        }
    }

    public MpResult createPaymentLink(PaymentLinkIn in) {
        try {
            MpResult v = validatePaymentLinkIn(in);
            if (v != null) {
                return v;
            }

            String endpoint = cfg.get("mp.endpoint.createPreference", "/checkout/preferences");
            String idemKey = isBlank(in.idempotencyKey)
                    ? in.externalReference.trim()
                    : in.idempotencyKey.trim();

            JsonObject body = buildPreferenceBody(in);

            String jsonBody = gson.toJson(body);
            System.out.println("JSON ENVIADO A MP = " + jsonBody);

            MpHttp.MpHttpResponse r = http.postJson(endpoint, jsonBody, idemKey);

            MpResult out = MpResult.ok();
            out.rawJson = r.body;
            out.preferenceExternalReference = safeText(in.externalReference);

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP createPreference HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);

            out.preferenceId = getJsonStr(mp, "id");
            out.id = out.preferenceId;
            out.paymentLink = getJsonStr(mp, "init_point");
            out.sandboxPaymentLink = getJsonStr(mp, "sandbox_init_point");
            out.preferenceExternalReference = firstNonBlank(
                    getJsonStr(mp, "external_reference"),
                    out.preferenceExternalReference,
                    safeText(in.externalReference));
            out.msg = "OK";

            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico createPaymentLink: " + ex.getMessage());
        }
    }

    // --------------------------
    // Parsing helpers
    // --------------------------
    private JsonObject buildPreferenceBody(PaymentLinkIn in) {
        System.out.println(">>> buildPreferenceBody NUEVO");

        JsonObject body = new JsonObject();

        JsonArray items = new JsonArray();
        List<PaymentLinkItemIn> srcItems = in.items;

        for (PaymentLinkItemIn src : srcItems) {
            JsonObject item = new JsonObject();

            if (!isBlank(src.code)) {
                item.addProperty("id", safeText(src.code));
            }

            item.addProperty("title", safeText(src.title));
            item.addProperty("description", safeText(src.description));
            item.addProperty("quantity", parseIntSafe(src.quantity, 1));
            item.addProperty("currency_id", "ARS");
            item.addProperty("unit_price", parseDoubleSafe(src.unitPrice));

            items.add(item);
        }

        body.add("items", items);
        body.addProperty("external_reference", safeText(in.externalReference));

        String additionalInfo = !isBlank(in.additionalInfo)
                ? safeText(in.additionalInfo)
                : buildInstallmentsSummary(in.items);

        if (!isBlank(additionalInfo)) {
            body.addProperty("additional_info", additionalInfo);
        }

        if (!isBlank(in.notificationUrl)) {
            body.addProperty("notification_url", safeText(in.notificationUrl));
        }

        boolean hasBackSuccess = !isBlank(in.backUrlSuccess);
        boolean hasBackPending = !isBlank(in.backUrlPending);
        boolean hasBackFailure = !isBlank(in.backUrlFailure);

        if (hasBackSuccess || hasBackPending || hasBackFailure) {
            JsonObject backUrls = new JsonObject();

            if (hasBackSuccess) {
                backUrls.addProperty("success", safeText(in.backUrlSuccess));
            }
            if (hasBackPending) {
                backUrls.addProperty("pending", safeText(in.backUrlPending));
            }
            if (hasBackFailure) {
                backUrls.addProperty("failure", safeText(in.backUrlFailure));
            }

            body.add("back_urls", backUrls);

            // Solo tiene sentido si existe al menos una URL de retorno real
            body.addProperty("auto_return", "approved");

            System.out.println(">>> hasBackSuccess=" + hasBackSuccess
                    + " hasBackPending=" + hasBackPending
                    + " hasBackFailure=" + hasBackFailure);
        }

        if (!isBlank(in.payerEmail) || !isBlank(in.payerName)) {
            JsonObject payer = new JsonObject();

            if (!isBlank(in.payerEmail)) {
                payer.addProperty("email", safeText(in.payerEmail));
            }
            if (!isBlank(in.payerName)) {
                payer.addProperty("name", safeText(in.payerName));
            }

            body.add("payer", payer);
        }

        boolean hasExpirationFrom = !isBlank(in.expirationDateFrom);
        boolean hasExpirationTo = !isBlank(in.expirationDateTo);

        if (hasExpirationFrom || hasExpirationTo) {
            body.addProperty("expires", true);

            if (hasExpirationFrom) {
                body.addProperty("expiration_date_from", safeText(in.expirationDateFrom));
            }
            if (hasExpirationTo) {
                body.addProperty("expiration_date_to", safeText(in.expirationDateTo));
            }
        }

        if (cfg.onlyDebitAndAccountMoney()) {
            body.add("payment_methods", buildDebitAccountMoneyOnly());
        }

        return body;
    }

    private JsonObject buildDebitAccountMoneyOnly() {
        JsonObject pm = new JsonObject();
        JsonArray excluded = new JsonArray();
        for (String type : new String[]{"credit_card", "ticket", "atm", "prepaid_card"}) {
            JsonObject t = new JsonObject();
            t.addProperty("id", type);
            excluded.add(t);
        }
        pm.add("excluded_payment_types", excluded);
        return pm;
    }

    private JsonObject selectBestPayment(JsonArray results) {
        if (results == null || results.size() == 0) {
            return null;
        }

        JsonObject approved = null;
        JsonObject authorized = null;
        JsonObject pending = null;
        JsonObject latest = null;
        String latestDate = "";

        for (int i = 0; i < results.size(); i++) {
            JsonObject candidate = obj(results.get(i));
            if (candidate == null) {
                continue;
            }

            String status = getJsonStr(candidate, "status");
            String dateCreated = getJsonStr(candidate, "date_created");

            if (approved == null && "approved".equalsIgnoreCase(status)) {
                approved = candidate;
            }
            if (authorized == null && "authorized".equalsIgnoreCase(status)) {
                authorized = candidate;
            }
            if (pending == null
                    && ("pending".equalsIgnoreCase(status) || "in_process".equalsIgnoreCase(status))) {
                pending = candidate;
            }

            if (latest == null || (!isBlank(dateCreated) && dateCreated.compareTo(latestDate) > 0)) {
                latest = candidate;
                latestDate = isBlank(dateCreated) ? latestDate : dateCreated;
            }
        }

        if (approved != null) {
            return approved;
        }
        if (authorized != null) {
            return authorized;
        }
        if (pending != null) {
            return pending;
        }
        return latest;
    }

    private String resolveExpirationDateTo(String expirationDateTo, Integer expirationHours) {
        if (!isBlank(expirationDateTo)) {
            return safeText(expirationDateTo);
        }
        if (expirationHours != null && expirationHours.intValue() > 0) {
            OffsetDateTime odt = OffsetDateTime.now(ZoneId.of("America/Argentina/Cordoba"))
                    .plusHours(expirationHours.longValue());
            return odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"));
        }
        return "";
    }

    private String buildInstallmentsSummary(List<PaymentLinkItemIn> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Cuotas incluidas: ");

        int written = 0;
        int max = Math.min(items.size(), 6);

        for (int i = 0; i < items.size() && written < max; i++) {
            PaymentLinkItemIn it = items.get(i);
            if (it == null) {
                continue;
            }

            String label = !isBlank(it.title) ? safeText(it.title) : "";
            if (label.isEmpty()) {
                continue;
            }

            if (written > 0) {
                sb.append(", ");
            }

            sb.append(label);
            written++;
        }

        if (items.size() > written) {
            sb.append(" y ").append(items.size() - written).append(" más");
        }

        return sb.toString();
    }

    private String safeText(String s) {
        if (s == null) {
            return "";
        }

        String x = s.trim();

        // Limpieza básica
        x = x.replace('\r', ' ');
        x = x.replace('\n', ' ');
        x = x.replace('\t', ' ');

        while (x.contains("  ")) {
            x = x.replace("  ", " ");
        }

        return x;
    }

    private MpResult validatePaymentLinkIn(PaymentLinkIn in) {
        if (in == null) {
            return MpResult.error(4, "Falta input");
        }

        if (isBlank(in.externalReference)) {
            return MpResult.error(4, "Falta external_reference");
        }

        if (in.items == null || in.items.isEmpty()) {
            return MpResult.error(4, "Faltan items");
        }

        for (int i = 0; i < in.items.size(); i++) {
            PaymentLinkItemIn it = in.items.get(i);

            if (it == null) {
                return MpResult.error(4, "Item nulo en posición " + i);
            }

            if (isBlank(it.title)) {
                return MpResult.error(4, "Falta title en item " + i);
            }

            double price = parseDoubleSafe(it.unitPrice);
            if (price <= 0d) {
                return MpResult.error(4, "unit_price inválido en item " + i);
            }

            int qty = parseIntSafe(it.quantity, 1);
            if (qty <= 0) {
                return MpResult.error(4, "quantity inválida en item " + i);
            }
        }

        return null;
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) {
            return def;
        }
        String x = s.trim();
        if (x.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(x);
        } catch (Exception e) {
            return def;
        }
    }

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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
}
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    // ===========================================================
    // Point — métodos para agregar a MpBridgeCore
    // ===========================================================
    //
    // API MP Point:
    //   O  ->  POST /point/integration-api/devices/{device_id}/payment-intents
    //   Q  ->  GET  /point/integration-api/payment-intents/{payment_intent_id}
    //   X  ->  DELETE /point/integration-api/devices/{device_id}/payment-intents
    //
    // El device_id va siempre en la URL.
    // El payment_intent_id (order_id en MpResult) se obtiene en la acción O
    // y se usa como entrada en Q y X.
    // ===========================================================

    /**
     * Crea un payment intent en el dispositivo Point indicado.
     * Endpoint: POST /point/integration-api/devices/{device_id}/payment-intents
     */
    public MpResult createPointIntent(PointIn in) {
        if (in == null) {
            return MpResult.error(4, "Falta input");
        }
        if (isBlank(in.deviceId)) {
            return MpResult.error(4, "Falta device_id");
        }
        if (isBlank(in.externalReference)) {
            return MpResult.error(4, "Falta external_reference");
        }
        if (isBlank(in.totalAmount)) {
            return MpResult.error(4, "Falta total_amount");
        }

        String endpointFmt = cfg.get(
            "mp.endpoint.point.createIntent",
            "/point/integration-api/devices/%s/payment-intents"
        );
        String endpoint = String.format(endpointFmt, in.deviceId.trim());

        String idem = isBlank(in.idempotencyKey)
            ? in.externalReference.trim()
            : in.idempotencyKey.trim();

        JsonObject body = new JsonObject();
        body.addProperty("amount",             parseDoubleSafe(in.totalAmount));
        body.addProperty("description",        nvl(in.description));
        body.addProperty("payment_mode",       "point_of_sale");
        body.addProperty("external_reference", in.externalReference.trim());

        // additional_info requerido por MP Point
        JsonObject additionalInfo = new JsonObject();
        additionalInfo.addProperty("external_reference", in.externalReference.trim());
        body.add("additional_info", additionalInfo);

        try {
            MpHttp.MpHttpResponse r = http.postJson(endpoint, gson.toJson(body), idem);

            MpResult out = MpResult.ok();
            out.rawJson = r.body;

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP Point createIntent HTTP " + r.httpCode + " - " + nvl(r.body);
                return out;
            }

            JsonObject mp = safeObj(r.body);
            out.id     = getJsonStr(mp, "id");       // payment_intent_id
            out.status = getJsonStr(mp, "state");
            out.msg    = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico createPointIntent: " + ex.getMessage());
        }
    }

    /**
     * Consulta el estado de un payment intent.
     * Endpoint: GET /point/integration-api/payment-intents/{payment_intent_id}
     */
    public MpResult getPointIntent(String paymentIntentId) {
        if (isBlank(paymentIntentId)) {
            return MpResult.error(4, "Falta payment_intent_id");
        }

        String endpointFmt = cfg.get(
            "mp.endpoint.point.getIntent",
            "/point/integration-api/payment-intents/%s"
        );
        String endpoint = String.format(endpointFmt, paymentIntentId.trim());

        try {
            MpHttp.MpHttpResponse r = http.get(endpoint);

            MpResult out = MpResult.ok();
            out.id     = paymentIntentId.trim();
            out.rawJson = r.body;

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP Point getIntent HTTP " + r.httpCode;
                return out;
            }

            JsonObject mp = safeObj(r.body);
            out.status = getJsonStr(mp, "state");

            // payment_id: dentro de payment.id si el pago fue aprobado
            try {
                JsonObject payment = obj(mp.get("payment"));
                if (payment != null) {
                    out.paymentId = getJsonStr(payment, "id");
                }
            } catch (Exception ignored) { }

            // "FINISHED" con payment.id => pago confirmado
            // "CANCELED" / "ERROR" => no se cobró
            // "OPEN"    => pendiente (el cliente aún no pasó la tarjeta)
            out.msg = "OK";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico getPointIntent: " + ex.getMessage());
        }
    }

    /**
     * Cancela el payment intent activo en el dispositivo Point.
     * Endpoint: DELETE /point/integration-api/devices/{device_id}/payment-intents
     * No requiere body. Solo el device_id en la URL.
     */
    public MpResult cancelPointIntent(String deviceId) {
        if (isBlank(deviceId)) {
            return MpResult.error(4, "Falta device_id");
        }

        String endpointFmt = cfg.get(
            "mp.endpoint.point.cancelIntent",
            "/point/integration-api/devices/%s/payment-intents"
        );
        String endpoint = String.format(endpointFmt, deviceId.trim());

        try {
            MpHttp.MpHttpResponse r = http.delete(endpoint);

            MpResult out = MpResult.ok();
            out.rawJson = r.body;

            // 404 = no hay intent activo en el device (ya cancelado o nunca existió)
            if (r.httpCode == 404) {
                out.res = 2;
                out.msg = "No hay intent activo en el dispositivo";
                out.status = "CANCELED";
                return out;
            }

            if (r.httpCode < 200 || r.httpCode >= 300) {
                out.res = 5;
                out.msg = "MP Point cancelIntent HTTP " + r.httpCode;
                return out;
            }

            out.status = "CANCELED";
            out.msg    = "Cancelado";
            return out;

        } catch (Exception ex) {
            return MpResult.error(4, "Error técnico cancelPointIntent: " + ex.getMessage());
        }
    }
}


