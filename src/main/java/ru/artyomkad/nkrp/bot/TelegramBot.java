package ru.artyomkad.nkrp.bot;

import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.artyomkad.nkrp.service.DatabaseService;
import ru.artyomkad.nkrp.service.DatabaseService.Platform;

import java.io.File;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ru.artyomkad.nkrp.bot.BotUtil.parseDateAndArg;
import static ru.artyomkad.nkrp.bot.BotUtil.ParsedArg;

public class TelegramBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final long creatorId;
    private final DatabaseService dbService;
    private final Map<Long, BotState> userStates = new ConcurrentHashMap<>();

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{1,2}\\s+(?:янв|фев|мар|апр|ма[йя]|июн|июл|авг|сен|окт|ноя|дек)[а-я]*(\\s+\\d{4})?)\\b"
    );

    private enum BotState {
        DEFAULT,
        WAITING_FOR_SUB_GROUP,
        WAITING_FOR_SUB_TEACHER,
        WAITING_SEARCH_GROUP,
        WAITING_SEARCH_TEACHER,
        WAITING_SEARCH_ROOM
    }

    public TelegramBot(DefaultBotOptions botOptions, String botToken, String botUsername, long creatorId, DatabaseService dbService) {
        super(botOptions, botToken);
        this.botUsername = botUsername;
        this.creatorId = creatorId;
        this.dbService = dbService;
    }

    @Override
    public String getBotUsername() { return botUsername; }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        Message message = update.getMessage();
        boolean isPrivate = message.getChat().isUserChat();

        if (isPrivate) {
            handlePrivateChat(message);
        } else {
            handleGroupChat(message);
        }
    }

    private void handlePrivateChat(Message message) {
        String text = message.getText();
        long chatId = message.getChatId();
        Integer threadId = message.getMessageThreadId();
        BotState state = userStates.getOrDefault(chatId, BotState.DEFAULT);
        long userId = message.getFrom().getId();

        User user = message.getFrom();
        String name = (user.getFirstName() + " " + (user.getLastName() == null ? "" : user.getLastName())).trim();
        dbService.logUser(userId, Platform.Telegram, user.getUserName(), name);

        try {
            if (userId == this.creatorId) {
                switch (text) {
                    case "/admin" -> {
                        sendMenu(chatId, threadId, "⚙️ Панель администратора:", getAdminMenu());
                        return;
                    }
                    case "📊 Статистика" -> {
                        sendMessage(chatId, threadId, dbService.getUsersStats());
                        return;
                    }
                    case "📜 Список пользователей" -> {
                        sendUsersFile(chatId, threadId);
                        return;
                    }
                    case "🔙 Выход" -> {
                        userStates.put(chatId, BotState.DEFAULT);
                        sendMenu(chatId, threadId, "Главное меню", getMainMenu());
                        return;
                    }
                }
            }
            if (text.equals("/start") || text.equals("🔙 В главное меню")) {
                userStates.put(chatId, BotState.DEFAULT);
                sendMenu(chatId, threadId, "Главное меню", getMainMenu());
                return;
            }

            if (state == BotState.DEFAULT) {
                switch (text) {
                    case "📅 Моё расписание":
                    case "/my":
                        handleMySchedule(chatId, threadId, null);
                        return;
                    case "🔔 Подписка":
                        sendMenu(chatId, threadId, "На что подписываемся?", getSubMenu());
                        return;
                    case "🔍 Поиск":
                        sendMenu(chatId, threadId, "Что ищем?", getSearchMenu());
                        return;
                    case "🍽️ Столовая":
                    case "/food":
                        sendCanteenMenu(chatId, threadId);
                        return;
                }

                if (text.startsWith("/my ")) {
                    handleTextCommand(chatId, threadId, text, true, message);
                    return;
                }

                switch (text) {
                    case "🎓 Подписаться на группу" -> {
                        userStates.put(chatId, BotState.WAITING_FOR_SUB_GROUP);
                        sendDynamicKeyboard(chatId, threadId, "Выберите группу:", dbService.getAllGroups());
                        return;
                    }
                    case "👨‍🏫 Подписаться на преподавателя" -> {
                        userStates.put(chatId, BotState.WAITING_FOR_SUB_TEACHER);
                        sendDynamicKeyboard(chatId, threadId, "Выберите преподавателя:", dbService.getAllTeachers());
                        return;
                    }
                    case "🔕 Отписаться" -> {
                        dbService.unsubscribeUser(chatId, threadId, Platform.Telegram);
                        sendMessage(chatId, threadId, "✅ Вы успешно отписались от уведомлений.");
                        return;
                    }
                    case "🎓 Поиск по группе" -> {
                        userStates.put(chatId, BotState.WAITING_SEARCH_GROUP);
                        sendDynamicKeyboard(chatId, threadId, "Выберите группу:", dbService.getAllGroups());
                        return;
                    }

                    case "👨‍🏫 Поиск по преподавателю" -> {
                        userStates.put(chatId, BotState.WAITING_SEARCH_TEACHER);
                        sendBackButtonKeyboard(chatId, threadId);
                        return;
                    }

                    case "\uD83D\uDEAA Поиск по кабинету" -> {
                        userStates.put(chatId, BotState.WAITING_SEARCH_ROOM);
                        List<String> rooms = dbService.getActiveRooms().stream().map(String::valueOf).collect(Collectors.toList());
                        sendDynamicKeyboard(chatId, threadId, "Выберите кабинет:", rooms);
                        return;
                    }
                }

            }

            switch (state) {
                case WAITING_FOR_SUB_GROUP:
                    dbService.subscribeUser(chatId, threadId, 0, text.trim(), Platform.Telegram);
                    sendMessage(chatId, threadId, "✅ Вы подписались на группу: " + text);
                    goBackToMain(chatId, threadId);
                    break;
                case WAITING_FOR_SUB_TEACHER:
                    dbService.subscribeUser(chatId, threadId, 1, text.trim(), Platform.Telegram);
                    sendMessage(chatId, threadId, "✅ Вы подписались на преподавателя: " + text);
                    goBackToMain(chatId, threadId);
                    break;
                case WAITING_SEARCH_GROUP:
                    ParsedArg pa = parseDateAndArg(text);
                    sendMessageHTML(chatId, threadId, dbService.getScheduleByGroup(pa.text(), pa.date()));
                    goBackToMain(chatId, threadId);
                    break;
                case WAITING_SEARCH_TEACHER:
                    ParsedArg pt = parseDateAndArg(text);
                    sendMessageHTML(chatId, threadId, dbService.getScheduleByTeacher(pt.text(), pt.date()));
                    goBackToMain(chatId, threadId);
                    break;
                case WAITING_SEARCH_ROOM:
                    try {
                        ParsedArg pr = parseDateAndArg(text);
                        int room = Integer.parseInt(pr.text());
                        sendMessageHTML(chatId, threadId, dbService.getScheduleByRoom(room, pr.date()));
                        goBackToMain(chatId, threadId);
                    } catch (NumberFormatException e) {
                        sendMessage(chatId, threadId, "Введите число.");
                    }
                    break;
                default:
                    if (text.startsWith("/")) handleTextCommand(chatId, threadId, text, true, message);
                    else sendMessage(chatId, threadId, "Неизвестная команда или ввод.");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            sendMessage(chatId, threadId, "Ошибка БД.");
        }
    }

    private void handleGroupChat(Message message) {
        String text = message.getText().trim();
        long chatId = message.getChatId();
        Integer threadId = message.getMessageThreadId();

        User user = message.getFrom();
        String name = (user.getFirstName() + " " + (user.getLastName() == null ? "" : user.getLastName())).trim();
        dbService.logUser(user.getId(), Platform.Telegram, user.getUserName(), name);

        if (!text.startsWith("/")) return;

        handleTextCommand(chatId, threadId, text, false, message);
    }

    private void handleTextCommand(long chatId, Integer threadId, String fullText, boolean isPrivate, Message originalMessage) {
        String[] parts = fullText.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        if (command.contains("@")) command = command.substring(0, command.indexOf("@"));
        String argRaw = parts.length > 1 ? parts[1] : "";
        long userId = originalMessage.getFrom().getId();

        ParsedArg parsed = parseDateAndArg(argRaw);

        try {
            switch (command) {
                case "/start":
                case "/help":
                    sendHelp(chatId, threadId, isPrivate);
                    break;

                case "/fg":
                case "/find_group":
                    if (parsed.text().isEmpty()) sendMessage(chatId, threadId, "Пример: /fg 1-ИП-2 [дата]");
                    else sendMessageHTML(chatId, threadId, dbService.getScheduleByGroup(parsed.text(), parsed.date()));
                    break;

                case "/ft":
                case "/find_teacher":
                    if (parsed.text().isEmpty()) sendMessage(chatId, threadId, "Пример: /ft Сергеева [дата]");
                    else sendMessageHTML(chatId, threadId, dbService.getScheduleByTeacher(parsed.text(), parsed.date()));
                    break;

                case "/fr":
                case "/find_room":
                    try {
                        if (parsed.text().isEmpty()) throw new NumberFormatException();
                        sendMessageHTML(chatId, threadId, dbService.getScheduleByRoom(Integer.parseInt(parsed.text()), parsed.date()));
                    } catch (NumberFormatException e) {
                        sendMessage(chatId, threadId, "Пример: /fr 205 [дата]");
                    }
                    break;

                case "/sg":
                case "/sub_group":
                    if (!isPrivate && cannotManageSubscription(originalMessage)) {
                        sendMessage(chatId, threadId, "⛔ Только админы могут менять подписку.");
                        return;
                    }
                    if (parsed.text().isEmpty()) sendMessage(chatId, threadId, "Пример: /sg 1-ИП-2");
                    else {
                        dbService.subscribeUser(chatId, threadId, 0, parsed.text(), Platform.Telegram);
                        sendMessage(chatId, threadId, "✅ Этот тред подписан на группу: " + parsed.text());
                    }
                    break;

                case "/st":
                case "/sub_teacher":
                    if (!isPrivate && cannotManageSubscription(originalMessage)) {
                        sendMessage(chatId, threadId, "⛔ Только админы могут менять подписку.");
                        return;
                    }
                    if (parsed.text().isEmpty()) sendMessage(chatId, threadId, "Пример: /st Сергеева");
                    else {
                        dbService.subscribeUser(chatId, threadId, 1, parsed.text(), Platform.Telegram);
                        sendMessage(chatId, threadId, "✅ Этот тред подписан на преподавателя: " + parsed.text());
                    }
                    break;

                case "/unsub":
                case "/unsubscribe":
                    if (!isPrivate && cannotManageSubscription(originalMessage)) {
                        sendMessage(chatId, threadId, "⛔ Только админы могут менять подписку.");
                        return;
                    }
                    dbService.unsubscribeUser(chatId, threadId, Platform.Telegram);
                    sendMessage(chatId, threadId, "✅ Подписка отключена для этого чата/треда.");
                    break;

                case "/my":
                    String dateForMy = parsed.date();
                    if (dateForMy == null && !parsed.text().isEmpty()) {
                        Matcher m = DATE_PATTERN.matcher(parsed.text());
                        if (m.find()) dateForMy = m.group(1);
                    }
                    handleMySchedule(chatId, threadId, dateForMy);
                    break;

                case "/food":
                    sendCanteenMenu(chatId, threadId);
                    break;
                case "/admin":
                    if (userId == this.creatorId && isPrivate) {
                        sendMenu(chatId, threadId, "⚙️ Панель администратора:", getAdminMenu());
                    }
                    break;
                case "/broadcast":
                case "/b":
                    if (userId != this.creatorId) return;
                    if (argRaw.isEmpty()) {
                        sendMessage(chatId, threadId, "Введите текст рассылки. Пример: /b Всем привет!");
                        return;
                    }
                    performBroadcast(chatId, threadId, argRaw);
                    break;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void performBroadcast(long adminChatId, Integer adminThreadId, String text) {
        sendMessage(adminChatId, adminThreadId, "⏳ Начинаю рассылку...");
        List<DatabaseService.Subscriber> subscribers = dbService.getAllSubscribersUnique();
        int count = 0;
        for (DatabaseService.Subscriber sub : subscribers) {
            if (sub.getPlatform() != Platform.Telegram) continue;
            try {
                sendMessageHTML(sub.chatId(), sub.messageThreadId(), "⚠️ <b>Объявление:</b>\n\n" + text);
                count++;
                Thread.sleep(35);
            } catch (Exception e) {
                // Ignore block
            }
        }
        sendMessage(adminChatId, adminThreadId, "✅ Рассылка завершена. Отправлено: " + count);
    }

    private void handleMySchedule(long chatId, Integer threadId, String date) throws SQLException {
        String[] sub = dbService.getUserSubscription(chatId, threadId, Platform.Telegram);
        if (sub == null) {
            sendMessage(chatId, threadId, "В этом треде нет активной подписки.");
            return;
        }
        String res = (Integer.parseInt(sub[0]) == 0)
                ? dbService.getScheduleByGroup(sub[1], date)
                : dbService.getScheduleByTeacher(sub[1], date);
        sendMessageHTML(chatId, threadId, res);
    }

    private void sendCanteenMenu(long chatId, Integer threadId) {
        try (InputStream in = BotUtil.downloadAsStream()) {
            SendDocument doc = new SendDocument();
            doc.setChatId(String.valueOf(chatId));
            doc.setMessageThreadId(threadId);
            doc.setDocument(new InputFile(in, "menu_" + System.currentTimeMillis() + ".pdf"));
            doc.setCaption("\uD83C\uDF7D️ Меню столовой");
            execute(doc);
        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(chatId, threadId, "Не удалось скачать меню.");
        }
    }

    private void sendUsersFile(long chatId, Integer threadId) {
        sendMessage(chatId, threadId, "⏳ Генерация отчета...");
        String report = dbService.getAllUsersReport();
        File file = null;
        try {
            file = BotUtil.createTextFile(report, "users_report");
            SendDocument doc = new SendDocument();
            doc.setChatId(String.valueOf(chatId));
            doc.setMessageThreadId(threadId);
            doc.setDocument(new InputFile(file, "users_list.txt"));
            doc.setCaption("📜 Список всех пользователей");
            execute(doc);
        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(chatId, threadId, "Ошибка при создании отчета.");
        } finally {
            if (file != null) file.delete();
        }
    }

    private boolean cannotManageSubscription(Message message) {
        if (message == null || message.getChat().isUserChat()) return false;
        try {
            GetChatMember getChatMember = new GetChatMember(String.valueOf(message.getChatId()), message.getFrom().getId());
            ChatMember member = execute(getChatMember);
            String status = member.getStatus();
            return !status.equals("administrator") && !status.equals("creator");
        } catch (TelegramApiException e) {
            return true;
        }
    }

    private void goBackToMain(long chatId, Integer threadId) {
        userStates.put(chatId, BotState.DEFAULT);
        sendMenu(chatId, threadId, "Главное меню", getMainMenu());
    }

    private void sendHelp(long chatId, Integer threadId, boolean isPrivate) {
        String txt = "🤖 <b>Команды:</b>\n/fg [группа] [дата], /ft [преподаватель] [дата], /fr [кабинет] [дата], /my [дата], /food";
        if (!isPrivate) txt += "\n\n🔒 <b>Админам:</b>\n/sg [группа] - Подписка на группу\n/st [фамилия] - Подписка на преподавателя\n/unsub - Отписаться";
        sendMessageHTML(chatId, threadId, txt);
    }

    public void sendMessage(long chatId, Integer threadId, String text) {
        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setMessageThreadId(threadId);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            checkAndRemoveBlockedUser(e, chatId, threadId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendMessageHTML(long chatId, Integer threadId, String text) {
        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setMessageThreadId(threadId);
        msg.setParseMode("HTML");
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            checkAndRemoveBlockedUser(e, chatId, threadId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkAndRemoveBlockedUser(TelegramApiException e, long chatId, Integer threadId) {
        String errorMsg = e.getMessage().toLowerCase();
        if (errorMsg.contains("blocked") ||
                errorMsg.contains("forbidden") ||
                errorMsg.contains("user is deactivated") ||
                errorMsg.contains("chat not found")) {

            dbService.unsubscribeUser(chatId, threadId, Platform.Telegram);
        } else {
            e.printStackTrace();
        }
    }

    private void sendMenu(long chatId, Integer threadId, String text, ReplyKeyboardMarkup keyboard) {
        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setMessageThreadId(threadId);
        msg.setReplyMarkup(keyboard);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            checkAndRemoveBlockedUser(e, chatId, threadId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendBackButtonKeyboard(long chatId, Integer threadId) {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("🔙 В главное меню");
        rows.add(row);
        markup.setKeyboard(rows);
        sendMenu(chatId, threadId, "✍️ Введите фамилию преподавателя (полностью или часть):", markup);
    }

    private void sendDynamicKeyboard(long chatId, Integer threadId, String text, List<String> data) {
        if (data.isEmpty()) { sendMessage(chatId, threadId, "Список пуст."); return; }
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow currentRow = new KeyboardRow();
        int buttonsPerRow = 2;
        for (int i = 0; i < data.size(); i++) {
            currentRow.add(data.get(i));
            if ((i + 1) % buttonsPerRow == 0 || i == data.size() - 1) {
                rows.add(currentRow); currentRow = new KeyboardRow();
            }
        }
        KeyboardRow backRow = new KeyboardRow(); backRow.add("🔙 В главное меню"); rows.add(backRow);
        markup.setKeyboard(rows);
        sendMenu(chatId, threadId, text, markup);
    }

    private ReplyKeyboardMarkup getMainMenu() {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("📅 Моё расписание"); r1.add("🔍 Поиск");
        KeyboardRow r2 = new KeyboardRow(); r2.add("🔔 Подписка"); r2.add("🍽️ Столовая");
        rows.add(r1); rows.add(r2); markup.setKeyboard(rows); return markup;
    }
    private ReplyKeyboardMarkup getSubMenu() {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("🎓 Подписаться на группу");
        KeyboardRow r2 = new KeyboardRow(); r2.add("👨‍🏫 Подписаться на преподавателя");
        KeyboardRow r3 = new KeyboardRow(); r3.add("🔕 Отписаться");
        KeyboardRow r4 = new KeyboardRow(); r4.add("🔙 В главное меню");
        rows.add(r1); rows.add(r2); rows.add(r3); rows.add(r4); markup.setKeyboard(rows); return markup;
    }
    private ReplyKeyboardMarkup getSearchMenu() {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("🎓 Поиск по группе"); r1.add("👨‍🏫 Поиск по преподавателю");
        KeyboardRow r2 = new KeyboardRow(); r2.add("\uD83D\uDEAA Поиск по кабинету");
        KeyboardRow r3 = new KeyboardRow(); r3.add("🔙 В главное меню");
        rows.add(r1); rows.add(r2); rows.add(r3); markup.setKeyboard(rows); return markup;
    }

    private ReplyKeyboardMarkup getAdminMenu() {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("📊 Статистика"); r1.add("📜 Список пользователей");
        KeyboardRow r2 = new KeyboardRow(); r2.add("🔙 Выход");
        rows.add(r1);
        rows.add(r2);
        markup.setKeyboard(rows);
        return markup;
    }
}