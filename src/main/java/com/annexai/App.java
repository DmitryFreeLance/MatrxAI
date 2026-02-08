package com.annexai;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class App {
    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        Database db = new Database(config.dbPath);
        db.init();

        KieClient kieClient = new KieClient(config);
        AnnexAiBot bot = new AnnexAiBot(config, db, kieClient);

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);

        System.out.println("Bot started");
    }
}
