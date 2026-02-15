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
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AnnexAiBot extends TelegramLongPollingBot {
    private static final String STATE_WAIT_PROMO = "WAIT_PROMO";
    private static final String STATE_ADMIN_GRANT = "ADMIN_GRANT";

    private static final String MODEL_NANO_BANANA = "google/nano-banana";
    private static final String MODEL_NANO_BANANA_EDIT = "google/nano-banana-edit";
    private static final String MODEL_NANO_BANANA_PRO = "nano-banana-pro";
    private static final String MODEL_MIDJOURNEY = "midjourney";

    private final Config config;
    private final Database db;
    private final KieClient kieClient;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Set<Long> activeGenerations = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> lastAlbumNotice = new ConcurrentHashMap<>();
    private final Set<Long> modelSelectedThisSession = ConcurrentHashMap.newKeySet();

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
        if (db.ensureWelcomeBonus(userId)) {
            user.balance += 10_000;
        }
        if (referrerId != null && user.referrerId == null) {
            boolean linked = db.setReferrerIfEmpty(userId, referrerId);
            if (linked) {
                db.addBalance(userId, 50_000);
                safeSend(userId, "🎉 Вам начислено 50 000 токенов за переход по реферальной ссылке.");
            }
            user = db.getUser(userId);
        }

        if (message.hasPhoto()) {
            boolean handled = saveIncomingPhotos(user, message);
            if (message.getCaption() != null && !message.getCaption().isBlank()) {
                handlePrompt(user, message.getCaption());
            } else {
                if (!handled) {
                    int count = db.countPendingImages(userId);
                    int maxPhotos = maxPendingImages(user);
                    String replyText = "📷 Фото получено: " + count + "/" + maxPhotos + "\n\n" +
                            "❗️Отправляйте фото по одному, не альбомом.\n" +
                            "Можете добавить ещё фото или отправить текстовый промпт ✏️";
                    SendMessage reply = new SendMessage(String.valueOf(message.getChatId()), replyText);
                    executeWithRetry(reply);
                }
            }
            return;
        }

        if (message.hasText()) {
            String text = message.getText().trim();
            if (text.startsWith("/start")) {
                modelSelectedThisSession.remove(userId);
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

            if (!text.startsWith("/")) {
                if (!modelSelectedThisSession.contains(userId) || user.currentModel == null) {
                    executeWithRetry(new SendMessage(String.valueOf(message.getChatId()),
                            "Сначала выберите модель через меню /start."));
                    return;
                }
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
            modelSelectedThisSession.remove(userId);
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
            db.clearPendingImages(userId);
            modelSelectedThisSession.add(userId);
            editMessage(chatId, messageId, modelInfoText(user), modelInfoKeyboard());
            return;
        }
        if ("model:nano-pro".equals(data)) {
            db.setCurrentModel(userId, MODEL_NANO_BANANA_PRO);
            user.currentModel = MODEL_NANO_BANANA_PRO;
            db.clearPendingImages(userId);
            modelSelectedThisSession.add(userId);
            editMessage(chatId, messageId, modelInfoText(user), modelInfoKeyboard());
            return;
        }
        if ("model:midjourney".equals(data)) {
            db.setCurrentModel(userId, MODEL_MIDJOURNEY);
            user.currentModel = MODEL_MIDJOURNEY;
            db.clearPendingImages(userId);
            modelSelectedThisSession.add(userId);
            editMessage(chatId, messageId, modelInfoText(user), modelInfoKeyboard());
            return;
        }
        if ("model:back".equals(data)) {
            sendStart(chatId, user);
            return;
        }
        if ("settings".equals(data)) {
            editMessage(chatId, messageId, settingsMenuText(user), settingsMenuKeyboard(user));
            return;
        }
        if ("settings:format_menu".equals(data)) {
            editMessage(chatId, messageId, formatMenuText(user), formatKeyboard(user));
            return;
        }
        if ("settings:resolution_menu".equals(data)) {
            if (isMidjourneyModel(normalizeModel(user.currentModel))) {
                executeWithRetry(new SendMessage(String.valueOf(chatId), "Для Midjourney качество не настраивается."));
                return;
            }
            editMessage(chatId, messageId, resolutionMenuText(user), resolutionKeyboard(user));
            return;
        }
        if (data.startsWith("settings:format:")) {
            String format = data.substring("settings:format:".length());
            if (!"auto".equalsIgnoreCase(format)) {
                executeWithRetry(new SendMessage(String.valueOf(chatId), "Сейчас доступен только автоматический формат."));
                return;
            }
            db.setOutputFormat(userId, "auto");
            user.outputFormat = "auto";
            editMessage(chatId, messageId, formatMenuText(user), formatKeyboard(user));
            return;
        }
        if (data.startsWith("settings:res:")) {
            if (isMidjourneyModel(normalizeModel(user.currentModel))) {
                executeWithRetry(new SendMessage(String.valueOf(chatId), "Для Midjourney качество не настраивается."));
                return;
            }
            String res = data.substring("settings:res:".length());
            db.setResolution(userId, res);
            user.resolution = res;
            editMessage(chatId, messageId, resolutionMenuText(user), resolutionKeyboard(user));
            return;
        }
        if ("settings:raw_toggle".equals(data)) {
            if (!isMidjourneyModel(normalizeModel(user.currentModel))) {
                return;
            }
            boolean next = !user.midjourneyRawEnabled;
            db.setMidjourneyRawEnabled(userId, next);
            user.midjourneyRawEnabled = next;
            editMessage(chatId, messageId, settingsMenuText(user), settingsMenuKeyboard(user));
            return;
        }
        if ("settings:translate_toggle".equals(data)) {
            if (!isMidjourneyModel(normalizeModel(user.currentModel))) {
                return;
            }
            boolean next = !user.midjourneyTranslateEnabled;
            db.setMidjourneyTranslateEnabled(userId, next);
            user.midjourneyTranslateEnabled = next;
            editMessage(chatId, messageId, settingsMenuText(user), settingsMenuKeyboard(user));
            return;
        }
        if (data.startsWith("settings:ratio:")) {
            String ratio = data.substring("settings:ratio:".length());
            db.setAspectRatio(userId, ratio);
            user.aspectRatio = ratio;
            editMessage(chatId, messageId, formatMenuText(user), formatKeyboard(user));
            return;
        }
        if ("settings:back".equals(data)) {
            editMessage(chatId, messageId, settingsMenuText(user), settingsMenuKeyboard(user));
            return;
        }
        if ("settings:back_to_model".equals(data)) {
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
        if ("menu:invite".equals(data)) {
            editMessage(chatId, messageId, referralText(userId), referralKeyboard());
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
        if ("admin:promo_list".equals(data)) {
            if (!isAdmin(userId)) {
                execute(new SendMessage(String.valueOf(chatId), "Нет доступа."));
                return;
            }
            editMessage(chatId, messageId, adminPromoListText(), adminKeyboard());
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
        if ("admin:promo_menu".equals(data)) {
            if (!isAdmin(userId)) {
                execute(new SendMessage(String.valueOf(chatId), "Нет доступа."));
                return;
            }
            editMessage(chatId, messageId, "Выберите сумму промокода:", adminPromoKeyboard());
            return;
        }
        if (data.startsWith("admin:promo:")) {
            if (!isAdmin(userId)) {
                execute(new SendMessage(String.valueOf(chatId), "Нет доступа."));
                return;
            }
            String suffix = data.substring("admin:promo:".length());
            if ("back".equals(suffix)) {
                sendAdminPanel(chatId, userId);
                return;
            }
            long tokens = parsePromoAmount(suffix);
            if (tokens <= 0) {
                execute(new SendMessage(String.valueOf(chatId), "Некорректная сумма промокода."));
                return;
            }
            String code = generatePromoCode();
            db.createPromoCode(code, tokens);
            execute(new SendMessage(String.valueOf(chatId),
                    "Промокод на " + formatNumber(tokens) + " токенов: " + code));
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
            long bonus = Math.round(option.tokens * 0.02);
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
            case SUCCESS -> execute(new SendMessage(String.valueOf(chatId), "Промокод успешно активирован."));
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
        String normalizedModel = normalizeModel(user.currentModel);
        boolean isMidjourney = isMidjourneyModel(normalizedModel);
        if (!isNanoModel(normalizedModel) && !isMidjourney) {
            execute(new SendMessage(String.valueOf(user.tgId), "Сначала выберите модель через меню /start"));
            return;
        }
        if (!activeGenerations.add(user.tgId)) {
            executeWithRetry(new SendMessage(String.valueOf(user.tgId),
                    "⏳ Уже идет генерация. Дождитесь завершения перед новым запросом."));
            return;
        }
        long cost = costForUser(user);
        if (user.balance < cost) {
            db.clearPendingImages(user.tgId);
            executeWithRetry(new SendMessage(String.valueOf(user.tgId),
                    "Недостаточно токенов. Пополните баланс в разделе «Купить токены».\n\n" +
                            "📷 Загруженные фото сброшены — после пополнения отправьте их заново."));
            activeGenerations.remove(user.tgId);
            return;
        }

        List<String> fileIds = db.consumePendingImages(user.tgId);
        if (isMidjourney && fileIds.size() > 5) {
            executeWithRetry(new SendMessage(String.valueOf(user.tgId),
                    "Для Midjourney можно загрузить не более 5 фотографий."));
            activeGenerations.remove(user.tgId);
            return;
        }

        db.addBalance(user.tgId, -cost);

        String modelLabel = modelLabel(normalizeModel(user.currentModel));
        String ratioLabel = aspectRatioLabel(user.aspectRatio);
        StringBuilder startText = new StringBuilder("✅ Запрос принят. Генерация началась\n\n");
        startText.append("🧠 Модель: ").append(modelLabel).append("\n");
        if (isMidjourney) {
            startText.append("📐 Формат: ").append(ratioLabel).append("\n");
            startText.append("Автоперевод: ").append(user.midjourneyTranslateEnabled ? "включен" : "выключен").append("\n");
            startText.append("RAW-MODE: ").append(user.midjourneyRawEnabled ? "включен" : "выключен").append("\n");
        } else {
            String resolutionLabel = resolutionLabel(user.resolution);
            String formatLabel = formatLabel(user.outputFormat);
            startText.append("📏 Разрешение: ").append(resolutionLabel).append("\n");
            startText.append("📐 Формат: ").append(ratioLabel).append("\n");
            startText.append("🖼️ Файл: ").append(formatLabel).append("\n");
        }
        startText.append("💰 Стоимость: ").append(formatNumber(cost)).append(" токенов");
        executeWithRetry(new SendMessage(String.valueOf(user.tgId), startText.toString()));

        executor.submit(() -> {
            boolean success = false;
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
                String aspectRatio = mapAspectRatio(user.aspectRatio);
                String model = normalizeModel(user.currentModel);
                String taskId;
                if (isMidjourneyModel(model)) {
                    String preparedPrompt = prepareMidjourneyPrompt(user, prompt);
                    String taskType = imageUrls.isEmpty() ? "mj_txt2img" : "mj_img2img";
                    System.out.println("Kie request model=mj-api taskType=" + taskType + " ratio=" + aspectRatio + " images=" + imageUrls.size());
                    taskId = kieClient.createMidjourneyTask(preparedPrompt, imageUrls, aspectRatio, taskType);
                } else {
                    if (MODEL_NANO_BANANA.equals(model) && !imageUrls.isEmpty()) {
                        model = MODEL_NANO_BANANA_EDIT;
                    }
                    System.out.println("Kie request model=" + model + " res=" + resolution + " ratio=" + aspectRatio + " images=" + imageUrls.size());
                    taskId = kieClient.createNanoBananaTask(model, prompt, imageUrls, aspectRatio, outputFormat, resolution);
                }

                success = pollTaskAndSend(taskId, user.tgId, model);
            } catch (Exception e) {
                if (isMidjourney) {
                    safeSend(user.tgId, midjourneyFailureMessage(e));
                } else {
                    safeSend(user.tgId, "Ошибка при генерации: " + e.getMessage() + "\nТокены возвращены.");
                }
            } finally {
                if (success) {
                    db.addSpent(user.tgId, cost);
                    db.recordModelUsage(user.tgId, normalizeModel(user.currentModel), cost);
                } else {
                    db.addBalance(user.tgId, cost);
                }
                activeGenerations.remove(user.tgId);
            }
        });
    }

    private boolean pollTaskAndSend(String taskId, long chatId, String modelUsed) {
        int attempts = 200;
        for (int i = 0; i < attempts; i++) {
            try {
                TimeUnit.SECONDS.sleep(3);
                KieClient.TaskInfo info = kieClient.getTaskInfo(taskId);
                if (i % 10 == 0) {
                    System.out.println("Kie task " + taskId + " state=" + info.state);
                }
                if ("success".equalsIgnoreCase(info.state) || "succeeded".equalsIgnoreCase(info.state) || "completed".equalsIgnoreCase(info.state)) {
                    List<String> urls = extractResultUrls(info.resultJson);
                    if (urls.isEmpty()) {
                        safeSend(chatId, "Готово, но без изображений. Попробуйте другой запрос.\nТокены возвращены.");
                        return false;
                    }
                    for (String url : urls) {
                        sendPhotoFromUrl(chatId, url);
                    }
                    return true;
                }
                if ("failed".equalsIgnoreCase(info.state)
                        || "fail".equalsIgnoreCase(info.state)
                        || "error".equalsIgnoreCase(info.state)
                        || "canceled".equalsIgnoreCase(info.state)
                        || "cancelled".equalsIgnoreCase(info.state)) {
                    safeSend(chatId, "Генерация не удалась: " + info.failReason + "\nТокены возвращены.");
                    return false;
                }
            } catch (Exception e) {
                safeSend(chatId, "Ошибка при проверке задачи: " + e.getMessage() + "\nТокены возвращены.");
                return false;
            }
        }
        safeSend(chatId, "Время ожидания истекло. Попробуйте ещё раз.\nТокены возвращены.");
        return false;
    }

    private String midjourneyFailureMessage(Exception e) {
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("No response from MidJourney Official Website")
                || msg.contains("\"code\":500")
                || msg.contains("code\":500")
                || msg.contains(" 500 ")) {
            return "Midjourney сейчас временно недоступен. Попробуйте ещё раз позже.\nТокены возвращены.";
        }
        return "Ошибка при генерации Midjourney: " + msg + "\nТокены возвращены.";
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
        String text = "Добро пожаловать в мульти-лабораторию контента. Выбирай модель под задачу — так результат будет быстрее и точнее\n\n" +
                "🧠 Текст\n" +
                "• <b>ChatGPT</b> — универсальный помощник: идеи, сценарии, продающие тексты, диалоги\n" +
                "• <b>Gemini</b> — аналитика, сравнения, факты, структурирование сложных тем\n" +
                "• <b>Grok</b> — дерзкий тон, трендовые форматы, короткие и цепкие формулировки\n\n" +
                "📸 Фото\n" +
                "• <b>Midjourney</b> — атмосферные сцены, стиль, арт, визуальные концепции\n" +
                "• <b>DALL·E 3</b> — точное следование описанию, сложные композиции и детали\n" +
                "• <b>NanoBanana</b> — быстрые правки: замена объектов, улучшение качества, вариации\n\n" +
                "🎬 Видео\n" +
                "• <b>Veo 3</b> — кинематографичные ролики и красивые планы\n" +
                "• <b>Sora 2</b> — сюжетные клипы, движение камеры, сложные сцены\n" +
                "• <b>Kling 3.0</b> — динамика, эффектные переходы, короткие рекламные видео\n\n" +
                "Твой баланс: <b>" + formatNumber(user.balance) + "</b> токенов";

        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(startKeyboard());
        execute(msg);
    }

    private InlineKeyboardMarkup startKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("🧠 Выбор модели", "menu:models")),
                List.of(button("💳 Купить токены", "menu:buy")),
                List.of(button("🔗 Пригласить друга", "menu:invite")),
                List.of(button("👤 Мой профиль", "menu:profile"))
        ));
    }

    private InlineKeyboardMarkup modelSelectKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("🍌 Nano Banana", "model:nano")),
                List.of(button("🍌 Nano Banana Pro", "model:nano-pro")),
                List.of(button("🌌 Midjourney", "model:midjourney")),
                List.of(button("⬅️ Назад", "menu:start"))
        ));
    }

    private InlineKeyboardMarkup modelInfoKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("⚙️ Настройки", "settings")),
                List.of(button("🏠 Вернуться в меню", "menu:start"))
        ));
    }

    private InlineKeyboardMarkup settingsMenuKeyboard(Database.User user) {
        if (isMidjourneyModel(normalizeModel(user.currentModel))) {
            return new InlineKeyboardMarkup(List.of(
                    List.of(button("📐 Изменить формат", "settings:format_menu")),
                    List.of(button(toggleLabel("RAW-MODE", user.midjourneyRawEnabled), "settings:raw_toggle")),
                    List.of(button(toggleLabel("Автоперевод", user.midjourneyTranslateEnabled), "settings:translate_toggle")),
                    List.of(button("⬅️ Назад", "settings:back_to_model"))
            ));
        }
        return new InlineKeyboardMarkup(List.of(
                List.of(button("📐 Изменить формат", "settings:format_menu")),
                List.of(button("📏 Качество", "settings:resolution_menu")),
                List.of(button("⬅️ Назад", "settings:back_to_model"))
        ));
    }

    private InlineKeyboardMarkup formatKeyboard(Database.User user) {
        String ratio = user.aspectRatio == null ? "auto" : user.aspectRatio;
        return new InlineKeyboardMarkup(List.of(
                List.of(button(ratioButtonLabel("📐 1:1", "1:1", ratio), "settings:ratio:1:1"),
                        button(ratioButtonLabel("📐 2:3", "2:3", ratio), "settings:ratio:2:3"),
                        button(ratioButtonLabel("📐 3:2", "3:2", ratio), "settings:ratio:3:2")),
                List.of(button(ratioButtonLabel("📐 3:4", "3:4", ratio), "settings:ratio:3:4"),
                        button(ratioButtonLabel("📐 4:3", "4:3", ratio), "settings:ratio:4:3"),
                        button(ratioButtonLabel("📐 5:6", "5:6", ratio), "settings:ratio:5:6")),
                List.of(button(ratioButtonLabel("📐 6:5", "6:5", ratio), "settings:ratio:6:5"),
                        button(ratioButtonLabel("📐 1:2", "1:2", ratio), "settings:ratio:1:2"),
                        button(ratioButtonLabel("📐 2:1", "2:1", ratio), "settings:ratio:2:1")),
                List.of(button(ratioButtonLabel("📐 16:9", "16:9", ratio), "settings:ratio:16:9"),
                        button(ratioButtonLabel("📐 9:16", "9:16", ratio), "settings:ratio:9:16")),
                List.of(button(ratioButtonLabel("📐 auto", "auto", ratio), "settings:ratio:auto")),
                List.of(button("⬅️ Назад", "settings:back"))
        ));
    }

    private InlineKeyboardMarkup resolutionKeyboard(Database.User user) {
        String resolution = user.resolution == null ? "2k" : user.resolution;
        return new InlineKeyboardMarkup(List.of(
                List.of(button(resButtonLabel("📏 1K", "1k", resolution), "settings:res:1k"),
                        button(resButtonLabel("📏 2K", "2k", resolution), "settings:res:2k"),
                        button(resButtonLabel("📏 4K", "4k", resolution), "settings:res:4k")),
                List.of(button("⬅️ Назад", "settings:back"))
        ));
    }

    private InlineKeyboardMarkup buyKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("💎 50.000 токенов - 99р", "buy:pack:50k")),
                List.of(button("💎 200.000 токенов - 239р", "buy:pack:200k")),
                List.of(button("💎 500.000 токенов - 529р", "buy:pack:500k")),
                List.of(button("💎 1.000.000 токенов - 999р", "buy:pack:1m")),
                List.of(button("🎟️ Активировать промокод", "promo:activate")),
                List.of(button("⬅️ Назад", "buy:back"))
        ));
    }

    private InlineKeyboardMarkup profileKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("🧾 Мои платежи", "profile:payments")),
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
                List.of(button("⬅️ Назад", "menu:start"))
        ));
    }

    private InlineKeyboardMarkup adminKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("📊 Статистика", "admin:stats")),
                List.of(button("🎁 Выдать 50 000", "admin:grant")),
                List.of(button("🎟️ Промокод", "admin:promo_menu")),
                List.of(button("📃 Активные промокоды", "admin:promo_list"))
        ));
    }

    private InlineKeyboardMarkup adminPromoKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("50 000", "admin:promo:50000"), button("100 000", "admin:promo:100000")),
                List.of(button("250 000", "admin:promo:250000"), button("500 000", "admin:promo:500000")),
                List.of(button("⬅️ Назад", "admin:promo:back"))
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
        executeWithRetry(edit);
    }

    private String modelInfoText(Database.User user) {
        long cost = costForUser(user);
        long queries = cost == 0 ? 0 : user.balance / cost;
        String normalized = normalizeModel(user.currentModel);
        if (isMidjourneyModel(normalized)) {
            return "🌆 Midjourney · создаёт безупречные изображения\n\n" +
                    "– Пожалуйста, отправьте мне сообщение, в котором опишите желаемый сюжет фотографии или приложите фото с промптом для создания нового изображения.\n" +
                    "– Для смешивания изображений загрузите от двух до пяти фотографий.\n\n" +
                    "⚙️ Настройки · помогут улучшить качество\n" +
                    "Формат фото: " + mapAspectRatio(user.aspectRatio) + "\n" +
                    "Автоперевод: " + (user.midjourneyTranslateEnabled ? "включен" : "выключен") + "\n" +
                    "RAW-MODE: " + (user.midjourneyRawEnabled ? "включен" : "выключен") + "\n\n" +
                    "🔹 Баланса хватит на " + queries + " запросов.\n" +
                    "1 генерация = " + formatNumber(cost) + " токенов.";
        }
        String title = MODEL_NANO_BANANA_PRO.equals(user.currentModel) ? "🍌 Nano Banana Pro · твори и экспериментируй"
                : "🍌 Nano Banana · твори и экспериментируй";
        return title + "\n\n" +
                "📖 Создавайте:\n" +
                "– Создает фотографии по промпту и по вашим изображениям;\n" +
                "– Она отлично наследует исходное фото и может работать с ним. Попросите её, например, отредактировать ваши фото (добавлять, удалять, менять объекты и всё, что угодно).\n\n" +
                "❗️Отправляйте фото по одному, не альбомом.\n\n" +
                "📷 При желании можете прикрепить до 10 фото, а промпт добавить отдельно.\n\n" +
                "✏️ Если промпт не помещается в одном сообщении вместе с фото, прикрепите сначала фото, а следующим сообщением – промпт.\n\n" +
                "⚙️ Настройки\n" +
                "Формат фото: " + formatLabel(user.outputFormat) + "\n" +
                "🔹 Баланса хватит на " + queries + " запросов. 1 генерация = " + formatNumber(cost) + " токенов";
    }

    private String settingsMenuText(Database.User user) {
        if (isMidjourneyModel(normalizeModel(user.currentModel))) {
            return "⚙️ Настройки\n" +
                    "Формат кадра: " + mapAspectRatio(user.aspectRatio) + "\n" +
                    "Автоперевод: " + (user.midjourneyTranslateEnabled ? "включен" : "выключен") + "\n" +
                    "RAW-MODE: " + (user.midjourneyRawEnabled ? "включен" : "выключен") + "\n\n" +
                    "Стоимость генерации:\n" +
                    "1 генерация = " + formatNumber(18_000) + " токенов";
        }
        long costDefault = costForUserResolution(user, "2k");
        long cost4k = costForUserResolution(user, "4k");
        return "⚙️ Настройки\n" +
                "Формат файла: автоматический\n" +
                "Разрешение: " + resolutionLabel(user.resolution) + "\n" +
                "Формат кадра: " + aspectRatioLabel(user.aspectRatio) + "\n\n" +
                "Стоимость генерации:\n" +
                "1K = " + formatNumber(costDefault) + " токенов\n" +
                "2K = " + formatNumber(costDefault) + " токенов\n" +
                "4K = " + formatNumber(cost4k) + " токенов";
    }

    private String formatMenuText(Database.User user) {
        String modelName = isMidjourneyModel(normalizeModel(user.currentModel)) ? "Midjourney" : "Nano Banana";
        return "🖼️ Формат изображения\n" +
                "Формат файла: автоматический\n" +
                "Формат кадра: " + aspectRatioLabel(user.aspectRatio) + "\n\n" +
                "📐 Выберите формат создаваемого фото в " + modelName + "\n" +
                "1:1: идеально подходит для профильных фото в соцсетях, таких как VK, Telegram и т.д\n\n" +
                "1:2: высокий вертикальный кадр для сторис и плакатов\n\n" +
                "2:3: хорошо подходит для печатных фотографий, но также может использоваться для пинов на Pinterest\n\n" +
                "3:2: широко используемый формат для фотографий, подходит для постов в Telegram, VK, и др.\n\n" +
                "3:4: широко используемый формат для фотографий, карточек товаров и т.д.\n\n" +
                "4:3: классический формат для фото и презентаций\n\n" +
                "5:6: мягкий вертикальный формат для портретов\n\n" +
                "6:5: чуть более широкий портретный формат\n\n" +
                "16:9: стандартный формат для видео, идеален для YouTube, VK и др.\n\n" +
                "9:16: оптимальный формат для Stories в Telegram или вертикальных видео на YouTube\n\n" +
                "2:1: широкий панорамный кадр\n\n" +
                "auto: автоматически подберет нужный формат";
    }

    private String resolutionMenuText(Database.User user) {
        long costDefault = costForUserResolution(user, "2k");
        long cost4k = costForUserResolution(user, "4k");
        return "📏 Разрешение\n" +
                "Текущее: " + resolutionLabel(user.resolution) + "\n\n" +
                "Стоимость генерации:\n" +
                "1K = " + formatNumber(costDefault) + " токенов\n" +
                "2K = " + formatNumber(costDefault) + " токенов\n" +
                "4K = " + formatNumber(cost4k) + " токенов";
    }

    private String buyText() {
        return "🤩 Наш бот предоставляет вам лучший сервис без каких либо ограничений и продолжает это делать ежедневно 24/7.\n" +
                "Выберите пакет токенов ниже — оплата проходит прямо в Telegram, а пополнение происходит мгновенно.\n" +
                "<b>Токены расходуются только за реальные генерации, всё прозрачно и без скрытых условий.</b>\n";
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
        String invitees = db.listReferrals(userId, 30);
        Database.User user = db.getUser(userId);
        long earned = user == null ? 0 : user.referralEarned;
        String link = "https://t.me/" + config.botUsername + "?start=ref" + userId;
        return "🔹 Реферальная программа\n\n" +
                "Приглашенному начисляется 50 000 токенов за переход по вашей ссылке.\n" +
                "Вы получаете 2% токенами от каждой покупки приглашенного пользователя.\n\n" +
                "👥 Приглашено пользователей: " + count + "\n" +
                "🔶 Получено: " + formatNumber(earned) + " токенов\n\n" +
                "👤 Список приглашенных:\n" + invitees + "\n\n" +
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
        Map<String, Long> counts = db.getModelUsageCounts();
        StringBuilder sb = new StringBuilder("📊 Статистика\n\n");
        sb.append("Всего пользователей: ").append(total).append("\n");
        sb.append("Активных подписок: ").append(activeSubs).append("\n\n");
        sb.append("Использование моделей:\n");
        if (counts.isEmpty()) {
            sb.append("— нет данных");
        } else {
            counts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(entry -> sb.append("• ")
                            .append(modelLabel(entry.getKey()))
                            .append(": ")
                            .append(entry.getValue())
                            .append("\n"));
        }
        return sb.toString().trim();
    }

    private String adminPromoListText() {
        List<Database.PromoCode> codes = db.listActivePromoCodes(50);
        StringBuilder sb = new StringBuilder("📃 Активные промокоды\n\n");
        if (codes.isEmpty()) {
            sb.append("— нет активных");
            return sb.toString();
        }
        for (Database.PromoCode code : codes) {
            sb.append("• ").append(code.code)
                    .append(" — ").append(formatNumber(code.tokens)).append(" токенов\n");
        }
        return sb.toString().trim();
    }

    private void safeSend(long chatId, String text) {
        try {
            executeWithRetry(new SendMessage(String.valueOf(chatId), text));
        } catch (Exception ignored) {
        }
    }

    private void sendPhotoFromUrl(long chatId, String url) {
        Path tempFile = null;
        Path compressedFile = null;
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "annexai-bot/1.0")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    safeSend(chatId, "Не удалось загрузить изображение по ссылке.");
                    return;
                }
                tempFile = Files.createTempFile("kie_", ".png");
                try (var in = response.body().byteStream()) {
                    Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            long size = Files.size(tempFile);
            if (size > 10 * 1024 * 1024) {
                compressedFile = compressToTelegramPhoto(tempFile);
                if (compressedFile != null) {
                    SendPhoto photo = new SendPhoto();
                    photo.setChatId(String.valueOf(chatId));
                    photo.setPhoto(new InputFile(compressedFile.toFile()));
                    executeWithRetry(photo);
                } else {
                    safeSend(chatId, "❗️Не удалось сжать фото. Отправляю только качественную версию файлом.");
                }
                safeSend(chatId, "🗂️ Качественная версия (без сжатия) будет загружена файлом в течение 5 минут.");
                Path originalFile = tempFile;
                tempFile = null;
                executor.submit(() -> sendDocumentAsync(chatId, originalFile));
            } else {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(String.valueOf(chatId));
                photo.setPhoto(new InputFile(tempFile.toFile()));
                executeWithRetry(photo);
            }
        } catch (Exception e) {
            safeSend(chatId, "Ошибка отправки изображения: " + e.getMessage());
        } finally {
            if (compressedFile != null) {
                try {
                    Files.deleteIfExists(compressedFile);
                } catch (Exception ignored) {
                }
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void sendDocumentAsync(long chatId, Path file) {
        try {
            SendDocument doc = new SendDocument();
            doc.setChatId(String.valueOf(chatId));
            doc.setDocument(new InputFile(file.toFile()));
            doc.setCaption("Качественная версия (без сжатия)");
            executeWithRetry(doc);
        } catch (Exception e) {
            safeSend(chatId, "Не удалось отправить качественную версию: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
        }
    }

    private Path compressToTelegramPhoto(Path original) {
        try {
            BufferedImage source = ImageIO.read(original.toFile());
            if (source == null) {
                return null;
            }
            BufferedImage base = toRgb(source);
            int width = base.getWidth();
            int height = base.getHeight();
            float[] qualities = new float[]{0.92f, 0.85f, 0.75f, 0.65f, 0.55f, 0.45f, 0.35f};
            double scale = 1.0;

            for (int scaleTry = 0; scaleTry < 4; scaleTry++) {
                BufferedImage scaled = scale == 1.0 ? base : resize(base, (int) (width * scale), (int) (height * scale));
                for (float q : qualities) {
                    byte[] data = writeJpeg(scaled, q);
                    if (data != null && data.length <= 10 * 1024 * 1024) {
                        Path out = Files.createTempFile("kie_compressed_", ".jpg");
                        Files.write(out, data);
                        return out;
                    }
                }
                scale *= 0.85;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private BufferedImage toRgb(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return resized;
    }

    private byte[] writeJpeg(BufferedImage image, float quality) {
        ImageWriter writer = null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                return null;
            }
            writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (writer != null) {
                writer.dispose();
            }
        }
    }

    private String getTelegramFileUrl(String fileId) throws TelegramApiException {
        GetFile getFile = new GetFile(fileId);
        org.telegram.telegrambots.meta.api.objects.File file = execute(getFile);
        return "https://api.telegram.org/file/bot" + config.botToken + "/" + file.getFilePath();
    }

    private boolean saveIncomingPhotos(Database.User user, Message message) {
        long userId = user.tgId;
        List<PhotoSize> photos = message.getPhoto();
        if (photos == null || photos.isEmpty()) {
            return false;
        }
        int maxPhotos = maxPendingImages(user);
        int current = db.countPendingImages(userId);
        if (current >= maxPhotos) {
            safeSend(message.getChatId(),
                    "Лимит фото достигнут: " + maxPhotos + ". Отправьте промпт, чтобы начать генерацию.");
            return true;
        }
        PhotoSize best = photos.get(photos.size() - 1);
        String mediaGroupId = message.getMediaGroupId();
        if (mediaGroupId != null && !mediaGroupId.isBlank()) {
            String last = lastAlbumNotice.get(userId);
            if (last == null || !last.equals(mediaGroupId)) {
                lastAlbumNotice.put(userId, mediaGroupId);
                safeSend(message.getChatId(),
                        "❗️Пожалуйста, отправляйте фото по одному, не альбомом.\n" +
                                "Так фото корректно привязываются к промпту.");
            }
            return true;
        }
        db.addPendingImage(userId, best.getFileId(), maxPhotos);
        return false;
    }

    private long costForUser(Database.User user) {
        String normalized = normalizeModel(user.currentModel);
        if (isMidjourneyModel(normalized)) {
            return 18_000;
        }
        String res = user.resolution == null ? "2k" : user.resolution.toLowerCase(Locale.ROOT);
        return costForUserResolution(user, res);
    }

    private long costForUserResolution(Database.User user, String res) {
        boolean isPro = MODEL_NANO_BANANA_PRO.equals(normalizeModel(user.currentModel));
        if ("4k".equalsIgnoreCase(res)) {
            return isPro ? 40_000 : 10_000;
        }
        if ("1k".equalsIgnoreCase(res) || "2k".equalsIgnoreCase(res)) {
            return isPro ? 36_000 : 9_000;
        }
        return isPro ? 36_000 : 9_000;
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

    private String mapAspectRatio(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return "auto";
        }
        String normalized = ratio.toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized)) {
            return "1:1";
        }
        return normalized;
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

    private String aspectRatioLabel(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return "auto";
        }
        return ratio.toLowerCase(Locale.ROOT);
    }

    private String modelLabel(String model) {
        if (MODEL_NANO_BANANA.equals(model) || MODEL_NANO_BANANA_EDIT.equals(model) || "nano-banana".equalsIgnoreCase(model)) {
            return "Nano Banana";
        }
        if (MODEL_NANO_BANANA_PRO.equals(model)) {
            return "Nano Banana Pro";
        }
        if (isMidjourneyModel(model)) {
            return "Midjourney";
        }
        return model;
    }

    private String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            return MODEL_NANO_BANANA;
        }
        if ("nano-banana".equalsIgnoreCase(model)) {
            return MODEL_NANO_BANANA;
        }
        if ("nano-banana-pro".equalsIgnoreCase(model)) {
            return MODEL_NANO_BANANA_PRO;
        }
        if ("midjourney".equalsIgnoreCase(model)) {
            return MODEL_MIDJOURNEY;
        }
        return model;
    }

    private boolean isNanoModel(String model) {
        return MODEL_NANO_BANANA.equals(model)
                || MODEL_NANO_BANANA_EDIT.equals(model)
                || MODEL_NANO_BANANA_PRO.equals(model);
    }

    private boolean isMidjourneyModel(String model) {
        return MODEL_MIDJOURNEY.equals(model);
    }

    private String prepareMidjourneyPrompt(Database.User user, String prompt) {
        String base = prompt == null ? "" : prompt.trim();
        boolean rawMode = user == null || user.midjourneyRawEnabled;
        boolean translate = user == null || user.midjourneyTranslateEnabled;
        String translated = translate ? autoTranslateToEnglish(base) : base;
        if (translated.isBlank()) {
            return rawMode ? "--style raw" : translated;
        }
        String lower = translated.toLowerCase(Locale.ROOT);
        if (lower.contains("--style raw") || !rawMode) {
            return translated;
        }
        return translated + " --style raw";
    }

    private String autoTranslateToEnglish(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        if (!containsCyrillic(prompt)) {
            return prompt;
        }
        try {
            String encoded = java.net.URLEncoder.encode(prompt, java.nio.charset.StandardCharsets.UTF_8);
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=" + encoded;
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return prompt;
                }
                String body = response.body().string();
                JsonNode root = mapper.readTree(body);
                JsonNode sentences = root.get(0);
                if (sentences == null || !sentences.isArray()) {
                    return prompt;
                }
                StringBuilder sb = new StringBuilder();
                for (JsonNode sentence : sentences) {
                    JsonNode part = sentence.get(0);
                    if (part != null) {
                        sb.append(part.asText());
                    }
                }
                String result = sb.toString().trim();
                return result.isBlank() ? prompt : result;
            }
        } catch (Exception e) {
            return prompt;
        }
    }

    private boolean containsCyrillic(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u0400' && ch <= '\u04FF') {
                return true;
            }
        }
        return false;
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

    private String ratioButtonLabel(String label, String value, String current) {
        if (value.equalsIgnoreCase(current)) {
            return "✅ " + label;
        }
        return label;
    }

    private String toggleLabel(String label, boolean enabled) {
        return label + ": " + (enabled ? "включен" : "выключен");
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

    private int maxPendingImages(Database.User user) {
        if (user != null && isMidjourneyModel(normalizeModel(user.currentModel))) {
            return 5;
        }
        return 10;
    }

    private long parsePromoAmount(String raw) {
        if (raw == null) {
            return -1;
        }
        String digits = raw.replaceAll("\\D+", "");
        if (digits.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isAdmin(long userId) {
        return config.adminIds.contains(userId);
    }

    private String formatNumber(long value) {
        return String.format("%,d", value).replace(',', ' ');
    }

    private void executeWithRetry(SendMessage msg) throws TelegramApiException {
        executeWithRetryInternal(() -> execute(msg), 3);
    }

    private void executeWithRetry(SendPhoto photo) throws TelegramApiException {
        executeWithRetryInternal(() -> execute(photo), 3);
    }

    private void executeWithRetry(SendDocument doc) throws TelegramApiException {
        executeWithRetryInternal(() -> execute(doc), 3);
    }

    private void executeWithRetry(EditMessageText edit) throws TelegramApiException {
        executeWithRetryInternal(() -> execute(edit), 3);
    }

    private void executeWithRetryInternal(ThrowingAction action, int attempts) throws TelegramApiException {
        TelegramApiException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                action.run();
                return;
            } catch (TelegramApiException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("message is not modified")) {
                    return;
                }
                last = e;
                if (!isRetryable(e) || i == attempts - 1) {
                    throw e;
                }
                sleep(500);
            }
        }
        if (last != null) {
            throw last;
        }
    }

    private boolean isRetryable(TelegramApiException e) {
        Throwable cause = e.getCause();
        if (cause instanceof java.net.SocketException
                || cause instanceof java.net.UnknownHostException
                || cause instanceof java.net.SocketTimeoutException) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && (msg.contains("Connection reset") || msg.contains("NoHttpResponse"));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }


    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws TelegramApiException;
    }

    private String generatePromoCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
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
