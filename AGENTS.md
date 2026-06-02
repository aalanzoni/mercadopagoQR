# mercadopagoQR — Manual del Desarrollador

## Qué hace este proyecto

Middleware Java 8 que actúa de puente entre un sistema legacy **ISCobol** y las **APIs REST de Mercado Pago**. ISCobol llama clases Java pasando un array de `CobolVar[]` (argv); el Java arma los JSON, llama a MP y devuelve resultados escribiendo en las posiciones de salida del mismo array.

---

## Convención de nomenclatura: crítica para entender el código

| Estilo | Significado | Ejemplos |
|--------|-------------|---------|
| `ALL_CAPS.java` | Puente ISCobol — punto de entrada, implementa `IscobolCall` | `MP_QR_HIBRIDO`, `MP_LINK_PAGO`, `MP_POINT` |
| `PascalCase.java` | Lógica interna Java pura, no conoce ISCobol | `MpBridgeCore`, `MpConfig`, `MpHttpClient` |

Los puentes ALL_CAPS solo traducen argv[] → DTOs → delegan a `MpBridgeCore` → escriben el resultado de vuelta en argv[]. **Nunca tienen lógica de negocio propia.**

---

## Mapa de archivos

```
src/main/java/
├── MP_QR_HIBRIDO.java          Puente QR híbrido (órdenes QR, stores, POS, pagos)
├── MP_LINK_PAGO.java           Puente Checkout Pro (links de pago)
├── MP_POINT.java               Puente MP Point (terminales físicas)
└── com/hs/
    ├── core/
    │   └── MpBridgeCore.java   NÚCLEO: toda la lógica de negocio y armado de JSON
    ├── config/
    │   └── MpConfig.java       Carga de mercadopagoQR.properties
    ├── http/
    │   ├── MpHttp.java         Interfaz (permite mockear en tests)
    │   ├── MpHttpAdapter.java  Adaptador
    │   └── MpHttpClient.java   Implementación con Apache HttpComponents
    ├── bridge/
    │   └── MpOutWriter.java    Escribe MpResult → argv[26..35]
    └── dto/
        ├── MpResult.java       Contenedor universal de respuesta
        ├── OrderIn.java        Entrada para crear orden QR
        ├── StoreIn.java        Entrada para crear store
        ├── PosIn.java          Entrada para crear POS
        ├── PointIn.java        Entrada para MP Point
        ├── PaymentLinkIn.java  Entrada para link de pago
        ├── PaymentLinkItemIn.java  Ítem de link de pago
        └── SearchIn.java       Parámetros de búsqueda

src/main/resources/
└── mercadopagoQR.properties    Configuración (tokens, endpoints, timeouts)
```

**No usar:** `com/hs/MpQrService.java` — clase legacy, no está en uso.

---

## Dónde buscar cuando hay un bug

### 1. Primero: los logs

Cada puente escribe su propio log en `C:\A2JTMP\` por llamada:

| Clase puente | Archivo de log |
|-------------|---------------|
| `MP_QR_HIBRIDO` | `C:\A2JTMP\MP_QR_HIBRIDO.log` |
| `MP_LINK_PAGO` | `C:\A2JTMP\MP_LINK_PAGO.log` |
| `MP_POINT` | `C:\A2JTMP\MP_POINT.log` |

Cada log registra:
- Todos los **inputs** compactos al inicio de la llamada
- Todos los **outputs** al final (incluyendo `raw_json` de MP en chunks de 500 chars)
- El stack trace completo si hubo excepción

### 2. Leer el `raw_json` (argv[32])

El JSON crudo de la respuesta de MP siempre está disponible en `argv[32]` (o en el log). Es el primer lugar a mirar cuando el resultado no es el esperado.

### 3. Códigos de resultado (argv[26])

| Código | Significado | Dónde buscar |
|--------|-------------|--------------|
| `0` | OK | — |
| `2` | Error de negocio (estado inválido, ya existe, 409) | Lógica en `MpBridgeCore` |
| `4` | Validación o error técnico Java | Validaciones en `MpBridgeCore` o el puente |
| `5` | Error HTTP de Mercado Pago | Ver `raw_json` para el mensaje de MP |
| `8` | Acción inválida (argv[0] desconocida) | El switch en el puente ALL_CAPS |
| `9` | Excepción no controlada | Stack trace en el log |

---

## Contrato argv[] por puente

### MP_QR_HIBRIDO (36 posiciones, índices 0–35)

**Entradas:**
```
[0]  accion         O / Q / C / R / QP / S / P / LS / LP
[1]  ext_ref        external_reference (O) o order_id (Q/C/R) o payment_id (QP)
[2]  descripcion
[3]  ext_pos_id     ID externo de la caja (POS)
[4]  modo           "dynamic" o "static"
[5]  expiracion     ISO 8601
[6]  total_amount
[7]  unit_measure
[8]  item_title
[9]  external_code
[10] path_props     Ruta al .properties (override)
[11] idempotency_key
[12] store_name
[13] store_external_id
[14] store_street
[15] store_street_number
[16] store_city
[17] store_state
[18] store_latitude
[19] store_longitude
[20] pos_name
[21] pos_external_id
[22] pos_store_id   (numérico, store MP interno)
[23] limit          para búsquedas
[24] offset         para búsquedas
[25] filter_extid   para búsquedas
```

**Salidas:**
```
[26] resultado      0=OK, !=0 ERROR
[27] mensaje
[28] id             order_id / store_id / pos_id según acción
[29] qr_data        string del QR dinámico
[30] status         "created" / "cancelled" / "approved" / etc.
[31] payment_id
[32] raw_json       respuesta completa de MP
[33] qr_image       (solo createPos)
[34] qr_template_document
[35] qr_template_image
```

### MP_LINK_PAGO (77 posiciones, índices 0–76)

```
[0]  external_reference
[1]  payer_email
[2]  payer_name
[3]  additional_info
[4]  path_props
[5]  cant_items        0=modo query, >0=modo create
[6..65] items (12 bloques x 5 campos: code, title, desc, qty, price)
[66] idempotency_key
[67] expiration_date_to   formato: yyyy-MM-dd'T'HH:mm:ss
[68] expiration_hours     si no hay fecha, calcula desde ahora
--- salidas ---
[69] resultado
[70] mensaje
[71] preference_id
[72] payment_link        init_point (prod)
[73] sandbox_link        sandbox_init_point
[74] raw_json
[75] pref_external_reference
[76] effective_expiration_to  (fecha efectiva calculada)
```

**Modo de operación:** si `cant_items > 0` → crea preferencia; si `cant_items = 0` → consulta pagos por `external_reference`.

### MP_POINT (13 posiciones, índices 0–12)

```
[0]  accion       O=Crear Intent  Q=Consultar  X=Cancelar
[1]  ext_ref      referencia externa
[2]  device_id    ID del dispositivo Point (O y X)
[3]  total_amount
[4]  description
[5]  idempotency_key
[6]  intent_id    entrada en Q/X; salida en O (se sobreescribe)
[7]  path_props
--- salidas ---
[8]  resultado
[9]  mensaje
[10] status       OPEN / FINISHED / CANCELED / ERROR
[11] payment_id   solo si status=FINISHED
[12] raw_json
```

---

## Flujo de una llamada (traza completa)

```
ISCobol
  → llama MP_QR_HIBRIDO.call(argv[])          [puente ALL_CAPS]
    → switch(accion)
    → arma DTO (OrderIn, StoreIn, etc.)
    → new MpBridgeCore(MpConfig.load(), logger)
      → MpConfig.load() busca mercadopagoQR.properties
    → MpBridgeCore.createOrder(in)             [lógica de negocio]
      → valida campos obligatorios
      → arma JSON con Gson
      → MpHttpClient.postJson(endpoint, body, idem)  [HTTP]
        → Apache HttpComponents
        → Header Authorization: Bearer <accessToken>
        → Header X-Idempotency-Key: <idem>
      → parsea respuesta JSON
      → retorna MpResult
    → MpOutWriter.write(argv, result)          [escribe salidas en argv]
  → ISCobol recibe argv[] con salidas escritas
