package ru.artyomkad.nkrp.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get();

            Elements tables = doc.select("div.item-page table");

            if (tables.isEmpty()) {
                System.err.println("No schedule tables found");
                return data;
            }

            parseUsuallySchedule(tables.get(0), data.normal);

            if (tables.size() >= 2) {
                parseMondaySchedule(tables.get(1), data.monday);
            } else {
                System.err.println("Monday schedule table not found");
            }

        } catch (IOException e) {
            System.err.println("Error parsing bells: " + e.getMessage());
        }
        return data;
    }

    private void parseUsuallySchedule(Element table, Map<Integer, String> map) {
        Elements rows = table.select("tr");
        int pairCounter = 1;

        for (int i = 0; i < rows.size(); i++) {
            if (i % 2 == 0) {
                String period = extractPeriodText(rows.get(i));
                if (period != null && !period.isEmpty()) {
                    map.put(pairCounter, period);
                    pairCounter++;
                }
            }
        }
    }

    private void parseMondaySchedule(Element table, Map<Integer, String> map) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;

        String firstPeriod = extractPeriodText(rows.getFirst());
        if (firstPeriod != null && !firstPeriod.isEmpty()) {
            map.put(0, firstPeriod);
        }

        int pairCounter = 1;

        for (int i = 1; i < rows.size(); i++) {
            if (i % 2 != 0) {
                String period = extractPeriodText(rows.get(i));
                if (period != null && !period.isEmpty()) {
                    map.put(pairCounter, period);
                    pairCounter++;
                }
            }
        }
    }

    private String extractPeriodText(Element row) {
        Elements cells = row.select("td");
        if (cells.size() <= 1) return null;

        String text = cells.get(1).text();

        return text.trim()
                .replace("\n", " ")
                .replaceAll("\\s+", " ");
    }
}