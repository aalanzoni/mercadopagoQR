package com.hs;

import com.hs.config.MpConfig;
import com.hs.core.MpBridgeCore;
import com.hs.dto.MpResult;
import com.hs.dto.OrderIn;
import com.hs.dto.PaymentLinkIn;
import com.hs.dto.PaymentLinkItemIn;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Test manual por consola de métodos de MpBridgeCore (Admin: S/LS/P/LP).
 *
 * Uso: java ... com.hs.MainMpAdminTest C:\ruta\mercadopagoQR.properties
 */
public class MainMpAdminTest {


    // -----------------------------------------------------------------------
    // Ejecutar este main para reproducir el bug de cancel 400.
    // Pasa la ruta al .properties como args[0] o como -Dmp.config=<ruta>.
    // -----------------------------------------------------------------------
    public static void main(String[] args) {
        if (args != null && args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            System.setProperty("mp.config", args[0].trim());
        }
        testDobleOrden();
    }

    private static void testDobleOrden() {
        try {
            MpConfig cfg = MpConfig.load();
            MpBridgeCore core = new MpBridgeCore(cfg, Logger.getLogger("MP_DOBLE_ORDEN_TEST"));

            long ts = System.currentTimeMillis();

            // 1) Crear orden 1 ($100) — no se cancela
            System.out.println("===== CREATE_ORDER 1 ($100) =====");
            OrderIn in1 = new OrderIn();
            in1.externalReference = "TEST-DOBLE-A-" + ts;
            in1.description       = "Orden 1 de prueba doble";
            in1.externalPosId     = "caja01ofhs";
            in1.mode              = "dynamic";
            in1.totalAmount       = "100.00";
            in1.unitMeasure       = "unit";
            in1.itemTitle         = "Item test 1";
            in1.externalCode      = "ITEM-TEST-1";
            in1.idempotencyKey    = "IDEM-A-" + ts;
            MpResult r1 = core.createOrder(in1);
            dump(r1);

            if (r1.res != 0) {
                System.out.println("ERROR creando orden 1. Se corta la prueba.");
                return;
            }
            System.out.println("order_id_1=" + r1.id);

            // 2) Sin cancelar la orden 1, crear orden 2 ($150) en el mismo POS
            System.out.println("\n===== CREATE_ORDER 2 ($150) - misma caja, sin cancelar la anterior =====");
            OrderIn in2 = new OrderIn();
            in2.externalReference = "TEST-DOBLE-B-" + ts;
            in2.description       = "Orden 2 de prueba doble";
            in2.externalPosId     = "caja01ofhs";
            in2.mode              = "dynamic";
            in2.totalAmount       = "150.00";
            in2.unitMeasure       = "unit";
            in2.itemTitle         = "Item test 2";
            in2.externalCode      = "ITEM-TEST-2";
            in2.idempotencyKey    = "IDEM-B-" + ts;
            MpResult r2 = core.createOrder(in2);
            dump(r2);

            // 3) Consultar el estado de la orden 1 para ver si MP la canceló automáticamente
            System.out.println("\n===== GET_ORDER 1 (estado después de crear orden 2) =====");
            MpResult rGet1 = core.getOrder(r1.id);
            dump(rGet1);

        } catch (Exception e) {
            System.out.println("ERROR general: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testCancelOrder() {
        try {
            MpConfig cfg = MpConfig.load();
            MpBridgeCore core = new MpBridgeCore(cfg, Logger.getLogger("MP_CANCEL_TEST"));

            String extRef = "TEST-CANCEL-" + System.currentTimeMillis();
            String idem   = "IDEM-" + extRef;

            // 1) Crear orden
            System.out.println("===== CREATE_ORDER =====");
            OrderIn in = new OrderIn();
            in.externalReference = extRef;
            in.description       = "Orden de prueba cancel";
            in.externalPosId     = "caja01ofhs";
            in.mode              = "dynamic";
            in.totalAmount       = "100.00";
            in.unitMeasure       = "unit";
            in.itemTitle         = "Item test";
            in.externalCode      = "ITEM-TEST";
            in.idempotencyKey    = idem;
            MpResult rCreate = core.createOrder(in);
            dump(rCreate);

            if (rCreate.res != 0) {
                System.out.println("ERROR creando orden. Se corta la prueba.");
                return;
            }

            String orderId = rCreate.id;
            System.out.println("order_id=" + orderId);

            // 2) Consultar estado inmediatamente
            System.out.println("\n===== GET_ORDER (antes de cancelar) =====");
            MpResult rGet1 = core.getOrder(orderId);
            dump(rGet1);

            // 3) Cancelar (intento 1)
            System.out.println("\n===== CANCEL_ORDER (intento 1) =====");
            MpResult rCancel = core.cancelOrder(orderId, "CANCEL-" + idem);
            dump(rCancel);

            // 4) Consultar estado después del intento 1
            System.out.println("\n===== GET_ORDER (después intento 1) =====");
            MpResult rGet2 = core.getOrder(orderId);
            dump(rGet2);

            // 5) Si falló y la orden sigue activa, reintentar con nueva idem key
            if (rCancel.res != 0 && "created".equalsIgnoreCase(rGet2.status)) {
                System.out.println("\n===== CANCEL_ORDER (intento 2 - reintento) =====");
                MpResult rCancel2 = core.cancelOrder(orderId, "CANCEL2-" + idem);
                dump(rCancel2);

                System.out.println("\n===== GET_ORDER (después intento 2) =====");
                MpResult rGet3 = core.getOrder(orderId);
                dump(rGet3);
            } else {
                System.out.println("\n[No se reintentó: cancel ok o la orden ya no está en created]");
            }

        } catch (Exception e) {
            System.out.println("ERROR general: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    private static void testPaymentLink() {
        try {
            MpConfig cfg = MpConfig.load();
//            MpBridgeCore core = new MpBridgeCore(cfg);
            MpBridgeCore core = new MpBridgeCore(cfg, Logger.getLogger("MP_ADMIN_TEST"));
            PaymentLinkIn in = new PaymentLinkIn();
            in.externalReference = "COB-TEST-20260519-0004";
            in.payerName = "Socio de Prueba";
            in.payerEmail = "aalanzoni@gmail.com";
            in.notificationUrl = "";
            in.backUrlSuccess = "";
            in.backUrlPending = "";
            in.backUrlFailure = "";
            in.expirationDateFrom = "";
            in.expirationDateTo = "";
            in.idempotencyKey = "IDEMP-COB-TEST-20260519-0004";

            List<PaymentLinkItemIn> items = new ArrayList<PaymentLinkItemIn>();

            PaymentLinkItemIn i1 = new PaymentLinkItemIn();
            i1.code = "CUOTA-2026-01";
            i1.title = "Cuota enero 2026";
            i1.description = "Servicio social - período 01/2026";
            i1.quantity = "1";
            i1.unitPrice = "10500.00";
            items.add(i1);

            PaymentLinkItemIn i2 = new PaymentLinkItemIn();
            i2.code = "CUOTA-2026-02";
            i2.title = "Cuota febrero 2026";
            i2.description = "Servicio social - período 02/2026";
            i2.quantity = "1";
            i2.unitPrice = "10250.00";
            items.add(i2);

            in.items = items;

            MpResult r = core.createPaymentLink(in);

            dump(r);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void dump(MpResult r) {
        System.out.println("res=" + r.res);
        System.out.println("msg=" + r.msg);
        System.out.println("id=" + r.id);
        System.out.println("status=" + r.status);
        System.out.println("payment_id=" + r.paymentId);
        if (r.rawJson != null) {
            String preview = r.rawJson.length() > 800 ? r.rawJson.substring(0, 800) + "..." : r.rawJson;
            System.out.println("raw_json=" + preview);
        }
    }

    private static long parseLongSafe(String s) {
        try {
            if (s == null) {
                return 0;
            }
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
