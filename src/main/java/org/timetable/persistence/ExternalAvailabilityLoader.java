package org.timetable.persistence;

import org.timetable.domain.Room;
import org.timetable.domain.Teacher;
import org.timetable.domain.TimeSlot;

import java.io.*;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExternalAvailabilityLoader {
    public static class UnavailableSlot {
        public final String id; // teacher or room id
        public final DayOfWeek day;
        public final LocalTime start;
        public final LocalTime end;
        public UnavailableSlot(String id, DayOfWeek day, LocalTime start, LocalTime end) {
            this.id = id;
            this.day = day;
            this.start = start;
            this.end = end;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UnavailableSlot that = (UnavailableSlot) o;
            return Objects.equals(id, that.id) && day == that.day && Objects.equals(start, that.start) && Objects.equals(end, that.end);
        }
        @Override
        public int hashCode() {
            return Objects.hash(id, day, start, end);
        }
    }

    public static Set<UnavailableSlot> teacherUnavailable = new HashSet<>();
    public static Set<UnavailableSlot> roomUnavailable = new HashSet<>();

    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2}) ?- ?(\\d{1,2}):(\\d{2})");

    public static void loadAll(String teacherMatrixRoot, String roomMatrixRoot) throws IOException {
        teacherUnavailable.clear();
        roomUnavailable.clear();
        loadMatrix(teacherMatrixRoot, teacherUnavailable, true);
        loadMatrix(roomMatrixRoot, roomUnavailable, false);
    }

    private static void loadMatrix(String root, Set<UnavailableSlot> target, boolean isTeacher) throws IOException {
        Files.walk(Paths.get(root))
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".csv"))
                .forEach(path -> {
                    try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
                        String line = br.readLine(); // header
                        if (line == null) return;
                        while ((line = br.readLine()) != null) {
                            String[] parts = line.split(",");
                            if (parts.length < 2) continue;
                            String dayStr = parts[0].trim().toLowerCase();
                            String timeRange = parts[1].trim();
                            String id = isTeacher ? (parts.length > 2 ? parts[2].trim() : path.getParent().getFileName().toString())
                                                 : (parts.length > 11 ? parts[11].trim() : path.getParent().getFileName().toString());
                            DayOfWeek day = parseDay(dayStr);
                            LocalTime[] times = parseTimeRange(timeRange);
                            if (day != null && times != null) {
                                target.add(new UnavailableSlot(id, day, times[0], times[1]));
                            }
                        }
                    } catch (Exception e) {
                        // Ignore file errors
                    }
                });
    }

    private static DayOfWeek parseDay(String day) {
        switch (day) {
            case "mon": case "monday": return DayOfWeek.MONDAY;
            case "tue": case "tuesday": return DayOfWeek.TUESDAY;
            case "wed": case "wednesday": return DayOfWeek.WEDNESDAY;
            case "thu": case "thur": case "thursday": return DayOfWeek.THURSDAY;
            case "fri": case "friday": return DayOfWeek.FRIDAY;
            case "sat": case "saturday": return DayOfWeek.SATURDAY;
            default: return null;
        }
    }

    private static LocalTime[] parseTimeRange(String range) {
        Matcher m = TIME_RANGE_PATTERN.matcher(range);
        if (m.find()) {
            int h1 = Integer.parseInt(m.group(1));
            int m1 = Integer.parseInt(m.group(2));
            int h2 = Integer.parseInt(m.group(3));
            int m2 = Integer.parseInt(m.group(4));
            return new LocalTime[] { LocalTime.of(h1, m1), LocalTime.of(h2, m2) };
        }
        return null;
    }
} 