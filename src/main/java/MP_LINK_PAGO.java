import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
import com.hs.dto.PaymentLinkIn;
import com.hs.dto.PaymentLinkItemIn;
import com.iscobol.rts.IscobolCall;
import com.iscobol.types.CobolVar;
import com.iscobol.types.NumericVar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class MP_LINK_PAGO implements IscobolCall {

    private Logger logger;
    private FileHandler fh;

    private static final int MAX_ITEMS = 12;
    private static final int ITEM_BLOCK = 5;

    // Entradas fijas
    private static final int I_EXT_REF = 0;
    private static final int I_PAYER_EMAIL = 1;
    private static final int I_PAYER_NAME = 2;
    private static final int I_ADDITIONAL_INFO = 3;
    private static final int I_PATH = 4;
    private static final int I_CANT_ITEMS = 5;

    // Salidas
    private static final int O_RES = 66;
    private static final int O_MSG = 67;
    private static final int O_PREFERENCE_ID = 68;
    private static final int O_PAYMENT_LINK = 69;
    private static final int O_SANDBOX_LINK = 70;
    private static final int O_RAW_JSON = 71;

    @Override
    public Object call(Object[] argv) {
        CobolVar[] vars = new CobolVar[argv.length];
        for (int i = 0; i < argv.length; i++) {
            vars[i] = (CobolVar) argv[i];
        }
        return call(vars);
    }

    public CobolVar call(CobolVar[] argv) {
        initLoggerPerCall();

        try {
            logInputsCompact(argv);
            createPaymentLink(argv);
        } catch (Exception e) {
            safeLog(Level.SEVERE, "Excepción general en MP_LINK_PAGO", e);
            fail(argv, 9, "Excepción: " + safeMsg(e));
        } finally {
            try {
                logOutputsCompact(argv);
            } catch (Exception ignored) {
            }
            closeLoggerPerCall();
        }

        int res = parseIntDef(getStr(argv, O_RES), 9);
        return NumericVar.literal(res, false);
    }

    private int createPaymentLink(CobolVar[] argv) {
        String extRef = getStr(argv, I_EXT_REF);
        String payerEmail = getStr(argv, I_PAYER_EMAIL);
        String payerName = getStr(argv, I_PAYER_NAME);
        String additionalInfo = getStr(argv, I_ADDITIONAL_INFO);
        int cantItems = parseIntDef(getStr(argv, I_CANT_ITEMS), 0);

        if (isBlank(extRef)) {
            return fail(argv, 4, "external_reference es obligatorio");
        }
        if (cantItems <= 0) {
            return fail(argv, 4, "cant_items debe ser mayor a 0");
        }
        if (cantItems > MAX_ITEMS) {
            return fail(argv, 4, "cant_items supera el máximo permitido de " + MAX_ITEMS);
        }

        PaymentLinkIn in = new PaymentLinkIn();
        in.externalReference = extRef;
        in.payerEmail = payerEmail;
        in.payerName = payerName;
        in.additionalInfo = additionalInfo;
        in.items = new ArrayList<PaymentLinkItemIn>();

        for (int n = 1; n <= cantItems; n++) {
            int base = 6 + ((n - 1) * ITEM_BLOCK);

            String itemCode = getStr(argv, base);
            String itemTitle = getStr(argv, base + 1);
            String itemDesc = getStr(argv, base + 2);
            String itemQtyRaw = getStr(argv, base + 3);
            String itemPriceRaw = getStr(argv, base + 4);

            int qty = parseIntDef(itemQtyRaw, 0);
            double price = parseDoubleDef(itemPriceRaw, 0d);

            boolean filaVacia = isBlank(itemCode)
                    && isBlank(itemTitle)
                    && isBlank(itemDesc)
                    && qty == 0
                    && price == 0d;

            if (filaVacia) {
                continue;
            }

            if (isBlank(itemTitle)) {
                return fail(argv, 4, "El title del item " + n + " es obligatorio");
            }
            if (qty <= 0) {
                return fail(argv, 4, "La quantity del item " + n + " debe ser mayor a 0");
            }
            if (price <= 0d) {
                return fail(argv, 4, "El unit_price del item " + n + " debe ser mayor a 0");
            }

            PaymentLinkItemIn item = new PaymentLinkItemIn();
            item.code = itemCode;
            item.title = itemTitle;
            item.description = itemDesc;
            item.quantity = String.valueOf(qty);
            item.unitPrice = toMoneyString(price);

            in.items.add(item);
        }

        if (in.items.isEmpty()) {
            return fail(argv, 4, "No se informaron items válidos");
        }

        MpResult r = core(argv).createPaymentLink(in);
        writeOut(argv, r);
        return 0;
    }

    private MpBridgeCore core(CobolVar[] argv) {
        String path = getStr(argv, I_PATH);
        if (!isBlank(path)) {
            System.setProperty("mp.config", path);
        }
        MpConfig cfg = MpConfig.load();
        return new MpBridgeCore(cfg, logger);
    }

    private void writeOut(CobolVar[] argv, MpResult r) {
        setOut(argv, O_RES, String.valueOf(r != null ? r.res : 9));
        setOut(argv, O_MSG, r != null ? nn(r.msg) : "Respuesta nula");
        setOut(argv, O_PREFERENCE_ID, r != null ? nn(r.preferenceId) : "");
        setOut(argv, O_PAYMENT_LINK, r != null ? nn(r.paymentLink) : "");
        setOut(argv, O_SANDBOX_LINK, r != null ? nn(r.sandboxPaymentLink) : "");
        setOut(argv, O_RAW_JSON, r != null ? nn(r.rawJson) : "");
    }

    private int fail(CobolVar[] argv, int code, String msg) {
        setOut(argv, O_RES, String.valueOf(code));
        setOut(argv, O_MSG, msg);
        setOut(argv, O_PREFERENCE_ID, "");
        setOut(argv, O_PAYMENT_LINK, "");
        setOut(argv, O_SANDBOX_LINK, "");
        setOut(argv, O_RAW_JSON, "");
        return code;
    }

    private String getStr(CobolVar[] argv, int idx) {
        try {
            if (argv == null || idx < 0 || idx >= argv.length || argv[idx] == null) {
                return "";
            }
            String s = argv[idx].toString();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void setOut(CobolVar[] argv, int idx, String value) {
        try {
            if (argv != null && idx >= 0 && idx < argv.length && argv[idx] != null) {
                argv[idx].set(value == null ? "" : value);
            }
        } catch (Exception ignored) {
        }
    }

    private int parseIntDef(String s, int def) {
        try {
            return Integer.parseInt(nn(s).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private double parseDoubleDef(String s, double def) {
        try {
            String x = nn(s).trim().replace(",", ".");
            return Double.parseDouble(x);
        } catch (Exception e) {
            return def;
        }
    }

    private String toMoneyString(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String nn(String s) {
        return s == null ? "" : s;
    }

    private String safeMsg(Exception e) {
        String m = (e == null || e.getMessage() == null) ? "" : e.getMessage().trim();
        return m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private void initLoggerPerCall() {
        try {
            logger = Logger.getLogger("MP_LINK_PAGO_CALL");
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.INFO);

            removeAndCloseHandlers(logger);

            String dir = "C:\\A2JTMP";
            new File(dir).mkdirs();

            String file = dir + "\\MP_LINK_PAGO.log";
            fh = new FileHandler(file, true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.INFO);

            logger.addHandler(fh);
        } catch (Exception e) {
            logger = Logger.getLogger("MP_LINK_PAGO_FALLBACK");
        }
    }

    private void closeLoggerPerCall() {
        try {
            if (fh != null) {
                try { fh.flush(); } catch (Exception ignored) {}
                try { logger.removeHandler(fh); } catch (Exception ignored) {}
                try { fh.close(); } catch (Exception ignored) {}
            }
        } finally {
            fh = null;
        }
    }

    private void removeAndCloseHandlers(Logger log) {
        if (log == null) {
            return;
        }
        for (Handler h : log.getHandlers()) {
            try { h.flush(); } catch (Exception ignored) {}
            try { h.close(); } catch (Exception ignored) {}
            try { log.removeHandler(h); } catch (Exception ignored) {}
        }
    }

    private void logInputsCompact(CobolVar[] argv) {
        try {
            logger.info("==== MP_LINK_PAGO INPUTS ====");
            logger.info("external_reference=" + sanitize(getStr(argv, I_EXT_REF), 80)
                    + " payer_email=" + sanitize(getStr(argv, I_PAYER_EMAIL), 80)
                    + " payer_name=" + sanitize(getStr(argv, I_PAYER_NAME), 80)
                    + " cant_items=" + sanitize(getStr(argv, I_CANT_ITEMS), 10)
                    + " path=" + sanitize(getStr(argv, I_PATH), 200));
        } catch (Exception e) {
            safeLog(Level.WARNING, "No se pudieron loguear inputs", e);
        }
    }

    private void logOutputsCompact(CobolVar[] argv) {
        try {
            logger.info("==== MP_LINK_PAGO OUTPUTS ====");
            logger.info("res=" + sanitize(getStr(argv, O_RES), 10)
                    + " msg=" + sanitize(getStr(argv, O_MSG), 200));
            logger.info("preference_id=" + sanitize(getStr(argv, O_PREFERENCE_ID), 80));
            logger.info("payment_link=" + sanitize(getStr(argv, O_PAYMENT_LINK), 200));
            logger.info("sandbox_link=" + sanitize(getStr(argv, O_SANDBOX_LINK), 200));
            logger.info("raw_json_len=" + getStr(argv, O_RAW_JSON).length());
        } catch (Exception e) {
            safeLog(Level.WARNING, "No se pudieron loguear outputs", e);
        }
    }

    private String sanitize(String s, int max) {
        String x = nn(s).replace('\r', ' ').replace('\n', ' ').trim();
        if (max > 0 && x.length() > max) {
            return x.substring(0, max) + "...";
        }
        return x;
    }

    private void safeLog(Level level, String msg, Throwable t) {
        try {
            if (logger != null) {
                logger.log(level, msg, t);
            }
        } catch (Exception ignored) {
        }
    }

    
    @Override
    public void perform(int i, int i1) {
        // no-op
    }

    public void finalize() {
    }    
}