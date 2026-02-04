package ru.artyomkad.nkrp;

import io.github.cdimascio.dotenv.Dotenv;
import java.util.Timer;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.artyomkad.nkrp.bot.TelegramBot;
import ru.artyomkad.nkrp.bot.VKCollegeBot;
import ru.artyomkad.nkrp.service.BellParser;
import ru.artyomkad.nkrp.service.DatabaseService;
import ru.artyomkad.nkrp.service.ScheduleParser;
import ru.artyomkad.nkrp.service.ScheduleUpdater;

public class Main {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();
        String dbName = dotenv.get("DB_NAME");

        String url = dotenv.get("SCHEDULE_URL");
        String bellUrl = dotenv.get("BELL_URL");

        String tgBotToken = dotenv.get("TG_BOT_TOKEN");
        String tgBotName = dotenv.get("TG_BOT_NAME");

        int vkGroupId = Integer.parseInt(dotenv.get("VK_GROUP_ID"));
        String vkToken = dotenv.get("VK_TOKEN");

        try {
            DatabaseService dbService = new DatabaseService(dbName);
            ScheduleParser parser = new ScheduleParser(url);
            BellParser bellParser = new BellParser(bellUrl);

            System.out.println("Loading bells...");
            dbService.updateBells(bellParser.parse());

            TelegramBot tgBot = new TelegramBot(
                tgBotToken,
                tgBotName,
                dbService
            );
            TelegramBotsApi botsApi = new TelegramBotsApi(
                DefaultBotSession.class
            );
            botsApi.registerBot(tgBot);
            System.out.println("Telegram Bot started!");

            VKCollegeBot vkBot = new VKCollegeBot(
                vkGroupId,
                vkToken,
                dbService
            );

            vkBot.start();

            System.out.println("VK Bot started!");

            Timer timer = new Timer();
            timer.schedule(
                new ScheduleUpdater(
                    parser,
                    bellParser,
                    dbService,
                    tgBot,
                    vkBot
                ),
                0,
                180000
            );

            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    try {
                        System.out.println("Shutting down...");
                        vkBot.interrupt();
                        dbService.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
