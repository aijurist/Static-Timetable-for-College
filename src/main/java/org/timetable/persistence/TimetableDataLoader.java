package org.timetable.persistence;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.timetable.domain.*;
import org.timetable.config.TimetableConfig;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class TimetableDataLoader {
    private static final Logger LOGGER = Logger.getLogger(TimetableDataLoader.class.getName());
    private static final Logger LESSON_CREATION_LOGGER = Logger.getLogger("LessonCreation");

    private static final Map<String, String> DEPT_NAME_TO_CODE = Map.ofEntries(
            Map.entry("Computer Science & Design", "CSD"),
            Map.entry("Computer Science & Engineering", "CSE"),
            Map.entry("Computer Science & Engineering (Cyber Security)", "CSE-CS"),
            Map.entry("Computer Science & Business Systems", "CSBS"),
            Map.entry("Information Technology", "IT"),
            Map.entry("Artificial Intelligence & Machine Learning", "AIML"),
            Map.entry("AI & Data Science", "AIDS"),
            Map.entry("Electronics & Communication Engineering", "ECE"),
            Map.entry("Electrical & Electronics Engineering", "EEE"),
            Map.entry("Aeronautical Engineering", "AERO"),
            Map.entry("Automobile Engineering", "AUTO"),
            Map.entry("Mechatronics Engineering", "MCT"),
            Map.entry("Mechanical Engineering", "MECH"),
            Map.entry("Biotechnology", "BT"),
            Map.entry("Biomedical Engineering", "BME"),
            Map.entry("Robotics & Automation", "R&A"),
            Map.entry("Food Technology", "FT"),
            Map.entry("Civil Engineering", "CIVIL"),
            Map.entry("Chemical Engineering", "CHEM")
    );

    private static final Map<String, Map<String, Integer>> DEPARTMENT_DATA = Map.ofEntries(
            Map.entry("CSE-CS", Map.of("1", 3)),
            Map.entry("CSE", Map.of("1", 13)),
            Map.entry("CSBS", Map.of("1", 2)),
            Map.entry("CSD", Map.of("1", 1)),
            Map.entry("IT", Map.of("1", 5)),
            Map.entry("AIML", Map.of("1", 4)),
            Map.entry("AIDS", Map.of("1", 6)),
            Map.entry("ECE", Map.of("1", 8)),
            Map.entry("EEE", Map.of("1", 2)),
            Map.entry("AERO", Map.of("1", 1)),
            Map.entry("AUTO", Map.of("1", 1)),
            Map.entry("MCT", Map.of("1", 2)),
            Map.entry("MECH", Map.of("1", 2)),
            Map.entry("BT", Map.of("1", 3)),
            Map.entry("BME", Map.of("1", 2)),
            Map.entry("R&A", Map.of("1", 1)),
            Map.entry("FT", Map.of("1", 1)),
            Map.entry("CIVIL", Map.of("1", 1)),
            Map.entry("CHEM", Map.of("1", 1))
    );

    private static class RawDataRecord {
        final String courseId, courseCode, courseName, courseDept, courseType, teacherId, staffCode, firstName, lastName, teacherEmail, labType;
        final int semester, lectureHours, practicalHours, tutorialHours, credits;

        RawDataRecord(CSVRecord record) {
            this.courseId = record.get("course_id");
            this.courseCode = record.get("course_code");
            this.courseName = record.get("course_name");
            this.courseDept = record.get("course_dept");
            this.courseType = record.get("course_type");
            this.teacherId = record.get("teacher_id");
            this.staffCode = record.get("staff_code");
            this.firstName = record.get("first_name");
            this.lastName = record.get("last_name");
            this.teacherEmail = record.get("teacher_email");
            this.semester = parseIntSafely(record.get("semester"), 0);
            this.lectureHours = parseIntSafely(record.get("lecture_hours"), 0);
            this.practicalHours = parseIntSafely(record.get("practical_hours"), 0);
            this.tutorialHours = parseIntSafely(record.get("tutorial_hours"), 0);
            this.credits = parseIntSafely(record.get("credits"), 0);
            
            // Read lab_type field if available
            String labTypeValue = null;
            try {
                labTypeValue = record.get("lab_type");
                if (labTypeValue != null && labTypeValue.trim().isEmpty()) {
                    labTypeValue = null;
                }
            } catch (IllegalArgumentException e) {
                // lab_type column doesn't exist, that's fine
                labTypeValue = null;
            }
            this.labType = labTypeValue;
        }
    }

    static {
        try {
            // Setup main logger
            FileHandler fh = new FileHandler("timetable_loader.log");
            fh.setFormatter(new SimpleFormatter());
            LOGGER.addHandler(fh);
            LOGGER.setLevel(Level.INFO);
            
            // Setup lesson creation logger
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            FileHandler lessonHandler = new FileHandler("lesson_created_" + timestamp + ".log");
            lessonHandler.setFormatter(new SimpleFormatter());
            LESSON_CREATION_LOGGER.addHandler(lessonHandler);
            LESSON_CREATION_LOGGER.setLevel(Level.INFO);
            LESSON_CREATION_LOGGER.setUseParentHandlers(false); // Don't duplicate to console
            
            LOGGER.info("Logging system initialized - Main log: timetable_loader.log, Lesson creation: lesson_created_" + timestamp + ".log");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static TimetableProblem loadProblem(String coursesFile, String roomsDir) {
        try {
            List<RawDataRecord> rawData;
            if (coursesFile.contains(",")) {
                // Multiple files separated by comma
                String[] files = coursesFile.split(",");
                rawData = new ArrayList<>();
                for (String file : files) {
                    rawData.addAll(loadRawData(file.trim()));
                }
            } else {
                rawData = loadRawData(coursesFile);
            }
            
                
            
            Map<String, Teacher> teachers = createTeachers(rawData);
            Map<String, Course> courses = createCourses(rawData);
            List<StudentGroup> studentGroups = createStudentGroups(rawData);
            List<Room> rooms = loadRooms(roomsDir);
            List<TimeSlot> timeSlots = createTimeSlots();
            List<Lesson> lessons = createLessons(rawData, teachers, courses, studentGroups);

            return new TimetableProblem(
                    "University Timetable",
                    timeSlots,
                    rooms,
                    new ArrayList<>(teachers.values()),
                    new ArrayList<>(courses.values()),
                    studentGroups,
                    lessons
            );

        } catch (IOException e) {
            throw new RuntimeException("Failed to load timetable data", e);
        }
    }

    private static List<RawDataRecord> loadRawData(String filePath) throws IOException {
        List<RawDataRecord> rawData = new ArrayList<>();
        Set<Integer> validSemesters = Set.of(3, 5, 7);
        LOGGER.info("Loading raw data from: " + filePath);

        try (Reader reader = new FileReader(filePath);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader())) {

            int totalRecords = 0;
            int validRecords = 0;

            for (CSVRecord record : csvParser) {
                totalRecords++;
                if (validSemesters.contains(parseIntSafely(record.get("semester"), 0))) {
                    if (record.get("teacher_id") != null && !record.get("teacher_id").isEmpty() && !"Unknown".equalsIgnoreCase(record.get("teacher_id"))) {
                        rawData.add(new RawDataRecord(record));
                        validRecords++;
                    } else {
                        LOGGER.warning("Skipping record due to missing/invalid teacher_id: " + record);
                    }
                }
            }
            LOGGER.info(String.format("Data loading summary: %d total records, %d valid records", totalRecords, validRecords));
        }
        return rawData;
    }

    private static Map<String, Teacher> createTeachers(List<RawDataRecord> rawData) {
        Map<String, Teacher> teachers = new HashMap<>();
        LOGGER.info("Creating teacher records...");

        for (RawDataRecord record : rawData) {
            teachers.computeIfAbsent(record.teacherId, id -> {
                String name = (record.firstName + " " + record.lastName).trim();
                LOGGER.info("Creating teacher: " + name + " (ID: " + id + ")");
                return new Teacher(id, name, record.teacherEmail, TimetableConfig.MAX_TEACHER_HOURS);
            });
        }
        LOGGER.info("Created " + teachers.size() + " unique teachers");
        return teachers;
    }

    private static Map<String, Course> createCourses(List<RawDataRecord> rawData) {
        Map<String, Course> courses = new HashMap<>();
        LOGGER.info("Creating course records...");

        for (RawDataRecord record : rawData) {
            courses.computeIfAbsent(record.courseId, id -> {
                String deptCode = DEPT_NAME_TO_CODE.getOrDefault(record.courseDept, record.courseDept);
                CourseType type = record.practicalHours > 0 ? CourseType.LAB : CourseType.THEORY;
                LOGGER.info(String.format("Creating course: %s - %s (Dept: %s, Semester: %d, Lab Type: %s)",
                        record.courseCode, record.courseName, deptCode, record.semester, record.labType));
                return new Course(id, record.courseCode, record.courseName, deptCode, type,
                        record.lectureHours, record.tutorialHours, record.practicalHours, record.credits, record.labType);
            });
        }
        LOGGER.info("Created " + courses.size() + " unique courses");
        return courses;
    }

    private static List<StudentGroup> createStudentGroups(List<RawDataRecord> rawData) {
        List<StudentGroup> studentGroups = new ArrayList<>();
        int groupCounter = 1;
        LOGGER.info("Creating student groups...");

        Map<Integer, Integer> semesterToYear = Map.of(3, 2, 5, 3, 7, 4);

        Set<Map.Entry<String, Integer>> deptYearPairs = rawData.stream()
                .map(r -> new AbstractMap.SimpleEntry<>(
                        DEPT_NAME_TO_CODE.getOrDefault(r.courseDept, r.courseDept),
                        semesterToYear.get(r.semester)
                ))
                .collect(Collectors.toSet());

        for (Map.Entry<String, Integer> entry : deptYearPairs) {
            String dept = entry.getKey();
            Integer year = entry.getValue();
            
            // Map full department name to short code
            String deptCode = DEPT_NAME_TO_CODE.getOrDefault(dept, dept);
            
            Map<String, Integer> yearToSections = DEPARTMENT_DATA.get(deptCode);
            if (yearToSections == null) {
                LOGGER.info("Creating 0 sections for " + dept + " Year " + year + " (mapped to " + deptCode + ")");
                continue;
            }
            
            Integer sections = yearToSections.get(year.toString());
            if (sections == null || sections == 0) {
                LOGGER.info("Creating 0 sections for " + dept + " Year " + year + " (mapped to " + deptCode + ")");
                continue;
            }
            
            LOGGER.info("Creating " + sections + " sections for " + dept + " Year " + year + " (mapped to " + deptCode + ")");
            
            for (int i = 1; i <= sections; i++) {
                String groupName = dept + " " + year + "." + i;
                studentGroups.add(new StudentGroup(
                        String.valueOf(groupCounter++),
                        groupName,
                        "AUTO".equals(deptCode) ? 35 : TimetableConfig.CLASS_STRENGTH,
                        dept,
                        year
                ));
            }
        }
        LOGGER.info("Created " + studentGroups.size() + " total student groups");
        return studentGroups;
    }

    private static List<Lesson> createLessons(List<RawDataRecord> rawData, Map<String, Teacher> teachers, Map<String, Course> courses, List<StudentGroup> studentGroups) {
        List<Lesson> lessons = new ArrayList<>();
        long lessonIdCounter = 1;

        LOGGER.info("Creating lessons and assigning teachers to sections...");
        LOGGER.info("=== LESSON CREATION PROCESS STARTED ===");
        
        LESSON_CREATION_LOGGER.info("=== LESSON CREATION DETAILED LOG STARTED ===");
        LESSON_CREATION_LOGGER.info("Timestamp: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // First group by course department
        Map<String, List<RawDataRecord>> deptToRecords = rawData.stream()
            .collect(Collectors.groupingBy(r -> DEPT_NAME_TO_CODE.getOrDefault(r.courseDept, r.courseDept)));

        // For each department
        for (Map.Entry<String, List<RawDataRecord>> deptEntry : deptToRecords.entrySet()) {
            String dept = deptEntry.getKey();
            List<RawDataRecord> deptRecords = deptEntry.getValue();

            LOGGER.info(String.format("Processing department: %s with %d course records", dept, deptRecords.size()));
            LESSON_CREATION_LOGGER.info(String.format("Processing department: %s with %d course records", dept, deptRecords.size()));

            // Group by semester and year
            Map<Integer, List<RawDataRecord>> semesterToRecords = deptRecords.stream()
                .collect(Collectors.groupingBy(r -> r.semester));

            // For each semester
            for (Map.Entry<Integer, List<RawDataRecord>> semesterEntry : semesterToRecords.entrySet()) {
                int semester = semesterEntry.getKey();
                List<RawDataRecord> semesterRecords = semesterEntry.getValue();
                int year = semester == 3 ? 2 : semester == 5 ? 3 : 4;

                LOGGER.info(String.format("Processing semester %d (Year %d) for department %s with %d course records", 
                        semester, year, dept, semesterRecords.size()));

                // Get all student groups for this department and year
                List<StudentGroup> relevantGroups = studentGroups.stream()
                    .filter(g -> g.getDepartment().equals(dept) && g.getYear() == year)
                    .collect(Collectors.toList());

                LOGGER.info(String.format("Found %d student groups for %s Year %d: %s", 
                        relevantGroups.size(), dept, year, 
                        relevantGroups.stream().map(StudentGroup::getName).collect(Collectors.joining(", "))));

                // Group courses by their ID to ensure we have all courses for the semester
                Map<String, List<RawDataRecord>> courseToRecords = semesterRecords.stream()
                    .collect(Collectors.groupingBy(r -> r.courseId));

                LOGGER.info(String.format("Processing %d unique courses for %s Year %d", 
                        courseToRecords.size(), dept, year));

                // For each course in this semester
                for (Map.Entry<String, List<RawDataRecord>> courseEntry : courseToRecords.entrySet()) {
                    String courseId = courseEntry.getKey();
                    List<RawDataRecord> courseRecords = courseEntry.getValue();
                    Course course = courses.get(courseId);

                    if (course == null) {
                        LOGGER.warning("Skipping lesson creation for course " + courseId + " due to missing course.");
                        continue;
                    }

                    LOGGER.info(String.format("=== PROCESSING COURSE: %s (%s) ===", 
                            course.getCode(), course.getName()));
                    LOGGER.info(String.format("Course hours - Lecture: %d, Tutorial: %d, Practical: %d", 
                            course.getLectureHours(), course.getTutorialHours(), course.getPracticalHours()));
                    
                    LESSON_CREATION_LOGGER.info(String.format("=== PROCESSING COURSE: %s (%s) ===", 
                            course.getCode(), course.getName()));
                    LESSON_CREATION_LOGGER.info(String.format("Course hours - Lecture: %d, Tutorial: %d, Practical: %d", 
                            course.getLectureHours(), course.getTutorialHours(), course.getPracticalHours()));

                    // === CHECK: Enough teachers for all sections ===
                    List<String> availableTeacherIds = courseRecords.stream()
                        .map(r -> r.teacherId)
                        .distinct()
                        .collect(Collectors.toList());
                    int numSections = relevantGroups.size();
                    int numTeachers = availableTeacherIds.size();
                    if (numTeachers < numSections) {
                        throw new RuntimeException(String.format(
                            "Not enough teachers for course %s (%s) in %s Year %d: %d teachers for %d sections.",
                            course.getCode(), course.getName(), dept, year, numTeachers, numSections
                        ));
                    }
                    // Get all teachers who can teach this course (from this department)
                    // (already in availableTeacherIds)

                    if (availableTeacherIds.isEmpty()) {
                        LOGGER.warning("No teachers available for course " + courseId);
                        continue;
                    }

                    LOGGER.info(String.format("Available teachers for %s: %d teachers", 
                            course.getCode(), availableTeacherIds.size()));
                    for (String teacherId : availableTeacherIds) {
                        Teacher teacher = teachers.get(teacherId);
                        if (teacher != null) {
                            LOGGER.info(String.format("  - %s (ID: %s)", teacher.getName(), teacherId));
                        }
                    }

                    int totalLessonsForCourse = 0;
                    int totalGroupsProcessed = 0;

                    // For each student group in this department and year
                    for (StudentGroup group : relevantGroups) {
                        LOGGER.info(String.format("--- Creating lessons for group: %s (Size: %d) ---", 
                                group.getName(), group.getSize()));

                        // Assign a teacher to this group's course
                        int groupIndex = Integer.parseInt(group.getId()) - 1;
                        Teacher teacher = teachers.get(availableTeacherIds.get(groupIndex % availableTeacherIds.size()));

                        LOGGER.info(String.format("Assigned teacher: %s to group %s for course %s", 
                                teacher.getName(), group.getName(), course.getCode()));
                                
                        LESSON_CREATION_LOGGER.info(String.format("--- Creating lessons for group: %s (Size: %d) ---", 
                                group.getName(), group.getSize()));
                        LESSON_CREATION_LOGGER.info(String.format("Assigned teacher: %s to group %s for course %s", 
                                teacher.getName(), group.getName(), course.getCode()));

                        int lessonsForThisGroup = 0;

                        // Create lecture lessons
                        if (course.getLectureHours() > 0) {
                            LOGGER.info(String.format("Creating %d lecture lessons...", course.getLectureHours()));
                            LESSON_CREATION_LOGGER.info(String.format("Creating %d lecture lessons for %s - %s", 
                                    course.getLectureHours(), course.getCode(), group.getName()));
                        for (int i = 0; i < course.getLectureHours(); i++) {
                                String lessonId = "L-" + lessonIdCounter++;
                                lessons.add(new Lesson(lessonId, teacher, course, group, "lecture", null));
                                lessonsForThisGroup++;
                                LESSON_CREATION_LOGGER.info(String.format("Created LECTURE lesson %s: Teacher=%s, Course=%s, Group=%s", 
                                        lessonId, teacher.getName(), course.getCode(), group.getName()));
                            }
                        }

                        // Create tutorial lessons
                        if (course.getTutorialHours() > 0) {
                            LOGGER.info(String.format("Creating %d tutorial lessons...", course.getTutorialHours()));
                            LESSON_CREATION_LOGGER.info(String.format("Creating %d tutorial lessons for %s - %s", 
                                    course.getTutorialHours(), course.getCode(), group.getName()));
                        for (int i = 0; i < course.getTutorialHours(); i++) {
                                String lessonId = "L-" + lessonIdCounter++;
                                lessons.add(new Lesson(lessonId, teacher, course, group, "tutorial", null));
                                lessonsForThisGroup++;
                                LESSON_CREATION_LOGGER.info(String.format("Created TUTORIAL lesson %s: Teacher=%s, Course=%s, Group=%s", 
                                        lessonId, teacher.getName(), course.getCode(), group.getName()));
                            }
                        }

                        // Create lab lessons if needed
                        if (course.getPracticalHours() > 0) {
                            int labSessions = course.getPracticalHours() / 2; // Each session is 2 hours
                            LOGGER.info(String.format("Processing lab sessions: %d practical hours = %d lab sessions", 
                                    course.getPracticalHours(), labSessions));
                            LESSON_CREATION_LOGGER.info(String.format("Processing lab sessions for %s - %s: %d practical hours = %d lab sessions", 
                                    course.getCode(), group.getName(), course.getPracticalHours(), labSessions));

                            // BATCHING DECISION LOGIC:
                            // 1. Certain courses MUST use full 70-capacity labs (no batching)
                            // 2. Automobile dept has only 35 students and 35-capacity labs (no batching needed)
                            // 3. All other departments with >35 students need batching into B1/B2
                            
                            final java.util.Set<String> UNBATCHED_COURSES = java.util.Set.of(
                                    "CD23321",  // Python Programming for Design - needs full class
                                    "CS19P23",  // Advanced Application Development with Oracle APEX
                                    "CS19P21",  // Advanced Robotic Process Automation
                                    "PH23131",  // Physics Lab I
                                    "PH23132",  // Physics Lab II  
                                    "PH23231",  // Physics Lab III
                                    "PH23233"   // Physics Lab IV
                            );

                            // Get course department info
                            String courseDept = courseRecords.get(0).courseDept; // Department from CSV
                            String deptCode = DEPT_NAME_TO_CODE.getOrDefault(courseDept, courseDept);
                            
                            // Check batching criteria
                            boolean isUnbatchedCourse = UNBATCHED_COURSES.contains(course.getCode());
                            boolean isAutoDept = "Automobile Engineering".equals(courseDept) || "AUTO".equals(deptCode);
                            
                            // DECISION: Don't batch if EITHER condition is true
                            boolean shouldNotBatch = isUnbatchedCourse || isAutoDept;
                            boolean needsBatching = !shouldNotBatch;
                            
                            // Enhanced logging for clarity
                            LOGGER.info(String.format("=== BATCHING DECISION for %s ===", course.getCode()));
                            LOGGER.info(String.format("Course: %s, Department: %s (%s)", course.getCode(), courseDept, deptCode));
                            LOGGER.info(String.format("Group: %s, Size: %d", group.getName(), group.getSize()));
                            LOGGER.info(String.format("Is UNBATCHED_COURSE: %b", isUnbatchedCourse));
                            LOGGER.info(String.format("Is AUTO department: %b", isAutoDept));
                            LOGGER.info(String.format("Should NOT batch: %b (unbatched course OR auto dept)", shouldNotBatch));
                            LOGGER.info(String.format("FINAL DECISION - Needs batching: %b", needsBatching));
                            
                            LESSON_CREATION_LOGGER.info(String.format("BATCHING DECISION: %s - %s | Course in UNBATCHED: %b | AUTO dept: %b | Will batch: %b", 
                                    course.getCode(), group.getName(), isUnbatchedCourse, isAutoDept, needsBatching));
                             
                            if (needsBatching) {
                                // BATCHING: Split into B1 and B2 (each batch gets full practical hours)
                                LOGGER.info(String.format("✅ BATCHING: %s (%s) will be split into B1 & B2", course.getCode(), group.getName()));
                                LOGGER.info(String.format("Creating %d total lab sessions: B1 gets %d sessions, B2 gets %d sessions", 
                                        labSessions * 2, labSessions, labSessions));
                                
                                // Batch B1 gets full practical hours (all required sessions)
                                for (int i = 0; i < labSessions; i++) {
                                    String lessonId = "L-" + lessonIdCounter++;
                                    lessons.add(new Lesson(lessonId, teacher, course, group, "lab", "B1"));
                                    lessonsForThisGroup++;
                                    LESSON_CREATION_LOGGER.info(String.format("Created LAB lesson %s: Teacher=%s, Course=%s, Group=%s, Batch=B1", 
                                            lessonId, teacher.getName(), course.getCode(), group.getName()));
                                }
                                
                                // Batch B2 gets full practical hours (all required sessions)
                                for (int i = 0; i < labSessions; i++) {
                                    String lessonId = "L-" + lessonIdCounter++;
                                    lessons.add(new Lesson(lessonId, teacher, course, group, "lab", "B2"));
                                    lessonsForThisGroup++;
                                    LESSON_CREATION_LOGGER.info(String.format("Created LAB lesson %s: Teacher=%s, Course=%s, Group=%s, Batch=B2", 
                                            lessonId, teacher.getName(), course.getCode(), group.getName()));
                                }

                                LOGGER.info(String.format("Created %d B1 lab sessions and %d B2 lab sessions", 
                                        labSessions, labSessions));
                            } else {
                                // NO BATCHING: Use full group (due to UNBATCHED_COURSE or AUTO dept)
                                String reason = isUnbatchedCourse ? "UNBATCHED_COURSE (needs 70-capacity lab)" : "AUTO dept (35 students, 35-capacity labs)";
                                LOGGER.info(String.format("🚫 NO BATCHING: %s (%s) - Reason: %s", course.getCode(), group.getName(), reason));
                                LOGGER.info(String.format("Creating %d lab sessions for full group", labSessions));
                                
                                for (int i = 0; i < labSessions; i++) {
                                    String lessonId = "L-" + lessonIdCounter++;
                                    lessons.add(new Lesson(lessonId, teacher, course, group, "lab", null));
                                    lessonsForThisGroup++;
                                    LESSON_CREATION_LOGGER.info(String.format("Created LAB lesson %s: Teacher=%s, Course=%s, Group=%s (No batching)", 
                                            lessonId, teacher.getName(), course.getCode(), group.getName()));
                                }

                                LOGGER.info(String.format("✅ Created %d lab sessions (full group, no batching)", labSessions));
                            }
                        }

                        totalLessonsForCourse += lessonsForThisGroup;
                        totalGroupsProcessed++;

                        LOGGER.info(String.format("*** GROUP SUMMARY: Created %d total lessons for group %s ***", 
                                lessonsForThisGroup, group.getName()));
                        LESSON_CREATION_LOGGER.info(String.format("*** GROUP SUMMARY: Created %d total lessons for group %s ***", 
                                lessonsForThisGroup, group.getName()));
                    }

                    LOGGER.info(String.format("=== COURSE SUMMARY: %s ===", course.getCode()));
                    LOGGER.info(String.format("Total groups processed: %d", totalGroupsProcessed));
                    LOGGER.info(String.format("Total lessons created for course: %d", totalLessonsForCourse));
                    LOGGER.info(String.format("Average lessons per group: %.1f", 
                            totalGroupsProcessed > 0 ? (double) totalLessonsForCourse / totalGroupsProcessed : 0));
                            
                    LESSON_CREATION_LOGGER.info(String.format("=== COURSE SUMMARY: %s ===", course.getCode()));
                    LESSON_CREATION_LOGGER.info(String.format("Total groups processed: %d", totalGroupsProcessed));
                    LESSON_CREATION_LOGGER.info(String.format("Total lessons created for course: %d", totalLessonsForCourse));
                    LESSON_CREATION_LOGGER.info(String.format("Average lessons per group: %.1f", 
                            totalGroupsProcessed > 0 ? (double) totalLessonsForCourse / totalGroupsProcessed : 0));
                }
            }
        }

        LOGGER.info("=== LESSON CREATION PROCESS COMPLETED ===");
        LOGGER.info("Created " + lessons.size() + " total lessons");
        
        // Log final statistics
        long batchedLessons = lessons.stream()
            .filter(l -> l.getBatch() != null)
            .count();
        
        LOGGER.info("=== FINAL LESSON STATISTICS ===");
        LOGGER.info(String.format("Total lessons created: %d", lessons.size()));
        LOGGER.info(String.format("Batched lessons (B1/B2): %d", batchedLessons));
        LOGGER.info(String.format("Non-batched lessons: %d", lessons.size() - batchedLessons));
        
        LESSON_CREATION_LOGGER.info("=== LESSON CREATION PROCESS COMPLETED ===");
        LESSON_CREATION_LOGGER.info("=== FINAL LESSON STATISTICS ===");
        LESSON_CREATION_LOGGER.info(String.format("Total lessons created: %d", lessons.size()));
        LESSON_CREATION_LOGGER.info(String.format("Batched lessons (B1/B2): %d", batchedLessons));
        LESSON_CREATION_LOGGER.info(String.format("Non-batched lessons: %d", lessons.size() - batchedLessons));
        LESSON_CREATION_LOGGER.info("=== LESSON CREATION DETAILED LOG COMPLETED ===");

        return lessons;
    }

    private static List<TimeSlot> createTimeSlots() {
        List<TimeSlot> timeSlots = new ArrayList<>();
        int idCounter = 1;
        DayOfWeek[] days = {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY};

        LOGGER.info("Creating theory and lab timeslots...");

        for (DayOfWeek day : days) {
            for (LocalTime[] slot : TimetableConfig.THEORY_TIME_SLOTS) {
                timeSlots.add(new TimeSlot("TS-" + idCounter++, day, slot[0], slot[1], false)); // isLab = false
            }
            for (LocalTime[] slot : TimetableConfig.LAB_TIME_SLOTS) {
                timeSlots.add(new TimeSlot("TS-LAB-" + idCounter++, day, slot[0], slot[1], true)); // isLab = true
            }
        }
        LOGGER.info("Created " + timeSlots.size() + " total timeslots per week.");
        return timeSlots;
    }

    public static List<Room> loadRooms(String roomsDir) throws IOException {
        List<Room> rooms = new ArrayList<>();
        File classroomDir = new File(roomsDir, "classroom");
        if (classroomDir.exists() && classroomDir.isDirectory()) {
            File[] classroomFiles = classroomDir.listFiles((dir, name) -> name.endsWith(".csv"));
            if (classroomFiles != null) {
                for (File file : classroomFiles) {
                    // Load all classroom files
                    rooms.addAll(loadRoomsFromFile(file.getPath(), false));
                }
            }
        }
        File labsDir = new File(roomsDir, "labs");
        if (labsDir.exists() && labsDir.isDirectory()) {
            File[] labFiles = labsDir.listFiles((dir, name) -> name.endsWith(".csv"));
            if (labFiles != null) {
                for (File file : labFiles) {
                    rooms.addAll(loadRoomsFromFile(file.getPath(), true));
                }
            }
        }
        // Sort so D Block rooms come first (for assignment preference)
        rooms.sort((a, b) -> {
            boolean aD = "D Block".equalsIgnoreCase(a.getBlock());
            boolean bD = "D Block".equalsIgnoreCase(b.getBlock());
            if (aD && !bD) return -1;
            if (!aD && bD) return 1;
            return 0;
        });
        return rooms;
    }

    private static List<Room> loadRoomsFromFile(String filePath, boolean isLab) throws IOException {
        List<Room> rooms = new ArrayList<>();
        try (Reader reader = new FileReader(filePath);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreHeaderCase(true).withTrim())) {
            for (CSVRecord record : csvParser) {
                try {
                    String name = record.get("room_number");
                    String block = record.get("block");
                    String description = record.get("description");
                    String uniqueId = block.replaceAll("\\s+", "") + "_" + name;
                    int capacity = parseIntSafely(record.get("room_max_cap"), 70);
                    
                    // Read lab_type if available
                    String labType = null;
                    try {
                        labType = record.get("lab_type");
                        if (labType != null && labType.trim().isEmpty()) {
                            labType = null;
                        }
                    } catch (IllegalArgumentException e) {
                        // lab_type column doesn't exist, that's fine
                        labType = null;
                    }
                    
                    rooms.add(new Room(uniqueId, name, block, description, capacity, isLab, labType));
                } catch (IllegalArgumentException e) {
                    System.err.println("Skipping record in " + filePath + " due to missing fields: " + e.getMessage());
                }
            }
        }
        return rooms;
    }

    private static int parseIntSafely(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}