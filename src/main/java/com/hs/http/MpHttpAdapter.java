package com.hs.http;
import com.hs.config.MpConfig;
import java.util.logging.Logger;

public class MpHttpAdapter implements MpHttp {

    private final MpHttpClient http;
    

    public MpHttpAdapter(MpConfig cfg, Logger logger) {
        this.http = new MpHttpClient(cfg, logger);
    }

    @Override
    public MpHttpResponse get(String endpoint) throws Exception {
        // MpHttpClient.get(path, idempotencyKey)
        MpHttpClient.MpHttpResponse r = http.get(endpoint, null);
        return new MpHttpResponse(r.statusCode, r.body);
    }

    @Override
    public MpHttpResponse postJson(String endpoint, String jsonBody, String idempotencyKey) throws Exception {
        MpHttpClient.MpHttpResponse r = http.postJson(endpoint, jsonBody, idempotencyKey);
        return new MpHttpResponse(r.statusCode, r.body);
    }
}
