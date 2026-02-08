package com.annexai;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Config {
    public final String botToken;
    public final String botUsername;
    public final Set<Long> adminIds;
    public final String paymentProviderToken;
    public final String kieApiKey;
    public final String kieApiBase;
    public final String kieUploadBase;
    public final String dbPath;
    public final String timeZone;
    public final int vatCode;
    public final Integer taxSystemCode;

    private Config(String botToken,
                   String botUsername,
                   Set<Long> adminIds,
                   String paymentProviderToken,
                   String kieApiKey,
                   String kieApiBase,
                   String kieUploadBase,
                   String dbPath,
                   String timeZone,
                   int vatCode,
                   Integer taxSystemCode) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.adminIds = adminIds;
        this.paymentProviderToken = paymentProviderToken;
        this.kieApiKey = kieApiKey;
        this.kieApiBase = kieApiBase;
        this.kieUploadBase = kieUploadBase;
        this.dbPath = dbPath;
        this.timeZone = timeZone;
        this.vatCode = vatCode;
        this.taxSystemCode = taxSystemCode;
    }

    public static Config load() {
        String botToken = envRequired("BOT_TOKEN");
        String botUsername = envRequired("BOT_USERNAME");
        Set<Long> adminIds = parseAdminIds(System.getenv("ADMIN_IDS"));

        String paymentProviderToken = envRequired("PAYMENT_PROVIDER_TOKEN");

        String kieApiKey = envRequired("KIE_API_KEY");
        String kieApiBase = envDefault("KIE_API_BASE", "https://api.kie.ai");
        String kieUploadBase = envDefault("KIE_UPLOAD_BASE", "https://kieai.redpandaai.co");

        String dbPath = envDefault("BOT_DB_PATH", "data/bot.db");
        String timeZone = envDefault("BOT_TIMEZONE", "Europe/Moscow");

        int vatCode = Integer.parseInt(envDefault("YOOKASSA_VAT_CODE", "1"));
        String taxSystemCodeRaw = System.getenv("YOOKASSA_TAX_SYSTEM_CODE");
        Integer taxSystemCode = taxSystemCodeRaw == null || taxSystemCodeRaw.isBlank()
                ? null
                : Integer.parseInt(taxSystemCodeRaw.trim());

        return new Config(
                botToken,
                botUsername,
                adminIds,
                paymentProviderToken,
                kieApiKey,
                kieApiBase,
                kieUploadBase,
                dbPath,
                timeZone,
                vatCode,
                taxSystemCode
        );
    }

    private static String envRequired(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env: " + key);
        }
        return value.trim();
    }

    private static String envDefault(String key, String def) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return def;
        }
        return value.trim();
    }

    private static Set<Long> parseAdminIds(String raw) {
        Set<Long> ids = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(s -> ids.add(Long.parseLong(s)));
        return ids;
    }
}
