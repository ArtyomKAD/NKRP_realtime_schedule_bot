package ru.artyomkad.nkrp.bot;

import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.exceptions.ApiException;
import com.vk.api.sdk.exceptions.ClientException;
import com.vk.api.sdk.httpclient.HttpTransportClient;
import com.vk.api.sdk.objects.docs.Doc;
import com.vk.api.sdk.objects.docs.responses.SaveResponse;
import com.vk.api.sdk.objects.messages.*;
import com.vk.api.sdk.objects.messages.TemplateActionTypeNames;
import ru.artyomkad.nkrp.service.DatabaseService;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class VKCollegeBot extends Thread {

    private final VkApiClient vk;
    private final GroupActor actor;
    private final DatabaseService dbService;
    private final Random random = new Random();

    private static final long VK_CREATOR_ID = 863149626;

    private enum BotState { DEFAULT, WAITING_FOR_SUB_GROUP, WAITING_FOR_SUB_TEACHER, WAITING_SEARCH_GROUP, WAITING_SEARCH_TEACHER, WAITING_SEARCH_ROOM }
    private final java.util.Map<Long, BotState> userStates = new java.util.concurrent.ConcurrentHashMap<>();

    public VKCollegeBot(int groupId, String token, DatabaseService dbService) {
        this.vk = new VkApiClient(HttpTransportClient.getInstance());
        this.actor = new GroupActor(groupId, token);
        this.dbService = dbService;
    }

    @Override
    public void run() {
        System.out.println("VK Bot started!");
        try {
            var server = vk.groups().getLongPollServer(actor, actor.getGroupId()).execute();
            String key = server.getKey();
            String serverUrl = server.getServer();
            Integer ts = Integer.valueOf(server.getTs());

            while (!isInterrupted()) {
                try {
                    var response = vk.longPoll().getEvents(serverUrl, key, String.valueOf(ts)).waitTime(25).execute();
                    ts = Integer.valueOf(response.getTs());

                    for (var update : response.getUpdates()) {
                        String type = update.getAsJsonObject().get("type").getAsString();

                        if ("message_new".equals(type)) {
                            var msgObj = update.getAsJsonObject().get("object").getAsJsonObject();
                            var message = msgObj.has("message") ? msgObj.get("message").getAsJsonObject() : msgObj;

                            long peerId = message.get("peer_id").getAsLong();
                            String text = message.has("text") ? message.get("text").getAsString() : "";

                            handleMessage(peerId, text);
                        }
                    }
                } catch (Exception e) {
                    try {
                        var newServer = vk.groups().getLongPollServer(actor, actor.getGroupId()).execute();
                        key = newServer.getKey();
                        serverUrl = newServer.getServer();
                        ts = Integer.valueOf(newServer.getTs());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        Thread.sleep(1000);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(long peerId, String text) {
        BotState state = userStates.getOrDefault(peerId, BotState.DEFAULT);
        String rawText = text;
        text = text.trim();
        String lowerText = text.toLowerCase();

        try {
            if ((lowerText.startsWith("/broadcast") || lowerText.startsWith("/b"))) {
                if (peerId == VK_CREATOR_ID) {
                    String[] parts = rawText.split("\\s+", 2);
                    if (parts.length < 2) {
                        sendMessage(peerId, "Введите текст рассылки. Пример: /b Всем привет!");
                    } else {
                        performBroadcast(peerId, parts[1]);
                    }
                }
                return;
            }

            if (text.equalsIgnoreCase("назад") || text.equalsIgnoreCase("начало") || text.equals("🔙 В главное меню")) {
                userStates.put(peerId, BotState.DEFAULT);
                sendMenu(peerId, "Главное меню", getMainMenu());
                return;
            }
            if (text.equalsIgnoreCase("start") || text.equalsIgnoreCase("/start")) {
                userStates.put(peerId, BotState.DEFAULT);
                sendMenu(peerId, "Привет! Выберите действие:", getMainMenu());
                return;
            }

            if (state == BotState.DEFAULT) {
                switch (text) {
                    case "📅 Моё расписание":
                        String[] sub = dbService.getUserSubscription(peerId, null, "VK");
                        if (sub == null) sendMessage(peerId, "Нет активной подписки.");
                        else {
                            String res = (Integer.parseInt(sub[0]) == 0)
                                    ? dbService.getScheduleByGroup(sub[1])
                                    : dbService.getScheduleByTeacher(sub[1]);
                            sendMessage(peerId, res);
                        }
                        return;
                    case "🔔 Подписка":
                        sendMenu(peerId, "На что подписываемся?", getSubMenu());
                        return;
                    case "🔍 Поиск":
                        sendMenu(peerId, "Что ищем?", getSearchMenu());
                        return;
                    case "🍽️ Столовая":
                        sendCanteenMenu(peerId);
                        return;
                }

                switch (text) {
                    case "🎓 Подписаться на группу" -> {
                        userStates.put(peerId, BotState.WAITING_FOR_SUB_GROUP);
                        sendMenu(peerId, "✍️ Введите название группы (например, 1-ИП-2):", getBackKeyboard());
                        return;
                    }
                    case "👨‍🏫 Подписаться на преподавателя" -> {
                        userStates.put(peerId, BotState.WAITING_FOR_SUB_TEACHER);
                        sendMenu(peerId, "✍️ Введите фамилию преподавателя:", getBackKeyboard());
                        return;
                    }
                    case "🎓 Поиск по группе" -> {
                        userStates.put(peerId, BotState.WAITING_SEARCH_GROUP);
                        sendMenu(peerId, "✍️ Введите название группы для поиска:", getBackKeyboard());
                        return;
                    }
                    case "👨‍🏫 Поиск по преподавателю" -> {
                        userStates.put(peerId, BotState.WAITING_SEARCH_TEACHER);
                        sendMenu(peerId, "✍️ Введите фамилию преподавателя:", getBackKeyboard());
                        return;
                    }
                    case "🚪 Поиск по кабинету" -> {
                        userStates.put(peerId, BotState.WAITING_SEARCH_ROOM);
                        sendMenu(peerId, "✍️ Введите номер кабинета (например, 205):", getBackKeyboard());
                        return;
                    }
                }
            }

            switch (state) {
                case WAITING_FOR_SUB_GROUP:
                    dbService.subscribeUser(peerId, null, 0, text, "VK");
                    sendMessage(peerId, "✅ Вы подписались на группу: " + text);
                    goBack(peerId);
                    break;
                case WAITING_FOR_SUB_TEACHER:
                    dbService.subscribeUser(peerId, null, 1, text, "VK");
                    sendMessage(peerId, "✅ Вы подписались на преподавателя: " + text);
                    goBack(peerId);
                    break;
                case WAITING_SEARCH_GROUP:
                    sendMessage(peerId, dbService.getScheduleByGroup(text));
                    break;
                case WAITING_SEARCH_TEACHER:
                    sendMessage(peerId, dbService.getScheduleByTeacher(text));
                    break;
                case WAITING_SEARCH_ROOM:
                    try {
                        int r = Integer.parseInt(text);
                        sendMessage(peerId, dbService.getScheduleByRoom(r));
                    } catch(Exception e) { sendMessage(peerId, "Пожалуйста, введите числовой номер кабинета."); }
                    break;
                default:
                    sendMessage(peerId, "Я вас не понимаю. Напишите 'Начало'.");
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(peerId, "Ошибка: " + e.getMessage());
        }
    }

    private void performBroadcast(long adminPeerId, String text) {
        sendMessage(adminPeerId, "⏳ Начинаю рассылку пользователям VK...");

        List<DatabaseService.Subscriber> subscribers = dbService.getAllSubscribersUnique();

        int count = 0;
        String msg = "⚠️ ОБЪЯВЛЕНИЕ:\n\n" + text;

        for (DatabaseService.Subscriber sub : subscribers) {
            if ("VK".equals(sub.platform())) {
                try {
                    sendMessage(sub.chatId(), msg);
                    count++;
                    Thread.sleep(50);
                } catch (Exception e) {
                    System.err.println("Failed to send broadcast to " + sub.chatId());
                }
            }
        }
        sendMessage(adminPeerId, "✅ Рассылка завершена. Отправлено VK пользователям: " + count);
    }

    private void sendCanteenMenu(long peerId) {
        String pdfUrl = "https://www.novkrp.ru/data/covid_pit.pdf";
        File tempFile = null;
        try {
            sendMessage(peerId, "⏳ Загружаю меню...");

            tempFile = File.createTempFile("menu", ".pdf");
            try (InputStream in = new URL(pdfUrl).openStream()) {
                Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            var uploadServer = vk.docs().getMessagesUploadServer(actor).peerId((int)peerId).execute();
            var uploadResponse = vk.upload().doc(String.valueOf(uploadServer.getUploadUrl()), tempFile).execute();

            SaveResponse saveResponse = vk.docs().save(actor, uploadResponse.getFile())
                    .title("Menu.pdf")
                    .execute();

            Doc doc = saveResponse.getDoc();
            String attachment = "doc" + doc.getOwnerId() + "_" + doc.getId();

            vk.messages().send(actor)
                    .peerId((int) peerId)
                    .message("\uD83C\uDF7D️ Меню столовой")
                    .attachment(attachment)
                    .randomId(random.nextInt())
                    .execute();

        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(peerId, "Не удалось отправить меню: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void goBack(long peerId) {
        userStates.put(peerId, BotState.DEFAULT);
        sendMenu(peerId, "Главное меню", getMainMenu());
    }

    public void sendMessage(long peerId, String text) {
        if (text == null || text.isEmpty()) return;
        String cleanText = text
                .replace("<b>", "").replace("</b>", "")
                .replace("<i>", "").replace("</i>", "")
                .replace("&nbsp;", " ");

        try {
            vk.messages().send(actor)
                    .message(cleanText)
                    .peerId((int) peerId)
                    .randomId(random.nextInt())
                    .execute();
        } catch (ApiException | ClientException e) {
            System.err.println("VK Send Error: " + e.getMessage());
        }
    }

    private void sendMenu(long peerId, String text, Keyboard keyboard) {
        try {
            vk.messages().send(actor)
                    .message(text)
                    .peerId((int) peerId)
                    .randomId(random.nextInt())
                    .keyboard(keyboard)
                    .execute();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private Keyboard getMainMenu() {
        Keyboard k = new Keyboard();
        List<List<KeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("📅 Моё расписание"), createBtn("🔍 Поиск")));
        rows.add(List.of(createBtn("🔔 Подписка"), createBtn("🍽️ Столовая")));
        k.setButtons(rows);
        return k;
    }

    private Keyboard getSubMenu() {
        Keyboard k = new Keyboard();
        List<List<KeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("🎓 Подписаться на группу")));
        rows.add(List.of(createBtn("👨‍🏫 Подписаться на преподавателя")));
        rows.add(List.of(createBtn("🔙 В главное меню", KeyboardButtonColor.NEGATIVE)));
        k.setButtons(rows);
        return k;
    }

    private Keyboard getSearchMenu() {
        Keyboard k = new Keyboard();
        List<List<KeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("🎓 Поиск по группе"), createBtn("👨‍🏫 Поиск по преподавателю")));
        rows.add(List.of(createBtn("🚪 Поиск по кабинету")));
        rows.add(List.of(createBtn("🔙 В главное меню", KeyboardButtonColor.NEGATIVE)));
        k.setButtons(rows);
        return k;
    }

    private Keyboard getBackKeyboard() {
        Keyboard k = new Keyboard();
        List<List<KeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("🔙 В главное меню", KeyboardButtonColor.NEGATIVE)));
        k.setButtons(rows);
        return k;
    }

    private KeyboardButton createBtn(String label) {
        return createBtn(label, KeyboardButtonColor.PRIMARY);
    }

    private KeyboardButton createBtn(String label, KeyboardButtonColor color) {
        return new KeyboardButton()
                .setAction(new KeyboardButtonAction()
                        .setType(TemplateActionTypeNames.TEXT)
                        .setLabel(label))
                .setColor(color);
    }
}