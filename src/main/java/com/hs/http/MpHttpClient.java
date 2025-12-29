package com.hs.http;

import com.hs.config.MpConfig;
import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MpHttpClient {

    private final MpConfig cfg;
    private final CloseableHttpClient client;

    public MpHttpClient(MpConfig cfg) {
        this.cfg = cfg;

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(cfg.connectTimeoutMs())
                .setConnectionRequestTimeout(cfg.connectTimeoutMs())
                .setSocketTimeout(cfg.socketTimeoutMs())
                .build();

        this.client = HttpClients.custom()
                .setDefaultRequestConfig(rc)
                .build();
    }

    public MpHttpResponse get(String path, String idempotencyKey) throws Exception {
        HttpGet req = new HttpGet(fullUrl(path));
        applyCommonHeaders(req, idempotencyKey);
        return execute(req);
    }

    public MpHttpResponse postJson(String path, String jsonBody, String idempotencyKey) throws Exception {
        HttpPost req = new HttpPost(fullUrl(path));
        applyCommonHeaders(req, idempotencyKey);
        req.setEntity(new StringEntity(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
        return execute(req);
    }

    public MpHttpResponse putJson(String path, String jsonBody, String idempotencyKey) throws Exception {
        HttpPut req = new HttpPut(fullUrl(path));
        applyCommonHeaders(req, idempotencyKey);
        req.setEntity(new StringEntity(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
        return execute(req);
    }

    public MpHttpResponse delete(String path, String idempotencyKey) throws Exception {
        HttpDelete req = new HttpDelete(fullUrl(path));
        applyCommonHeaders(req, idempotencyKey);
        return execute(req);
    }

    private MpHttpResponse execute(HttpRequestBase req) throws Exception {
        CloseableHttpResponse resp = null;
        try {
            resp = client.execute(req);

            int status = resp.getStatusLine().getStatusCode();
            String body = resp.getEntity() != null
                    ? EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8)
                    : "";

            Header[] headers = resp.getAllHeaders();
            return new MpHttpResponse(status, body, headers);

        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
        }
    }

    private void applyCommonHeaders(HttpRequestBase req, String idempotencyKey) {
        // Auth
        req.setHeader("Authorization", "Bearer " + cfg.accessToken());

        // Content
        req.setHeader("Content-Type", "application/json");
        req.setHeader("Accept", "application/json");

        // Idempotency (clave para CREATE_ORDER, etc.)
        String key = (idempotencyKey == null || idempotencyKey.trim().isEmpty())
                ? null
                : idempotencyKey.trim();

        if (key != null) {
            // Mercado Pago usa X-Idempotency-Key
            req.setHeader("X-Idempotency-Key", key);
        }

        // Trace opcional
        req.setHeader("X-Request-Id", UUID.randomUUID().toString());
    }

    private String fullUrl(String path) {
        String base = cfg.baseUrl();
        if (path == null) path = "";
        if (!path.startsWith("/")) path = "/" + path;

        // Evita doble slash
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }

    /** Llamar al final del proceso si lo usás como “app standalone”. */
    public void closeQuietly() {
        try { client.close(); } catch (Exception ignored) {}
    }

    public static class MpHttpResponse {
        public final int statusCode;
        public final String body;
        public final Header[] headers;

        public MpHttpResponse(int statusCode, String body, Header[] headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
        }

        public boolean is2xx() {
            return statusCode >= 200 && statusCode <= 299;
        }
    }
}
