package ru.artyomkad.nkrp.service;

import ru.artyomkad.nkrp.bot.TelegramBot;
import ru.artyomkad.nkrp.bot.VKCollegeBot;
import ru.artyomkad.nkrp.model.DaySchedule;
import ru.artyomkad.nkrp.model.Lesson;
import ru.artyomkad.nkrp.model.Period;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScheduleUpdater extends TimerTask {
    private final ScheduleParser parser;
    private final BellParser bellParser;
    private final CanteenParser canteenParser;
    private final DatabaseService dbService;
    private final TelegramBot tgBot;
    private final VKCollegeBot vkBot;

    private String lastCanteenDateNormalized;
    private boolean isCheckingCanteen = false;
    private String targetScheduleDateNormalized = "";

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
            BellParser.BellsData bells = bellParser.parse();
            dbService.updateBells(bells);

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

            boolean scheduleUpdated = false;
            Set<String> affectedTeachers = new HashSet<>();

            // 1. Первый проход: просто проверяем, было ли обновление хотя бы у одной группы
            for (Map.Entry<String, Map<String, DaySchedule>> groupEntry : newData.entrySet()) {
                String groupName = groupEntry.getKey();
                for (Map.Entry<String, DaySchedule> dateEntry : groupEntry.getValue().entrySet()) {
                    String date = dateEntry.getKey();
                    String newSignature = generateSignature(dateEntry.getValue());
                    String oldSignature = dbService.getGroupScheduleSignature(groupName, date);
                    if (!newSignature.equals(oldSignature)) {
                        scheduleUpdated = true;
                        break;
                    }
                }
                if (scheduleUpdated) break;
            }

            // 2. Если расписание обновилось, активируем поллинг столовой для поиска этой даты
            if (scheduleUpdated) {
                targetScheduleDateNormalized = currentTargetNormalized;
                isCheckingCanteen = true;
            }

            // 3. Проверка обновлений столовой
            if (isCheckingCanteen && !targetScheduleDateNormalized.isEmpty()) {
                if (!targetScheduleDateNormalized.equals(lastCanteenDateNormalized)) {
                    System.out.println("Checking canteen updates. Target date: " + targetScheduleDateNormalized);
                    CanteenParser.CanteenData cData = canteenParser.parse();
                    String fetchedCanteenNormalized = normalizeDate(cData.getDate());

                    // Если файл столовой имеет новую дату (даже если она еще не та, которую мы ждем), обновляем базу
                    if (!fetchedCanteenNormalized.equals(lastCanteenDateNormalized) && !fetchedCanteenNormalized.isEmpty()) {
                        System.out.println("Canteen schedule updated to: " + cData.getDate());
                        dbService.setCanteenTimes(cData.getTimes());
                        lastCanteenDateNormalized = fetchedCanteenNormalized;
                    }

                    if (fetchedCanteenNormalized.equals(targetScheduleDateNormalized)) {
                        System.out.println("Canteen schedule is now fully in sync with main schedule.");
                        isCheckingCanteen = false; // Отключаем проверку, так как даты совпали
                    }
                } else {
                    isCheckingCanteen = false; // Даты уже совпадают
                }
            }

            // 4. Второй проход: сохранение нового расписания в БД и отправка уведомлений
            // Мы делаем это ПОСЛЕ проверки столовой, чтобы в уведомления попали свежие данные из столовой (если она уже обновилась)
            for (Map.Entry<String, Map<String, DaySchedule>> groupEntry : newData.entrySet()) {
                String groupName = groupEntry.getKey();
                for (Map.Entry<String, DaySchedule> dateEntry : groupEntry.getValue().entrySet()) {
                    String date = dateEntry.getKey();
                    DaySchedule newSchedule = dateEntry.getValue();

                    String newSignature = generateSignature(newSchedule);
                    String oldSignature = dbService.getGroupScheduleSignature(groupName, date);

                    if (!newSignature.equals(oldSignature)) {
                        System.out.println("Change detected for group: " + groupName + " on " + date);
                        dbService.saveSingleGroupSchedule(groupName, date, newSchedule);

                        notifyGroupSubscribers(groupName);
                        collectTeachers(newSchedule, affectedTeachers);
                    }
                }
            }

            for (String teacherName : affectedTeachers) {
                notifyTeacherSubscribers(teacherName);
            }

            System.out.println("Update check finished.");
        } catch (Exception e) {
            e.printStackTrace();
        }
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