package ru.artyomkad.nkrp.service;

import ru.artyomkad.nkrp.model.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private Map<String, String> canteenTimes = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String[] MONTHS_GENITIVE = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

    public enum Platform {
        Telegram,
        VKontakte;
        public String toString() {
            return switch (this) {
                case Telegram -> "TG";
                case VKontakte -> "VK";
            };
        }
    }

    public record Subscriber(long chatId, Integer messageThreadId, String platform) {
        public Platform getPlatform() {
            return switch (this.platform()) {
                case "TG" -> Platform.Telegram;
                case "VK" -> Platform.VKontakte;
                default -> throw new IllegalArgumentException("Database contains invalid platform.");
            };
        }
    }

    private record ScheduleMeta(String dateVal, boolean isMonday) {}

    public DatabaseService(String dbName, Map<String, String> canteenTimes) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbName);
        this.canteenTimes = canteenTimes;
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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bot_users (
                    user_id INTEGER,
                    platform TEXT,
                    username TEXT,
                    full_name TEXT,
                    last_seen TEXT,
                    PRIMARY KEY(user_id, platform)
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_actions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER,
                    platform TEXT,
                    action TEXT,
                    created_at TEXT
                );
            """);
        }
    }

    public void setCanteenTimes(Map<String, String> times) {
        if (times != null) {
            this.canteenTimes = new java.util.concurrent.ConcurrentHashMap<>(times);
        }
    }

    public void logUser(long userId, Platform platform, String username, String fullName) {
        String sql = "INSERT INTO bot_users(user_id, platform, username, full_name, last_seen) VALUES(?, ?, ?, ?, ?) " +
                "ON CONFLICT(user_id, platform) DO UPDATE SET " +
                "username = excluded.username, " +
                "full_name = excluded.full_name, " +
                "last_seen = excluded.last_seen";

        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, platform.toString());
            ps.setString(3, username);
            ps.setString(4, fullName);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error logging user", e);
        }
    }

    public void logAction(long userId, Platform platform, String action) {
        String sql = "INSERT INTO user_actions(user_id, platform, action, created_at) VALUES(?, ?, ?, ?)";
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, platform.toString());
            ps.setString(3, action);
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error logging action", e);
        }
        logger.info(String.format("[%s] User %d performed action: %s", platform, userId, action));
    }

    public String getUsersStats() {
        int total = 0, tg = 0, vk = 0, active24h = 0;
        String sql = "SELECT platform, COUNT(*) as cnt, " +
                     "SUM(CASE WHEN last_seen >= datetime('now', 'localtime', '-1 day') THEN 1 ELSE 0 END) as active " +
                     "FROM bot_users GROUP BY platform";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while(rs.next()) {
                String p = rs.getString("platform");
                int c = rs.getInt("cnt");
                int a = rs.getInt("active");
                total += c;
                active24h += a;
                if ("TG".equals(p)) tg = c;
                if ("VK".equals(p)) vk = c;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error getting user stats", e);
            return "Ошибка получения статистики."; 
        }
        return String.format("📊 Всего пользователей: %d\n🔥 Активных за 24ч: %d\n✈️ Telegram: %d\n🔵 VK: %d", 
                total, active24h, tg, vk);
    }

    public String getAllUsersReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-12s | %-4s | %-20s | %-30s | %-19s\n", "ID", "PLAT", "USERNAME", "NAME", "LAST SEEN"));
        sb.append("-".repeat(95)).append("\n");

        String sql = "SELECT * FROM bot_users ORDER BY last_seen DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String username = rs.getString("username");
                String name = rs.getString("full_name");
                
                sb.append(String.format("%-12d | %-4s | %-20s | %-30s | %-19s\n",
                        rs.getLong("user_id"),
                        rs.getString("platform"),
                        username == null ? "-" : (username.length() > 20 ? username.substring(0, 17) + "..." : username),
                        name == null ? "-" : (name.length() > 30 ? name.substring(0, 27) + "..." : name),
                        rs.getString("last_seen")
                ));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error generating users report", e);
            return "Error generating report: " + e.getMessage();
        }
        return sb.toString();
    }

    public String getActionsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-19s | %-4s | %-12s | %s\n", "TIME", "PLAT", "USER ID", "ACTION"));
        sb.append("-".repeat(80)).append("\n");

        String sql = "SELECT * FROM user_actions ORDER BY created_at DESC LIMIT 2000";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                sb.append(String.format("%-19s | %-4s | %-12d | %s\n",
                        rs.getString("created_at"),
                        rs.getString("platform"),
                        rs.getLong("user_id"),
                        rs.getString("action")
                ));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error generating actions report", e);
            return "Error generating report: " + e.getMessage();
        }
        return sb.toString();
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
            logger.log(Level.SEVERE, "Error updating bells", e);
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

    public void subscribeUser(long chatId, Integer threadId, int type, String value, Platform platform) throws SQLException {
        int tid = (threadId == null) ? 0 : threadId;
        String plat = (platform == null) ? "TG" : platform.toString();

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

    public void unsubscribeUser(long chatId, Integer threadId, Platform platform) {
        int tid = (threadId == null) ? 0 : threadId;
        String plat = (platform == null) ? "TG" : platform.toString();

        String sql = "DELETE FROM users WHERE chat_id = ? AND message_thread_id = ? AND platform = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setInt(2, tid);
            ps.setString(3, plat);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                logger.info(String.format("User unsubscribed/removed: %d (Thread: %d, %s)", chatId, tid, plat));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error unsubscribing user", e);
        }
    }

    public String[] getUserSubscription(long chatId, Integer threadId, Platform platform) throws SQLException {
        int tid = (threadId == null) ? 0 : threadId;
        String plat = (platform == null) ? "TG" : platform.toString();

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
        String sql = "SELECT chat_id, message_thread_id, platform, sub_value FROM users WHERE sub_type = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, type);
            ResultSet rs = ps.executeQuery();
            String targetLower = targetValue.toLowerCase();

            while (rs.next()) {
                String subValue = rs.getString("sub_value");
                if (subValue == null) continue;

                boolean match;
                if (type == 1) { // Teacher: case-insensitive startswith
                    match = targetLower.startsWith(subValue.toLowerCase());
                } else { // Group: case-insensitive equals
                    match = targetLower.equals(subValue.toLowerCase());
                }

                if (match) {
                    long chatId = rs.getLong("chat_id");
                    int threadId = rs.getInt("message_thread_id");
                    String platform = rs.getString("platform");
                    subs.add(new Subscriber(chatId, threadId == 0 ? null : threadId, platform));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error getting subscribers", e);
        }
        return subs;
    }

    private String convertDateToRussianText(String inputDate) {
        if (inputDate == null) return null;
        Pattern p = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})$");
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

    private ScheduleMeta resolveTargetDate(String inputDate) {
        if (inputDate != null && !inputDate.isEmpty()) {
            String textDate = convertDateToRussianText(inputDate);
            String sql = "SELECT date_val, is_monday FROM schedules WHERE (date_val LIKE ? OR date_val LIKE ?) LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, "%" + inputDate + "%");
                ps.setString(2, "%" + (textDate != null ? textDate : inputDate) + "%");
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return new ScheduleMeta(rs.getString("date_val"), rs.getInt("is_monday") == 1);
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error resolving date", e);
            }
            return null;
        } else {
            String sql = "SELECT date_val, is_monday FROM schedules ORDER BY id DESC LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new ScheduleMeta(rs.getString("date_val"), rs.getInt("is_monday") == 1);
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Error fetching latest date", e);
            }
            return null;
        }
    }

    public String getScheduleByGroup(String groupName) {
        return getScheduleByGroup(groupName, null);
    }

    public String getScheduleByGroup(String groupName, String date) {
        StringBuilder sb = new StringBuilder();
        String sql;
        String textDate = convertDateToRussianText(date);
        String groupNameUpper = groupName.toUpperCase();

        if (date != null && !date.isEmpty()) {
            sql = "SELECT id, date_val, is_monday FROM schedules WHERE group_name LIKE ? AND (date_val LIKE ? OR date_val LIKE ?) ORDER BY id DESC LIMIT 1";
        } else {
            sql = "SELECT id, date_val, is_monday FROM schedules WHERE group_name LIKE ? ORDER BY id DESC LIMIT 1";
        }

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + groupNameUpper + "%");
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

                if (canteenTimes.containsKey(groupNameUpper)) {
                    sb.append("🍽️ <b>В столовую:</b> в ").append(canteenTimes.get(groupNameUpper)).append("\n\n");
                }

                appendLessons(sb, scheduleId, isMonday);
            } else {
                if (date != null && !date.isEmpty()) {
                    return "Расписание для группы '" + groupName + "' на дату '" + date + "' не найдено.";
                }
                return "Расписание для группы '" + groupName + "' не найдено.";
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Error fetching schedule by group", e);
            return "Ошибка БД: " + e.getMessage();
        }
        return sb.toString();
    }

    public String getScheduleByTeacher(String teacherName) {
        return getScheduleByTeacher(teacherName, null);
    }

    public String getScheduleByTeacher(String teacherName, String date) {
        ScheduleMeta meta = resolveTargetDate(date);
        if (meta == null) {
            return (date != null && !date.isEmpty()) ?
                    "Расписание на дату " + date + " не найдено в базе." :
                    "Расписание ещё не загружено.";
        }
        String targetDate = meta.dateVal();
        boolean isMonday = meta.isMonday();
        StringBuilder sb = new StringBuilder();

        String sql = """
        SELECT l.pair_number, l.subject, l.start_time, s.group_name, lt.name as teacher_name,
               GROUP_CONCAT(DISTINCT lr.room_number ORDER BY lr.room_number) as rooms
        FROM schedules s
        JOIN lessons l ON s.id = l.schedule_id
        JOIN lesson_teachers lt ON l.id = lt.lesson_id
        LEFT JOIN lesson_rooms lr ON l.id = lr.lesson_id
        WHERE s.date_val = ?
        GROUP BY l.pair_number, l.subject, l.start_time, s.group_name, lt.name
        ORDER BY l.pair_number
        """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, targetDate);
            ResultSet rs = ps.executeQuery();
            
            TreeMap<Integer, List<String>> lessonsByPair = new TreeMap<>();
            List<String> specialEvents = new ArrayList<>();
            String searchTeacherLower = teacherName.toLowerCase();

            while (rs.next()) {
                String dbTeacher = rs.getString("teacher_name");
                // Реализация нестрогого поиска (Case Insensitive) через Java
                if (dbTeacher == null || !dbTeacher.toLowerCase().contains(searchTeacherLower)) {
                    continue;
                }

                int pair = rs.getInt("pair_number");
                String subject = rs.getString("subject").trim();
                String group = rs.getString("group_name");
                String rooms = rs.getString("rooms");
                String roomStr = (rooms == null || rooms.isEmpty()) ? "" : " [Каб: " + rooms.replace(",", ", ") + "]";
                String line = subject + " — <b>" + group + "</b>" + roomStr;

                if (pair == -1) {
                    specialEvents.add(line);
                } else {
                    lessonsByPair.computeIfAbsent(pair, _ -> new ArrayList<>()).add(line);
                }
            }

            if (lessonsByPair.isEmpty() && specialEvents.isEmpty())
                return "На <b>" + targetDate + "</b> у преподавателя <b>" + teacherName + "</b> пар нет.";

            sb.append("🗓 Расписание:\n📅 <b>").append(targetDate).append("</b>\n");
            sb.append("Преподаватель: <b>").append(teacherName).append("</b>\n\n");

            if (!specialEvents.isEmpty()) {
                sb.append("📢 <b>События / Экзамены / Практика:</b>\n");
                for (String line : specialEvents) sb.append("  • ").append(line).append("\n");
                sb.append("\n");
            }
            if (!lessonsByPair.isEmpty()) {
                sb.append("🗓 <b>Расписание занятий:</b>\n");
                appendFormattedMap(sb, lessonsByPair, isMonday);
            }
            return sb.toString();

        } catch (SQLException e) { 
            logger.log(Level.SEVERE, "Error loading schedule by teacher", e); 
            return "Ошибка при загрузке расписания."; 
        }
    }

    public String getScheduleByRoom(int roomNumber) {
        return getScheduleByRoom(roomNumber, null);
    }

    public String getScheduleByRoom(int roomNumber, String date) {
        ScheduleMeta meta = resolveTargetDate(date);
        if (meta == null) {
            return (date != null && !date.isEmpty()) ?
                    "Расписание на дату " + date + " не найдено в базе." :
                    "Расписание ещё не загружено.";
        }
        String targetDate = meta.dateVal();
        boolean isMonday = meta.isMonday();
        StringBuilder sb = new StringBuilder();

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
            List<String> specialEvents = new ArrayList<>();

            while (rs.next()) {
                int pair = rs.getInt("pair_number");
                String subject = rs.getString("subject").trim();
                String group = rs.getString("group_name");
                String teachers = rs.getString("teachers");
                String teacherStr = (teachers == null || teachers.isEmpty()) ? "" : " (" + teachers.replace(",", ", ") + ")";
                String line = subject + " — <b>" + group + "</b>" + teacherStr;

                if (pair == -1) {
                    specialEvents.add(line);
                } else {
                    lessonsByPair.computeIfAbsent(pair, _ -> new ArrayList<>()).add(line);
                }
            }

            if (lessonsByPair.isEmpty() && specialEvents.isEmpty())
                return "На <b>" + targetDate + "</b> в кабинете <b>" + roomNumber + "</b> пар нет.";

            sb.append("🗓 Расписание:\n📅 <b>").append(targetDate).append("</b>\n");
            sb.append("Кабинет: <b>").append(roomNumber).append("</b>\n\n");

            if (!specialEvents.isEmpty()) {
                sb.append("📢 <b>События / Экзамены / Практика:</b>\n");
                for (String line : specialEvents) sb.append("  • ").append(line).append("\n");
                sb.append("\n");
            }
            if (!lessonsByPair.isEmpty()) {
                sb.append("🗓 <b>Расписание занятий:</b>\n");
                appendFormattedMap(sb, lessonsByPair, isMonday);
            }
            return sb.toString();
        } catch (SQLException e) { 
            logger.log(Level.SEVERE, "Error loading schedule by room", e); 
            return "Ошибка."; 
        }
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
        List<String> events = new ArrayList<>();
        List<String> pairs = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, scheduleId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (rs.getInt("pair_number") == -1) {
                    events.add(formatEventStr(rs));
                } else {
                    pairs.add(formatLessonStr(rs, isMonday));
                }
            }
        }

        if (!events.isEmpty()) {
            sb.append("📢 <b>События / Экзамены / Практика:</b>\n");
            for (String e : events) sb.append("  • ").append(e).append("\n\n");
        }

        if (!pairs.isEmpty()) {
            sb.append("🗓 <b>Расписание занятий:</b>\n");
            for (String p : pairs) sb.append(p).append("\n");
        }
    }

    private String formatEventStr(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(rs.getString("subject")).append("</b>");

        List<String> rooms = getRelated(id, "lesson_rooms", "room_number");
        if (!rooms.isEmpty()) sb.append(" [Каб: ").append(String.join(",", rooms)).append("]");

        List<String> teachers = getRelated(id, "lesson_teachers", "name");
        if (!teachers.isEmpty()) sb.append(" (").append(String.join(", ", teachers)).append(")");

        List<String> labels = getRelated(id, "lesson_labels", "label");
        if (!labels.isEmpty()) sb.append(" ").append(String.join(" ", labels));

        return sb.toString();
    }

    private String formatLessonStr(ResultSet rs, boolean isMonday) throws SQLException {
        long id = rs.getLong("id");
        int pair = rs.getInt("pair_number");
        String timeStr = getBellTime(pair, isMonday);

        String customTime = rs.getString("start_time");
        if (customTime != null && !customTime.isEmpty()) {
            timeStr = "Начало в " + customTime;
        } else if (timeStr == null) {
            timeStr = "";
        }

        StringBuilder sb = new StringBuilder();
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

        sb.append("\n");
        return sb.toString();
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
        SELECT DISTINCT lr.room_number 
        FROM lesson_rooms lr
        JOIN lessons l ON lr.lesson_id = l.id
        JOIN schedules s ON l.schedule_id = s.id
        ORDER BY s.id DESC LIMIT 100
        """;
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
        } catch (SQLException e) { 
            logger.log(Level.SEVERE, "Error getting unique subscribers", e); 
        }
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
        } catch (SQLException e) { 
            logger.log(Level.SEVERE, "Error fetching group signature", e); 
        }
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
                    // Сохраняем обычные пары
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
                    
                    // Сохраняем события/экзамены/практики под номером -1
                    for (Lesson event : daySchedule.getSpecialEvents()) {
                        psLesson.setLong(1, scheduleId);
                        psLesson.setInt(2, -1);
                        psLesson.setString(3, event.getSubject());
                        psLesson.setString(4, event.getStartTime());
                        psLesson.setString(5, event.getRaw());
                        psLesson.executeUpdate();

                        long eventId;
                        try (ResultSet rsEvent = psLesson.getGeneratedKeys()) {
                            if (rsEvent.next()) eventId = rsEvent.getLong(1);
                            else continue;
                        }
                        saveDetails(eventId, event);
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