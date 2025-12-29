package com.hs.dto;

/**
 * Entrada campo-por-campo para crear una Orden QR (acción O).
 *
 * Nota: todos los campos son String porque en el contrato ISCOBOL llegan como PIC X.
 */
public class OrderIn {
    public String externalReference;
    public String description;
    public String externalPosId;
    public String mode;           // dynamic / static / hybrid según configuración
    public String expirationTime; // ISO-8601 duration (ej: PT23M)
    public String totalAmount;    // "54.00" (String)
    public String unitMeasure;
    public String itemTitle;
    public String externalCode;
    public String idempotencyKey;
}
