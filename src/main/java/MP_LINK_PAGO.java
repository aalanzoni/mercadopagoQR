
import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
import com.hs.dto.PaymentLinkIn;
import com.hs.dto.PaymentLinkItemIn;
import com.iscobol.rts.IscobolCall;
import com.iscobol.types.CobolVar;
import com.iscobol.types.NumericVar;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

    // Contrato actualizado
    // 00..05 : entradas fijas
    // 06..65 : items (12 bloques x 5)
    // 66..68 : nuevos inputs (idempotency / expiration_date_to / expiration_hours)
    // 69..75 : outputs
    private static final int MIN_ARGS = 76;

    // Entradas fijas
    private static final int I_EXT_REF = 0;
    private static final int I_PAYER_EMAIL = 1;
    private static final int I_PAYER_NAME = 2;
    private static final int I_ADDITIONAL_INFO = 3;
    private static final int I_PATH = 4;
    private static final int I_CANT_ITEMS = 5;
    private static final int I_FIRST_ITEM = 6;
    private static final int I_IDEMPOTENCY = 66;
    private static final int I_EXPIRATION_DATE_TO = 67;
    private static final int I_EXPIRATION_HOURS = 68;

    // Salidas
    private static final int O_RES = 69;
    private static final int O_MSG = 70;
    private static final int O_PREFERENCE_ID = 71;
    private static final int O_PAYMENT_LINK = 72;
    private static final int O_SANDBOX_LINK = 73;
    private static final int O_RAW_JSON = 74;
    private static final int O_PREF_EXTREF = 75;
    private static final int O_EFFECTIVE_EXPIRATION_TO = 76;

    private static final DateTimeFormatter EXPIRATION_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    public Object call(Object[] argv) {
        CobolVar[] argv2 = new CobolVar[argv.length];
        for (int i = 0; i < argv.length; i++) {
            argv2[i] = (CobolVar) argv[i];
        }
        return call(argv2);
    }

    public CobolVar call(CobolVar[] argv) {
        int resultado;

        initLoggerPerCall();

        try {
            if (argv == null || argv.length < MIN_ARGS) {
                safeLog(Level.SEVERE,
                        "Cantidad de argumentos inválida: "
                        + (argv == null ? 0 : argv.length),
                        null);
                return NumericVar.literal(9, false);
            }

            logInputsCompact(argv);

            int cantItems = parseIntDef(getStr(argv, I_CANT_ITEMS), 0);
            if (cantItems > 0) {
                resultado = createPaymentLink(argv);
            } else {
                resultado = queryPaymentLink(argv);
            }

        } catch (Exception e) {
            safeLog(Level.SEVERE, "Excepción general", e);
            fail(argv, 9, "Excepción: " + safeMsg(e));
            resultado = 9;

        } finally {
            try {
                if (argv != null && argv.length >= MIN_ARGS) {
                    logOutputsCompact(argv);
                }
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
        String idempotency = getStr(argv, I_IDEMPOTENCY);
        String expirationDateTo = getStr(argv, I_EXPIRATION_DATE_TO);
        int expirationHours = parseIntDef(getStr(argv, I_EXPIRATION_HOURS), 0);
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
        in.idempotencyKey = idempotency;
        in.expirationDateTo = expirationDateTo;
        in.expirationHours = expirationHours > 0 ? Integer.valueOf(expirationHours) : null;
        in.items = new ArrayList<PaymentLinkItemIn>();

        for (int n = 1; n <= cantItems; n++) {
            int base = I_FIRST_ITEM + ((n - 1) * ITEM_BLOCK);

            String itemCode = getStr(argv, base);
            String itemTitle = getStr(argv, base + 1);
//            if (itemTitle.isEmpty()){
//                itemTitle = buildDefaultItemTitle(itemCodigo, itemLetra, itemSucursal, itemNumero);
//            }
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

            if (qty <= 0) {
                qty = 1;
            }
            if (price <= 0d) {
                return fail(argv, 4, "El unit_price del item " + n + " debe ser mayor a 0");
            }
            if (isBlank(itemTitle)) {
                itemTitle = "Item " + n;
            }

            if (logger != null) {
                logger.info("item " + n
                        + " code=" + sanitize(itemCode, 40)
                        + " title=" + sanitize(itemTitle, 60)
                        + " qty=" + qty
                        + " price=" + toMoneyString(price));
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

        String effectiveExpirationTo =
                resolveEffectiveExpirationTo(expirationDateTo, expirationHours);

        MpResult r = core(argv).createPaymentLink(in);
        writeOut(argv, r);
        setOptionalEffectiveExpirationOut(argv,
                r != null && r.res == 0 ? effectiveExpirationTo : "");
        return 0;
    }

    private int queryPaymentLink(CobolVar[] argv) {
        String extRef = getStr(argv, I_EXT_REF);

        if (isBlank(extRef)) {
            return fail(argv, 4, "external_reference es obligatorio para query_payment");
        }

        MpResult r = core(argv).queryPaymentLink(extRef);
        writeOut(argv, r);
        setOptionalEffectiveExpirationOut(argv, "");
        return r != null ? r.res : 9;
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
        setOut(argv, O_PAYMENT_LINK,
                r != null ? firstNonBlank(r.paymentLink, r.paymentId) : "");
        setOut(argv, O_SANDBOX_LINK, r != null ? nn(r.sandboxPaymentLink) : "");
        setOut(argv, O_RAW_JSON, r != null ? nn(r.rawJson) : "");
        setOut(argv, O_PREF_EXTREF, r != null ? nn(r.preferenceExternalReference) : "");
    }

    private int fail(CobolVar[] argv, int code, String msg) {
        setOut(argv, O_RES, String.valueOf(code));
        setOut(argv, O_MSG, msg);
        setOut(argv, O_PREFERENCE_ID, "");
        setOut(argv, O_PAYMENT_LINK, "");
        setOut(argv, O_SANDBOX_LINK, "");
        setOut(argv, O_RAW_JSON, "");
        setOut(argv, O_PREF_EXTREF, "");
        setOptionalEffectiveExpirationOut(argv, "");
        return code;
    }


    private void setOptionalEffectiveExpirationOut(CobolVar[] argv, String value) {
        if (argv != null && argv.length > O_EFFECTIVE_EXPIRATION_TO) {
            setOut(argv, O_EFFECTIVE_EXPIRATION_TO, value);
        }
    }

    private String resolveEffectiveExpirationTo(String expirationDateTo,
            int expirationHours) {
        if (!isBlank(expirationDateTo)) {
            String normalized = normalizeExpirationDateTo(expirationDateTo);
            if (!isBlank(normalized)) {
                return normalized;
            }
        }

        if (expirationHours > 0) {
            return LocalDateTime.now().plusHours(expirationHours)
                    .format(EXPIRATION_FMT);
        }

        return "";
    }

    private String normalizeExpirationDateTo(String expirationDateTo) {
        String value = nn(expirationDateTo).trim();
        if (value.isEmpty()) {
            return "";
        }

        try {
            if (value.length() >= 19) {
                String base = value.substring(0, 19);
                return LocalDateTime.parse(base, EXPIRATION_FMT)
                        .format(EXPIRATION_FMT);
            }
            return LocalDateTime.parse(value, EXPIRATION_FMT)
                    .format(EXPIRATION_FMT);
        } catch (DateTimeParseException e) {
            return "";
        }
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

    private String buildItemCode(String codigo, String letra,
            String sucursal, String numero) {
        return nn(codigo).trim()
                + "|" + nn(letra).trim()
                + "|" + nn(sucursal).trim()
                + "|" + nn(numero).trim();
    }

    private String buildDefaultItemTitle(String codigo, String letra,
            String sucursal, String numero) {
            StringBuilder sb = new StringBuilder();
            sb.append("Comp.");
        if (!isBlank(codigo)) {
            sb.append(" ").append(nn(codigo).trim());
            }
        if (!isBlank(letra)) {
            sb.append(" ").append(nn(letra).trim());
            }
        if (!isBlank(sucursal)) {
            sb.append(" Suc.").append(nn(sucursal).trim());
            }
        if (!isBlank(numero)) {
            sb.append(" Nro.").append(nn(numero).trim());
            }
            return sb.toString().trim();
        }

    private String buildDefaultItemDescription(String fechaFiscal,
            String keyHistor) {
        StringBuilder sb = new StringBuilder();

        if (!isBlank(fechaFiscal)) {
            sb.append("Periodo ").append(nn(fechaFiscal).trim());
        }
        if (!isBlank(keyHistor)) {
            if (sb.length() > 0) {
                sb.append(" - ");
            }
            sb.append("Histor ").append(nn(keyHistor).trim());
        }

        return sb.toString();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String nn(String s) {
        return s == null ? "" : s;
    }

    private String firstNonBlank(String... values) {
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
            logger.setUseParentHandlers(true);
            logger.setLevel(Level.INFO);
        }
    }

    private void closeLoggerPerCall() {
        try {
            if (fh != null) {
                try {
                    fh.flush();
                } catch (Exception ignored) {
                }
                try {
                    logger.removeHandler(fh);
                } catch (Exception ignored) {
                }
                try {
                    fh.close();
                } catch (Exception ignored) {
                }
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
            try {
                h.flush();
            } catch (Exception ignored) {
            }
            try {
                h.close();
            } catch (Exception ignored) {
            }
            try {
                log.removeHandler(h);
            } catch (Exception ignored) {
            }
        }
    }

    private void logInputsCompact(CobolVar[] argv) {
        try {
            logger.info("==== MP_LINK_PAGO INPUTS ====");
            int cantItems = parseIntDef(getStr(argv, I_CANT_ITEMS), 0);
            logger.info("modo=" + (cantItems > 0 ? "CREATE" : "QUERY_PAYMENT")
                    + " external_reference=" + sanitize(getStr(argv, I_EXT_REF), 80)
                    + " payer_email=" + sanitize(getStr(argv, I_PAYER_EMAIL), 80)
                    + " payer_name=" + sanitize(getStr(argv, I_PAYER_NAME), 80)
                    + " additional_info=" + sanitize(getStr(argv, I_ADDITIONAL_INFO), 120)
                    + " cant_items=" + sanitize(getStr(argv, I_CANT_ITEMS), 10)
                    + " idempotency=" + sanitize(getStr(argv, I_IDEMPOTENCY), 80)
                    + " expiration_date_to=" + sanitize(getStr(argv, I_EXPIRATION_DATE_TO), 80)
                    + " expiration_hours=" + sanitize(getStr(argv, I_EXPIRATION_HOURS), 10)
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
            logger.info("pref_extref=" + sanitize(getStr(argv, O_PREF_EXTREF), 120));
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
                if (t != null) {
                    logger.log(level, msg, t);
                } else {
                    logger.log(level, msg);
                }
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
