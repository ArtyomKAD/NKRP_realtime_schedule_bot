package ru.artyomkad.nkrp.service;

import ru.artyomkad.nkrp.bot.TelegramBot;
import ru.artyomkad.nkrp.bot.VKCollegeBot;
import ru.artyomkad.nkrp.model.DaySchedule;
import ru.artyomkad.nkrp.model.Lesson;
import ru.artyomkad.nkrp.model.Period;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScheduleUpdater extends TimerTask {
    private static final long CANTEEN_WAIT_TIMEOUT_MS = 30 * 60 * 1000L; // 30 минут

    private final ScheduleParser parser;
    private final BellParser bellParser;
    private final CanteenParser canteenParser;
    private final DatabaseService dbService;
    private final TelegramBot tgBot;
    private final VKCollegeBot vkBot;

    private String lastCanteenDateNormalized;

    // --- Состояние ожидания публикации ---
    // Группы, у которых обнаружено изменение и ожидается публикация
    private final Map<String, DaySchedule> pendingGroups = new LinkedHashMap<>();
    // Дата расписания (нормализованная), для которой ждём столовую
    private String pendingScheduleDateNormalized = "";
    // Время, когда началось ожидание столовой
    private long pendingStartTimeMs = 0L;

    public ScheduleUpdater(ScheduleParser parser, BellParser bellParser, CanteenParser canteenParser, DatabaseService dbService,
                           TelegramBot tgBot, VKCollegeBot vkBot, String initialCanteenDate) {
        this.parser = parser;
        this.bellParser = bellParser;
        this.canteenParser = canteenParser;
        this.dbService = dbService;
        this.tgBot = tgBot;
        this.vkBot = vkBot;
        this.lastCanteenDateNormalized = normalizeDate(initialCanteenDate);
    }

    private String normalizeDate(String input) {
        if (input == null || input.isEmpty()) return "";
        // Проверяем формат "DD.MM" или "DD.MM.YYYY"
        Matcher m = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})").matcher(input);
        if (m.find()) {
            return String.format("%02d.%02d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        // Проверяем формат с названием месяца (напр., "12 октября")
        String[] months = {"янв", "фев", "мар", "апр", "ма", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"};
        Matcher mt = Pattern.compile("(\\d{1,2})\\s+([а-я]+)", Pattern.CASE_INSENSITIVE).matcher(input);
        if (mt.find()) {
            int d = Integer.parseInt(mt.group(1));
            String monStr = mt.group(2).toLowerCase();
            for (int i = 0; i < 12; i++) {
                if (monStr.startsWith(months[i])) {
                    return String.format("%02d.%02d", d, i + 1);
                }
            }
        }
        return input.trim();
    }

    @Override
    public void run() {
        System.out.println("Checking for updates (" + new Date() + ")...");
        try {
            // Обновляем звонки
            BellParser.BellsData bells = bellParser.parse();
            dbService.updateBells(bells);

            // Парсим расписание
            Map<String, Map<String, DaySchedule>> newData = parser.parse();
            if (newData.isEmpty()) return;

            // Определяем последнюю дату из загруженного расписания
            String latestDateRaw = "";
            for (Map<String, DaySchedule> groupMap : newData.values()) {
                for (String d : groupMap.keySet()) {
                    latestDateRaw = d;
                    break;
                }
                if (!latestDateRaw.isEmpty()) break;
            }
            String currentTargetNormalized = normalizeDate(latestDateRaw);

            // Если дата расписания сменилась — сбрасываем старый pending
            if (!currentTargetNormalized.isEmpty()
                    && !currentTargetNormalized.equals(pendingScheduleDateNormalized)
                    && !pendingGroups.isEmpty()) {
                System.out.println("Schedule date changed (" + pendingScheduleDateNormalized + " -> " + currentTargetNormalized + "). Resetting pending queue.");
                pendingGroups.clear();
                pendingScheduleDateNormalized = "";
                pendingStartTimeMs = 0L;
            }

            // 1. Первый проход: собираем группы с изменениями в pending
            for (Map.Entry<String, Map<String, DaySchedule>> groupEntry : newData.entrySet()) {
                String groupName = groupEntry.getKey();
                for (Map.Entry<String, DaySchedule> dateEntry : groupEntry.getValue().entrySet()) {
                    String date = dateEntry.getKey();
                    DaySchedule newSchedule = dateEntry.getValue();

                    String newSignature = generateSignature(newSchedule);
                    String oldSignature = dbService.getGroupScheduleSignature(groupName, date);

                    if (!newSignature.equals(oldSignature)) {
                        System.out.println("Change detected for group: " + groupName + " on " + date);
                        // Сохраняем в БД сразу — чтобы подписчики видели актуальное расписание при запросе
                        dbService.saveSingleGroupSchedule(groupName, date, newSchedule);

                        // Добавляем в очередь ожидания публикации (если ещё не добавлено)
                        pendingGroups.putIfAbsent(groupName, newSchedule);

                        if (pendingScheduleDateNormalized.isEmpty()) {
                            pendingScheduleDateNormalized = currentTargetNormalized;
                            pendingStartTimeMs = System.currentTimeMillis();
                            System.out.println("Pending publish started. Waiting for canteen date: " + pendingScheduleDateNormalized);
                        }
                    }
                }
            }

            // 2. Если есть pending группы — проверяем столовую
            if (!pendingGroups.isEmpty()) {
                boolean canteenReady = checkCanteenSync();
                boolean timedOut = (System.currentTimeMillis() - pendingStartTimeMs) >= CANTEEN_WAIT_TIMEOUT_MS;

                if (canteenReady) {
                    System.out.println("Canteen date matches schedule date. Publishing with canteen info.");
                    publishPending();
                } else if (timedOut) {
                    System.out.println("Canteen wait timeout (30 min) reached. Publishing without canteen info.");
                    publishPending();
                } else {
                    long remaining = (CANTEEN_WAIT_TIMEOUT_MS - (System.currentTimeMillis() - pendingStartTimeMs)) / 1000;
                    System.out.println("Waiting for canteen... timeout in " + remaining + "s. " +
                            "Canteen date: " + lastCanteenDateNormalized + ", need: " + pendingScheduleDateNormalized);
                }
            }

            System.out.println("Update check finished.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Проверяет, совпадает ли дата столовой с датой расписания.
     * Если столовая обновилась — обновляем данные в dbService.
     * @return true если дата столовой совпадает с ожидаемой
     */
    private boolean checkCanteenSync() {
        // Если даты уже совпадают — сразу true (столовая уже актуальна)
        if (pendingScheduleDateNormalized.equals(lastCanteenDateNormalized)) {
            return true;
        }

        System.out.println("Checking canteen update. Target: " + pendingScheduleDateNormalized + ", current: " + lastCanteenDateNormalized);
        try {
            CanteenParser.CanteenData cData = canteenParser.parse();
            String fetchedNormalized = normalizeDate(cData.getDate());

            // Если столовая обновилась — применяем новые данные
            if (!fetchedNormalized.isEmpty() && !fetchedNormalized.equals(lastCanteenDateNormalized)) {
                System.out.println("Canteen schedule updated: " + lastCanteenDateNormalized + " -> " + fetchedNormalized);
                dbService.setCanteenTimes(cData.getTimes());
                lastCanteenDateNormalized = fetchedNormalized;
            }

            return pendingScheduleDateNormalized.equals(lastCanteenDateNormalized);
        } catch (Exception e) {
            System.err.println("Error checking canteen: " + e.getMessage());
            return false;
        }
    }

    /**
     * Публикует уведомления для всех pending-групп и сбрасывает очередь.
     */
    private void publishPending() {
        Set<String> affectedTeachers = new HashSet<>();

        for (Map.Entry<String, DaySchedule> entry : pendingGroups.entrySet()) {
            String groupName = entry.getKey();
            notifyGroupSubscribers(groupName);
            collectTeachers(entry.getValue(), affectedTeachers);
        }

        for (String teacherName : affectedTeachers) {
            notifyTeacherSubscribers(teacherName);
        }

        // Сброс состояния
        pendingGroups.clear();
        pendingScheduleDateNormalized = "";
        pendingStartTimeMs = 0L;
    }

    private void notifyGroupSubscribers(String groupName) {
        List<DatabaseService.Subscriber> subscribers = dbService.getSubscribers(groupName, 0);
        if (subscribers.isEmpty()) return;

        String messageText = "📢 <b>ОБНОВЛЕНИЕ РАСПИСАНИЯ!</b>\n\n" + dbService.getScheduleByGroup(groupName);

        for (DatabaseService.Subscriber sub : subscribers) {
            sendToSubscriber(sub, messageText);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }

    private void notifyTeacherSubscribers(String teacherName) {
        List<DatabaseService.Subscriber> subscribers = dbService.getSubscribers(teacherName, 1);
        if (subscribers.isEmpty()) return;

        String messageText = "📢 <b>Расписание обновилось!</b>\n\n" + dbService.getScheduleByTeacher(teacherName);

        for (DatabaseService.Subscriber sub : subscribers) {
            sendToSubscriber(sub, messageText);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }

    private void sendToSubscriber(DatabaseService.Subscriber sub, String text) {
        switch (sub.getPlatform()) {
            case Telegram -> tgBot.sendMessageHTML(sub.chatId(), sub.messageThreadId(), text);
            case VKontakte -> vkBot.sendMessage(sub.chatId(), text);
        }
    }

    private String generateSignature(DaySchedule schedule) {
        StringBuilder sb = new StringBuilder();
        TreeMap<Integer, Period> sortedPeriods = new TreeMap<>(schedule.getPeriods());
        for (Map.Entry<Integer, Period> entry : sortedPeriods.entrySet()) {
            for (Lesson lesson : entry.getValue().getLessons()) {
                sb.append(entry.getKey()).append(":").append(lesson.getSubject()).append(":").append(lesson.getRaw()).append("|");
            }
        }
        for (Lesson event : schedule.getSpecialEvents()) {
            sb.append("-1:").append(event.getSubject()).append(":").append(event.getRaw()).append("|");
        }
        return sb.toString();
    }

    private void collectTeachers(DaySchedule schedule, Set<String> accumulator) {
        for (Period period : schedule.getPeriods().values()) {
            for (Lesson lesson : period.getLessons()) {
                if (lesson.getTeachers() != null) accumulator.addAll(lesson.getTeachers());
            }
        }
        for (Lesson event : schedule.getSpecialEvents()) {
            if (event.getTeachers() != null) accumulator.addAll(event.getTeachers());
        }
    }
}