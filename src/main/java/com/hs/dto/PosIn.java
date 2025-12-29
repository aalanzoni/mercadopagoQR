package com.hs.dto;

/** Campos para CREATE_POS (acción P). */
public class PosIn {
    public String name;
    public String externalId;
    public long storeId;
    public String idempotencyKey;
}
