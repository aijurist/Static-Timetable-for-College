package org.timetable.config;

import java.time.LocalTime;
import java.util.List;

public class TimetableConfig {
    
    public static final List<String> DAYS = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday");

    // Theory time slots
    public static final List<LocalTime[]> THEORY_TIME_SLOTS = List.of(
            new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(8, 50)},
            new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(9, 50)},
            new LocalTime[]{LocalTime.of(10, 0), LocalTime.of(10, 50)},
            new LocalTime[]{LocalTime.of(11, 0), LocalTime.of(11, 50)},
            new LocalTime[]{LocalTime.of(12, 0), LocalTime.of(12, 50)},
            new LocalTime[]{LocalTime.of(13, 0), LocalTime.of(13, 50)},
            new LocalTime[]{LocalTime.of(14, 0), LocalTime.of(14, 50)}
    );

    // Lab time slots
    public static final List<LocalTime[]> LAB_TIME_SLOTS = List.of(
            new LocalTime[]{LocalTime.of(8, 0), LocalTime.of(9, 40)},
            new LocalTime[]{LocalTime.of(10, 00), LocalTime.of(11, 40)},
            new LocalTime[]{LocalTime.of(11, 50), LocalTime.of(13, 20)},
            new LocalTime[]{LocalTime.of(13, 20), LocalTime.of(15, 00)}
    );

    // Constants
    public static final int MAX_TEACHER_HOURS = 21;
    public static final int CLASS_STRENGTH = 70;
    public static final int LAB_BATCH_SIZE = 35;
    // If a lab room can seat the entire class strength, both B1 and B2 can be combined.
    public static final int FULL_CLASS_LAB_THRESHOLD = CLASS_STRENGTH;
    public static final int LAB_DURATION_IN_MINUTES = 100; 

}