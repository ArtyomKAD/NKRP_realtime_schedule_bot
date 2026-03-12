package ru.artyomkad.nkrp.bot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BotUtil {
    public static final Pattern DATE_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{1,2}\\s+(?:янв|фев|мар|апр|ма[йя]|июн|июл|авг|сен|окт|ноя|дек)[а-я]*(\\s+\\d{4})?)\\b"
    );

    public record ParsedArg(String text, String date) {}

    public static ParsedArg parseDateAndArg(String raw) {
        if (raw == null || raw.isEmpty()) return new ParsedArg("", null);
        Matcher m = DATE_PATTERN.matcher(raw);
        String date = null;
        String text = raw;
        if (m.find()) {
            date = m.group(1);
            text = raw.replace(date, "").trim().replaceAll("\\s+", " ");
        }
        return new ParsedArg(text, date);
    }

    private static final String MENU_URL = "https://www.novkrp.ru/data/covid_pit.pdf";

    public static InputStream downloadAsStream() throws IOException {
        URLConnection connection = new URL(MENU_URL).openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream(); 
    }

    public static File downloadToTempFile() throws IOException {
        File tempFile = File.createTempFile("canteen_menu", ".pdf");
        try (InputStream in = downloadAsStream()) {
            Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    public static File createTextFile(String content, String prefix) throws IOException {
        File tempFile = File.createTempFile(prefix, ".txt");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(content);
        }
        return tempFile;
    }

    public BotUtil() {}
}