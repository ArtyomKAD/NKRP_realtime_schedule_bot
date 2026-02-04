package ru.artyomkad.nkrp.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.artyomkad.nkrp.model.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ScheduleParser {
    private final String url;

    private final Map<String, Map<String, DaySchedule>> result = new LinkedHashMap<>();

    private static final Pattern RE_DATE = Pattern.compile("(\\d{1,2})\\s+[а-яА-Я]+\\s+\\d{4}|(\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final Pattern RE_GROUP = Pattern.compile("^\\d-[\\wа-яА-Я]+-\\d");
    private static final Pattern RE_TEACHER = Pattern.compile("^[А-ЯЁ][а-яёА-ЯЁ-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?");
    private static final Pattern RE_TEACHER_SEARCH = Pattern.compile("[А-ЯЁ][а-яёА-ЯЁ-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.?");
    private static final Pattern RE_TIME_RANGE = Pattern.compile("[сc]\\s*(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RE_START_TIME = Pattern.compile("начало\\s+в\\s+(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RE_LABEL_FULL = Pattern.compile("^\\([^)]+\\)$");
    private static final Pattern RE_LABEL_INLINE = Pattern.compile("\\([^)]+\\)");
    private static final Pattern RE_ROOM = Pattern.compile("[Аа]уд\\.?\\s*(.*)");
    private static final Pattern RE_PAIR_NUM = Pattern.compile("(\\d)\\s*пара");
    private static final Pattern RE_ROLES_CLEAN = Pattern.compile("(?:Зам\\.?|Пред\\.?|Чл\\.?|Секр\\.?|Преп\\.?)[\\wа-яА-Я-]*|\\s+|[,.;]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public ScheduleParser(String url) {
        this.url = (url != null && !url.isEmpty()) ? url : "https://www.novkrp.ru/raspisanie.htm";
    }

    public Map<String, Map<String, DaySchedule>> parse() {
        long start = System.currentTimeMillis();
        result.clear();
        try {
            System.out.println("Connecting to " + url + "...");
            Document doc = Jsoup.connect(this.url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0")
                    .timeout(15000)
                    .get();
            processTables(doc);
        } catch (IOException e) {
            System.err.println("Parse error: " + e.getMessage());
        }
        System.out.println("Schedule Parse took: " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }

    private void processTables(Document doc) {
        Elements tables = doc.select("table.MsoNormalTable");
        for (Element table : tables) {
            DateInfo dateInfo = findDate(table);
            if (dateInfo != null) {
                parseTable(table, dateInfo.date, dateInfo.isMonday);
            }
        }
    }

    private void parseTable(Element table, String date, boolean isMonday) {
        Map<Integer, Map<Integer, Element>> matrix = buildMatrix(table);
        if (matrix.isEmpty()) return;

        int maxRow = Collections.max(matrix.keySet());
        HeaderInfo header = findHeader(matrix, maxRow);
        if (header.headerRow == -1) return;

        Map<Integer, Integer> rowToPair = new HashMap<>();
        int currentPair = 0;

        for (int r = header.headerRow + 1; r <= maxRow; r++) {
            Map<Integer, Element> row = matrix.get(r);
            if (row == null) continue;

            // Определение номера пары
            Element firstCell = row.get(0);
            String pairCellText = (firstCell != null) ? firstCell.text().trim().toLowerCase() : "";
            Matcher mPair = RE_PAIR_NUM.matcher(pairCellText);

            if (mPair.find()) {
                currentPair = Integer.parseInt(mPair.group(1));
            } else if (pairCellText.contains("классный") || pairCellText.contains("разговоры")) {
                currentPair = 0; // Нулевая пара
            }
            rowToPair.put(r, currentPair);

            // Проход по колонкам групп
            for (Map.Entry<Integer, String> entry : header.colMap.entrySet()) {
                int c = entry.getKey();
                String groupName = entry.getValue();

                Element cell = row.get(c);
                if (cell == null) continue;

                Map<Integer, Element> prevRow = matrix.get(r - 1);
                boolean isContinuation = (r > header.headerRow + 1) && (prevRow != null) && (prevRow.get(c) == cell);

                Integer prevPair = rowToPair.get(r - 1);
                boolean isSamePairBlock = (prevPair != null && prevPair == currentPair);

                if (!isContinuation) {
                    if (isSamePairBlock) {
                        mergeOrAddLesson(cell, groupName, date, isMonday, currentPair);
                    } else {
                        addLesson(cell, groupName, date, isMonday, currentPair);
                    }
                }
            }
        }
    }

    private void addLesson(Element cell, String group, String date, boolean isMonday, int pair) {
        List<String> lines = extractLines(cell);
        if (lines.isEmpty() || (lines.size() == 1 && lines.getFirst().equals("&nbsp;"))) return;

        Lesson lesson = parseLessonData(lines);
        if (lesson.getSubject().isEmpty() && lesson.getRaw().length() < 3) return;

        getPeriod(group, date, isMonday, pair).getLessons().add(lesson);
    }

    private void mergeOrAddLesson(Element cell, String group, String date, boolean isMonday, int pair) {
        List<String> newLines = extractLines(cell);
        if (newLines.isEmpty() || (newLines.size() == 1 && newLines.getFirst().equals("&nbsp;"))) return;

        Period period = getPeriod(group, date, isMonday, pair);
        List<Lesson> lessons = period.getLessons();

        if (lessons.isEmpty()) {
            addLesson(cell, group, date, isMonday, pair);
        } else {
            Lesson lastLesson = lessons.getLast();

            List<String> combinedLines = new ArrayList<>();
            if (lastLesson.getRaw() != null && !lastLesson.getRaw().isEmpty()) {
                combinedLines.addAll(Arrays.asList(lastLesson.getRaw().split(" \\| ")));
            }
            combinedLines.addAll(newLines);

            Lesson mergedLesson = parseLessonData(combinedLines);
            lessons.set(lessons.size() - 1, mergedLesson);
        }
    }

    private Period getPeriod(String group, String date, boolean isMonday, int pair) {
        return result.computeIfAbsent(group, _ -> new LinkedHashMap<>())
                .computeIfAbsent(date, _ -> {
                    DaySchedule ds = new DaySchedule();
                    ds.setMonday(isMonday);
                    return ds;
                })
                .getPeriods()
                .computeIfAbsent(pair, k -> {
                    Period p = new Period();
                    p.setNumber(k);
                    return p;
                });
    }

    private Map<Integer, Map<Integer, Element>> buildMatrix(Element table) {
        Map<Integer, Map<Integer, Element>> matrix = new HashMap<>();
        Elements rows = table.select("tr");

        for (int r = 0; r < rows.size(); r++) {
            Element tr = rows.get(r);
            Elements cells = tr.select("td");

            matrix.putIfAbsent(r, new HashMap<>());
            int c = 0;
            for (Element td : cells) {
                while (matrix.get(r).containsKey(c)) c++;

                int rs = parseSpan(td.attr("rowspan"));
                int cs = parseSpan(td.attr("colspan"));

                for (int i = 0; i < rs; i++) {
                    for (int j = 0; j < cs; j++) {
                        matrix.computeIfAbsent(r + i, _ -> new HashMap<>()).put(c + j, td);
                    }
                }
                c += cs;
            }
        }
        return matrix;
    }

    private int parseSpan(String attr) {
        if (attr == null || attr.isEmpty()) return 1;
        try { return Integer.parseInt(attr); } catch (NumberFormatException e) { return 1; }
    }

    private HeaderInfo findHeader(Map<Integer, Map<Integer, Element>> matrix, int maxRow) {
        Map<Integer, String> colMap = new HashMap<>();
        for (int r = 0; r < Math.min(maxRow + 1, 5); r++) {
            Map<Integer, Element> row = matrix.get(r);
            if (row == null) continue;
            boolean found = false;
            int maxCol = row.keySet().stream().max(Integer::compareTo).orElse(0);

            for (int c = 1; c <= maxCol; c++) {
                Element cell = row.get(c);
                if (cell == null) continue;
                String text = cell.text().trim();
                if (RE_GROUP.matcher(text).find() || (text.contains("-") && text.length() < 15 && text.chars().anyMatch(Character::isDigit))) {
                    colMap.put(c, text);
                    found = true;
                }
            }
            if (found) return new HeaderInfo(r, colMap);
        }
        return new HeaderInfo(-1, Collections.emptyMap());
    }

    private DateInfo findDate(Element el) {
        Element curr = el;
        for (int i = 0; i < 20 && curr != null; i++) {
            if ("body".equalsIgnoreCase(curr.tagName())) break;
            Element prev = curr.previousElementSibling();
            while (prev != null) {
                String text = prev.text().replaceAll("\\s+", " ").trim();
                Matcher m = RE_DATE.matcher(text);
                if (m.find()) return new DateInfo(m.group(0), text.toLowerCase().contains("понедельник"));
                prev = prev.previousElementSibling();
            }
            curr = curr.parent();
        }
        return null;
    }

    private List<String> extractLines(Element cell) {
        Elements ps = cell.select("p");
        if (!ps.isEmpty()) {
            return ps.stream().map(e -> e.text().trim()).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }
        String rawText = cell.text().trim();
        return rawText.isEmpty() ? Collections.emptyList() : Collections.singletonList(rawText);
    }

    private Lesson parseLessonData(List<String> rawLines) {
        Lesson l = new Lesson();
        l.setRaw(String.join(" | ", rawLines));

        List<String> subjectParts = new ArrayList<>();
        List<String> teachers = new ArrayList<>();
        List<Integer> rooms = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String customTime = null;

        for (String line : rawLines) {
            String text = line.replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) continue;

            if (RE_LABEL_FULL.matcher(text).matches()) {
                labels.add(text); continue;
            }

            Matcher mInline = RE_LABEL_INLINE.matcher(text);
            StringBuilder sbText = new StringBuilder();
            int lastEnd = 0; boolean foundLabel = false;
            while(mInline.find()) {
                labels.add(mInline.group());
                sbText.append(text, lastEnd, mInline.start());
                lastEnd = mInline.end();
                foundLabel = true;
            }
            if(foundLabel) { sbText.append(text.substring(lastEnd)); text = sbText.toString().trim(); }

            Matcher mStart = RE_START_TIME.matcher(text);
            Matcher mRange = RE_TIME_RANGE.matcher(text);
            String timeFound = null; String matchStr = null;
            if (mStart.find()) { timeFound = mStart.group(1); matchStr = mStart.group(0); }
            else if (mRange.find()) { timeFound = mRange.group(1); matchStr = mRange.group(0); }

            if (timeFound != null) {
                customTime = timeFound.replace(".", ":");
                if (text.length() < 15) continue;
                text = text.replace(matchStr, "").trim();
            }

            if (text.toLowerCase().contains("ауд")) {
                Matcher mRoom = RE_ROOM.matcher(text);
                if (mRoom.find()) {
                    String content = mRoom.group(1);
                    Matcher mDigits = Pattern.compile("\\d+").matcher(content);
                    while (mDigits.find()) rooms.add(Integer.parseInt(mDigits.group()));
                    if (mRoom.start() > 0) {
                        String preRoomText = text.substring(0, mRoom.start()).trim();
                        if (RE_TEACHER.matcher(preRoomText).matches()) teachers.add(preRoomText.replaceAll(",+$", ""));
                    }
                    continue;
                }
            }

            if (RE_TEACHER.matcher(text).matches()) { teachers.add(text.replaceAll(",+$", "")); continue; }

            Matcher mTeachSearch = RE_TEACHER_SEARCH.matcher(text);
            List<String> foundTeachers = new ArrayList<>();
            while (mTeachSearch.find()) foundTeachers.add(mTeachSearch.group());

            if (!foundTeachers.isEmpty()) {
                teachers.addAll(foundTeachers);
                String remainingText = text;
                for (String t : foundTeachers) remainingText = remainingText.replace(t, "");
                remainingText = RE_ROLES_CLEAN.matcher(remainingText).replaceAll("").trim();
                if (remainingText.length() < 3) continue;
                text = remainingText;
            }
            if (!subjectParts.isEmpty() && text.length() < 3) continue;
            subjectParts.add(text);
        }

        l.setSubject(String.join(" ", subjectParts));
        l.setTeachers(new ArrayList<>(new LinkedHashSet<>(teachers)));
        l.setRooms(new ArrayList<>(new LinkedHashSet<>(rooms)));
        l.setLabels(new ArrayList<>(new LinkedHashSet<>(labels)));
        l.setStartTime(customTime);
        return l;
    }

    // --- DTO ---
    private static class DateInfo { String date; boolean isMonday; DateInfo(String d, boolean m) { date = d; isMonday = m; } }
    private static class HeaderInfo { int headerRow; Map<Integer, String> colMap; HeaderInfo(int h, Map<Integer, String> c) { headerRow = h; colMap = c; } }
}