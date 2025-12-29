package com.hs.dto;

public class MpResult {
    public int res;          // 00 OK, 02 negocio, >=4 error
    public String msg;
    public String id;        // order_id / store_id / pos_id
    public String qrData;
    public String status;
    public String paymentId;
    public String rawJson;

    public static MpResult ok() {
        MpResult r = new MpResult();
        r.res = 0;
        r.msg = "OK";
        return r;
    }

    public static MpResult business(String msg) {
        MpResult r = new MpResult();
        r.res = 2;
        r.msg = msg;
        return r;
    }

    public static MpResult error(int code, String msg) {
        MpResult r = new MpResult();
        r.res = code;
        r.msg = msg;
        return r;
    }
}
