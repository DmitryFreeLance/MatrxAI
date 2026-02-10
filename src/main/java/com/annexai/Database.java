package com.annexai;

import java.io.File;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Database {
    private final String dbPath;

    public Database(String dbPath) {
        this.dbPath = dbPath;
    }

    public void init() {
        File file = new File(dbPath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (Connection conn = connect(); Statement st = conn.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    tg_id INTEGER PRIMARY KEY,
                    username TEXT,
                    first_name TEXT,
                    last_name TEXT,
                    balance INTEGER NOT NULL DEFAULT 0,
                    spent INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    referrer_id INTEGER,
                    referral_earned INTEGER NOT NULL DEFAULT 0,
                    receipt_email TEXT,
                    current_model TEXT,
                    output_format TEXT,
                    resolution TEXT,
                    aspect_ratio TEXT
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS model_usage (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    model TEXT NOT NULL,
                    tokens INTEGER NOT NULL,
                    created_at TEXT NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS payments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    provider_payment_charge_id TEXT UNIQUE,
                    telegram_payment_charge_id TEXT,
                    payload TEXT,
                    amount_rub INTEGER NOT NULL,
                    tokens INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    receipt_email TEXT,
                    description TEXT
                )
            """);

            try {
                st.execute("ALTER TABLE payments ADD COLUMN provider_payment_charge_id TEXT");
            } catch (SQLException ignored) {
            }
            try {
                st.execute("ALTER TABLE payments ADD COLUMN telegram_payment_charge_id TEXT");
            } catch (SQLException ignored) {
            }
            try {
                st.execute("ALTER TABLE payments ADD COLUMN payload TEXT");
            } catch (SQLException ignored) {
            }
            try {
                st.execute("ALTER TABLE users ADD COLUMN aspect_ratio TEXT");
            } catch (SQLException ignored) {
            }

            st.execute("""
                CREATE TABLE IF NOT EXISTS promo_codes (
                    code TEXT PRIMARY KEY,
                    tokens INTEGER NOT NULL,
                    is_used INTEGER NOT NULL DEFAULT 0,
                    used_by INTEGER,
                    created_at TEXT NOT NULL,
                    used_at TEXT
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS pending_actions (
                    user_id INTEGER PRIMARY KEY,
                    state TEXT NOT NULL,
                    data TEXT,
                    updated_at TEXT NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS pending_images (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    file_id TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS subscriptions (
                    user_id INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    expires_at TEXT,
                    created_at TEXT NOT NULL
                )
            """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to init DB", e);
        }
    }

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }

    public synchronized User getOrCreateUser(long tgId, String username, String firstName, String lastName, Long referrerId) {
        User existing = getUser(tgId);
        if (existing != null) {
            boolean needUpdate = false;
            if (!safeEquals(existing.username, username)) {
                existing.username = username;
                needUpdate = true;
            }
            if (!safeEquals(existing.firstName, firstName)) {
                existing.firstName = firstName;
                needUpdate = true;
            }
            if (!safeEquals(existing.lastName, lastName)) {
                existing.lastName = lastName;
                needUpdate = true;
            }
            if (needUpdate) {
                updateUserNames(existing);
            }
            return existing;
        }

        String created = now();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (tg_id, username, first_name, last_name, balance, spent, created_at, updated_at, referrer_id, referral_earned, current_model, output_format, resolution, aspect_ratio) " +
                        "VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, 0, NULL, 'auto', '2k', 'auto')")) {
            ps.setLong(1, tgId);
            ps.setString(2, username);
            ps.setString(3, firstName);
            ps.setString(4, lastName);
            ps.setString(5, created);
            ps.setString(6, created);
            if (referrerId == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, referrerId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create user", e);
        }
        return getUser(tgId);
    }

    public synchronized User getUser(long tgId) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT tg_id, username, first_name, last_name, balance, spent, created_at, updated_at, referrer_id, referral_earned, receipt_email, current_model, output_format, resolution, aspect_ratio FROM users WHERE tg_id = ?")) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    User u = new User();
                    u.tgId = rs.getLong("tg_id");
                    u.username = rs.getString("username");
                    u.firstName = rs.getString("first_name");
                    u.lastName = rs.getString("last_name");
                    u.balance = rs.getLong("balance");
                    u.spent = rs.getLong("spent");
                    u.createdAt = rs.getString("created_at");
                    u.updatedAt = rs.getString("updated_at");
                    long ref = rs.getLong("referrer_id");
                    u.referrerId = rs.wasNull() ? null : ref;
                    u.referralEarned = rs.getLong("referral_earned");
                    u.receiptEmail = rs.getString("receipt_email");
                    u.currentModel = rs.getString("current_model");
                    u.outputFormat = rs.getString("output_format");
                    u.resolution = rs.getString("resolution");
                    u.aspectRatio = rs.getString("aspect_ratio");
                    return u;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load user", e);
        }
        return null;
    }

    private void updateUserNames(User user) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET username = ?, first_name = ?, last_name = ?, updated_at = ? WHERE tg_id = ?")) {
            ps.setString(1, user.username);
            ps.setString(2, user.firstName);
            ps.setString(3, user.lastName);
            ps.setString(4, now());
            ps.setLong(5, user.tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update user names", e);
        }
    }

    public synchronized void setCurrentModel(long tgId, String model) {
        updateUserField(tgId, "current_model", model);
    }

    public synchronized void setReceiptEmail(long tgId, String email) {
        updateUserField(tgId, "receipt_email", email);
    }

    public synchronized void setOutputFormat(long tgId, String format) {
        updateUserField(tgId, "output_format", format);
    }

    public synchronized void setResolution(long tgId, String resolution) {
        updateUserField(tgId, "resolution", resolution);
    }

    public synchronized void setAspectRatio(long tgId, String ratio) {
        updateUserField(tgId, "aspect_ratio", ratio);
    }

    private void updateUserField(long tgId, String field, String value) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET " + field + " = ?, updated_at = ? WHERE tg_id = ?")) {
            ps.setString(1, value);
            ps.setString(2, now());
            ps.setLong(3, tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update user field " + field, e);
        }
    }

    public synchronized void addBalance(long tgId, long delta) {
        runWithRetry(() -> {
            try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET balance = balance + ?, updated_at = ? WHERE tg_id = ?")) {
                ps.setLong(1, delta);
                ps.setString(2, now());
                ps.setLong(3, tgId);
                ps.executeUpdate();
            }
        }, "Failed to update balance");
    }

    public synchronized void addSpent(long tgId, long tokens) {
        runWithRetry(() -> {
            try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET spent = spent + ?, updated_at = ? WHERE tg_id = ?")) {
                ps.setLong(1, tokens);
                ps.setString(2, now());
                ps.setLong(3, tgId);
                ps.executeUpdate();
            }
        }, "Failed to update spent");
    }

    public synchronized void addReferralEarned(long tgId, long tokens) {
        runWithRetry(() -> {
            try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET referral_earned = referral_earned + ?, balance = balance + ?, updated_at = ? WHERE tg_id = ?")) {
                ps.setLong(1, tokens);
                ps.setLong(2, tokens);
                ps.setString(3, now());
                ps.setLong(4, tgId);
                ps.executeUpdate();
            }
        }, "Failed to update referral");
    }

    private void runWithRetry(SqlRunnable runnable, String errorMessage) {
        int attempts = 5;
        for (int i = 0; i < attempts; i++) {
            try {
                runnable.run();
                return;
            } catch (SQLException e) {
                if (isBusy(e) && i < attempts - 1) {
                    sleep(200);
                    continue;
                }
                throw new IllegalStateException(errorMessage, e);
            }
        }
    }

    private boolean isBusy(SQLException e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("database is locked");
    }

    private void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }

    public synchronized void recordModelUsage(long tgId, String model, long tokens) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO model_usage (user_id, model, tokens, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, tgId);
            ps.setString(2, model);
            ps.setLong(3, tokens);
            ps.setString(4, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record model usage", e);
        }
    }

    public synchronized Map<String, Long> getModelUsageTotals(long tgId) {
        Map<String, Long> totals = new HashMap<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT model, SUM(tokens) as total FROM model_usage WHERE user_id = ? GROUP BY model")) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    totals.put(rs.getString("model"), rs.getLong("total"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load usage totals", e);
        }
        return totals;
    }

    public synchronized void upsertSuccessfulPayment(long tgId,
                                                     String providerPaymentChargeId,
                                                     String telegramPaymentChargeId,
                                                     String payload,
                                                     int amountRub,
                                                     long tokens,
                                                     String receiptEmail,
                                                     String description) {
        String updated = now();
        try (Connection conn = connect(); PreparedStatement update = conn.prepareStatement(
                "UPDATE payments SET telegram_payment_charge_id = ?, payload = ?, amount_rub = ?, tokens = ?, status = 'succeeded', updated_at = ?, receipt_email = ?, description = ? WHERE provider_payment_charge_id = ?")) {
            update.setString(1, telegramPaymentChargeId);
            update.setString(2, payload);
            update.setInt(3, amountRub);
            update.setLong(4, tokens);
            update.setString(5, updated);
            update.setString(6, receiptEmail);
            update.setString(7, description);
            update.setString(8, providerPaymentChargeId);
            int rows = update.executeUpdate();
            if (rows > 0) {
                return;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update payment", e);
        }

        String created = updated;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO payments (user_id, provider_payment_charge_id, telegram_payment_charge_id, payload, amount_rub, tokens, status, created_at, updated_at, receipt_email, description) VALUES (?, ?, ?, ?, ?, ?, 'succeeded', ?, ?, ?, ?)")) {
            ps.setLong(1, tgId);
            ps.setString(2, providerPaymentChargeId);
            ps.setString(3, telegramPaymentChargeId);
            ps.setString(4, payload);
            ps.setInt(5, amountRub);
            ps.setLong(6, tokens);
            ps.setString(7, created);
            ps.setString(8, updated);
            ps.setString(9, receiptEmail);
            ps.setString(10, description);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                return;
            }
            throw new IllegalStateException("Failed to create payment", e);
        }
    }

    public synchronized List<Payment> listSuccessfulPayments(long tgId) {
        List<Payment> list = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id, user_id, provider_payment_charge_id, telegram_payment_charge_id, payload, amount_rub, tokens, status, created_at, updated_at, receipt_email, description FROM payments WHERE user_id = ? AND status = 'succeeded' ORDER BY updated_at DESC")) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Payment p = new Payment();
                    p.id = rs.getLong("id");
                    p.userId = rs.getLong("user_id");
                    p.providerPaymentChargeId = rs.getString("provider_payment_charge_id");
                    p.telegramPaymentChargeId = rs.getString("telegram_payment_charge_id");
                    p.payload = rs.getString("payload");
                    p.amountRub = rs.getInt("amount_rub");
                    p.tokens = rs.getLong("tokens");
                    p.status = rs.getString("status");
                    p.createdAt = rs.getString("created_at");
                    p.updatedAt = rs.getString("updated_at");
                    p.receiptEmail = rs.getString("receipt_email");
                    p.description = rs.getString("description");
                    list.add(p);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list payments", e);
        }
        return list;
    }

    public synchronized void createPromoCode(String code, long tokens) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO promo_codes (code, tokens, is_used, used_by, created_at, used_at) VALUES (?, ?, 0, NULL, ?, NULL)")) {
            ps.setString(1, normalized);
            ps.setLong(2, tokens);
            ps.setString(3, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create promo", e);
        }
    }

    public synchronized PromoRedeemResult redeemPromo(long tgId, String code) {
        if (code == null) {
            return PromoRedeemResult.NOT_FOUND;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT code, tokens, is_used FROM promo_codes WHERE code = ?")) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return PromoRedeemResult.NOT_FOUND;
                }
                int isUsed = rs.getInt("is_used");
                long tokens = rs.getLong("tokens");
                if (isUsed == 1) {
                    return PromoRedeemResult.ALREADY_USED;
                }
                try (PreparedStatement upd = conn.prepareStatement(
                        "UPDATE promo_codes SET is_used = 1, used_by = ?, used_at = ? WHERE code = ?")) {
                    upd.setLong(1, tgId);
                    upd.setString(2, now());
                    upd.setString(3, normalized);
                    upd.executeUpdate();
                }
                addBalance(tgId, tokens);
                return PromoRedeemResult.SUCCESS;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to redeem promo", e);
        }
    }

    public synchronized void setPendingAction(long tgId, String state, String data) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_actions (user_id, state, data, updated_at) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(user_id) DO UPDATE SET state = excluded.state, data = excluded.data, updated_at = excluded.updated_at")) {
            ps.setLong(1, tgId);
            ps.setString(2, state);
            ps.setString(3, data);
            ps.setString(4, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set pending action", e);
        }
    }

    public synchronized PendingAction getPendingAction(long tgId) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT state, data, updated_at FROM pending_actions WHERE user_id = ?")) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PendingAction action = new PendingAction();
                    action.state = rs.getString("state");
                    action.data = rs.getString("data");
                    action.updatedAt = rs.getString("updated_at");
                    return action;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get pending action", e);
        }
        return null;
    }

    public synchronized void clearPendingAction(long tgId) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM pending_actions WHERE user_id = ?")) {
            ps.setLong(1, tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear pending action", e);
        }
    }

    public synchronized void addPendingImage(long tgId, String fileId) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_images (user_id, file_id, created_at) VALUES (?, ?, ?)")) {
            ps.setLong(1, tgId);
            ps.setString(2, fileId);
            ps.setString(3, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add pending image", e);
        }
        trimPendingImages(tgId, 10);
    }

    private void trimPendingImages(long tgId, int max) {
        try (Connection conn = connect(); PreparedStatement count = conn.prepareStatement(
                "SELECT COUNT(*) AS cnt FROM pending_images WHERE user_id = ?")) {
            count.setLong(1, tgId);
            try (ResultSet rs = count.executeQuery()) {
                if (rs.next()) {
                    int cnt = rs.getInt("cnt");
                    int extra = cnt - max;
                    if (extra > 0) {
                        try (PreparedStatement del = conn.prepareStatement(
                                "DELETE FROM pending_images WHERE id IN (SELECT id FROM pending_images WHERE user_id = ? ORDER BY created_at ASC LIMIT ?)")) {
                            del.setLong(1, tgId);
                            del.setInt(2, extra);
                            del.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to trim pending images", e);
        }
    }

    public synchronized List<String> consumePendingImages(long tgId) {
        List<String> list = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id, file_id FROM pending_images WHERE user_id = ? ORDER BY created_at ASC")) {
            ps.setLong(1, tgId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                    list.add(rs.getString("file_id"));
                }
            }
            if (!ids.isEmpty()) {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM pending_images WHERE user_id = ?")) {
                    del.setLong(1, tgId);
                    del.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to consume pending images", e);
        }
        return list;
    }

    public synchronized void clearPendingImages(long tgId) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM pending_images WHERE user_id = ?")) {
            ps.setLong(1, tgId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear pending images", e);
        }
    }

    public synchronized int countPendingImages(long tgId) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS cnt FROM pending_images WHERE user_id = ?")) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("cnt");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count pending images", e);
        }
        return 0;
    }

    public synchronized long countUsers() {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS cnt FROM users")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("cnt");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count users", e);
        }
        return 0;
    }

    public synchronized long countActiveSubscriptions() {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS cnt FROM subscriptions WHERE status = 'active' AND (expires_at IS NULL OR expires_at > ?)")) {
            ps.setString(1, now());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("cnt");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count subscriptions", e);
        }
        return 0;
    }

    public synchronized long countReferrals(long tgId) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS cnt FROM users WHERE referrer_id = ?")) {
            ps.setLong(1, tgId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("cnt");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count referrals", e);
        }
        return 0;
    }

    public synchronized String listReferrals(long tgId, int limit) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "SELECT tg_id, username, first_name FROM users WHERE referrer_id = ? ORDER BY created_at DESC LIMIT ?")) {
            ps.setLong(1, tgId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                int idx = 1;
                while (rs.next()) {
                    long id = rs.getLong("tg_id");
                    String username = rs.getString("username");
                    String firstName = rs.getString("first_name");
                    sb.append(idx).append(". ");
                    if (username != null && !username.isBlank()) {
                        sb.append("@").append(username);
                    } else if (firstName != null && !firstName.isBlank()) {
                        sb.append(firstName);
                    } else {
                        sb.append("ID ").append(id);
                    }
                    sb.append("\n");
                    idx++;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list referrals", e);
        }
        if (sb.length() == 0) {
            return "Пока нет приглашенных.";
        }
        return sb.toString().trim();
    }

    public synchronized boolean setReferrerIfEmpty(long tgId, long referrerId) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET referrer_id = ? WHERE tg_id = ? AND referrer_id IS NULL AND tg_id <> ?")) {
            ps.setLong(1, referrerId);
            ps.setLong(2, tgId);
            ps.setLong(3, referrerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set referrer", e);
        }
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    public enum PromoRedeemResult {
        SUCCESS,
        NOT_FOUND,
        ALREADY_USED
    }

    public static class User {
        public long tgId;
        public String username;
        public String firstName;
        public String lastName;
        public long balance;
        public long spent;
        public String createdAt;
        public String updatedAt;
        public Long referrerId;
        public long referralEarned;
        public String receiptEmail;
        public String currentModel;
        public String outputFormat;
        public String resolution;
        public String aspectRatio;
    }

    public static class PendingAction {
        public String state;
        public String data;
        public String updatedAt;
    }

    public static class Payment {
        public long id;
        public long userId;
        public String providerPaymentChargeId;
        public String telegramPaymentChargeId;
        public String payload;
        public int amountRub;
        public long tokens;
        public String status;
        public String createdAt;
        public String updatedAt;
        public String receiptEmail;
        public String description;
    }
}
