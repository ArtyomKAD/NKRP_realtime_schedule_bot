package ru.artyomkad.nkrp.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BellParser {
    private final String url;

    public BellParser(String url) {
        this.url = url;
    }

    public static class BellsData {
        public Map<Integer, String> normal = new HashMap<>();
        public Map<Integer, String> monday = new HashMap<>();
    }

    public BellsData parse() {
        BellsData data = new BellsData();
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15000)
                    .get();

            Elements tables = doc.select("table");

            if (tables.isEmpty()) {
                System.err.println("Таблицы с расписанием звонков не найдены по адресу: " + url);
                return data;
            }

            int tableIndex = 0;
            for (Element table : tables) {
                parseTable(table, data, tableIndex);
                tableIndex++;
            }

            if (data.normal.isEmpty() && !data.monday.isEmpty()) data.normal.putAll(data.monday);
            if (data.monday.isEmpty() && !data.normal.isEmpty()) data.monday.putAll(data.normal);

        } catch (IOException e) {
            System.err.println("Ошибка при парсинге звонков с " + url + ": " + e.getMessage());
        }
        return data;
    }

    private void parseTable(Element table, BellsData data, int tableIndex) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;

        int colMonday = -1;
        int colNormal = -1;

        Elements headers = rows.get(0).select("th, td");
        for (int i = 0; i < headers.size(); i++) {
            String text = headers.get(i).text().toLowerCase();
            if (text.contains("понедельник")) colMonday = i;
            if (text.contains("вторник") || text.contains("остальные") || text.contains("обычн") || text.contains("основн")) colNormal = i;
        }

        boolean contextIsMonday = false;
        boolean contextIsNormal = false;
        if (colMonday == -1 && colNormal == -1) {
            Element prev = table.previousElementSibling();
            for (int i = 0; i < 3 && prev != null; i++) {
                String pt = prev.text().toLowerCase();
                if (pt.contains("понедельник")) { contextIsMonday = true; break; }
                if (pt.contains("вторник") || pt.contains("обычное") || pt.contains("основное")) { contextIsNormal = true; break; }
                prev = prev.previousElementSibling();
            }
        }

        Pattern timePattern = Pattern.compile("(\\d{1,2}[:.]\\d{2}\\s*[-–—]\\s*\\d{1,2}[:.]\\d{2})");

        for (int r = 0; r < rows.size(); r++) {
            Elements cells = rows.get(r).select("td, th");
            if (cells.size() < 2) continue;

            String pairText = cells.get(0).text().trim().toLowerCase();
            int pairNum = extractPairNumber(pairText);
            
            if (pairNum == -1 && !pairText.matches(".*\\d.*")) continue; 

            if (colMonday != -1 && cells.size() > colMonday) {
                String time = extractTime(cells.get(colMonday).text(), timePattern);
                if (time != null) data.monday.put(pairNum, time);
            }
            if (colNormal != -1 && cells.size() > colNormal) {
                String time = extractTime(cells.get(colNormal).text(), timePattern);
                if (time != null) data.normal.put(pairNum, time);
            }

            if (colMonday == -1 && colNormal == -1) {
                if (cells.size() == 2) {
                    String time = extractTime(cells.get(1).text(), timePattern);
                    if (time != null) {
                        if (contextIsMonday) {
                            data.monday.put(pairNum, time);
                        } else if (contextIsNormal) {
                            data.normal.put(pairNum, time);
                        } else {
                            if (tableIndex == 0) data.normal.put(pairNum, time);
                            else data.monday.put(pairNum, time);
                        }
                    }
                } else if (cells.size() >= 3) {
                    String time1 = extractTime(cells.get(1).text(), timePattern);
                    String time2 = extractTime(cells.get(2).text(), timePattern);

                    if (time1 != null) data.monday.put(pairNum, time1);
                    if (time2 != null) data.normal.put(pairNum, time2);
                }
            }
        }
    }

    private int extractPairNumber(String text) {
        if (text.contains("классный") || text.contains("разговоры")) return 0;
        Matcher m = Pattern.compile("(\\d+)").matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    private String extractTime(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return m.group(1).replace(".", ":").replaceAll("\\s*[-–—]\\s*", "-");
        }
        return null;
    }
}