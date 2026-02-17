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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
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
    private static final String MODEL_FLUX_2_TEXT = "flux-2/pro-text-to-image";
    private static final String MODEL_FLUX_2_IMAGE = "flux-2/pro-image-to-image";
    private static final String MODEL_FLUX_2_FLEX_TEXT = "flux-2/flex-text-to-image";
    private static final String MODEL_FLUX_2_FLEX_IMAGE = "flux-2/flex-image-to-image";
    private static final String MODEL_IDEOGRAM_CHARACTER = "ideogram/character";
    private static final String MODEL_IDEOGRAM_V3_REMIX = "ideogram/v3-remix";
    private static final String MODEL_IDEOGRAM_V3_EDIT = "ideogram/v3-edit";
    private static final String MODEL_GEMINI_3_FLASH = "gemini-3-flash";
    private static final String MODEL_GEMINI_3_PRO = "gemini-3-pro";
    private static final int GEMINI_HISTORY_LIMIT = 12;

    private final Config config;
    private final Database db;
    private final KieClient kieClient;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
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

        if (isGeminiModel(normalizeModel(user.currentModel))) {
            if (message.hasVoice()) {
                handleGeminiVoice(user, message);
                return;
            }
            if (message.hasDocument()) {
                handleGeminiDocument(user, message);
                return;
            }
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
            if ("/clear".equalsIgnoreCase(text) && isGeminiModel(normalizeModel(user.currentModel))) {
                db.clearGeminiMessages(userId);
                execute(new SendMessage(String.valueOf(message.getChatId()), "историю диалога очищена"));
                return;
            }
            if ("/end".equalsIgnoreCase(text) && isGeminiModel(normalizeModel(user.currentModel))) {
                db.clearGeminiMessages(userId);
                modelSelectedThisSession.remove(userId);
                sendStart(message.getChatId(), user);
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
        if ("model:flux".equals(data)) {
            db.setCurrentModel(userId, MODEL_FLUX_2_TEXT);
            user.currentModel = MODEL_FLUX_2_TEXT;
            db.clearPendingImages(userId);
            modelSelectedThisSession.add(userId);
            editMessage(chatId, messageId, modelInfoText(user), modelInfoKeyboard());
            return;
        }
        if ("model:gemini".equals(data)) {
            if (!isGeminiModel(normalizeModel(user.currentModel))) {
                db.setCurrentModel(userId, MODEL_GEMINI_3_PRO);
                user.currentModel = MODEL_GEMINI_3_PRO;
            }
            db.clearPendingImages(userId);
            modelSelectedThisSession.add(userId);
            editMessage(chatId, messageId, geminiDialogText(user), geminiDialogKeyboard(user));
            return;
        }
        if ("model:ideogram".equals(data)) {
            if (!isIdeogramModel(normalizeModel(user.currentModel))) {
                db.setCurrentModel(userId, MODEL_IDEOGRAM_V3_REMIX);
                user.currentModel = MODEL_IDEOGRAM_V3_REMIX;
            }
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
            editMessage(chatId, messageId, resolutionMenuText(user), resolutionKeyboard(user));
            return;
        }
        if ("settings:ideogram_model_menu".equals(data)) {
            editMessage(chatId, messageId, ideogramModelMenuText(user), ideogramModelKeyboard(user));
            return;
        }
        if ("settings:ideogram_speed_menu".equals(data)) {
            editMessage(chatId, messageId, ideogramSpeedMenuText(user), ideogramSpeedKeyboard(user));
            return;
        }
        if ("settings:ideogram_style_menu".equals(data)) {
            editMessage(chatId, messageId, ideogramStyleMenuText(user), ideogramStyleKeyboard(user));
            return;
        }
        if ("settings:ideogram_size_menu".equals(data)) {
            editMessage(chatId, messageId, ideogramSizeMenuText(user), ideogramSizeKeyboard(user));
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
            String res = data.substring("settings:res:".length());
            db.setResolution(userId, res);
            user.resolution = res;
            editMessage(chatId, messageId, resolutionMenuText(user), resolutionKeyboard(user));
            return;
        }
        if (data.startsWith("settings:ratio:")) {
            String ratio = data.substring("settings:ratio:".length());
            db.setAspectRatio(userId, ratio);
            user.aspectRatio = ratio;
            editMessage(chatId, messageId, formatMenuText(user), formatKeyboard(user));
            return;
        }
        if (data.startsWith("settings:ideogram_model:")) {
            String model = data.substring("settings:ideogram_model:".length());
            String next;
            switch (model) {
                case "character" -> next = MODEL_IDEOGRAM_CHARACTER;
                case "edit" -> next = MODEL_IDEOGRAM_V3_EDIT;
                case "remix" -> next = MODEL_IDEOGRAM_V3_REMIX;
                default -> next = MODEL_IDEOGRAM_CHARACTER;
            }
            db.setCurrentModel(userId, next);
            user.currentModel = next;
            db.clearPendingImages(userId);
            editMessage(chatId, messageId, ideogramModelMenuText(user), ideogramModelKeyboard(user));
            return;
        }
        if (data.startsWith("settings:ideogram_speed:")) {
            String speed = data.substring("settings:ideogram_speed:".length());
            db.setIdeogramSpeed(userId, speed);
            user.ideogramSpeed = speed;
            editMessage(chatId, messageId, ideogramSpeedMenuText(user), ideogramSpeedKeyboard(user));
            return;
        }
        if (data.startsWith("settings:ideogram_style:")) {
            db.setIdeogramStyle(userId, "auto");
            user.ideogramStyle = "auto";
            editMessage(chatId, messageId, ideogramStyleMenuText(user), ideogramStyleKeyboard(user));
            return;
        }
        if (data.startsWith("settings:ideogram_size:")) {
            String size = data.substring("settings:ideogram_size:".length());
            db.setIdeogramImageSize(userId, size);
            user.ideogramImageSize = size;
            editMessage(chatId, messageId, ideogramSizeMenuText(user), ideogramSizeKeyboard(user));
            return;
        }
        if ("settings:ideogram_expand_toggle".equals(data)) {
            boolean next = !user.ideogramExpandPrompt;
            db.setIdeogramExpandPrompt(userId, next);
            user.ideogramExpandPrompt = next;
            editMessage(chatId, messageId, settingsMenuText(user), settingsMenuKeyboard(user));
            return;
        }
        if ("gemini:history_toggle".equals(data)) {
            boolean next = !user.geminiHistoryEnabled;
            db.setGeminiHistoryEnabled(userId, next);
            user.geminiHistoryEnabled = next;
            editMessage(chatId, messageId, geminiDialogText(user), geminiDialogKeyboard(user));
            return;
        }
        if ("gemini:cost_toggle".equals(data)) {
            boolean next = !user.geminiShowCostEnabled;
            db.setGeminiShowCostEnabled(userId, next);
            user.geminiShowCostEnabled = next;
            editMessage(chatId, messageId, geminiDialogText(user), geminiDialogKeyboard(user));
            return;
        }
        if ("gemini:change_model".equals(data)) {
            editMessage(chatId, messageId, geminiChangeModelText(user), geminiChangeModelKeyboard(user));
            return;
        }
        if ("gemini:model:flash".equals(data)) {
            db.setCurrentModel(userId, MODEL_GEMINI_3_FLASH);
            user.currentModel = MODEL_GEMINI_3_FLASH;
            editMessage(chatId, messageId, geminiDialogText(user), geminiDialogKeyboard(user));
            return;
        }
        if ("gemini:model:pro".equals(data)) {
            db.setCurrentModel(userId, MODEL_GEMINI_3_PRO);
            user.currentModel = MODEL_GEMINI_3_PRO;
            editMessage(chatId, messageId, geminiDialogText(user), geminiDialogKeyboard(user));
            return;
        }
        if ("gemini:clear_history".equals(data)) {
            db.clearGeminiMessages(userId);
            execute(new SendMessage(String.valueOf(chatId), "историю диалога очищена"));
            editMessage(chatId, messageId, geminiDialogText(user), geminiDialogKeyboard(user));
            return;
        }
        if ("settings:flux_flex_toggle".equals(data)) {
            if (!isFluxModel(normalizeModel(user.currentModel))) {
                return;
            }
            String next = isFluxFlexModel(normalizeModel(user.currentModel))
                    ? MODEL_FLUX_2_TEXT
                    : MODEL_FLUX_2_FLEX_TEXT;
            db.setCurrentModel(userId, next);
            user.currentModel = next;
            editMessage(chatId, messageId, settingsMenuText(user), settingsMenuKeyboard(user));
            return;
        }
        if ("settings:nano_pro_toggle".equals(data)) {
            if (!isNanoModel(normalizeModel(user.currentModel))) {
                return;
            }
            String next = MODEL_NANO_BANANA_PRO.equals(normalizeModel(user.currentModel))
                    ? MODEL_NANO_BANANA
                    : MODEL_NANO_BANANA_PRO;
            db.setCurrentModel(userId, next);
            user.currentModel = next;
            db.clearPendingImages(userId);
            editMessage(chatId, messageId, settingsMenuText(user), settingsMenuKeyboard(user));
            return;
        }
        if ("settings:back".equals(data)) {
            editMessage(chatId, messageId, settingsMenuText(user), settingsMenuKeyboard(user));
            return;
        }
        if ("settings:back_to_model".equals(data)) {
            if (isGeminiModel(normalizeModel(user.currentModel))) {
                editMessage(chatId, messageId, geminiDialogText(user), geminiDialogKeyboard(user));
            } else {
                editMessage(chatId, messageId, modelInfoText(user), modelInfoKeyboard());
            }
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

    private void handleGeminiVoice(Database.User user, Message message) throws TelegramApiException {
        if (!modelSelectedThisSession.contains(user.tgId)) {
            executeWithRetry(new SendMessage(String.valueOf(message.getChatId()),
                    "Сначала выберите модель через меню /start."));
            return;
        }
        Voice voice = message.getVoice();
        if (voice == null) {
            return;
        }
        String url = getTelegramFileUrl(voice.getFileId());
        String prompt = (message.getCaption() == null ? "" : message.getCaption().trim());
        if (!prompt.isBlank()) {
            prompt += "\n\n";
        }
        prompt += "Голосовое сообщение: " + url;
        handlePrompt(user, prompt);
    }

    private void handleGeminiDocument(Database.User user, Message message) throws TelegramApiException {
        if (!modelSelectedThisSession.contains(user.tgId)) {
            executeWithRetry(new SendMessage(String.valueOf(message.getChatId()),
                    "Сначала выберите модель через меню /start."));
            return;
        }
        Document doc = message.getDocument();
        if (doc == null) {
            return;
        }
        String url = getTelegramFileUrl(doc.getFileId());
        String prompt = (message.getCaption() == null ? "" : message.getCaption().trim());
        String text = null;
        if (isTextDocument(doc)) {
            text = loadTextFromUrl(url, 8000);
        }
        if (!prompt.isBlank()) {
            prompt += "\n\n";
        }
        if (text != null && !text.isBlank()) {
            prompt += "Файл " + doc.getFileName() + ":\n" + text;
        } else {
            prompt += "Файл: " + (doc.getFileName() == null ? "без имени" : doc.getFileName()) + "\n" + url;
        }
        handlePrompt(user, prompt);
    }

    private void handlePrompt(Database.User user, String prompt) throws TelegramApiException {
        String normalizedModel = normalizeModel(user.currentModel);
        boolean isFlux = isFluxModel(normalizedModel);
        boolean isIdeogram = isIdeogramModel(normalizedModel);
        boolean isGemini = isGeminiModel(normalizedModel);
        if (!isNanoModel(normalizedModel) && !isFlux && !isIdeogram && !isGemini) {
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
            SendMessage msg = new SendMessage(String.valueOf(user.tgId),
                    "😔 У вас не хватает токенов на обработку этого запроса. <b>Купите дополнительные токены</b> в нашем магазине или <b>пригласите друзей</b> по своей реферальной ссылке. За каждого друга вы будете получать 5% токенов от их пополнений.");
            msg.setParseMode("HTML");
            msg.setReplyMarkup(insufficientTokensKeyboard());
            executeWithRetry(msg);
            activeGenerations.remove(user.tgId);
            return;
        }
        if (isIdeogram) {
            int required = ideogramRequiredImages(normalizedModel);
            int current = db.countPendingImages(user.tgId);
            if (required > 0 && current < required) {
                String hint = switch (normalizedModel) {
                    case MODEL_IDEOGRAM_V3_EDIT -> "Для Edit нужно 2 изображения: исходник и маска (белым обозначьте область изменения).";
                    case MODEL_IDEOGRAM_V3_REMIX -> "Для Remix нужно 1 изображение с исходной сценой.";
                    default -> "Добавьте изображения и повторите запрос.";
                };
                executeWithRetry(new SendMessage(String.valueOf(user.tgId),
                        "Недостаточно изображений для генерации.\n" +
                                "Сейчас: " + current + "/" + required + "\n" +
                                hint));
                activeGenerations.remove(user.tgId);
                return;
            }
        }

        List<String> fileIds = db.consumePendingImages(user.tgId);
        if (isFlux && fileIds.size() > 8) {
            fileIds = fileIds.subList(0, 8);
        }
        if (isIdeogram) {
            int max = ideogramMaxImages(normalizedModel);
            if (fileIds.size() > max) {
                fileIds = fileIds.subList(0, max);
            }
        }
        List<String> pendingImages = List.copyOf(fileIds);
        db.addBalance(user.tgId, -cost);

        String modelLabel = modelLabel(normalizedModel);
        String ratioLabel = aspectRatioLabel(user.aspectRatio);
        Integer progressMessageId = null;
        if (isGemini) {
            SendMessage progress = new SendMessage(String.valueOf(user.tgId), "Пишу ответ...");
            Message sent = executeWithRetryMessage(progress);
            progressMessageId = sent == null ? null : sent.getMessageId();
        } else {
            StringBuilder startText = new StringBuilder("✅ Запрос принят. Генерация началась\n\n");
            startText.append("🧠 Модель: ").append(modelLabel).append("\n");
            if (isFlux) {
                boolean flex = isFluxFlexModel(normalizedModel);
                String resolutionLabel = fluxResolutionLabel(user.resolution);
                startText.append("✨ FLEX: ").append(flex ? "включен" : "выключен").append("\n");
                startText.append("📏 Разрешение: ").append(resolutionLabel).append("\n");
                startText.append("📐 Формат: ").append(ratioLabel).append("\n");
            } else if (isIdeogram) {
                boolean isEdit = isIdeogramEdit(normalizedModel);
                startText.append("⚡ Скорость: ").append(ideogramSpeedLabel(user.ideogramSpeed)).append("\n");
                if (!isEdit) {
                    startText.append("📐 Формат: ").append(ideogramSizeLabel(user.ideogramImageSize)).append("\n");
                }
                startText.append("✨ Magic Prompt: ").append(user.ideogramExpandPrompt ? "включен" : "выключен").append("\n");
            } else {
                String resolutionLabel = resolutionLabel(user.resolution);
                String formatLabel = formatLabel(user.outputFormat);
                startText.append("📏 Разрешение: ").append(resolutionLabel).append("\n");
                startText.append("📐 Формат: ").append(ratioLabel).append("\n");
                startText.append("🖼️ Файл: ").append(formatLabel).append("\n");
            }
            startText.append("💰 Стоимость: ").append(formatNumber(cost)).append(" токенов");
            executeWithRetry(new SendMessage(String.valueOf(user.tgId), startText.toString()));
        }

        Integer finalProgressMessageId = progressMessageId;
        executor.submit(() -> {
            boolean success = false;
            try {
                List<String> imageUrls = new ArrayList<>();
                int i = 1;
                for (String fileId : pendingImages) {
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
                if (isFluxModel(model)) {
                    String preparedPrompt = prepareFluxPrompt(prompt);
                    boolean flex = isFluxFlexModel(model);
                    String fluxModel = imageUrls.isEmpty()
                            ? (flex ? MODEL_FLUX_2_FLEX_TEXT : MODEL_FLUX_2_TEXT)
                            : (flex ? MODEL_FLUX_2_FLEX_IMAGE : MODEL_FLUX_2_IMAGE);
                    String fluxResolution = fluxResolutionValue(user.resolution);
                    String fluxAspectRatio = normalizeFluxAspectRatio(user.aspectRatio, !imageUrls.isEmpty());
                    System.out.println("Kie request model=" + fluxModel + " res=" + fluxResolution + " ratio=" + fluxAspectRatio + " images=" + imageUrls.size());
                    taskId = kieClient.createFluxTask(fluxModel, preparedPrompt, imageUrls, fluxAspectRatio, fluxResolution);
                } else if (isIdeogramModel(model)) {
                    String preparedPrompt = prepareIdeogramPrompt(prompt);
                    String speed = ideogramSpeedValue(user.ideogramSpeed);
                    String style = null;
                    String size = ideogramSizeValue(user.ideogramImageSize);
                    boolean expand = user.ideogramExpandPrompt;
                    if (isIdeogramCharacter(model)) {
                        List<String> refs = imageUrls.isEmpty()
                                ? List.of()
                                : imageUrls.subList(0, Math.min(3, imageUrls.size()));
                        System.out.println("Kie request model=" + MODEL_IDEOGRAM_CHARACTER + " speed=" + speed + " style=" + style + " size=" + size + " refs=" + refs.size());
                        taskId = kieClient.createIdeogramTask(MODEL_IDEOGRAM_CHARACTER, preparedPrompt, speed, style, expand, size, refs, null, null, 1, null);
                    } else if (isIdeogramEdit(model)) {
                        String imageUrl = imageUrls.size() > 0 ? imageUrls.get(0) : null;
                        String maskUrl = imageUrls.size() > 1 ? imageUrls.get(1) : null;
                        if (imageUrl == null || maskUrl == null) {
                            throw new IllegalStateException("Для Edit нужны 2 изображения: исходник и маска.");
                        }
                        System.out.println("Kie request model=" + MODEL_IDEOGRAM_V3_EDIT + " speed=" + speed + " images=2");
                        taskId = kieClient.createIdeogramTask(MODEL_IDEOGRAM_V3_EDIT, preparedPrompt, speed, style, expand, null, null, imageUrl, maskUrl, null, null);
                    } else {
                        String imageUrl = imageUrls.size() > 0 ? imageUrls.get(0) : null;
                        if (imageUrl == null) {
                            throw new IllegalStateException("Для Remix нужно 1 изображение.");
                        }
                        System.out.println("Kie request model=" + MODEL_IDEOGRAM_V3_REMIX + " speed=" + speed + " style=" + style + " size=" + size + " images=1");
                        taskId = kieClient.createIdeogramTask(MODEL_IDEOGRAM_V3_REMIX, preparedPrompt, speed, style, expand, size, null, imageUrl, null, 1, null);
                    }
                } else if (isGeminiModel(model)) {
                    List<Database.GeminiMessage> history = user.geminiHistoryEnabled
                            ? db.listGeminiMessages(user.tgId, GEMINI_HISTORY_LIMIT)
                            : List.of();
                    List<KieClient.ChatMessage> messages = buildGeminiMessages(history, prompt, imageUrls);
                    System.out.println("Kie request gemini=" + model + " history=" + history.size() + " images=" + imageUrls.size());
                    String responseText = kieClient.createGeminiCompletion(model, messages);
                    if (responseText == null || responseText.isBlank()) {
                        throw new IllegalStateException("Пустой ответ от модели.");
                    }
                    deleteMessageQuietly(user.tgId, finalProgressMessageId);
                    String outputText = responseText;
                    if (user.geminiShowCostEnabled) {
                        outputText = outputText + "\n\n💰 Стоимость: " + formatNumber(cost) + " токенов";
                    }
                    sendLongMessage(user.tgId, outputText);
                    if (user.geminiHistoryEnabled) {
                        db.addGeminiMessage(user.tgId, "user", prompt);
                        db.addGeminiMessage(user.tgId, "assistant", responseText);
                    }
                    success = true;
                    return;
                } else {
                    if (MODEL_NANO_BANANA.equals(model) && !imageUrls.isEmpty()) {
                        model = MODEL_NANO_BANANA_EDIT;
                    }
                    System.out.println("Kie request model=" + model + " res=" + resolution + " ratio=" + aspectRatio + " images=" + imageUrls.size());
                    taskId = kieClient.createNanoBananaTask(model, prompt, imageUrls, aspectRatio, outputFormat, resolution);
                }

                success = pollTaskAndSend(taskId, user.tgId, model);
            } catch (Exception e) {
                deleteMessageQuietly(user.tgId, finalProgressMessageId);
                safeSend(user.tgId, "Ошибка при генерации: " + e.getMessage() + "\nТокены возвращены.");
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
                if (!urls.isEmpty()) {
                    return urls;
                }
            }
            JsonNode imageUrls = node.path("image_urls");
            if (imageUrls.isArray()) {
                for (JsonNode n : imageUrls) {
                    urls.add(n.asText());
                }
                if (!urls.isEmpty()) {
                    return urls;
                }
            }
            JsonNode images = node.path("images");
            if (images.isArray()) {
                for (JsonNode n : images) {
                    if (n.isTextual()) {
                        urls.add(n.asText());
                    } else {
                        String url = n.path("url").asText();
                        if (url == null || url.isBlank()) {
                            url = n.path("image_url").asText();
                        }
                        if (url != null && !url.isBlank()) {
                            urls.add(url);
                        }
                    }
                }
                if (!urls.isEmpty()) {
                    return urls;
                }
            }
            String singleUrl = node.path("image_url").asText();
            if (singleUrl != null && !singleUrl.isBlank()) {
                urls.add(singleUrl);
            }
        } catch (Exception e) {
            return urls;
        }
        return urls;
    }

    private String extractResultText(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(resultJson);
            JsonNode text = node.path("text");
            if (text.isTextual()) {
                return text.asText();
            }
            JsonNode content = node.path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            JsonNode result = node.path("result");
            if (result.isTextual()) {
                return result.asText();
            }
            JsonNode resultText = result.path("text");
            if (resultText.isTextual()) {
                return resultText.asText();
            }
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode first = choices.get(0);
                JsonNode msg = first.path("message");
                JsonNode msgContent = msg.path("content");
                if (msgContent.isTextual()) {
                    return msgContent.asText();
                }
                JsonNode choiceText = first.path("text");
                if (choiceText.isTextual()) {
                    return choiceText.asText();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
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
                "• <b>Gemini 3</b> — аналитика, сравнения, факты, структурирование сложных тем\n" +
                "• <b>Grok</b> — дерзкий тон, трендовые форматы, короткие и цепкие формулировки\n\n" +
                "📸 Фото\n" +
                "• <b>Flux 2</b> — универсальная генерация с гибкими настройками\n" +
                "• <b>Ideogram V3</b> — сильная типографика, постеры и точная работа с текстом\n" +
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
                List.of(button("💬 Gemini 3", "model:gemini")),
                List.of(button("🍌 Nano Banana", "model:nano")),
                List.of(button("🌀 Flux 2", "model:flux")),
                List.of(button("🧩 Ideogram V3", "model:ideogram")),
                List.of(button("⬅️ Назад", "menu:start"))
        ));
    }

    private InlineKeyboardMarkup modelInfoKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("⚙️ Настройки", "settings")),
                List.of(button("🏠 Вернуться в меню", "menu:start"))
        ));
    }

    private InlineKeyboardMarkup geminiDialogKeyboard(Database.User user) {
        String historyLabel = (user.geminiHistoryEnabled ? "✅ " : "❌ ") + "История " + (user.geminiHistoryEnabled ? "включена" : "выключена");
        String costLabel = (user.geminiShowCostEnabled ? "✅ " : "❌ ") + "Показ затрат " + (user.geminiShowCostEnabled ? "включен" : "выключен");
        return new InlineKeyboardMarkup(List.of(
                List.of(button(historyLabel, "gemini:history_toggle")),
                List.of(button(costLabel, "gemini:cost_toggle")),
                List.of(button("🔁 Изменить модель", "gemini:change_model")),
                List.of(button("🧹 Очистить историю", "gemini:clear_history")),
                List.of(button("⬅️ Назад к моделям", "menu:models"))
        ));
    }

    private InlineKeyboardMarkup geminiChangeModelKeyboard(Database.User user) {
        String current = normalizeModel(user.currentModel);
        return new InlineKeyboardMarkup(List.of(
                List.of(button(modelSelectLabel("⚡ Gemini 3 Flash", MODEL_GEMINI_3_FLASH, current), "gemini:model:flash")),
                List.of(button(modelSelectLabel("🌟 Gemini 3 Pro", MODEL_GEMINI_3_PRO, current), "gemini:model:pro")),
                List.of(button("⬅️ Назад", "settings:back_to_model"))
        ));
    }

    private InlineKeyboardMarkup settingsMenuKeyboard(Database.User user) {
        if (isNanoModel(normalizeModel(user.currentModel))) {
            boolean isPro = MODEL_NANO_BANANA_PRO.equals(normalizeModel(user.currentModel));
            return new InlineKeyboardMarkup(List.of(
                    List.of(button("📐 Изменить формат", "settings:format_menu")),
                    List.of(button("📏 Разрешение", "settings:resolution_menu")),
                    List.of(button((isPro ? "✅ " : "❌ ") + "Pro режим", "settings:nano_pro_toggle")),
                    List.of(button("⬅️ Назад", "settings:back_to_model"))
            ));
        }
        if (isFluxModel(normalizeModel(user.currentModel))) {
            boolean flex = isFluxFlexModel(normalizeModel(user.currentModel));
            return new InlineKeyboardMarkup(List.of(
                    List.of(button("📐 Изменить формат", "settings:format_menu")),
                    List.of(button("📏 Разрешение", "settings:resolution_menu")),
                    List.of(button((flex ? "✅ " : "❌ ") + "Ультрареалистичность (FLEX)", "settings:flux_flex_toggle")),
                    List.of(button("⬅️ Назад", "settings:back_to_model"))
            ));
        }
        if (isIdeogramModel(normalizeModel(user.currentModel))) {
            boolean isEdit = isIdeogramEdit(normalizeModel(user.currentModel));
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            rows.add(List.of(button("🧩 Выбор модели", "settings:ideogram_model_menu")));
            rows.add(List.of(button("⚡ Скорость", "settings:ideogram_speed_menu")));
            if (isEdit) {
                rows.add(List.of(button("📐 Формат (не используется в Edit)", "settings:ideogram_size_menu")));
            } else {
                rows.add(List.of(button("📐 Формат", "settings:ideogram_size_menu")));
            }
            rows.add(List.of(button((user.ideogramExpandPrompt ? "✅ " : "❌ ") + "Magic Prompt", "settings:ideogram_expand_toggle")));
            rows.add(List.of(button("⬅️ Назад", "settings:back_to_model")));
            return new InlineKeyboardMarkup(rows);
        }
        return new InlineKeyboardMarkup(List.of(
                List.of(button("📐 Изменить формат", "settings:format_menu")),
                List.of(button("📏 Разрешение", "settings:resolution_menu")),
                List.of(button("⬅️ Назад", "settings:back_to_model"))
        ));
    }

    private InlineKeyboardMarkup formatKeyboard(Database.User user) {
        String ratio = user.aspectRatio == null ? "auto" : user.aspectRatio;
        if (isFluxModel(normalizeModel(user.currentModel))) {
            return new InlineKeyboardMarkup(List.of(
                    List.of(button(ratioButtonLabel("📐 1:1", "1:1", ratio), "settings:ratio:1:1"),
                            button(ratioButtonLabel("📐 4:3", "4:3", ratio), "settings:ratio:4:3"),
                            button(ratioButtonLabel("📐 3:4", "3:4", ratio), "settings:ratio:3:4")),
                    List.of(button(ratioButtonLabel("📐 16:9", "16:9", ratio), "settings:ratio:16:9"),
                            button(ratioButtonLabel("📐 9:16", "9:16", ratio), "settings:ratio:9:16")),
                    List.of(button(ratioButtonLabel("📐 3:2", "3:2", ratio), "settings:ratio:3:2"),
                            button(ratioButtonLabel("📐 2:3", "2:3", ratio), "settings:ratio:2:3")),
                    List.of(button(ratioButtonLabel("📐 auto", "auto", ratio), "settings:ratio:auto")),
                    List.of(button("⬅️ Назад", "settings:back"))
            ));
        }
        return new InlineKeyboardMarkup(List.of(
                List.of(button(ratioButtonLabel("📐 1:1", "1:1", ratio), "settings:ratio:1:1"),
                        button(ratioButtonLabel("📐 2:3", "2:3", ratio), "settings:ratio:2:3"),
                        button(ratioButtonLabel("📐 3:2", "3:2", ratio), "settings:ratio:3:2")),
                List.of(button(ratioButtonLabel("📐 3:4", "3:4", ratio), "settings:ratio:3:4"),
                        button(ratioButtonLabel("📐 16:9", "16:9", ratio), "settings:ratio:16:9"),
                        button(ratioButtonLabel("📐 9:16", "9:16", ratio), "settings:ratio:9:16")),
                List.of(button(ratioButtonLabel("📐 auto", "auto", ratio), "settings:ratio:auto")),
                List.of(button("⬅️ Назад", "settings:back"))
        ));
    }

    private InlineKeyboardMarkup resolutionKeyboard(Database.User user) {
        String resolution = user.resolution == null ? "2k" : user.resolution;
        if (isFluxModel(normalizeModel(user.currentModel))) {
            return new InlineKeyboardMarkup(List.of(
                    List.of(button(resButtonLabel("📏 1K", "1k", resolution), "settings:res:1k"),
                            button(resButtonLabel("📏 2K", "2k", resolution), "settings:res:2k")),
                    List.of(button("⬅️ Назад", "settings:back"))
            ));
        }
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

    private InlineKeyboardMarkup insufficientTokensKeyboard() {
        return new InlineKeyboardMarkup(List.of(
                List.of(button("💳 Купить токены", "menu:buy")),
                List.of(button("🔗 Пригласить друга", "menu:invite")),
                List.of(button("🏠 Вернуться в меню", "menu:start"))
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
        if (isFluxModel(normalized)) {
            boolean flex = isFluxFlexModel(normalized);
            return "🌀 Flux 2 · быстрые и чистые кадры\n\n" +
                    "– Текст → изображение, изображение → изображение: опишите желаемую сцену.\n" +
                    "– Референсы: можно добавить от 1 до 8 изображений, чтобы задать стиль или пересобрать сцену.\n" +
                    "– Ультрареалистичность (FLEX): больше деталей и реализма, но выше стоимость.\n\n" +
                    "⚙️ Настройки\n" +
                    "Ультрареалистичность (FLEX): " + (flex ? "включена" : "выключена") + "\n" +
                    "Разрешение: " + fluxResolutionLabel(user.resolution) + "\n" +
                    "Формат кадра: " + aspectRatioLabel(user.aspectRatio) + "\n\n" +
                    "🔹 Баланса хватит на " + queries + " запросов.\n" +
                    "1 генерация = " + formatNumber(cost) + " токенов.";
        }
        if (isIdeogramModel(normalized)) {
            boolean isEdit = isIdeogramEdit(normalized);
            return "🧩 Ideogram V3 · сильная типографика и точная работа с текстом\n\n" +
                    "🧩 Character — создаёт персонажей и сохраняет консистентность (нужно 1–3 фото).\n" +
                    "🎨 Remix — стилизация и вариации по исходному изображению (1 фото).\n" +
                    "✏️ Edit — точечные правки с маской (2 фото: оригинал + маска).\n" +
                    "Маска: только ч/б (RGB/RGBA/Grayscale), где белым отмечена зона изменений.\n\n" +
                    "⚙️ Настройки\n" +
                    "Модель: " + ideogramModelLabel(normalized) + "\n" +
                    "Скорость: " + ideogramSpeedLabel(user.ideogramSpeed) + "\n" +
                    "Формат: " + (isEdit ? "—" : ideogramSizeLabel(user.ideogramImageSize)) + "\n" +
                    "Magic Prompt: " + (user.ideogramExpandPrompt ? "включен" : "выключен") + " (авторасширение запроса)\n\n" +
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

    private String geminiDialogText(Database.User user) {
        String model = normalizeModel(user.currentModel);
        String name = isGeminiPro(model) ? "🌟 Gemini 3 Pro" : "⚡ Gemini 3 Flash";
        String historyLine = user.geminiHistoryEnabled ? "сохраняется (📈)" : "не сохраняется (🚫)";
        return "💬 Диалог начался\n\n" +
                "Для ввода используй:\n" +
                "└ 📝 текст;\n" +
                "└ 🎤 голосовое сообщение;\n" +
                "└ 📸 фотографии (до 10 шт.);\n" +
                "└ 📎 файл: любой текстовый формат (txt, .py и т.п).\n\n" +
                "Название: " + name + "\n" +
                "История: " + historyLine + "\n\n" +
                "/end — завершит этот диалог\n" +
                "/clear — очистит историю в этом диалоге";
    }

    private String geminiChangeModelText(Database.User user) {
        String model = normalizeModel(user.currentModel);
        String name = isGeminiPro(model) ? "🌟 Gemini 3 Pro" : "⚡ Gemini 3 Flash";
        return "Выберите модель Gemini 3:\n\n" +
                "Текущая: " + name;
    }

    private String settingsMenuText(Database.User user) {
        if (isFluxModel(normalizeModel(user.currentModel))) {
            boolean flex = isFluxFlexModel(normalizeModel(user.currentModel));
            long cost1k = costForFluxResolution(user, "1k");
            long cost2k = costForFluxResolution(user, "2k");
            return "⚙️ Настройки\n" +
                    "Ультрареалистичность (FLEX): " + (flex ? "включена" : "выключена") + "\n" +
                    "Разрешение: " + fluxResolutionLabel(user.resolution) + "\n" +
                    "Формат кадра: " + aspectRatioLabel(user.aspectRatio) + "\n\n" +
                    "Стоимость генерации:\n" +
                    "1K = " + formatNumber(cost1k) + " токенов\n" +
                    "2K = " + formatNumber(cost2k) + " токенов";
        }
        if (isIdeogramModel(normalizeModel(user.currentModel))) {
            boolean isEdit = isIdeogramEdit(normalizeModel(user.currentModel));
            long cost = costForUser(user);
            return "⚙️ Настройки\n" +
                    "Модель: " + ideogramModelLabel(normalizeModel(user.currentModel)) + "\n" +
                    "Скорость: " + ideogramSpeedLabel(user.ideogramSpeed) + "\n" +
                    "Формат: " + (isEdit ? "—" : ideogramSizeLabel(user.ideogramImageSize)) + "\n" +
                    "Magic Prompt: " + (user.ideogramExpandPrompt ? "включен" : "выключен") + " (авторасширение запроса)\n\n" +
                    "Подсказка:\n" +
                    "Character — нужно 1–3 фото.\n" +
                    "Remix — нужно 1 фото.\n" +
                    "Edit — нужно 2 фото (оригинал + маска).\n" +
                    "Маска: только ч/б (RGB/RGBA/Grayscale), белым зона изменений.\n\n" +
                    "Стоимость генерации:\n" +
                    ideogramSpeedLabel(user.ideogramSpeed) + " = " + formatNumber(cost) + " токенов";
        }
        if (isNanoModel(normalizeModel(user.currentModel))) {
            boolean isPro = MODEL_NANO_BANANA_PRO.equals(normalizeModel(user.currentModel));
            long costDefault = costForUserResolution(user, "2k");
            long cost4k = costForUserResolution(user, "4k");
            return "⚙️ Настройки\n" +
                    "Pro режим: " + (isPro ? "включен" : "выключен") + "\n" +
                    "Формат файла: автоматический\n" +
                    "Разрешение: " + resolutionLabel(user.resolution) + "\n" +
                    "Формат кадра: " + aspectRatioLabel(user.aspectRatio) + "\n\n" +
                    "Стоимость генерации:\n" +
                    "1K = " + formatNumber(costDefault) + " токенов\n" +
                    "2K = " + formatNumber(costDefault) + " токенов\n" +
                    "4K = " + formatNumber(cost4k) + " токенов";
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
        if (isFluxModel(normalizeModel(user.currentModel))) {
            return "🖼️ Формат изображения\n" +
                    "Формат кадра: " + aspectRatioLabel(user.aspectRatio) + "\n\n" +
                    "📐 Доступные форматы в Flux 2:\n" +
                    "1:1: квадратный кадр\n\n" +
                    "4:3: классический формат\n\n" +
                    "3:4: вертикальный классический\n\n" +
                    "16:9: широкий кадр\n\n" +
                    "9:16: вертикальный видео-формат\n\n" +
                    "3:2: фотоформат\n\n" +
                    "2:3: вертикальный фотоформат\n\n" +
                    "auto: автоматически подберет формат (если есть референс)";
        }
        return "🖼️ Формат изображения\n" +
                "Формат файла: автоматический\n" +
                "Формат кадра: " + aspectRatioLabel(user.aspectRatio) + "\n\n" +
                "📐 Выберите формат создаваемого фото в Nano Banana\n" +
                "1:1: идеально подходит для профильных фото в соцсетях, таких как VK, Telegram и т.д\n\n" +
                "2:3: хорошо подходит для печатных фотографий, но также может использоваться для пинов на Pinterest\n\n" +
                "3:2: широко используемый формат для фотографий, подходит для постов в Telegram, VK, и др.\n\n" +
                "3:4: широко используемый формат для фотографий, карточек товаров и т.д.\n\n" +
                "16:9: стандартный формат для видео, идеален для YouTube, VK и др.\n\n" +
                "9:16: оптимальный формат для Stories в Telegram или вертикальных видео на YouTube\n\n" +
                "auto: автоматически подберет нужный формат";
    }

    private String resolutionMenuText(Database.User user) {
        if (isFluxModel(normalizeModel(user.currentModel))) {
            long cost1k = costForFluxResolution(user, "1k");
            long cost2k = costForFluxResolution(user, "2k");
            return "📏 Разрешение\n" +
                    "Текущее: " + fluxResolutionLabel(user.resolution) + "\n\n" +
                    "Стоимость генерации:\n" +
                    "1K = " + formatNumber(cost1k) + " токенов\n" +
                    "2K = " + formatNumber(cost2k) + " токенов";
        }
        long costDefault = costForUserResolution(user, "2k");
        long cost4k = costForUserResolution(user, "4k");
        return "📏 Разрешение\n" +
                "Текущее: " + resolutionLabel(user.resolution) + "\n\n" +
                "Стоимость генерации:\n" +
                "1K = " + formatNumber(costDefault) + " токенов\n" +
                "2K = " + formatNumber(costDefault) + " токенов\n" +
                "4K = " + formatNumber(cost4k) + " токенов";
    }

    private String ideogramModelMenuText(Database.User user) {
        String current = ideogramModelLabel(normalizeModel(user.currentModel));
        return "🧩 Выбор модели Ideogram V3\n\n" +
                "Character — персонажи, консистентность, референсы 1–3 фото.\n" +
                "Remix — стилизация и вариации по исходному изображению.\n" +
                "Edit — точечные правки по маске (2 фото: оригинал + маска).\n\n" +
                "Текущая модель: " + current;
    }

    private InlineKeyboardMarkup ideogramModelKeyboard(Database.User user) {
        String current = normalizeModel(user.currentModel);
        return new InlineKeyboardMarkup(List.of(
                List.of(button(modelSelectLabel("🧩 Character", MODEL_IDEOGRAM_CHARACTER, current), "settings:ideogram_model:character")),
                List.of(button(modelSelectLabel("🎨 Remix", MODEL_IDEOGRAM_V3_REMIX, current), "settings:ideogram_model:remix")),
                List.of(button(modelSelectLabel("✏️ Edit", MODEL_IDEOGRAM_V3_EDIT, current), "settings:ideogram_model:edit")),
                List.of(button("⬅️ Назад", "settings:back"))
        ));
    }

    private String ideogramSpeedMenuText(Database.User user) {
        String speed = ideogramSpeedLabel(user.ideogramSpeed);
        long turbo = costForIdeogram(user, "turbo");
        long balanced = costForIdeogram(user, "balanced");
        long quality = costForIdeogram(user, "quality");
        return "⚡ Скорость генерации\n" +
                "Текущая: " + speed + "\n\n" +
                "Стоимость:\n" +
                "Turbo = " + formatNumber(turbo) + " токенов\n" +
                "Balanced = " + formatNumber(balanced) + " токенов\n" +
                "Quality = " + formatNumber(quality) + " токенов";
    }

    private InlineKeyboardMarkup ideogramSpeedKeyboard(Database.User user) {
        String current = ideogramSpeedKey(user.ideogramSpeed);
        return new InlineKeyboardMarkup(List.of(
                List.of(button(optionLabel("⚡ Turbo", "turbo", current), "settings:ideogram_speed:turbo")),
                List.of(button(optionLabel("⚡ Balanced", "balanced", current), "settings:ideogram_speed:balanced")),
                List.of(button(optionLabel("⚡ Quality", "quality", current), "settings:ideogram_speed:quality")),
                List.of(button("⬅️ Назад", "settings:back"))
        ));
    }

    private String ideogramStyleMenuText(Database.User user) {
        if (isIdeogramEdit(normalizeModel(user.currentModel))) {
            return "🎨 Стиль\n\n" +
                    "Для режима Edit стиль не используется.\n" +
                    "Переключитесь на Character или Remix.";
        }
        return "🎨 Стиль\n" +
                "Стиль в Ideogram V3 фиксирован: Auto.\n" +
                "Дополнительные варианты временно недоступны.";
    }

    private InlineKeyboardMarkup ideogramStyleKeyboard(Database.User user) {
        String current = ideogramStyleKey(user.ideogramStyle);
        return new InlineKeyboardMarkup(List.of(
                List.of(button(optionLabel("🎨 Auto", "auto", current), "settings:ideogram_style:auto")),
                List.of(button("⬅️ Назад", "settings:back"))
        ));
    }

    private String ideogramSizeMenuText(Database.User user) {
        if (isIdeogramEdit(normalizeModel(user.currentModel))) {
            return "📐 Формат\n\n" +
                    "Для режима Edit формат не используется.\n" +
                    "Переключитесь на Character или Remix.";
        }
        return "📐 Формат\n" +
                "Текущий: " + ideogramSizeLabel(user.ideogramImageSize) + "\n\n" +
                "Выберите размер кадра для Ideogram V3.";
    }

    private InlineKeyboardMarkup ideogramSizeKeyboard(Database.User user) {
        String current = ideogramSizeKey(user.ideogramImageSize);
        return new InlineKeyboardMarkup(List.of(
                List.of(button(optionLabel("📐 Квадрат 1:1", "square", current), "settings:ideogram_size:square")),
                List.of(button(optionLabel("📐 Квадрат HD", "square_hd", current), "settings:ideogram_size:square_hd")),
                List.of(button(optionLabel("📐 Портрет 3:4", "portrait_4_3", current), "settings:ideogram_size:portrait_4_3")),
                List.of(button(optionLabel("📐 Портрет 9:16", "portrait_16_9", current), "settings:ideogram_size:portrait_16_9")),
                List.of(button(optionLabel("📐 Ландшафт 4:3", "landscape_4_3", current), "settings:ideogram_size:landscape_4_3")),
                List.of(button(optionLabel("📐 Ландшафт 16:9", "landscape_16_9", current), "settings:ideogram_size:landscape_16_9")),
                List.of(button("⬅️ Назад", "settings:back"))
        ));
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
                "Вы получаете 5% токенами от каждой покупки приглашенного пользователя.\n\n" +
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
        if (trySendPhotoByUrl(chatId, url)) {
            return;
        }
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
                    photo.setCaption("Вот <a href=\"" + url + "\">прямая ссылка</a> на качественную версию.");
                    photo.setParseMode("HTML");
                    executeWithRetry(photo);
                } else {
                    SendMessage msg = new SendMessage(String.valueOf(chatId),
                            "❗️Не удалось сжать фото. Вот <a href=\"" + url + "\">прямая ссылка</a> на качественную версию.");
                    msg.setParseMode("HTML");
                    executeWithRetry(msg);
                }
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

    private boolean trySendPhotoByUrl(long chatId, String url) {
        try {
            SendPhoto photo = new SendPhoto();
            photo.setChatId(String.valueOf(chatId));
            photo.setPhoto(new InputFile(url));
            executeWithRetry(photo);
            return true;
        } catch (Exception e) {
            return false;
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

    private boolean isTextDocument(Document doc) {
        String mime = doc.getMimeType();
        if (mime != null && mime.startsWith("text")) {
            return true;
        }
        String name = doc.getFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt")
                || lower.endsWith(".md")
                || lower.endsWith(".csv")
                || lower.endsWith(".json")
                || lower.endsWith(".xml")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".py")
                || lower.endsWith(".js")
                || lower.endsWith(".ts")
                || lower.endsWith(".java")
                || lower.endsWith(".kt")
                || lower.endsWith(".go")
                || lower.endsWith(".rs")
                || lower.endsWith(".c")
                || lower.endsWith(".cpp")
                || lower.endsWith(".h");
    }

    private String loadTextFromUrl(String url, int maxChars) {
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                byte[] bytes = response.body().bytes();
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (text.length() > maxChars) {
                    return text.substring(0, maxChars);
                }
                return text;
            }
        } catch (Exception e) {
            return null;
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
        if (isFluxModel(normalized)) {
            return costForFluxResolution(user, user.resolution);
        }
        if (isIdeogramModel(normalized)) {
            return costForIdeogram(user, null);
        }
        if (isGeminiModel(normalized)) {
            int historyCount = user.geminiHistoryEnabled ? countGeminiHistoryPrompts(user.tgId) : 0;
            return costForGemini(user, historyCount);
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
        if (MODEL_FLUX_2_FLEX_TEXT.equalsIgnoreCase(model) || MODEL_FLUX_2_FLEX_IMAGE.equalsIgnoreCase(model)) {
            return "Flux 2 (FLEX)";
        }
        if (isFluxModel(model)) {
            return "Flux 2";
        }
        if (MODEL_IDEOGRAM_CHARACTER.equalsIgnoreCase(model)) {
            return "Ideogram V3 (Character)";
        }
        if (MODEL_IDEOGRAM_V3_REMIX.equalsIgnoreCase(model)) {
            return "Ideogram V3 (Remix)";
        }
        if (MODEL_IDEOGRAM_V3_EDIT.equalsIgnoreCase(model)) {
            return "Ideogram V3 (Edit)";
        }
        if (MODEL_GEMINI_3_FLASH.equalsIgnoreCase(model)) {
            return "Gemini 3 Flash";
        }
        if (MODEL_GEMINI_3_PRO.equalsIgnoreCase(model)) {
            return "Gemini 3 Pro";
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
        if (MODEL_FLUX_2_TEXT.equalsIgnoreCase(model)) {
            return MODEL_FLUX_2_TEXT;
        }
        if (MODEL_FLUX_2_IMAGE.equalsIgnoreCase(model)) {
            return MODEL_FLUX_2_IMAGE;
        }
        if (MODEL_FLUX_2_FLEX_TEXT.equalsIgnoreCase(model)) {
            return MODEL_FLUX_2_FLEX_TEXT;
        }
        if (MODEL_FLUX_2_FLEX_IMAGE.equalsIgnoreCase(model)) {
            return MODEL_FLUX_2_FLEX_IMAGE;
        }
        if ("flux-2".equalsIgnoreCase(model) || "flux2".equalsIgnoreCase(model)) {
            return MODEL_FLUX_2_TEXT;
        }
        if (MODEL_IDEOGRAM_CHARACTER.equalsIgnoreCase(model)) {
            return MODEL_IDEOGRAM_CHARACTER;
        }
        if (MODEL_IDEOGRAM_V3_REMIX.equalsIgnoreCase(model)) {
            return MODEL_IDEOGRAM_V3_REMIX;
        }
        if (MODEL_IDEOGRAM_V3_EDIT.equalsIgnoreCase(model)) {
            return MODEL_IDEOGRAM_V3_EDIT;
        }
        if ("ideogram".equalsIgnoreCase(model) || "ideogram-v3".equalsIgnoreCase(model) || "ideogram/v3".equalsIgnoreCase(model)) {
            return MODEL_IDEOGRAM_CHARACTER;
        }
        if (MODEL_GEMINI_3_FLASH.equalsIgnoreCase(model)) {
            return MODEL_GEMINI_3_FLASH;
        }
        if (MODEL_GEMINI_3_PRO.equalsIgnoreCase(model)) {
            return MODEL_GEMINI_3_PRO;
        }
        if ("gemini-3-flash".equalsIgnoreCase(model) || "gemini-3-flash-preview".equalsIgnoreCase(model)) {
            return MODEL_GEMINI_3_FLASH;
        }
        if ("gemini-3-pro".equalsIgnoreCase(model) || "gemini-3-pro-preview".equalsIgnoreCase(model)) {
            return MODEL_GEMINI_3_PRO;
        }
        if ("google/gemini-3-flash".equalsIgnoreCase(model)) {
            return MODEL_GEMINI_3_FLASH;
        }
        if ("google/gemini-3-pro-preview".equalsIgnoreCase(model) || "google/gemini-3-pro".equalsIgnoreCase(model)) {
            return MODEL_GEMINI_3_PRO;
        }
        if ("gemini".equalsIgnoreCase(model) || "gemini-3".equalsIgnoreCase(model)) {
            return MODEL_GEMINI_3_PRO;
        }
        return model;
    }

    private boolean isNanoModel(String model) {
        return MODEL_NANO_BANANA.equals(model)
                || MODEL_NANO_BANANA_EDIT.equals(model)
                || MODEL_NANO_BANANA_PRO.equals(model);
    }

    private boolean isFluxModel(String model) {
        return MODEL_FLUX_2_TEXT.equalsIgnoreCase(model)
                || MODEL_FLUX_2_IMAGE.equalsIgnoreCase(model)
                || MODEL_FLUX_2_FLEX_TEXT.equalsIgnoreCase(model)
                || MODEL_FLUX_2_FLEX_IMAGE.equalsIgnoreCase(model);
    }

    private boolean isIdeogramModel(String model) {
        return MODEL_IDEOGRAM_CHARACTER.equalsIgnoreCase(model)
                || MODEL_IDEOGRAM_V3_REMIX.equalsIgnoreCase(model)
                || MODEL_IDEOGRAM_V3_EDIT.equalsIgnoreCase(model);
    }

    private boolean isGeminiModel(String model) {
        return MODEL_GEMINI_3_FLASH.equalsIgnoreCase(model)
                || MODEL_GEMINI_3_PRO.equalsIgnoreCase(model);
    }

    private boolean isGeminiPro(String model) {
        return MODEL_GEMINI_3_PRO.equalsIgnoreCase(model);
    }

    private boolean isGeminiFlash(String model) {
        return MODEL_GEMINI_3_FLASH.equalsIgnoreCase(model);
    }

    private boolean isIdeogramCharacter(String model) {
        return MODEL_IDEOGRAM_CHARACTER.equalsIgnoreCase(model);
    }

    private boolean isIdeogramRemix(String model) {
        return MODEL_IDEOGRAM_V3_REMIX.equalsIgnoreCase(model);
    }

    private boolean isIdeogramEdit(String model) {
        return MODEL_IDEOGRAM_V3_EDIT.equalsIgnoreCase(model);
    }

    private boolean isFluxFlexModel(String model) {
        return MODEL_FLUX_2_FLEX_TEXT.equalsIgnoreCase(model)
                || MODEL_FLUX_2_FLEX_IMAGE.equalsIgnoreCase(model);
    }

    private String prepareFluxPrompt(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }

    private String prepareIdeogramPrompt(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }

    private List<KieClient.ChatMessage> buildGeminiMessages(List<Database.GeminiMessage> history,
                                                            String prompt,
                                                            List<String> imageUrls) {
        List<KieClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new KieClient.ChatMessage("system",
                "Ты — текстовая модель. Отвечай только текстом. " +
                        "Не предлагай генерацию файлов, изображений, аудио, видео и не выводи JSON-команды. " +
                        "Не используй символы '*' и '#', не применяй Markdown-разметку."));
        if (history != null && !history.isEmpty()) {
            List<Database.GeminiMessage> ordered = new ArrayList<>(history);
            Collections.reverse(ordered);
            for (Database.GeminiMessage msg : ordered) {
                String role = "assistant";
                if ("user".equalsIgnoreCase(msg.role)) {
                    role = "user";
                }
                messages.add(new KieClient.ChatMessage(role, msg.content));
            }
        }
        String content = prompt == null ? "" : prompt.trim();
        if (imageUrls != null && !imageUrls.isEmpty()) {
            if (!content.isBlank()) {
                content += "\n\n";
            }
            content += "Изображения:\n";
            for (String url : imageUrls) {
                content += url + "\n";
            }
        }
        if (content.isBlank()) {
            content = " ";
        }
        messages.add(new KieClient.ChatMessage("user", content));
        return messages;
    }

    private String fluxResolutionLabel(String res) {
        String normalized = res == null ? "" : res.trim().toLowerCase(Locale.ROOT);
        if ("1k".equals(normalized)) {
            return "1K";
        }
        return "2K";
    }

    private String fluxResolutionValue(String res) {
        String normalized = res == null ? "" : res.trim().toLowerCase(Locale.ROOT);
        if ("1k".equals(normalized)) {
            return "1K";
        }
        return "2K";
    }

    private long costForFluxResolution(Database.User user, String res) {
        String normalized = res == null ? "" : res.trim().toLowerCase(Locale.ROOT);
        boolean flex = isFluxFlexModel(normalizeModel(user.currentModel));
        if ("1k".equals(normalized)) {
            return flex ? 30_000 : 12_000;
        }
        return flex ? 40_000 : 15_000;
    }

    private long costForIdeogram(Database.User user, String speedOverride) {
        String model = normalizeModel(user.currentModel);
        String speed = speedOverride == null ? ideogramSpeedKey(user.ideogramSpeed) : ideogramSpeedKey(speedOverride);
        boolean character = isIdeogramCharacter(model);
        return switch (speed) {
            case "turbo" -> character ? 25_000 : 10_000;
            case "quality" -> character ? 40_000 : 20_000;
            default -> character ? 35_000 : 15_000;
        };
    }

    private long costForGemini(Database.User user, int historyPrompts) {
        String model = normalizeModel(user.currentModel);
        long base = isGeminiPro(model) ? 3_000 : 1_500;
        long step = isGeminiPro(model) ? 600 : 300;
        if (historyPrompts <= 0 || !user.geminiHistoryEnabled) {
            return base;
        }
        return base + step * historyPrompts;
    }

    private int countGeminiHistoryPrompts(long userId) {
        return db.countGeminiUserMessages(userId);
    }

    private String ideogramSpeedKey(String speed) {
        if (speed == null) {
            return "balanced";
        }
        String normalized = speed.trim().toLowerCase(Locale.ROOT);
        if ("turbo".equals(normalized)) {
            return "turbo";
        }
        if ("quality".equals(normalized)) {
            return "quality";
        }
        return "balanced";
    }

    private String ideogramSpeedValue(String speed) {
        return ideogramSpeedKey(speed).toUpperCase(Locale.ROOT);
    }

    private String ideogramSpeedLabel(String speed) {
        return switch (ideogramSpeedKey(speed)) {
            case "turbo" -> "Turbo";
            case "quality" -> "Quality";
            default -> "Balanced";
        };
    }

    private String ideogramStyleKey(String style) {
        return "auto";
    }

    private String ideogramStyleValue(String style) {
        return ideogramStyleKey(style).toUpperCase(Locale.ROOT);
    }

    private String ideogramStyleLabel(String style) {
        return "Auto";
    }

    private String ideogramSizeKey(String size) {
        if (size == null) {
            return "square_hd";
        }
        String normalized = size.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "square", "square_hd", "portrait_4_3", "portrait_16_9", "landscape_4_3", "landscape_16_9" -> normalized;
            default -> "square_hd";
        };
    }

    private String ideogramSizeValue(String size) {
        return ideogramSizeKey(size);
    }

    private String ideogramSizeLabel(String size) {
        return switch (ideogramSizeKey(size)) {
            case "square" -> "Квадрат 1:1";
            case "square_hd" -> "Квадрат HD";
            case "portrait_4_3" -> "Портрет 3:4";
            case "portrait_16_9" -> "Портрет 9:16";
            case "landscape_4_3" -> "Ландшафт 4:3";
            case "landscape_16_9" -> "Ландшафт 16:9";
            default -> "Квадрат HD";
        };
    }

    private String ideogramModelLabel(String model) {
        if (isIdeogramEdit(model)) {
            return "Edit";
        }
        if (isIdeogramRemix(model)) {
            return "Remix";
        }
        return "Character";
    }

    private int ideogramRequiredImages(String model) {
        if (isIdeogramEdit(model)) {
            return 2;
        }
        if (isIdeogramRemix(model)) {
            return 1;
        }
        return 1;
    }

    private int ideogramMaxImages(String model) {
        if (isIdeogramEdit(model)) {
            return 2;
        }
        if (isIdeogramRemix(model)) {
            return 1;
        }
        return 3;
    }

    private String normalizeFluxAspectRatio(String ratio, boolean hasImages) {
        if (ratio == null || ratio.isBlank()) {
            return hasImages ? "auto" : "1:1";
        }
        String normalized = ratio.toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized)) {
            return hasImages ? "auto" : "1:1";
        }
        Set<String> allowed = Set.of("1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3");
        if (!allowed.contains(normalized)) {
            return "1:1";
        }
        return normalized;
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

    private void sendLongMessage(long chatId, String text) throws TelegramApiException {
        if (text == null) {
            return;
        }
        String remaining = text;
        int max = 3900;
        while (remaining.length() > max) {
            int cut = remaining.lastIndexOf('\n', max);
            if (cut < max * 0.5) {
                cut = max;
            }
            String part = remaining.substring(0, cut).trim();
            if (!part.isBlank()) {
                executeWithRetry(new SendMessage(String.valueOf(chatId), part));
            }
            remaining = remaining.substring(Math.min(cut, remaining.length())).trim();
        }
        if (!remaining.isBlank()) {
            executeWithRetry(new SendMessage(String.valueOf(chatId), remaining));
        }
    }

    private String optionLabel(String label, String value, String current) {
        if (value.equalsIgnoreCase(current)) {
            return "✅ " + label;
        }
        return label;
    }

    private String modelSelectLabel(String label, String value, String current) {
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

    private int maxPendingImages(Database.User user) {
        if (user != null && isFluxModel(normalizeModel(user.currentModel))) {
            return 8;
        }
        if (user != null && isIdeogramModel(normalizeModel(user.currentModel))) {
            return ideogramMaxImages(normalizeModel(user.currentModel));
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

    private Message executeWithRetryMessage(SendMessage msg) throws TelegramApiException {
        final Message[] result = {null};
        executeWithRetryInternal(() -> result[0] = execute(msg), 3);
        return result[0];
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

    private void deleteMessageQuietly(long chatId, Integer messageId) {
        if (messageId == null) {
            return;
        }
        try {
            DeleteMessage del = new DeleteMessage(String.valueOf(chatId), messageId);
            execute(del);
        } catch (Exception ignored) {
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
