package com.hs;

public class Main {

    public static void main(String[] args) {

        try {
            MP_QR_HIBRIDO puente = new MP_QR_HIBRIDO();

            String jsonEntrada =
                    "{\n" +
                    "  \"type\": \"qr\",\n" +
                    "  \"total_amount\": \"54.00\",\n" +
                    "  \"description\": \"cuotas 5\",\n" +
                    "  \"external_reference\": \"ext_ref_00007\",\n" +
                    "  \"expiration_time\": \"PT23M\",\n" +
                    "  \"config\": {\n" +
                    "    \"qr\": {\n" +
                    "      \"external_pos_id\": \"SUCURSAL001PDV001\",\n" +
                    "      \"mode\": \"dynamic\"\n" +
                    "    }\n" +
                    "  },\n" +
                    "  \"transactions\": {\n" +
                    "    \"payments\": [ { \"amount\": \"54.00\" } ]\n" +
                    "  },\n" +
                    "  \"items\": [\n" +
                    "    {\n" +
                    "      \"title\": \"Cuotas Pendientes\",\n" +
                    "      \"unit_price\": \"54.00\",\n" +
                    "      \"quantity\": 1,\n" +
                    "      \"unit_measure\": \"unidad\",\n" +
                    "      \"external_code\": \"cuota 1\"\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}";

            System.out.println("===== LLAMADA PUENTE MP_QR_HIBRIDO =====");
//            String jsonSalida = puente.EXEC("CREATE_ORDER", jsonEntrada);
            System.out.println("===== RESPUESTA =====");
//            System.out.println(jsonSalida);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
