package ru.artyomkad.nkrp.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CanteenParser {
    private final String pdfUrl;

    public static class CanteenData {
        private final String date;
        private final Map<String, String> times;

        public CanteenData(String date, Map<String, String> times) {
            this.date = date;
            this.times = times;
        }

        public String getDate() {
            return date;
        }

        public Map<String, String> getTimes() {
            return times;
        }
    }

    public CanteenParser(String pdfUrl) {
        this.pdfUrl = pdfUrl;
    }

    /**
     * Парсит PDF-документ и возвращает CanteenData, где:
     * date - дата расписания столовой (если найдена)
     * times - Карта: Ключ - Название группы, Значение - Время столовой
     */
    public CanteenData parse() {
        Map<String, String> groupCanteenTimes = new HashMap<>();
        String documentDate = "";

        try {
            URLConnection connection = new URL(pdfUrl).openConnection();
            connection.setUseCaches(false); // Отключаем кэш, чтобы всегда качать свежий PDF
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            try (InputStream in = connection.getInputStream();
                 PDDocument document = PDDocument.load(in)) {

                PDFTextStripper stripper = new PDFTextStripper();
                // Включаем сортировку по позициям на странице
                stripper.setSortByPosition(true);

                String text = stripper.getText(document);

                // Ищем дату в тексте PDF
                Pattern datePattern1 = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})");
                Pattern datePattern2 = Pattern.compile("(\\d{1,2})\\s+(янв|фев|мар|апр|ма[йя]|июн|июл|авг|сен|окт|ноя|дек)[а-я]*\\s+(\\d{4})?", Pattern.CASE_INSENSITIVE);

                Matcher m1 = datePattern1.matcher(text);
                if (m1.find()) {
                    documentDate = m1.group();
                } else {
                    Matcher m2 = datePattern2.matcher(text);
                    if (m2.find()) {
                        documentDate = m2.group();
                    }
                }

                String[] lines = text.split("\\r?\\n");
                String currentTimeOrBreak = "";

                // Регулярка для поиска времени (например: 11.20 - 11.40 или 11:20)
                Pattern timePattern = Pattern.compile("(\\d{1,2}[:.]\\d{2}\\s*[-–—]?\\s*\\d{1,2}[:.]\\d{2}?)");
                // Регулярка для поиска названия группы (например: 1-ИП-2)
                Pattern groupPattern = Pattern.compile("\\d-[А-Яа-яA-Za-z]+-\\d");

                for (String line : lines) {
                    // Ищем время в текущей строке
                    Matcher timeMatcher = timePattern.matcher(line);
                    if (timeMatcher.find()) {
                        // Запоминаем время, заменяя точки на двоеточия для красоты
                        currentTimeOrBreak = timeMatcher.group(1).replace(".", ":");
                    }

                    // Если в этой строке есть и время, и группы, или время было найдено строкой выше
                    Matcher groupMatcher = groupPattern.matcher(line);
                    while (groupMatcher.find()) {
                        String group = groupMatcher.group().toUpperCase();
                        if (!currentTimeOrBreak.isEmpty()) {
                            // Привязываем найденную группу к последнему найденному времени
                            groupCanteenTimes.put(group, currentTimeOrBreak.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка при парсинге PDF столовой: " + e.getMessage());
        }

        return new CanteenData(documentDate, groupCanteenTimes);
    }
}