```

---

## Configuración — mercadopagoQR.properties

### Orden de búsqueda (MpConfig.load)
1. `-Dmp.config=RUTA` (VM option, override por llamada via argv[10])
2. `./mercadopagoQR.properties` (working directory)
3. `/mercadopagoQR.properties` (classpath)
4. `target/classes/mercadopagoQR.properties` (fallback dev)

### Propiedades clave

```properties
mp.etapa=test               # "test" o "prod" — determina qué token usar

# Credenciales (separadas por entorno)
mp.accessTokenTest=APP_USR-...
mp.accessToken=??           # PROD — completar antes de producción
mp.userIdTest=1859061146
mp.userId=??                # PROD

mp.baseUrl=https://api.mercadopago.com
mp.timeout.connect=10000    # ms
mp.timeout.socket=20000     # ms

mp.log.http=true            # loguea requests/responses HTTP
mp.log.http.max=2000        # trunca el body a N chars en el log
```

---

## Casos especiales conocidos

### HTTP 500 al cancelar orden (entorno TEST)
`MpBridgeCore.cancelOrder()` línea ~200: si MP devuelve 500 durante un cancel en testing, se trata como éxito y se devuelve `status="cancelled"`. En producción MP devuelve 2xx normalmente. **No es un bug del código, es un bug del sandbox de MP.**

### Idempotency key
Si no se pasa `idempotency_key`, el código usa `external_reference` como fallback. Esto significa que reintentar con el mismo `external_reference` sin cambiar la clave puede resultar en respuesta cacheada de MP.

### qr_data en createOrder
Se extrae de `type_response.qr_data` en el JSON de respuesta. Si viene vacío, la orden existe pero el QR aún no está disponible — hay que consultar con acción `Q`.

### selectBestPayment (MP_LINK_PAGO query)
Cuando hay múltiples pagos para un mismo `external_reference`, la lógica elige en este orden de prioridad: `approved` > `authorized` > `pending/in_process` > el más reciente.

---

## Cómo agregar una nueva acción

1. Definir constantes de índice en el puente ALL_CAPS correspondiente
2. Agregar el `case` en el `switch(accion)` del puente
3. Crear el DTO de entrada en `com/hs/dto/` si es necesario
4. Implementar el método en `MpBridgeCore` (esta clase no conoce ISCobol)
5. Llamar `MpOutWriter.write(argv, result)` o escribir salidas manualmente si el contrato es distinto a los 36 estándar (como en MP_LINK_PAGO y MP_POINT)

---

## Build y artefactos

```bash
mvn clean package
# Genera: target/mercadopagoQR-1.0.jar
# Dependencia local: lib/iscobol2020R2.jar (no está en Maven Central)
```

El JAR resultante se despliega donde ISCobol lo pueda encontrar en su classpath.

---

## Tests

Ubicación: `src/test/java/com/hs/core/MpBridgeCoreTest.java`

Los tests mockean `MpHttp` (interfaz) para no llamar a MP real. `MpBridgeCore` recibe `MpHttp` en construcción, lo que hace trivial inyectar el mock.

```java
MpHttp mockHttp = mock(MpHttp.class);
// MpBridgeCore tiene constructor alternativo para tests:
// new MpBridgeCore(cfg, mockHttp, logger)
```
