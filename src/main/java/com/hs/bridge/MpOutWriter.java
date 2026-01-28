package com.hs.bridge;

import com.hs.dto.MpResult;
import com.iscobol.types.CobolVar;

public final class MpOutWriter {

    // OUT (contrato definitivo)
    private static final int O_RES   = 26;
    private static final int O_MSG   = 27;
    private static final int O_ID    = 28;
    private static final int O_QR    = 29;
    private static final int O_STATUS= 30;
    private static final int O_PAYID = 31;
    private static final int O_RAW   = 32;

    private MpOutWriter() {}

    public static void write(CobolVar[] argv, MpResult r) {
        setNum(argv, O_RES, r.res);
        setStr(argv, O_MSG, r.msg);
        setStr(argv, O_ID, r.id);
        setStr(argv, O_QR, r.qrData);
        setStr(argv, O_STATUS, r.status);
        setStr(argv, O_PAYID, r.paymentId);
        setStr(argv, O_RAW, r.rawJson);
    }

    // Ajustá estas 2 funciones a tus helpers reales si ya los tenés en MP_QR_HIBRIDO.
    private static void setStr(CobolVar[] argv, int idx, String v) {
        if (argv[idx] == null) return;
        argv[idx].set(v == null ? "" : v);
    }

    private static void setNum(CobolVar[] argv, int idx, int v) {
        if (argv[idx] == null) return;
        argv[idx].set(String.valueOf(v));
    }
}
