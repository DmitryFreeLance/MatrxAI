package com.annexai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.payments.OrderInfo;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.objects.InputFile;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AnnexAiBot extends TelegramLongPollingBot {
    private static final String STATE_WAIT_PROMO = "WAIT_PROMO";
    private static final String STATE_ADMIN_GRANT = "ADMIN_GRANT";

    private static final String MODEL_NANO_BANANA = "nano-banana-pro";

    private final Config config;
    private final Database db;
    private final KieClient kieClient;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, PurchaseOption> purchaseOptions = Map.of(
            "50k", new PurchaseOption(50_000, 99),
            "200k", new PurchaseOption(200_000, 239),
            "500k", new PurchaseOption(500_000, 529),
            "1m", new PurchaseOption(1_000_000, 999)
    );

    public AnnexAiBot(Config config, Database db, KieClient kieClient) {
        this.config = config;
        this.db = db;
        this.kieClient = kieClient;
    }

    @Override
    public String getBotUsername() {
        return config.botUsername;
    }

    @Override
    public String getBotToken() {
        return config.botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasPreCheckoutQuery()) {
                handlePreCheckout(update.getPreCheckoutQuery());
                return;
            }
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(Message message) throws TelegramApiException {
        if (message.getFrom() == null) {
            return;
        }
        if (message.hasSuccessfulPayment()) {
            handleSuccessfulPayment(message);
            return;
        }

        long userId = message.getFrom().getId();
        String username = message.getFrom().getUserName();
        String firstName = message.getFrom().getFirstName();
        String lastName = message.getFrom().getLastName();
        Long referrerId = extractReferrer(message.getText());

        Database.User user = db.getOrCreateUser(userId, username, firstName, lastName, referrerId);
        if (referrerId != null && user.referrerId == null) {
            db.setReferrerIfEmpty(userId, referrerId);
            user = db.getUser(userId);
        }

        if (message.hasPhoto()) {
            saveIncomingPhotos(userId, message.getPhoto());
            if (message.getCaption() != null && !message.getCaption().isBlank()) {
                handlePrompt(user, message.getCaption());
            } else {
                SendMessage reply = new SendMessage(String.valueOf(message.getChatId()),
                        "Фото получены. Теперь отправьте промпт отдельным сообщением ✏️");
                execute(reply);
            }
            return;
        }

        if (message.hasText()) {
            String text = message.getText().trim();
            if (text.startsWith("/start")) {
                sendStart(message.getChatId(), user);
                return;
            }
            if (text.startsWith("/admin")) {
                sendAdminPanel(message.getChatId(), userId);
                return;
            }

            Database.PendingAction pending = db.getPendingAction(userId);
            if (pending != null) {
                if (STATE_WAIT_PROMO.equals(pending.state)) {
                    handlePromoInput(user, text, message.getChatId());
                    return;
                }
                if (STATE_ADMIN_GRANT.equals(pending.state)) {
                    handleAdminGrant(userId, text, message.getChatId());
                    return;
                }
            }

            if (user.currentModel != null && !text.startsWith("/")) {
                handlePrompt(user, text);
                return;
            }
        }
    }

    private void handleCallback(CallbackQuery query) throws TelegramApiException {
        String data = query.getData();
        var message = query.getMessage();
        long chatId = message.getChatId();
        int messageId = message.getMessageId();
        long userId = query.getFrom().getId();
        Database.User user = db.getUser(userId);
        if (user == null) {
            user = db.getOrCreateUser(userId, query.getFrom().getUserName(), query.getFrom().getFirstName(), query.getFrom().getLastName(), null);
        }

        if ("menu:start".equals(data)) {
            sendStart(chatId, user);
            return;
        }
        if ("menu:models".equals(data)) {
            editMessage(chatId, messageId, "Выберите модель:", modelSelectKeyboard());
            return;
        }
        if ("model:nano".equals(data)) {
            db.setCurrentModel(userId, MODEL_NANO_BANANA);
            user.currentModel = MODEL_NANO_BANANA;
            editMessage(chatId, messageId, modelInfoText(user), modelInfoKeyboard());
            return;
        }
        if ("model:back".equals(data)) {
            sendStart(chatId, user);
            return;
        }
        if ("settings".equals(data)) {
            editMessage(chatId, messageId, settingsText(user), settingsKeyboard(user));
            return;
        }
        if (data.startsWith("settings:format:")) {
            String format = data.substring("settings:format:".length());
            db.setOutputFormat(userId, format);
            user.outputFormat = format;
            editMessage(chatId, messageId, settingsText(user), settingsKeyboard(user));
            return;
        }
        if (data.startsWith("settings:res:")) {
            String res = data.substring("settings:res:".length());
            db.setResolution(userId, res);
            user.resolution = res;
            editMessage(chatId, messageId, settingsText(user), settingsKeyboard(user));
            return;
        }
        if ("settings:back".equals(data)) {
            editMessage(chatId, messageId, modelInfoText(user), modelInfoKeyboard());
            return;
        }
        if ("menu:buy".equals(data)) {
            editMessage(chatId, messageId, buyText(), buyKeyboard());
            return;
        }
        if ("buy:back".equals(data)) {
            sendStart(chatId, user);
            return;
        }
        if ("promo:activate".equals(data)) {
            db.setPendingAction(userId, STATE_WAIT_PROMO, null);
            execute(new SendMessage(String.valueOf(chatId), "Введите промокод:"));
            return;
        }
        if (data.startsWith("buy:pack:")) {
            String key = data.substring("buy:pack:".length());
            PurchaseOption option = purchaseOptions.get(key);
            if (option == null) {
                execute(new SendMessage(String.valueOf(chatId), "Пакет не найден."));
                return;
            }
            sendInvoice(chatId, user, key, option);
            return;
        }
        if ("menu:profile".equals(data)) {
            editMessage(chatId, messageId, profileText(user), profileKeyboard());
            return;
        }
        if ("profile:back".equals(data)) {
            sendStart(chatId, user);
            return;
        }
        if ("profile:payments".equals(data)) {
            editMessage(chatId, messageId, paymentsText(userId), paymentsKeyboard());
            return;
        }
        if ("profile:ref".equals(data)) {
            editMessage(chatId, messageId, referralText(userId), referralKeyboard());
            return;
        }
        if ("admin:panel".equals(data)) {
            sendAdminPanel(chatId, userId);
            return;
        }
        if ("admin:stats".equals(data)) {
            if (!isAdmin(userId)) {
                execute(new SendMessage(String.valueOf(chatId), "Нет доступа."));
                return;
            }
            editMessage(chatId, messageId, adminStatsText(), adminKeyboard());
            return;
        }
        if ("admin:grant".equals(data)) {
            if (!isAdmin(userId)) {
                execute(new SendMessage(String.valueOf(chatId), "Нет доступа."));
                return;
            }
            db.setPendingAction(userId, STATE_ADMIN_GRANT, null);
            execute(new SendMessage(String.valueOf(chatId), "Введите tg_id пользователя для выдачи 50 000 токенов:"));
            return;
        }
        if ("admin:promo".equals(data)) {
            if (!isAdmin(userId)) {
                execute(new SendMessage(String.valueOf(chatId), "Нет доступа."));
                return;
            }
            String code = generatePromoCode();
            db.createPromoCode(code, 50_000);
            execute(new SendMessage(String.valueOf(chatId), "Промокод на 50 000 токенов: " + code));
            return;
        }
    }

    private void handlePreCheckout(PreCheckoutQuery query) throws TelegramApiException {
        String payload = query.getInvoicePayload();
        String optionKey = extractOptionKey(payload);
        AnswerPreCheckoutQuery answer = new AnswerPreCheckoutQuery();
        answer.setPreCheckoutQueryId(query.getId());
        if (optionKey == null || !purchaseOptions.containsKey(optionKey)) {
            answer.setOk(false);
            answer.setErrorMessage("Некорректный платеж. Попробуйте снова.");
        } else {
            answer.setOk(true);
        }
        execute(answer);
    }

    private void handleSuccessfulPayment(Message message) throws TelegramApiException {
        SuccessfulPayment payment = message.getSuccessfulPayment();
        long userId = message.getFrom().getId();
        Database.User user = db.getUser(userId);
        if (user == null) {
            user = db.getOrCreateUser(userId, message.getFrom().getUserName(), message.getFrom().getFirstName(), message.getFrom().getLastName(), null);
        }

        String payload = payment.getInvoicePayload();
        String optionKey = extractOptionKey(payload);
        PurchaseOption option = optionKey == null ? null : purchaseOptions.get(optionKey);
        if (option == null) {
            execute(new SendMessage(String.valueOf(message.getChatId()), "Оплата получена, но пакет не найден. Напишите @maxsekret"));
            return;
        }

        int amountRub = payment.getTotalAmount() / 100;
        String providerChargeId = payment.getProviderPaymentChargeId();
        String telegramChargeId = payment.getTelegramPaymentChargeId();

        String receiptEmail = null;
        OrderInfo orderInfo = payment.getOrderInfo();
        if (orderInfo != null && orderInfo.getEmail() != null) {
            receiptEmail = orderInfo.getEmail();
            db.setReceiptEmail(userId, receiptEmail);
        }

        String description = "Пакет " + formatNumber(option.tokens) + " токенов";
        db.upsertSuccessfulPayment(userId, providerChargeId, telegramChargeId, payload, amountRub, option.tokens, receiptEmail, description);

        db.addBalance(userId, option.tokens);

        if (user.referrerId != null) {
            long bonus = Math.round(option.tokens * 0.05);
            if (bonus > 0) {
                db.addReferralEarned(user.referrerId, bonus);
                safeSend(user.referrerId, "Вам начислен реферальный бонус: " + formatNumber(bonus) + " токенов.");
            }
        }

        execute(new SendMessage(String.valueOf(message.getChatId()), "Оплата прошла успешно. Начислено " + formatNumber(option.tokens) + " токенов."));
    }

    private void handlePromoInput(Database.User user, String text, long chatId) throws TelegramApiException {
        String code = text.trim();
        db.clearPendingAction(user.tgId);
        Database.PromoRedeemResult result = db.redeemPromo(user.tgId, code);
        switch (result) {
            case SUCCESS -> execute(new SendMessage(String.valueOf(chatId), "Промокод активирован. 50 000 токенов начислены."));
            case ALREADY_USED -> execute(new SendMessage(String.valueOf(chatId), "Этот промокод уже использован."));
            case NOT_FOUND -> execute(new SendMessage(String.valueOf(chatId), "Промокод не найден."));
        }
    }

    private void handleAdminGrant(long adminId, String text, long chatId) throws TelegramApiException {
        if (!isAdmin(adminId)) {
            execute(new SendMessage(String.valueOf(chatId), "Нет доступа."));
            return;
        }
        db.clearPendingAction(adminId);
        try {
            long targetId = Long.parseLong(text.trim());
            Database.User target = db.getUser(targetId);
            if (target == null) {
                execute(new SendMessage(String.valueOf(chatId), "Пользователь не найден."));
                return;
            }
            db.addBalance(targetId, 50_000);
            execute(new SendMessage(String.valueOf(chatId), "Выдано 50 000 токенов пользователю " + targetId));
        } catch (NumberFormatException e) {
            execute(new SendMessage(String.valueOf(chatId), "Некорректный tg_id."));
        }
    }

    private void handlePrompt(Database.User user, String prompt) throws TelegramApiException {
        if (!MODEL_NANO_BANANA.equals(user.currentModel)) {
            execute(new SendMessage(String.valueOf(user.tgId), "Сначала выберите модель через меню /start"));
            return;
        }
        long cost = costForUser(user);
        if (user.balance < cost) {
            execute(new SendMessage(String.valueOf(user.tgId), "Недостаточно токенов. Пополните баланс в разделе «Купить токены»."));
            return;
        }

        List<String> fileIds = db.consumePendingImages(user.tgId);

        db.addBalance(user.tgId, -cost);
        db.addSpent(user.tgId, cost);
        db.recordModelUsage(user.tgId, MODEL_NANO_BANANA, cost);

        execute(new SendMessage(String.valueOf(user.tgId), "Запрос принят. Генерация началась 🍌"));

        executor.submit(() -> {
            try {
                List<String> imageUrls = new ArrayList<>();
                int i = 1;
                for (String fileId : fileIds) {
                    String url = getTelegramFileUrl(fileId);
                    String uploaded = kieClient.uploadFileUrl(url, "tg_" + user.tgId + "_" + i);
                    if (uploaded != null && !uploaded.isBlank()) {
                        imageUrls.add(uploaded);
                    }
                    i++;
                }

                String resolution = mapResolution(user.resolution);
                String outputFormat = mapFormat(user.outputFormat);
                String taskId = kieClient.createNanoBananaTask(prompt, imageUrls, "1:1", outputFormat, resolution);

                pollTaskAndSend(taskId, user.tgId);
            } catch (Exception e) {
                safeSend(user.tgId, "Ошибка при генерации: " + e.getMessage());
            }
        });
    }

    private void pollTaskAndSend(String taskId, long chatId) {
        int attempts = 60;
        for (int i = 0; i < attempts; i++) {
            try {
                TimeUnit.SECONDS.sleep(3);
                KieClient.TaskInfo info = kieClient.getTaskInfo(taskId);
                if ("success".equalsIgnoreCase(info.state) || "succeeded".equalsIgnoreCase(info.state)) {
                    List<String> urls = extractResultUrls(info.resultJson);
                    if (urls.isEmpty()) {
                        safeSend(chatId, "Готово, но без изображений. Попробуйте другой запрос.");
                        return;
                    }
                    for (String url : urls) {
                        SendPhoto photo = new SendPhoto();
                        photo.setChatId(String.valueOf(chatId));
                        photo.setPhoto(new InputFile(url));
                        execute(photo);
                    }
                    return;
                }
                if ("failed".equalsIgnoreCase(info.state)) {
                    safeSend(chatId, "Генерация не удалась: " + info.failReason);
                    return;
                }
            } catch (Exception e) {
                safeSend(chatId, "Ошибка при проверке задачи: " + e.getMessage());
                return;
            }
        }
        safeSend(chatId, "Время ожидания истекло. Попробуйте ещё раз.");
    }

    private List<String> extractResultUrls(String resultJson) {
        List<String> urls = new ArrayList<>();
        if (resultJson == null || resultJson.isBlank()) {
            return urls;
        }
        try {
            JsonNode node = mapper.readTree(resultJson);
            JsonNode arr = node.path("resultUrls");
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    urls.add(n.asText());
                }
            }
        } catch (Exception e) {
            return urls;
        }
        return urls;
    }

    private void sendInvoice(long chatId, Database.User user, String optionKey, PurchaseOption option) throws TelegramApiException {
        String description = "Пакет " + formatNumber(option.tokens) + " токенов";
        String payload = buildPayload(optionKey, user.tgId);

        SendInvoice invoice = new SendInvoice();
        invoice.setChatId(String.valueOf(chatId));
        invoice.setTitle("Покупка токенов");
        invoice.setDescription(description);
        invoice.setPayload(payload);
        invoice.setProviderToken(config.paymentProviderToken);
        invoice.setCurrency("RUB");
        invoice.setPrices(List.of(new LabeledPrice(description, option.amountRub * 100)));
        invoice.setNeedEmail(true);
        invoice.setSendEmailToProvider(true);
        invoice.setStartParameter("buy_" + optionKey);
        invoice.setProviderData(buildProviderData(user, option, description));

        execute(invoice);
    }

    private String buildProviderData(Database.User user, PurchaseOption option, String description) {
        String amountValue = String.format(Locale.US, "%d.00", option.amountRub);

        ObjectNode root = mapper.createObjectNode();
        ObjectNode receipt = root.putObject("receipt");
        if (user.receiptEmail != null && !user.receiptEmail.isBlank()) {
            ObjectNode customer = receipt.putObject("customer");
            customer.put("email", user.receiptEmail);
        }
        if (config.taxSystemCode != null) {
            receipt.put("tax_system_code", config.taxSystemCode);
        }
        ArrayNode items = receipt.putArray("items");
        ObjectNode item = items.addObject();
        item.put("description", description);
        item.put("quantity", 1);
        ObjectNode amount = item.putObject("amount");
        amount.put("value", amountValue);
        amount.put("currency", "RUB");
        item.put("vat_code", config.vatCode);
        item.put("payment_subject", "service");
        item.put("payment_mode", "full_payment");

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildPayload(String optionKey, long userId) {
        return "pay:" + optionKey + ":" + userId + ":" + System.currentTimeMillis();
    }

    private String extractOptionKey(String payload) {
        if (payload == null) {
            return null;
        }
        String[] parts = payload.split(":");
        if (parts.length < 2) {
            return null;
        }
        if (!"pay".equals(parts[0])) {
            return null;
        }
        return parts[1];
    }

    private void sendStart(long chatId, Database.User user) throws TelegramApiException {
        String text = "👋🏻 Привет! У тебя на балансе " + formatNumber(user.balance) + " токенов – используй их для запросов к нейросетям.\n\n" +
                "🍌 Nano Banana Pro помогает генерировать и редактировать изображения: опиши сцену, меняй объекты и получай чистые детали в 2K.\n" +
                "✨ Быстро, креативно и с точным наследованием исходного фото.\n\n" +
                "❓ По всем вопросам писать – @maxsekret";

        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setReplyMarkup(startKeyboard());
        execute(msg);
    }

    private InlineKeyboardMarkup startKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("Выбор модели", "menu:models")),
                List.of(button("Купить токены", "menu:buy")),
                List.of(button("Мой профиль", "menu:profile"))
        ));
    }

    private InlineKeyboardMarkup modelSelectKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("🍌 Nano Banana Pro", "model:nano")),
                List.of(button("⬅️ Назад", "menu:start"))
        ));
    }

    private InlineKeyboardMarkup modelInfoKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("⚙️ Настройки", "settings")),
                List.of(button("Вернуться в меню", "menu:start"))
        ));
    }

    private InlineKeyboardMarkup settingsKeyboard(Database.User user) {
        String format = user.outputFormat == null ? "auto" : user.outputFormat;
        String resolution = user.resolution == null ? "2k" : user.resolution;
        return new InlineKeyboardMarkup(List.of(
                List.of(button(formatButtonLabel("Авто", "auto", format), "settings:format:auto"),
                        button(formatButtonLabel("PNG", "png", format), "settings:format:png"),
                        button(formatButtonLabel("JPG", "jpg", format), "settings:format:jpg")),
                List.of(button(resButtonLabel("1K", "1k", resolution), "settings:res:1k"),
                        button(resButtonLabel("2K", "2k", resolution), "settings:res:2k"),
                        button(resButtonLabel("4K", "4k", resolution), "settings:res:4k")),
                List.of(button("⬅️ Назад", "settings:back"))
        ));
    }

    private InlineKeyboardMarkup buyKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("50.000 токенов - 99р", "buy:pack:50k")),
                List.of(button("200.000 токенов - 239р", "buy:pack:200k")),
                List.of(button("500.000 токенов - 529р", "buy:pack:500k")),
                List.of(button("1.000.000 токенов - 999р", "buy:pack:1m")),
                List.of(button("Активировать промокод", "promo:activate")),
                List.of(button("⬅️ Назад", "buy:back"))
        ));
    }

    private InlineKeyboardMarkup profileKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("Мои платежи", "profile:payments")),
                List.of(button("Пригласить друга", "profile:ref")),
                List.of(button("⬅️ Назад", "profile:back"))
        ));
    }

    private InlineKeyboardMarkup paymentsKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("⬅️ Назад", "menu:profile"))
        ));
    }

    private InlineKeyboardMarkup referralKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("⬅️ Назад", "menu:profile"))
        ));
    }

    private InlineKeyboardMarkup adminKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("📊 Статистика", "admin:stats")),
                List.of(button("🎁 Выдать 50 000", "admin:grant")),
                List.of(button("🎟️ Промокод 50 000", "admin:promo"))
        ));
    }

    private InlineKeyboardButton button(String text, String data) {
        InlineKeyboardButton btn = new InlineKeyboardButton(text);
        btn.setCallbackData(data);
        return btn;
    }

    private void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup markup) throws TelegramApiException {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText(text);
        edit.setReplyMarkup(markup);
        execute(edit);
    }

    private String modelInfoText(Database.User user) {
        long cost = costForUser(user);
        long queries = cost == 0 ? 0 : user.balance / cost;
        return "🍌 Nano Banana · твори и экспериментируй\n\n" +
                "📖 Создавайте:\n" +
                "– Создает фотографии по промпту и по вашим изображениям;\n" +
                "– Она отлично наследует исходное фото и может работать с ним. Попросите её, например, отредактировать ваши фото (добавлять, удалять, менять объекты и всё, что угодно).\n\n" +
                "📷 При желании можете прикрепить до 10 фото, а промпт добавить отдельно.\n\n" +
                "✏️ Если промпт не помещается в одном сообщении вместе с фото, прикрепите сначала фото, а следующим сообщением – промпт.\n\n" +
                "⚙️ Настройки\n" +
                "Формат фото: " + formatLabel(user.outputFormat) + "\n" +
                "PRO-режим: отключен\n\n" +
                "🔹 Баланса хватит на " + queries + " запросов. 1 генерация = " + formatNumber(cost) + " токенов";
    }

    private String settingsText(Database.User user) {
        return "⚙️ Настройки\n" +
                "Формат фото: " + formatLabel(user.outputFormat) + "\n" +
                "Разрешение: " + resolutionLabel(user.resolution) + "\n\n" +
                "Стоимость генерации:\n" +
                "1K = 10 000 токенов\n" +
                "2K = 10 000 токенов\n" +
                "4K = 14 000 токенов";
    }

    private String buyText() {
        return "Выберите пакет токенов и оплатите через ЮKassa прямо в Telegram. После успешной оплаты токены начислятся автоматически.";
    }

    private String profileText(Database.User user) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Мой профиль\n\n");
        sb.append("🆔 ID: ").append(user.tgId).append("\n");
        sb.append("👤 Имя: ").append(user.firstName == null ? "" : user.firstName).append("\n");
        sb.append("🔹 Баланс: ").append(formatNumber(user.balance)).append(" токенов\n");
        sb.append("🔸 Потрачено: ").append(formatNumber(user.spent)).append(" токенов\n\n");

        Map<String, Long> usage = db.getModelUsageTotals(user.tgId);
        if (usage.isEmpty()) {
            sb.append("Пока нет расхода по моделям.");
        } else {
            sb.append("Расход по моделям:\n");
            for (Map.Entry<String, Long> entry : usage.entrySet()) {
                sb.append("• ").append(modelLabel(entry.getKey())).append(": ").append(formatNumber(entry.getValue())).append(" токенов\n");
            }
        }
        return sb.toString();
    }

    private String paymentsText(long userId) {
        List<Database.Payment> payments = db.listSuccessfulPayments(userId);
        if (payments.isEmpty()) {
            return "Пока нет успешных оплат.";
        }
        StringBuilder sb = new StringBuilder("Мои платежи\n\n");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of(config.timeZone));
        for (Database.Payment p : payments) {
            OffsetDateTime time = OffsetDateTime.parse(p.updatedAt);
            sb.append("• ").append(formatter.format(time)).append(" — ").append(p.amountRub).append(" ₽\n");
        }
        return sb.toString();
    }

    private String referralText(long userId) {
        long count = db.countReferrals(userId);
        Database.User user = db.getUser(userId);
        long earned = user == null ? 0 : user.referralEarned;
        String link = "https://t.me/" + config.botUsername + "?start=ref" + userId;
        return "🔹 Реферальная программа\n\n" +
                "Получайте 5% токенами от каждой покупки тарифа приглашенного пользователя в боте.\n\n" +
                "👥 Приглашено пользователей: " + count + "\n" +
                "🔶 Получено: " + formatNumber(earned) + " токенов\n\n" +
                "🔗 Моя реферальная ссылка:\n" + link;
    }

    private void sendAdminPanel(long chatId, long userId) throws TelegramApiException {
        if (!isAdmin(userId)) {
            execute(new SendMessage(String.valueOf(chatId), "Нет доступа."));
            return;
        }
        SendMessage msg = new SendMessage(String.valueOf(chatId), "Админ панель:");
        msg.setReplyMarkup(adminKeyboard());
        execute(msg);
    }

    private String adminStatsText() {
        long total = db.countUsers();
        long activeSubs = db.countActiveSubscriptions();
        return "📊 Статистика\n\n" +
                "Всего пользователей: " + total + "\n" +
                "Активных подписок: " + activeSubs;
    }

    private void safeSend(long chatId, String text) {
        try {
            execute(new SendMessage(String.valueOf(chatId), text));
        } catch (Exception ignored) {
        }
    }

    private String getTelegramFileUrl(String fileId) throws TelegramApiException {
        GetFile getFile = new GetFile(fileId);
        org.telegram.telegrambots.meta.api.objects.File file = execute(getFile);
        return "https://api.telegram.org/file/bot" + config.botToken + "/" + file.getFilePath();
    }

    private void saveIncomingPhotos(long userId, List<PhotoSize> photos) {
        if (photos == null || photos.isEmpty()) {
            return;
        }
        PhotoSize best = photos.get(photos.size() - 1);
        db.addPendingImage(userId, best.getFileId());
    }

    private long costForUser(Database.User user) {
        String res = user.resolution == null ? "2k" : user.resolution.toLowerCase(Locale.ROOT);
        return switch (res) {
            case "4k" -> 14_000;
            case "1k", "2k" -> 10_000;
            default -> 9_000;
        };
    }

    private String mapResolution(String res) {
        if (res == null || res.isBlank()) {
            return "2K";
        }
        return res.toUpperCase(Locale.ROOT);
    }

    private String mapFormat(String format) {
        if (format == null || format.isBlank() || "auto".equalsIgnoreCase(format)) {
            return "png";
        }
        return format.toLowerCase(Locale.ROOT);
    }

    private String formatLabel(String format) {
        if (format == null || format.isBlank() || "auto".equalsIgnoreCase(format)) {
            return "автоматический";
        }
        return format.toUpperCase(Locale.ROOT);
    }

    private String resolutionLabel(String res) {
        if (res == null || res.isBlank()) {
            return "2K";
        }
        return res.toUpperCase(Locale.ROOT);
    }

    private String modelLabel(String model) {
        if (MODEL_NANO_BANANA.equals(model)) {
            return "Nano Banana Pro";
        }
        return model;
    }

    private String formatButtonLabel(String label, String value, String current) {
        if (value.equalsIgnoreCase(current)) {
            return "✅ " + label;
        }
        return label;
    }

    private String resButtonLabel(String label, String value, String current) {
        if (value.equalsIgnoreCase(current)) {
            return "✅ " + label;
        }
        return label;
    }

    private Long extractReferrer(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        String param = parts[1];
        if (!param.startsWith("ref")) {
            return null;
        }
        try {
            return Long.parseLong(param.substring(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isAdmin(long userId) {
        return config.adminIds.contains(userId);
    }

    private String formatNumber(long value) {
        return String.format("%,d", value).replace(',', ' ');
    }

    private String generatePromoCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder("ANNEX");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static class PurchaseOption {
        final long tokens;
        final int amountRub;

        private PurchaseOption(long tokens, int amountRub) {
            this.tokens = tokens;
            this.amountRub = amountRub;
        }
    }
}
