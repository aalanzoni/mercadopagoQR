package com.hs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class MpBridgeResponse {
    private static final Gson GSON = new Gson();

    public static String ok(String message, JsonObject data) {
        JsonObject out = new JsonObject();
        out.addProperty("status", "OK");
        out.addProperty("message", message == null ? "" : message);
        out.add("data", data == null ? new JsonObject() : data);
        return GSON.toJson(out);
    }

    public static String fail(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("status", "ERROR");
        out.addProperty("message", message == null ? "" : message);
        out.add("data", new JsonObject());
        return GSON.toJson(out);
    }

    public static String error(String status, String message, Exception ex) {
        JsonObject out = new JsonObject();
        out.addProperty("status", status == null ? "ERROR" : status);
        out.addProperty("message", message == null ? "" : message);

        JsonObject err = new JsonObject();
        err.addProperty("exception", ex == null ? "" : ex.getClass().getName());
        err.addProperty("detail", ex == null ? "" : String.valueOf(ex.getMessage()));
        out.add("error", err);

        out.add("data", new JsonObject());
        return GSON.toJson(out);
    }
}
