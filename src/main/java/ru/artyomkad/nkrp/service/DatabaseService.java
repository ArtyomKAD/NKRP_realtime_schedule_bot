package ru.artyomkad.nkrp.service;

import ru.artyomkad.nkrp.model.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabaseService implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(DatabaseService.class.getName());
    private final Connection connection;

    private static final String[] MONTHS_GENITIVE = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

    public record Subscriber(long chatId, Integer messageThreadId, String platform) {}

    public DatabaseService(String dbName) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbName);
        initTables();
    }

    private void initTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS schedules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    group_name TEXT NOT NULL,
                    date_val TEXT NOT NULL,
                    is_monday INTEGER DEFAULT 0,
                    UNIQUE(group_name, date_val) ON CONFLICT REPLACE
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS lessons (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    schedule_id INTEGER NOT NULL,
                    pair_number INTEGER NOT NULL,
                    subject TEXT,
                    start_time TEXT,
                    raw_text TEXT,
                    FOREIGN KEY(schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
                );
            """);

            stmt.execute("CREATE TABLE IF NOT EXISTS lesson_teachers (lesson_id INTEGER, name TEXT, FOREIGN KEY(lesson_id) REFERENCES lessons(id) ON DELETE CASCADE)");
            stmt.execute("CREATE TABLE IF NOT EXISTS lesson_rooms (lesson_id INTEGER, room_number INTEGER, FOREIGN KEY(lesson_id) REFERENCES lessons(id) ON DELETE CASCADE)");
            stmt.execute("CREATE TABLE IF NOT EXISTS lesson_labels (lesson_id INTEGER, label TEXT, FOREIGN KEY(lesson_id) REFERENCES lessons(id) ON DELETE CASCADE)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    chat_id INTEGER,
                    message_thread_id INTEGER DEFAULT 0,
                    sub_type INTEGER,
                    sub_value TEXT,
                    platform TEXT DEFAULT 'TG',
                    PRIMARY KEY (chat_id, message_thread_id, platform)
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bells (
                    pair_number INTEGER PRIMARY KEY,
                    time_normal TEXT,
                    time_monday TEXT
                );
            """);
        }
    }

    public void updateBells(BellParser.BellsData data) {
        if (data.normal.isEmpty() && data.monday.isEmpty()) return;

        Set<Integer> allPairs = new HashSet<>();
        allPairs.addAll(data.normal.keySet());
        allPairs.addAll(data.monday.keySet());

        String sql = "INSERT OR REPLACE INTO bells(pair_number, time_normal, time_monday) VALUES(?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Integer pair : allPairs) {
                ps.setInt(1, pair);
                ps.setString(2, data.normal.getOrDefault(pair, null));
                ps.setString(3, data.monday.getOrDefault(pair, null));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String getBellTime(int pairNumber, boolean isMonday) {
        String col = isMonday ? "time_monday" : "time_normal";
        String sql = "SELECT " + col + " FROM bells WHERE pair_number = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, pairNumber);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (SQLException ignored) {}
        return null;
    }

    public void subscribeUser(long chatId, Integer threadId, int type, String value, String platform) throws SQLException {
        int tid = (threadId == null) ? 0 : threadId;
        String plat = (platform == null) ? "TG" : platform;

        String sql = "INSERT INTO users(chat_id, message_thread_id, sub_type, sub_value, platform) VALUES(?, ?, ?, ?, ?) " +
                "ON CONFLICT(chat_id, message_thread_id, platform) DO UPDATE SET sub_type=excluded.sub_type, sub_value=excluded.sub_value";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setInt(2, tid);
            ps.setInt(3, type);
            ps.setString(4, value);
            ps.setString(5, plat);
            ps.executeUpdate();
        }
    }

    public String[] getUserSubscription(long chatId, Integer threadId, String platform) throws SQLException {
        int tid = (threadId == null) ? 0 : threadId;
        String plat = (platform == null) ? "TG" : platform;

        String sql = "SELECT sub_type, sub_value FROM users WHERE chat_id = ? AND message_thread_id = ? AND platform = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setInt(2, tid);
            ps.setString(3, plat);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{String.valueOf(rs.getInt(1)), rs.getString(2)};
            }
        }
        return null;
    }

    public List<Subscriber> getSubscribers(String targetValue, int type) {
        List<Subscriber> subs = new ArrayList<>();
        String sql = getString(type);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, targetValue);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                long chatId = rs.getLong("chat_id");
                int threadId = rs.getInt("message_thread_id");
                String platform = rs.getString("platform");
                subs.add(new Subscriber(chatId, threadId == 0 ? null : threadId, platform));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error getting subscribers", e);
        }
        return subs;
    }

    private static String getString(int type) {
        String sql;
        if (type == 1) {
            sql = "SELECT chat_id, message_thread_id, platform FROM users WHERE ? LIKE sub_value || '%' AND sub_type = 1";
        } else {
            sql = "SELECT chat_id, message_thread_id, platform FROM users WHERE sub_value = ? AND sub_type = 0";
        }
        return sql;
    }

    private String convertDateToRussianText(String inputDate) {
        if (inputDate == null) return null;
        Pattern p = Pattern.compile("^(\\d{1,2})[.\\/-](\\d{1,2})[.\\/-](\\d{2,4})$");
        Matcher m = p.matcher(inputDate.trim());
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                String year = m.group(3);
                if (year.length() == 2) year = "20" + year;

                if (month >= 1 && month <= 12) {
                    return day + " " + MONTHS_GENITIVE[month - 1] + " " + year;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public String getScheduleByGroup(String groupName) {
        return getScheduleByGroup(groupName, null);
    }

    public String getScheduleByGroup(String groupName, String date) {
        StringBuilder sb = new StringBuilder();
        String sql;

        String textDate = convertDateToRussianText(date);

        if (date != null && !date.isEmpty()) {
            sql = "SELECT id, date_val, is_monday FROM schedules WHERE group_name LIKE ? AND (date_val LIKE ? OR date_val LIKE ?) ORDER BY id DESC LIMIT 1";
        } else {
            sql = "SELECT id, date_val, is_monday FROM schedules WHERE group_name LIKE ? ORDER BY id DESC LIMIT 1";
        }

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + groupName + "%");
            if (date != null && !date.isEmpty()) {
                ps.setString(2, "%" + date + "%");
                ps.setString(3, "%" + (textDate != null ? textDate : date) + "%");
            }

            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                long scheduleId = rs.getLong("id");
                String foundDate = rs.getString("date_val");
                boolean isMonday = rs.getInt("is_monday") == 1;

                sb.append("📅 <b>").append(foundDate).append("</b> (").append(groupName).append(")\n");
                if (isMonday) sb.append("<i>(Понедельник)</i>\n");
                sb.append("\n");

                appendLessons(sb, scheduleId, isMonday);
            } else {
                if (date != null && !date.isEmpty()) {
                    return "Расписание для группы '" + groupName + "' на дату '" + date + "' не найдено.";
                }
                return "Расписание для группы '" + groupName + "' не найдено.";
            }
        } catch (SQLException e) {
            return "Ошибка БД: " + e.getMessage();
        }
        return sb.toString();
    }

    public String getScheduleByTeacher(String teacherName) {
        return getScheduleByTeacher(teacherName, null);
    }

    public String getScheduleByTeacher(String teacherName, String date) {
        StringBuilder sb = new StringBuilder();
        String targetDate;
        boolean isMonday;
        String textDate = convertDateToRussianText(date);

        if (date != null && !date.isEmpty()) {
            String checkDateSql = "SELECT date_val, is_monday FROM schedules WHERE (date_val LIKE ? OR date_val LIKE ?) LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(checkDateSql)) {
                ps.setString(1, "%" + date + "%");
                ps.setString(2, "%" + (textDate != null ? textDate : date) + "%");
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    targetDate = rs.getString("date_val");
                    isMonday = rs.getInt("is_monday") == 1;
                } else {
                    return "Расписание на дату " + date + " не найдено в базе.";
                }
            } catch (SQLException e) { return "Ошибка БД при поиске даты."; }
        } else {
            String latestDateSql = "SELECT date_val, is_monday FROM schedules ORDER BY id DESC LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(latestDateSql); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "Расписание ещё не загружено.";
                targetDate = rs.getString("date_val");
                isMonday = rs.getInt("is_monday") == 1;
            } catch (SQLException e) { return "Ошибка базы данных."; }
        }

        String sql = """
        SELECT l.pair_number, l.subject, l.start_time, s.group_name,
               GROUP_CONCAT(DISTINCT lr.room_number ORDER BY lr.room_number) as rooms
        FROM schedules s
        JOIN lessons l ON s.id = l.schedule_id
        JOIN lesson_teachers lt ON l.id = lt.lesson_id
        LEFT JOIN lesson_rooms lr ON l.id = lr.lesson_id
        WHERE lt.name LIKE ? AND s.date_val = ?
        GROUP BY l.pair_number, l.subject, l.start_time, s.group_name
        ORDER BY l.pair_number
        """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + teacherName + "%");
            ps.setString(2, targetDate);
            ResultSet rs = ps.executeQuery();
            TreeMap<Integer, List<String>> lessonsByPair = new TreeMap<>();

            while (rs.next()) {
                int pair = rs.getInt("pair_number");
                String subject = rs.getString("subject").trim();
                String group = rs.getString("group_name");
                String rooms = rs.getString("rooms");
                String roomStr = (rooms == null || rooms.isEmpty()) ? "" : " [Каб: " + rooms.replace(",", ", ") + "]";
                String line = subject + " — <b>" + group + "</b>" + roomStr;
                lessonsByPair.computeIfAbsent(pair, _ -> new ArrayList<>()).add(line);
            }

            if (lessonsByPair.isEmpty()) return "На <b>" + targetDate + "</b> у преподавателя <b>" + teacherName + "</b> пар нет.";

            sb.append("🗓 Расписание:\n📅 <b>").append(targetDate).append("</b>\n");
            sb.append("Преподаватель: <b>").append(teacherName).append("</b>\n\n");

            appendFormattedMap(sb, lessonsByPair, isMonday);
            return sb.toString();

        } catch (SQLException e) { e.printStackTrace(); return "Ошибка при загрузке расписания."; }
    }

    public String getScheduleByRoom(int roomNumber) {
        return getScheduleByRoom(roomNumber, null);
    }

    public String getScheduleByRoom(int roomNumber, String date) {
        StringBuilder sb = new StringBuilder();
        String targetDate;
        boolean isMonday;
        String textDate = convertDateToRussianText(date);

        if (date != null && !date.isEmpty()) {
            String checkDateSql = "SELECT date_val, is_monday FROM schedules WHERE (date_val LIKE ? OR date_val LIKE ?) LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(checkDateSql)) {
                ps.setString(1, "%" + date + "%");
                ps.setString(2, "%" + (textDate != null ? textDate : date) + "%");
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    targetDate = rs.getString("date_val");
                    isMonday = rs.getInt("is_monday") == 1;
                } else {
                    return "Расписание на дату " + date + " не найдено в базе.";
                }
            } catch (SQLException e) { return "Ошибка БД при поиске даты."; }
        } else {
            String latestDateSql = "SELECT date_val, is_monday FROM schedules ORDER BY id DESC LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(latestDateSql); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "Расписание ещё не загружено.";
                targetDate = rs.getString("date_val");
                isMonday = rs.getInt("is_monday") == 1;
            } catch (SQLException e) { return "Ошибка базы данных."; }
        }

        String sql = """
        SELECT l.pair_number, l.subject, s.group_name,
               GROUP_CONCAT(DISTINCT lt.name ORDER BY lt.name) as teachers
        FROM schedules s
        JOIN lessons l ON s.id = l.schedule_id
        JOIN lesson_rooms lr ON l.id = lr.lesson_id
        LEFT JOIN lesson_teachers lt ON l.id = lt.lesson_id
        WHERE lr.room_number = ? AND s.date_val = ?
        GROUP BY l.pair_number, l.subject, s.group_name
        ORDER BY l.pair_number
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roomNumber); ps.setString(2, targetDate); ResultSet rs = ps.executeQuery();
            TreeMap<Integer, List<String>> lessonsByPair = new TreeMap<>();
            while (rs.next()) {
                int pair = rs.getInt("pair_number");
                String subject = rs.getString("subject").trim();
                String group = rs.getString("group_name");
                String teachers = rs.getString("teachers");
                String teacherStr = (teachers == null || teachers.isEmpty()) ? "" : " (" + teachers.replace(",", ", ") + ")";
                String line = subject + " — <b>" + group + "</b>" + teacherStr;
                lessonsByPair.computeIfAbsent(pair, _ -> new ArrayList<>()).add(line);
            }
            if (lessonsByPair.isEmpty()) return "На <b>" + targetDate + "</b> в кабинете <b>" + roomNumber + "</b> пар нет.";

            sb.append("🗓 Расписание:\n📅 <b>").append(targetDate).append("</b>\n");
            sb.append("Кабинет: <b>").append(roomNumber).append("</b>\n\n");
            appendFormattedMap(sb, lessonsByPair, isMonday);
            return sb.toString();
        } catch (SQLException e) { return "Ошибка."; }
    }

    private void appendFormattedMap(StringBuilder sb, Map<Integer, List<String>> lessonsByPair, boolean isMonday) {
        for (Map.Entry<Integer, List<String>> entry : lessonsByPair.entrySet()) {
            int pair = entry.getKey();
            String time = getBellTime(pair, isMonday);
            String timeStr = (time != null) ? " (" + time + ")" : "";

            sb.append("<b>").append(pair).append(" пара").append(timeStr).append("</b>\n");
            for (String line : entry.getValue()) {
                sb.append("   • ").append(line).append("\n");
            }
            sb.append("\n");
        }
    }

    private void appendLessons(StringBuilder sb, long scheduleId, boolean isMonday) throws SQLException {
        String sql = "SELECT * FROM lessons WHERE schedule_id = ? ORDER BY pair_number";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, scheduleId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) formatLesson(sb, rs, isMonday);
        }
    }

    private void formatLesson(StringBuilder sb, ResultSet rs, boolean isMonday) throws SQLException {
        long id = rs.getLong("id");
        int pair = rs.getInt("pair_number");
        String timeStr = getBellTime(pair, isMonday);

        String customTime = rs.getString("start_time");
        if (customTime != null && !customTime.isEmpty()) {
            timeStr = "Начало в " + customTime;
        } else if (timeStr == null) {
            timeStr = "";
        }

        sb.append(pair).append(" пара");
        if (!timeStr.isEmpty()) sb.append(" <i>(").append(timeStr).append(")</i> ");
        sb.append("\n");
        sb.append("<b>").append(rs.getString("subject")).append("</b>");

        List<String> rooms = getRelated(id, "lesson_rooms", "room_number");
        if (!rooms.isEmpty()) sb.append(" [Каб: ").append(String.join(",", rooms)).append("]");

        List<String> teachers = getRelated(id, "lesson_teachers", "name");
        if (!teachers.isEmpty()) sb.append(" (").append(String.join(", ", teachers)).append(")");

        List<String> labels = getRelated(id, "lesson_labels", "label");
        if (!labels.isEmpty()) sb.append(" ").append(String.join(" ", labels));

        sb.append("\n\n");
    }

    private List<String> getRelated(long lessonId, String table, String col) throws SQLException {
        List<String> res = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT " + col + " FROM " + table + " WHERE lesson_id = ?")) {
            ps.setLong(1, lessonId);
            ResultSet rs = ps.executeQuery();
            while(rs.next()) res.add(rs.getString(1));
        }
        return res;
    }

    public List<String> getAllGroups() {
        List<String> groups = new ArrayList<>();
        String sql = "SELECT DISTINCT group_name FROM schedules ORDER BY group_name";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                groups.add(rs.getString("group_name"));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error loading groups", e);
        }
        return groups;
    }

    public List<String> getAllTeachers() {
        List<String> teachers = new ArrayList<>();
        String sql = "SELECT DISTINCT name FROM lesson_teachers ORDER BY name";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                teachers.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error loading teachers", e);
        }
        return teachers;
    }

    public List<Integer> getActiveRooms() {
        List<Integer> rooms = new ArrayList<>();
        String sql = """
        SELECT DISTINCT lr.room_number\s
        FROM lesson_rooms lr
        JOIN lessons l ON lr.lesson_id = l.id
        JOIN schedules s ON l.schedule_id = s.id
        ORDER BY s.id DESC LIMIT 100
       \s""";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rooms.add(rs.getInt("room_number"));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error loading rooms", e);
        }
        return rooms.stream().distinct().sorted().toList();
    }

    private void saveDetails(long lessonId, Lesson lesson) throws SQLException {
        saveList(lessonId, lesson.getTeachers(), "INSERT INTO lesson_teachers(lesson_id, name) VALUES(?, ?)");
        saveList(lessonId, lesson.getRooms(), "INSERT INTO lesson_rooms(lesson_id, room_number) VALUES(?, ?)");
        saveList(lessonId, lesson.getLabels(), "INSERT INTO lesson_labels(lesson_id, label) VALUES(?, ?)");
    }

    private <T> void saveList(long lessonId, List<T> list, String sql) throws SQLException {
        if (list == null || list.isEmpty()) return;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (T item : list) {
                ps.setLong(1, lessonId);
                ps.setObject(2, item);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<Subscriber> getAllSubscribersUnique() {
        List<Subscriber> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT chat_id, message_thread_id, platform FROM users")) {
            while (rs.next()) {
                int tid = rs.getInt("message_thread_id");
                list.add(new Subscriber(rs.getLong("chat_id"), tid == 0 ? null : tid, rs.getString("platform")));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public String getGroupScheduleSignature(String groupName, String dateVal) {
        StringBuilder sb = new StringBuilder();
        String sqlId = "SELECT id FROM schedules WHERE group_name = ? AND date_val = ?";
        long scheduleId = -1;
        try (PreparedStatement ps = connection.prepareStatement(sqlId)) {
            ps.setString(1, groupName);
            ps.setString(2, dateVal);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) scheduleId = rs.getLong("id");
        } catch (SQLException e) { return ""; }
        if (scheduleId == -1) return "";

        String sqlLessons = "SELECT pair_number, subject, raw_text FROM lessons WHERE schedule_id = ? ORDER BY pair_number";
        try (PreparedStatement ps = connection.prepareStatement(sqlLessons)) {
            ps.setLong(1, scheduleId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                sb.append(rs.getInt("pair_number")).append(":")
                        .append(rs.getString("subject")).append(":")
                        .append(rs.getString("raw_text")).append("|");
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.toString();
    }


    public void saveSingleGroupSchedule(String groupName, String date, DaySchedule daySchedule) {
        String insertScheduleSQL = "INSERT INTO schedules(group_name, date_val, is_monday) VALUES(?, ?, ?)";
        String insertLessonSQL = "INSERT INTO lessons(schedule_id, pair_number, subject, start_time, raw_text) VALUES(?, ?, ?, ?, ?)";

        try {
            try (PreparedStatement psSchedule = connection.prepareStatement(insertScheduleSQL, Statement.RETURN_GENERATED_KEYS)) {
                psSchedule.setString(1, groupName);
                psSchedule.setString(2, date);
                psSchedule.setInt(3, daySchedule.isMonday() ? 1 : 0);
                psSchedule.executeUpdate();

                long scheduleId;
                try (ResultSet rs = psSchedule.getGeneratedKeys()) {
                    if (rs.next()) scheduleId = rs.getLong(1);
                    else return;
                }

                try (PreparedStatement psLesson = connection.prepareStatement(insertLessonSQL, Statement.RETURN_GENERATED_KEYS)) {
                    for (Map.Entry<Integer, Period> periodEntry : daySchedule.getPeriods().entrySet()) {
                        int pairNum = periodEntry.getKey();
                        for (Lesson lesson : periodEntry.getValue().getLessons()) {
                            psLesson.setLong(1, scheduleId);
                            psLesson.setInt(2, pairNum);
                            psLesson.setString(3, lesson.getSubject());
                            psLesson.setString(4, lesson.getStartTime());
                            psLesson.setString(5, lesson.getRaw());
                            psLesson.executeUpdate();

                            long lessonId;
                            try (ResultSet rsLesson = psLesson.getGeneratedKeys()) {
                                if (rsLesson.next()) lessonId = rsLesson.getLong(1);
                                else continue;
                            }
                            saveDetails(lessonId, lesson);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error saving single group schedule", e);
        }
    }

    @Override
    public void close() throws Exception {
        if (connection != null && !connection.isClosed()) connection.close();
    }
}