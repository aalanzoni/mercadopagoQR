package com.hs.http;

public interface MpHttp {

    MpHttpResponse get(String endpoint) throws Exception;

    MpHttpResponse postJson(String endpoint, String jsonBody, String idempotencyKey) throws Exception;

    class MpHttpResponse {
        public final int httpCode;
        public final String body;

        public MpHttpResponse(int httpCode, String body) {
            this.httpCode = httpCode;
            this.body = body;
        }
    }
}
