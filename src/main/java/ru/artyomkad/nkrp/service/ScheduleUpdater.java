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

public class ScheduleUpdater extends TimerTask {
    private final ScheduleParser parser;
    private final BellParser bellParser;
    private final DatabaseService dbService;
    private final TelegramBot tgBot;
    private final VKCollegeBot vkBot;

    public ScheduleUpdater(ScheduleParser parser, BellParser bellParser, DatabaseService dbService,
                           TelegramBot tgBot, VKCollegeBot vkBot) {
        this.parser = parser;
        this.bellParser = bellParser;
        this.dbService = dbService;
        this.tgBot = tgBot;
        this.vkBot = vkBot;
    }

    @Override
    public void run() {
        System.out.println("Checking for updates (" + new Date() + ")...");
        try {
            BellParser.BellsData bells = bellParser.parse();
            dbService.updateBells(bells);

            Map<String, Map<String, DaySchedule>> newData = parser.parse();
            if (newData.isEmpty()) return;

            Set<String> affectedTeachers = new HashSet<>();

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

        String messageText = "\uD83D\uDCE2️ <b>ОБНОВЛЕНИЕ РАСПИСАНИЯ!</b>\n\n" + dbService.getScheduleByGroup(groupName);

        for (DatabaseService.Subscriber sub : subscribers) {
            sendToSubscriber(sub, messageText);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }

    private void notifyTeacherSubscribers(String teacherName) {
        List<DatabaseService.Subscriber> subscribers = dbService.getSubscribers(teacherName, 1);
        if (subscribers.isEmpty()) return;

        String messageText = "\uD83D\uDCE2️ <b>Расписание обновилось!</b>\n\n" + dbService.getScheduleByTeacher(teacherName);

        for (DatabaseService.Subscriber sub : subscribers) {
            sendToSubscriber(sub, messageText);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }

    private void sendToSubscriber(DatabaseService.Subscriber sub, String text) {
        if ("TG".equals(sub.platform())) {
            tgBot.sendMessageHTML(sub.chatId(), sub.messageThreadId(), text);
        } else if ("VK".equals(sub.platform())) {
            vkBot.sendMessage(sub.chatId(), text);
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
        return sb.toString();
    }

    private void collectTeachers(DaySchedule schedule, Set<String> accumulator) {
        for (Period period : schedule.getPeriods().values()) {
            for (Lesson lesson : period.getLessons()) {
                if (lesson.getTeachers() != null) accumulator.addAll(lesson.getTeachers());
            }
        }
    }
}