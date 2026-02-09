package com.hs.http;

import com.hs.config.MpConfig;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class MpHttpClient {

    private final MpConfig cfg;
    private final Logger logger;
    private final CloseableHttpClient client;

    private final boolean logHttp;
    private final int logHttpMax;

    public MpHttpClient(MpConfig cfg) {
        this(cfg, null);
    }

    public MpHttpClient(MpConfig cfg, Logger logger) {
        this.cfg = cfg;
        this.logger = logger;
        this.logHttp = cfg.logHttp();
        this.logHttpMax = cfg.logHttpMax();
        this.client = HttpClients.createDefault();
    }

    /* =========================================================
       PUBLIC API
       ========================================================= */
    public MpHttpResponse get(String endpoint) throws Exception {
        HttpGet req = new HttpGet(cfg.baseUrl() + endpoint);
        applyHeaders(req);
        return execute(req, null);
    }

    public MpHttpResponse get(String endpoint, String idempotencyKey) throws Exception {
        HttpGet req = new HttpGet(cfg.baseUrl() + endpoint);
        applyHeaders(req);
        applyIdempotency(req, idempotencyKey);
        return execute(req, null);
    }

    public MpHttpResponse postJson(String endpoint, String jsonBody) throws Exception {
        HttpPost req = new HttpPost(cfg.baseUrl() + endpoint);
        applyHeaders(req);
        if (jsonBody != null) {
            req.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
        }
        return execute(req, jsonBody);
    }

    public MpHttpResponse postJson(String endpoint, String jsonBody, String idempotencyKey) throws Exception {
        HttpPost req = new HttpPost(cfg.baseUrl() + endpoint);
        applyHeaders(req);
        applyIdempotency(req, idempotencyKey);
        if (jsonBody != null) {
            req.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
        }
        return execute(req, jsonBody);
    }

    public MpHttpResponse putJson(String endpoint, String jsonBody) throws Exception {
        HttpPut req = new HttpPut(cfg.baseUrl() + endpoint);
        applyHeaders(req);
        if (jsonBody != null) {
            req.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
        }
        return execute(req, jsonBody);
    }

    public MpHttpResponse putJson(String endpoint, String jsonBody, String idempotencyKey) throws Exception {
        HttpPut req = new HttpPut(cfg.baseUrl() + endpoint);
        applyHeaders(req);
        applyIdempotency(req, idempotencyKey);
        if (jsonBody != null) {
            req.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
        }
        return execute(req, jsonBody);
    }

    public MpHttpResponse delete(String endpoint) throws Exception {
        HttpDelete req = new HttpDelete(cfg.baseUrl() + endpoint);
        applyHeaders(req);
        return execute(req, null);
    }

    private void applyIdempotency(HttpRequestBase req, String idempotencyKey) {
        if (idempotencyKey != null) {
            String k = idempotencyKey.trim();
            if (!k.isEmpty()) {
                req.setHeader("X-Idempotency-Key", k); // (si antes usabas "Idempotency-Key", cambiamos abajo)
            }
        }
    }

    /* =========================================================
       CORE EXECUTION
       ========================================================= */
    private MpHttpResponse execute(HttpRequestBase req, String requestBody) throws Exception {

        if (logHttp && logger != null) {
            logger.info("MP HTTP REQUEST " + req.getMethod() + " " + req.getURI());
            if (requestBody != null && !requestBody.isEmpty()) {
                logger.info("MP HTTP REQUEST JSON: " + preview(requestBody));
            }
        }

        try ( CloseableHttpResponse resp = client.execute(req)) {

            int status = resp.getStatusLine().getStatusCode();
            String body = extractBody(resp);
            Header[] headers = resp.getAllHeaders();

            if (logHttp && logger != null) {
                logger.info("MP HTTP RESPONSE status=" + status);
                if (body != null && !body.isEmpty()) {
                    logger.info("MP HTTP RESPONSE JSON: " + preview(body));
                }
            }

            // ✅ ahora con headers
            return new MpHttpResponse(status, body, headers);
        }
    }

    /* =========================================================
       HELPERS
       ========================================================= */
    private void applyHeaders(HttpRequestBase req) {
        req.setHeader("Authorization", "Bearer " + cfg.accessToken());
        req.setHeader("Content-Type", "application/json");
        req.setHeader("Accept", "application/json");
    }

    private String extractBody(HttpResponse resp) throws Exception {
        HttpEntity entity = resp.getEntity();
        if (entity == null) {
            return "";
        }
        return EntityUtils.toString(entity, StandardCharsets.UTF_8);
    }

    private String preview(String s) {
        if (s == null) {
            return "";
        }
        return (s.length() <= logHttpMax)
                ? s
                : s.substring(0, logHttpMax) + "...";
    }

    /* =========================================================
       RESPONSE DTO
       ========================================================= */
    /**
     * Llamar al final del proceso si lo usás como “app standalone”.
     */
    public void closeQuietly() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
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
