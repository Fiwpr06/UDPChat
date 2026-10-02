package com.udpchat.shared.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.udpchat.shared.model.Email;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Tiện ích chuyển đổi đối tượng sang JSON và ngược lại, tích hợp mã hóa Base64 cho UDP
 */
public class JsonUtil {
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final Gson compactGson = new Gson();

    public static String toJson(Object obj) {
        if (obj == null) return "";
        return gson.toJson(obj);
    }

    public static String toCompactJson(Object obj) {
        if (obj == null) return "";
        return compactGson.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> classOfT) {
        if (json == null || json.trim().isEmpty()) return null;
        return gson.fromJson(json, classOfT);
    }

    public static <T> T fromJson(String json, Type typeOfT) {
        if (json == null || json.trim().isEmpty()) return null;
        return gson.fromJson(json, typeOfT);
    }

    public static List<Email> emailListFromJson(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        Type listType = new TypeToken<List<Email>>(){}.getType();
        List<Email> list = gson.fromJson(json, listType);
        return list != null ? list : new ArrayList<>();
    }

    /**
     * Mã hóa chuỗi sang Base64 chuẩn UTF-8 để truyền an toàn qua gói tin UDP
     */
    public static String encodeBase64(String plainText) {
        if (plainText == null) return "";
        return Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Giải mã chuỗi Base64 về chuỗi chuẩn UTF-8
     */
    public static String decodeBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return base64; // fallback nếu không phải base64
        }
    }
}
