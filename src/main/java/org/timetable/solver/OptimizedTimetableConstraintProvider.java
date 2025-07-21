package org.timetable.solver;

import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;
import org.optaplanner.core.api.score.stream.Joiners;
import org.timetable.domain.Lesson;
import org.timetable.domain.Teacher;
import org.timetable.domain.TimeSlot;
import org.timetable.config.TimetableConfig;
import org.timetable.config.DepartmentBlockConfig;
import org.timetable.config.DepartmentWorkdayConfig;
import org.timetable.persistence.CourseLabMappingUtil;
import static org.optaplanner.core.api.score.stream.ConstraintCollectors.*;
import static org.optaplanner.core.api.score.stream.Joiners.*;
import org.optaplanner.core.api.score.stream.uni.UniConstraintStream;
import org.timetable.domain.Room;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import static org.optaplanner.core.api.score.stream.ConstraintCollectors.*;

/**
 * Optimized constraint provider with enhanced scheduling logic
 * Focuses on core timetabling constraints without A105-specific requirements
 */
public class OptimizedTimetableConstraintProvider implements ConstraintProvider {
    private final ConstraintMonitor monitor = ConstraintMonitor.getInstance();

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        monitor.newEvaluation(); // Track each constraint evaluation
        return new Constraint[]{
                // ########################################################################
                // TIER 1: CRITICAL HARD CONSTRAINTS (Must be satisfied for feasibility)
                // ########################################################################
                
                // Time and room slot enforcement - highest priority
                labInLabSlot(constraintFactory),
                theoryInTheorySlot(constraintFactory),
                labInLabRoom(constraintFactory), 
                theoryInTheoryRoom(constraintFactory),
                
                // Room capacity and basic conflicts
                roomCapacity(constraintFactory),
                roomConflict(constraintFactory),
                teacherConflict(constraintFactory),
                studentGroupConflict(constraintFactory),

                // === NEW: External unavailability constraints ===
                teacherExternalUnavailability(constraintFactory),
                roomExternalUnavailability(constraintFactory),
                
                // Core lab mapping MUST be enforced (highest priority hard constraint)
                strictCoreLabMappingEnforcement(constraintFactory),
                
                // Lab type enforcement for mapped courses (very important)
                courseLabMustMatchMapping(constraintFactory),
                
                // Department workday policies
                departmentOutsideAllowedDays(constraintFactory),
                
                // Lecture/lab batching rules
                lectureOrTutorialMustBeForFullGroup(constraintFactory),
                labForLargeGroupMustBeBatched(constraintFactory),
                
                // Special room restrictions
                specialRoomForAuto(constraintFactory),
                
                // Mandatory lunch break constraint
                mandatoryLunchBreak(constraintFactory),
                
                // Student campus time constraint  
                studentStrictShiftAdherence(constraintFactory),
                
                // CRITICAL: Explicit same batch conflict prevention
                preventSameBatchOverlap(constraintFactory),
                
                // ########################################################################
                // TIER 2: IMPORTANT SOFT CONSTRAINTS (Preferences and efficiency)
                // ########################################################################
                
                // Core lab priority enforcement (prefer lab_1 over lab_2 over lab_3)
                coreLabPriorityPreference(constraintFactory),
                
                // Large lab efficiency - encourage combining batches (DISABLED - only for labs)
                // largeLab70CapacityBatchCombining(constraintFactory),
                // NEW: Penalize inefficient batch splitting
                // penalizeSplitBatchesSeparateLargeLabs(constraintFactory),
                
                // Teacher workload management
                teacherMaxWeeklyHours(constraintFactory),
                teacherWorkdaySpan(constraintFactory),
                balanceTeacherDailyLoad(constraintFactory),
                
                // Student group preferences
                // studentGroupShiftPattern(constraintFactory),
                penalizePairedLabInDifferentSlots(constraintFactory),
                
                // ########################################################################
                // TIER 3: MILD SOFT CONSTRAINTS (Nice-to-have optimizations)
                // ########################################################################
                
                // Teaching preferences and efficiency
                // preferTeacherTimePreferences(constraintFactory),
                teacherMaxConsecutiveHours(constraintFactory),
                minimizeTeacherTravelTime(constraintFactory),
                preferConsecutiveLessons(constraintFactory),
                
                // Student daily class requirements
                minimumClassesPerDay(constraintFactory),
                balancedDailyClassLoad(constraintFactory),
                
                // NEW: Ensure at least one lab session per day
                minimumLabsPerDay(constraintFactory),
                
                // Weekly shift pattern enforcement
                // studentWeeklyShiftPattern(constraintFactory),
                
                // Lab utilization optimization
                preferHotspotLabsOnMonday(constraintFactory),
                
                // NEW: Prevent inefficient use of large labs by small batches
                efficientLargeLabUtilization(constraintFactory)
        };
    }

    // ############################################################################
    // Core Hard Constraints (Unchanged for Stability)
    // ############################################################################

    private Constraint roomConflict(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTimeSlot),
                        Joiners.equal(Lesson::getRoom))
                .penalize(HardSoftScore.ONE_HARD, (lesson1, lesson2) -> {
                    recordViolation("Room conflict", HardSoftScore.ONE_HARD);
                    return 1;
                })
                .asConstraint("Room conflict");
    }

    private Constraint teacherConflict(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTeacher),
                        Joiners.equal(l -> l.getTimeSlot().getDayOfWeek()),
                        Joiners.overlapping(Lesson::getStartTime, Lesson::getEndTime))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Teacher conflict");
    }

    private Constraint studentGroupConflict(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getStudentGroup),
                        Joiners.equal(l -> l.getTimeSlot().getDayOfWeek()),
                        Joiners.overlapping(Lesson::getStartTime, Lesson::getEndTime))
                .filter((lesson1, lesson2) -> {
                    boolean l1HasBatch = lesson1.isSplitBatch();
                    boolean l2HasBatch = lesson2.isSplitBatch();

                    if (!l1HasBatch && !l2HasBatch) { // Two full-group lessons (L/T). CONFLICT.
                        return true;
                    }
                    if (l1HasBatch != l2HasBatch) { // A full-group lesson and a batched lab. CONFLICT.
                        return true;
                    }
                    if (l1HasBatch && l2HasBatch) { // Two batched labs. Conflict only if for the SAME batch.
                        return lesson1.getLabBatch().equals(lesson2.getLabBatch());
                    }
                    return false;
                })
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Student group conflict");
    }

    private Constraint roomCapacity(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getRoom() != null && 
                        lesson.getRoom().getCapacity() < lesson.getRequiredCapacity())
                .penalize(HardSoftScore.ONE_HARD,
                        (lesson) -> lesson.getRequiredCapacity() - lesson.getRoom().getCapacity())
                .asConstraint("Room capacity");
    }

    private Constraint labInLabRoom(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.requiresLabRoom() && 
                        lesson.getRoom() != null && !lesson.getRoom().isLab())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Lab in a theory room");
    }

    private Constraint theoryInTheoryRoom(ConstraintFactory constraintFactory) {  
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.requiresTheoryRoom() && 
                        lesson.getRoom() != null && lesson.getRoom().isLab())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Theory in a lab room");
    }

    private Constraint labInLabSlot(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.requiresLabRoom() && 
                        lesson.getTimeSlot() != null && !lesson.getTimeSlot().isLab())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Lab in theory slot");
    }

    private Constraint theoryInTheorySlot(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.requiresTheoryRoom() && 
                        lesson.getTimeSlot() != null && lesson.getTimeSlot().isLab())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Theory in lab slot");
    }

    private Constraint lectureOrTutorialMustBeForFullGroup(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.requiresTheoryRoom() && lesson.isSplitBatch())
                .penalize(HardSoftScore.ONE_HARD.multiply(10000), lesson -> {
                    // Log this critical violation for debugging
                    String msg = String.format("CRITICAL VIOLATION: Theory/Tutorial session %s-%s assigned to batch %s (should be full group only)", 
                            lesson.getStudentGroup().getName(), 
                            lesson.getCourse().getCode(),
                            lesson.getLabBatch());
                    // System.err.println(msg);
                    return 1;
                })
                .asConstraint("CRITICAL: Theory/Tutorial must be for full group only");
    }

    private Constraint labForLargeGroupMustBeBatched(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.requiresLabRoom()
                        && lesson.getStudentGroup().getSize() > TimetableConfig.LAB_BATCH_SIZE
                        && !lesson.isSplitBatch())
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Lab for large group must be batched");
    }

    // ############################################################################
    // Enhanced Hard Constraints - REMOVED TO REDUCE CONFLICTS
    // These constraints were causing too many conflicts and making solutions infeasible
    // ############################################################################

    /**
     * DISABLED: Prevent the same teacher from teaching the same course at the same time
     * This constraint was causing excessive conflicts - teachers can handle multiple batches
     */
    /*
    private Constraint sameTeacherSameCourseConflict(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTeacher),
                        Joiners.equal(Lesson::getCourse),
                        Joiners.equal(Lesson::getTimeSlot))
                .penalize(HardSoftScore.of(5, 0)) // Reduced from 10
                .asConstraint("Same teacher same course conflict");
    }
    */

    /**
     * DISABLED: Prevent lab and theory sessions of the same course from overlapping
     * This constraint was too restrictive and causing scheduling conflicts
     */
    /*
    private Constraint labTheoryOverlapConflict(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getStudentGroup),
                        Joiners.equal(Lesson::getCourse),
                        Joiners.equal(l -> l.getTimeSlot().getDayOfWeek()),
                        Joiners.overlapping(Lesson::getStartTime, Lesson::getEndTime))
                .filter((lesson1, lesson2) -> 
                        lesson1.requiresLabRoom() != lesson2.requiresLabRoom()) // One is lab, other is theory
                .penalize(HardSoftScore.of(3, 0)) // Reduced from 10
                .asConstraint("Lab theory overlap conflict");
    }
    */

    /**
     * DISABLED: Minimum break between classes for teachers
     * This constraint was causing infeasibility when teachers have back-to-back classes
     */
    /*
    private Constraint minimumBreakBetweenClasses(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTeacher),
                        Joiners.equal(l -> l.getTimeSlot().getDayOfWeek()))
                .filter((lesson1, lesson2) -> {
                    if (lesson1.getTimeSlot() == null || lesson2.getTimeSlot() == null) return false;
                    
                    java.time.LocalTime end1 = lesson1.getTimeSlot().getEndTime();
                    java.time.LocalTime start2 = lesson2.getTimeSlot().getStartTime();
                    java.time.LocalTime end2 = lesson2.getTimeSlot().getEndTime();
                    java.time.LocalTime start1 = lesson1.getTimeSlot().getStartTime();
                    
                    // Check if lessons are adjacent with no break
                    return (end1.equals(start2) || end2.equals(start1)) ||
                           (Duration.between(end1, start2).toMinutes() < 15 && Duration.between(end1, start2).toMinutes() > 0) ||
                           (Duration.between(end2, start1).toMinutes() < 15 && Duration.between(end2, start1).toMinutes() > 0);
                })
                .penalize(HardSoftScore.of(1, 0)) // Light penalty
                .asConstraint("Minimum break between classes");
    }
    */

    /**
     * DISABLED: Teacher consecutive hours limit  
     * This constraint was too restrictive for academic scheduling
     */
    private Constraint teacherMaxConsecutiveHours(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .groupBy(Lesson::getTeacher, 
                        lesson -> lesson.getTimeSlot().getDayOfWeek(),
                        toList())
                .filter((teacher, day, lessons) -> calculateMaxContinuousHours(lessons) > 4)
                .penalize(HardSoftScore.of(0, 50), // Higher soft penalty for 4+ hour violation
                        (teacher, day, lessons) -> {
                            int continuousHours = calculateMaxContinuousHours(lessons);
                            return (continuousHours - 4) * 10; // Penalty scales with violation
                        })
                .asConstraint("Teacher max 4 continuous hours");
    }

    /**
     * DISABLED: Prevent random lab assignment for mapped courses
     * This was redundant with courseLabMustMatchMapping and causing double penalties
     */
    /*
    private Constraint preventRandomLabAssignmentForMappedCourses(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Lesson.class)
                .filter(lesson -> lesson.requiresLabRoom()) // Any lab lesson
                .filter(lesson -> CourseLabMappingUtil.isCoreLabCourse(lesson.getCourse().getCode()))
                .filter(lesson -> lesson.getRoom() != null)
                .filter(lesson -> {
                    // Check if this room is in the allowed list for this course
                    String courseCode = lesson.getCourse().getCode();
                    String roomDesc = lesson.getRoom().getDescription();
                    return !CourseLabMappingUtil.isRoomAllowedForCourse(courseCode, roomDesc);
                })
                .penalize(HardSoftScore.ONE_HARD.multiply(50000)) // Even higher penalty
                .asConstraint("Prevent random lab assignment for mapped courses");
    }
    */

    // ############################################################################
    // Soft Constraints (Performance Optimized)
    // ############################################################################

    private Constraint teacherMaxWeeklyHours(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .groupBy(Lesson::getTeacher, sum(Lesson::getEffectiveHours))
                .filter((teacher, totalHours) -> totalHours > teacher.getMaxHours())
                .penalize(HardSoftScore.ONE_SOFT,
                        (teacher, totalHours) -> totalHours - teacher.getMaxHours())
                .asConstraint("Teacher max weekly hours");
    }

    private Constraint teacherWorkdaySpan(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .groupBy(Lesson::getTeacher, 
                        (Lesson lesson) -> lesson.getTimeSlot().getDayOfWeek(),
                        min((Lesson lesson) -> lesson.getTimeSlot().getStartTime()),
                        max((Lesson lesson) -> lesson.getTimeSlot().getEndTime())
                )
                .filter((teacher, day, minTime, maxTime) -> 
                        minTime != null && maxTime != null && 
                        Duration.between(minTime, maxTime).toHours() > 8)
                .penalize(HardSoftScore.ONE_SOFT,
                        (teacher, day, minTime, maxTime) -> 
                                (int) (Duration.between(minTime, maxTime).toMinutes() - 480))
                .asConstraint("Teacher workday span too long");
    }

    private Constraint penalizePairedLabInDifferentSlots(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getStudentGroup),
                        Joiners.equal(Lesson::getCourse),
                        Joiners.filtering((l1, l2) -> l1.isSplitBatch() && l2.isSplitBatch() && 
                                !l1.getLabBatch().equals(l2.getLabBatch()))
                )
                .filter((lesson1, lesson2) -> !Objects.equals(lesson1.getTimeSlot(), lesson2.getTimeSlot()))
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Paired lab batches in different slots");
    }

    // ############################################################################
    // Enhanced Soft Constraints
    // ############################################################################

    /**
     * Prefer teacher time preferences (morning vs afternoon)
     */
    private Constraint preferTeacherTimePreferences(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null && 
                        !isPreferredTimeForTeacher(lesson.getTeacher(), lesson.getTimeSlot()))
                .penalize(HardSoftScore.of(0, 5)) // Reduced penalty
                .asConstraint("Teacher time preferences");
    }

    /**
     * Balance teacher daily load
     */
    private Constraint balanceTeacherDailyLoad(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .groupBy(Lesson::getTeacher, 
                        l -> l.getTimeSlot().getDayOfWeek(),
                        sum(Lesson::getEffectiveHours))
                .filter((teacher, day, dailyHours) -> dailyHours > 6)
                .penalize(HardSoftScore.of(0, 3), // Reduced penalty
                        (teacher, day, dailyHours) -> dailyHours - 6)
                .asConstraint("Balance teacher daily load");
    }

    /**
     * Minimize teacher travel time between rooms
     */
    private Constraint minimizeTeacherTravelTime(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTeacher),
                        Joiners.equal(l -> l.getTimeSlot().getDayOfWeek()))
                .filter((lesson1, lesson2) -> {
                    if (lesson1.getRoom() == null || lesson2.getRoom() == null) return false;
                    return areConsecutiveLessons(lesson1, lesson2) && 
                           !lesson1.getRoom().getBlock().equals(lesson2.getRoom().getBlock());
                })
                .penalize(HardSoftScore.of(0, 2)) // Reduced penalty
                .asConstraint("Minimize teacher travel time");
    }

    /**
     * Prefer consecutive lessons for the same course
     */
    private Constraint preferConsecutiveLessons(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getStudentGroup),
                        Joiners.equal(Lesson::getCourse),
                        Joiners.equal(l -> l.getTimeSlot().getDayOfWeek()))
                .filter((lesson1, lesson2) -> areConsecutiveLessons(lesson1, lesson2))
                .reward(HardSoftScore.of(0, 2)) // Small reward
                .asConstraint("Prefer consecutive lessons");
    }

    /**
     * HARD: Room numbers 61, 62, C108 in block C are restricted to Automobile department (AUTO).
     */
    private Constraint specialRoomForAuto(ConstraintFactory constraintFactory) {
        Set<String> SPECIAL_ROOMS = java.util.Set.of("61", "62", "C108");
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getRoom() != null && SPECIAL_ROOMS.contains(lesson.getRoom().getName()))
                .filter(lesson -> !"AUTO".equalsIgnoreCase(lesson.getStudentGroup().getDepartment()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Special room AUTO only");
    }

    /**
     * HARD: Ensure mandatory lunch break between 11:00-14:00
     * Students must have at least 50 minutes break during this period if they have classes spanning it
     */
    private Constraint mandatoryLunchBreak(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .groupBy(Lesson::getStudentGroup, 
                        lesson -> lesson.getTimeSlot().getDayOfWeek(),
                        toList())
                .filter((studentGroup, day, lessons) -> violatesLunchBreakRule(lessons))
                .penalize(HardSoftScore.ONE_HARD.multiply(1000), // Very high penalty
                        (studentGroup, day, lessons) -> calculateLunchBreakViolations(lessons))
                .asConstraint("Mandatory lunch break violation");
    }

    // NEW: Encourage a 2-2-1 shift distribution (permutation of two days in two different shifts and one day in the third)
    private Constraint teacherShiftPattern(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .groupBy(Lesson::getTeacher, toList())
                .filter((teacher, lessons) -> calculateShiftPenalty(lessons) > 0)
                .penalize(HardSoftScore.of(0, 1),
                        (teacher, lessons) -> calculateShiftPenalty(lessons))
                .asConstraint("Teacher shift pattern");
    }

    /**
     * Calculates a penalty based on how far a teacher's weekly timetable deviates from the desired 2-2-1 shift distribution.
     * <p>
     * Shift buckets:
     *   0 – Early shift  (first lesson starts before 10:00)  →  8-3 shift
     *   1 – Mid shift    (first lesson starts between 10:00 ‑ 11:59) → 10-5 shift
     *   2 – Late shift   (first lesson starts at/after 12:00) → 12-7 shift
     * <p>
     * For every week day we only consider the EARLIEST lesson start time when classifying the shift for that day.
     * The ideal distribution for the five working days is two days in two of the buckets and one day in the remaining bucket (any permutation).
     * The function returns 0 if the ideal distribution is achieved; otherwise it returns a positive integer representing the magnitude of deviation.
     */
    private static int calculateShiftPenalty(java.util.List<Lesson> lessons) {
        // Earliest start time per day
        java.util.EnumMap<java.time.DayOfWeek, java.time.LocalTime> earliestByDay = new java.util.EnumMap<>(java.time.DayOfWeek.class);
        for (Lesson l : lessons) {
            if (l.getTimeSlot() == null) continue;
            var ts = l.getTimeSlot();
            var day = ts.getDayOfWeek();
            var start = ts.getStartTime();
            var current = earliestByDay.get(day);
            if (current == null || start.isBefore(current)) {
                earliestByDay.put(day, start);
            }
        }
        // Count shifts based on earliest appearance per day
        int[] shiftCounts = new int[3]; // [early, mid, late]
        for (java.time.LocalTime start : earliestByDay.values()) {
            int bucket = classifyShift(start);
            shiftCounts[bucket]++;
        }
        // Desired pattern after sorting should be [1,2,2]
        int[] sorted = java.util.Arrays.stream(shiftCounts).sorted().toArray();
        if (sorted[0] == 1 && sorted[1] == 2 && sorted[2] == 2) {
            return 0; // perfect match
        }
        // Otherwise compute deviation penalty.
        int penalty = 0;
        for (int c : shiftCounts) {
            if (c == 0) {
                penalty += 2; // missing a shift bucket entirely
            } else if (c > 2) {
                penalty += c - 2; // more than 2 days in same shift
            }
            // count==1 or 2 is acceptable, so no penalty
        }
        return penalty * 10;
    }

    // Helper to map a lesson start time to a shift bucket (0,1,2)
    private static int classifyShift(java.time.LocalTime start) {
        int hour = start.getHour();
        if (hour < 10) {
            return 0; // 8-3 shift
        } else if (hour < 12) {
            return 1; // 10-5 shift
        } else {
            return 2; // 12-7 shift
        }
    }

    // ############################################################################
    // Helper Methods
    // ############################################################################
    
    /**
     * Helper method to record constraint violations for monitoring
     */
    private void recordViolation(String constraintName, HardSoftScore penalty) {
        if (penalty.getHardScore() != 0) {
            monitor.recordHardViolation(constraintName, Math.abs(penalty.getHardScore()));
        }
        if (penalty.getSoftScore() != 0) {
            monitor.recordSoftViolation(constraintName, Math.abs(penalty.getSoftScore()));
        }
    }

    private boolean isPreferredTimeForTeacher(Teacher teacher, TimeSlot timeSlot) {
        // Assume teachers prefer morning sessions (before 12 PM)
        return timeSlot.getStartTime().getHour() < 12;
    }

    private boolean areConsecutiveLessons(Lesson lesson1, Lesson lesson2) {
        if (lesson1.getTimeSlot() == null || lesson2.getTimeSlot() == null) return false;
        if (!lesson1.getTimeSlot().getDayOfWeek().equals(lesson2.getTimeSlot().getDayOfWeek())) return false;
        
        java.time.LocalTime end1 = lesson1.getTimeSlot().getEndTime();
        java.time.LocalTime start2 = lesson2.getTimeSlot().getStartTime();
        java.time.LocalTime end2 = lesson2.getTimeSlot().getEndTime();
        java.time.LocalTime start1 = lesson1.getTimeSlot().getStartTime();
        
        return end1.equals(start2) || end2.equals(start1);
    }

    // ############################################################################
    // Lab Type and Priority Constraints
    // ############################################################################

    /**
     * CRITICAL HARD: Absolute enforcement of core lab mapping - NO VIOLATIONS ALLOWED
     * This constraint has the highest penalty to ensure core lab courses are ONLY assigned 
     * to their explicitly mapped labs (lab_1, lab_2, or lab_3).
     */
    private Constraint strictCoreLabMappingEnforcement(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Lesson.class)
                .filter(lesson -> lesson.getSessionType().equals("lab"))
                .filter(lesson -> lesson.getRoom() != null)
                .filter(lesson -> CourseLabMappingUtil.isCoreLabCourse(lesson.getCourse().getCode()))
                .filter(lesson -> {
                    String courseCode = lesson.getCourse().getCode();
                    String roomDesc = lesson.getRoom().getDescription();
                    
                    // STRICT: Course must be in one of its mapped labs
                    boolean isAllowed = CourseLabMappingUtil.isRoomAllowedForCourse(courseCode, roomDesc);
                    
                    // if (!isAllowed) {
                    //     // Log the violation for debugging
                    //     System.err.println("CRITICAL VIOLATION: Course " + courseCode + 
                    //                      " assigned to disallowed lab: " + roomDesc + 
                    //                      ". Allowed labs: " + CourseLabMappingUtil.getPriorityOrderedLabs(courseCode));
                    // }
                    
                    return !isAllowed; // Return true if this is a violation
                })
                .penalize(HardSoftScore.ONE_HARD.multiply(1000000), lesson -> {
                    // Maximum penalty - this should make violations impossible
                    String courseCode = lesson.getCourse().getCode();
                    String roomDesc = lesson.getRoom().getDescription();
                    recordViolation("CRITICAL: Core lab mapping violation: " + courseCode + " -> " + roomDesc, 
                                   HardSoftScore.ONE_HARD.multiply(1000000));
                    return 1;
                })
                .asConstraint("CRITICAL: Strict core lab mapping enforcement");
    }

    /**
     * HARD: Ensure lab type compatibility for courses with explicit lab_type and hardcoded computer courses
     * Note: Core lab mapping is handled by strictCoreLabMappingEnforcement()
     */
    private Constraint courseLabMustMatchMapping(ConstraintFactory constraintFactory) {
        // Courses that ALWAYS require computer labs even if not present in mapping CSV
        final java.util.Set<String> ALWAYS_COMPUTER = java.util.Set.of(
                "CD23321", // Python Programming for Design
                "CS19P23", // Advanced Application Development with Oracle APEX
                "CS19P21"  // Advanced Robotic Process Automation
        );
        
        return constraintFactory.forEach(Lesson.class)
                .filter(lesson -> lesson.getSessionType().equals("lab"))
                .filter(lesson -> lesson.getRoom() != null) // Only check assigned lessons
                .filter(lesson -> {
                    String courseCode = lesson.getCourse().getCode();
                    String roomLabType = lesson.getRoom().getLabType();
                    
                    // Skip core lab courses - they are handled by strictCoreLabMappingEnforcement()
                    if (CourseLabMappingUtil.isCoreLabCourse(courseCode)) {
                        return false;
                    }
                    
                    // Priority 1: If course has explicit lab_type from CSV, check lab type compatibility
                    String courseLabType = lesson.getCourse().getLabType();
                    if (courseLabType != null) {
                        // For CSV lab_type field, enforce lab type matching
                        if ("computer".equals(courseLabType)) {
                            // Computer courses can use any computer lab
                            return !"computer".equals(roomLabType);
                        } else if ("core".equals(courseLabType)) {
                            // Core courses from CSV can use any core lab (fallback)
                            return !"core".equals(roomLabType);
                        } else {
                            // Other lab types must match exactly
                            return !courseLabType.equals(roomLabType);
                        }
                    }
                    
                    // Priority 2: Hardcoded computer courses must be in computer labs
                    if (ALWAYS_COMPUTER.contains(courseCode)) {
                        return !"computer".equals(roomLabType);
                    }
                    
                    // No restrictions for other courses
                    return false;
                })
                .penalize(HardSoftScore.ONE_HARD.multiply(10000), lesson -> { // High penalty for violations
                    String courseCode = lesson.getCourse().getCode();
                    String courseLabType = lesson.getCourse().getLabType();
                    
                    // Record specific violation type for better debugging
                    if (courseLabType != null) {
                        recordViolation("CSV lab_type mismatch: " + courseCode + " requires " + courseLabType, 
                                       HardSoftScore.ONE_HARD.multiply(10000));
                    } else {
                        recordViolation("Computer lab requirement violation: " + courseCode, 
                                       HardSoftScore.ONE_HARD.multiply(10000));
                    }
                    return 1;
                })
                .asConstraint("Lab type enforcement (non-core)");
    }

    /**
     * SOFT: Enforce priority order for core lab courses (lab_1 > lab_2 > lab_3)
     * This implements the core logic: try lab_1 first, then lab_2, then lab_3
     */
    private Constraint coreLabPriorityPreference(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Lesson.class)
                .filter(lesson -> lesson.getSessionType().equals("lab"))
                .filter(lesson -> lesson.getRoom() != null)
                .filter(lesson -> CourseLabMappingUtil.isCoreLabCourse(lesson.getCourse().getCode()))
                .penalize(HardSoftScore.ONE_SOFT.multiply(50), 
                        lesson -> CourseLabMappingUtil.getPriorityPenalty(
                                lesson.getCourse().getCode(), 
                                lesson.getRoom().getDescription()))
                .asConstraint("Core lab priority preference");
    }

    /**
     * SOFT: Prefer assigning theory/tutorial sessions to rooms in the department's preferred block
     * to minimize student travel time between classes.
     * This constraint only penalizes when departments are assigned to NON-preferred blocks.
     */
    private Constraint departmentBlockPreference(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Lesson.class)
                .filter(lesson -> lesson.requiresTheoryRoom()) // Only theory and tutorial sessions
                .filter(lesson -> lesson.getRoom() != null && lesson.getStudentGroup() != null)
                .filter(lesson -> DepartmentBlockConfig.hasBlockPreference(lesson.getStudentGroup().getDepartment()))
                .filter(lesson -> !DepartmentBlockConfig.isPreferredBlock(
                        lesson.getStudentGroup().getDepartment(), 
                        lesson.getRoom().getBlock())) // Only penalize violations
                .penalize(HardSoftScore.ONE_SOFT.multiply(10))
                .asConstraint("Department block preference violation");
    }

    // FIXED: Student group shift pattern now as soft constraint with improved penalty calculation
    private Constraint studentGroupShiftPattern(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .groupBy(Lesson::getStudentGroup, toList())
                .filter((studentGroup, lessons) -> calculateStudentGroupShiftPenalty(lessons) > 0)
                .penalize(HardSoftScore.ONE_SOFT, // Changed from HARD to SOFT
                        (studentGroup, lessons) -> calculateStudentGroupShiftPenalty(lessons))
                .asConstraint("Student group shift pattern");
    }

    /**
     * Calculates a penalty based on how far a student group's weekly timetable deviates from the desired 2-2-1 shift distribution.
     * <p>
     * Shift buckets:
     *   0 – Early shift  (first lesson starts before 10:00)  →  8-3 shift
     *   1 – Mid shift    (first lesson starts between 10:00 ‑ 11:59) → 10-5 shift
     *   2 – Late shift   (first lesson starts at/after 12:00) → 12-7 shift
     * <p>
     * For every week day we only consider the EARLIEST lesson start time when classifying the shift for that day.
     * The ideal distribution for the five working days is two days in two of the buckets and one day in the remaining bucket (any permutation).
     * The function returns 0 if the ideal distribution is achieved; otherwise it returns a positive integer representing the magnitude of deviation.
     */
    private static int calculateStudentGroupShiftPenalty(java.util.List<Lesson> lessons) {
        // Earliest start time per day
        java.util.EnumMap<java.time.DayOfWeek, java.time.LocalTime> earliestByDay = new java.util.EnumMap<>(java.time.DayOfWeek.class);
        for (Lesson l : lessons) {
            if (l.getTimeSlot() == null) continue;
            var ts = l.getTimeSlot();
            var day = ts.getDayOfWeek();
            var start = ts.getStartTime();
            var current = earliestByDay.get(day);
            if (current == null || start.isBefore(current)) {
                earliestByDay.put(day, start);
            }
        }
        // Count shifts based on earliest appearance per day
        int[] shiftCounts = new int[3]; // [early, mid, late]
        for (java.time.LocalTime start : earliestByDay.values()) {
            int bucket = classifyShift(start);
            shiftCounts[bucket]++;
        }
        // Desired pattern after sorting should be [1,2,2]
        int[] sorted = java.util.Arrays.stream(shiftCounts).sorted().toArray();
        if (sorted[0] == 1 && sorted[1] == 2 && sorted[2] == 2) {
            return 0; // perfect match
        }
        // Otherwise compute deviation penalty.
        int penalty = 0;
        for (int c : shiftCounts) {
            if (c == 0) {
                penalty += 2; // missing a shift bucket entirely
            } else if (c > 2) {
                penalty += c - 2; // more than 2 days in same shift
            }
            // count==1 or 2 is acceptable, so no penalty
        }
        return penalty * 10;
    }

    // ############################################################################
    // Department Workday Constraints
    // ############################################################################

    /**
     * HARD: Ensure departments only schedule lessons on their allowed working days
     * Uses simplified department-only logic (no year dependency)
     */
    private Constraint departmentOutsideAllowedDays(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null && lesson.getStudentGroup() != null)
                .filter(lesson -> !DepartmentWorkdayConfig.isAllowedDay(
                        lesson.getStudentGroup().getDepartment(), 
                        lesson.getTimeSlot().getDayOfWeek()))
                .penalize(HardSoftScore.ONE_HARD.multiply(1000)) // Very high penalty
                .asConstraint("Department outside allowed working days");
    }

    /**
     * SOFT: Prefer hotspot labs on Monday for Monday-Friday departments
     * Uses simplified department-only logic
     */
    private Constraint preferHotspotLabsOnMonday(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null && 
                        lesson.getRoom() != null && 
                        lesson.getStudentGroup() != null)
                .filter(lesson -> lesson.getTimeSlot().getDayOfWeek() == java.time.DayOfWeek.MONDAY)
                .filter(lesson -> DepartmentWorkdayConfig.isMondayFridayDepartment(
                        lesson.getStudentGroup().getDepartment()))
                .filter(lesson -> DepartmentWorkdayConfig.isHotspotLab(
                        lesson.getRoom().getDescription()))
                .reward(HardSoftScore.of(0, 25)) // Mild reward
                .asConstraint("Prefer hotspot labs on Monday");
    }

    /**
     * SOFT: Encourage combining B1 and B2 batches of the same course and student group
     * in large-capacity labs (70+ seats) for efficient resource utilization.
     */
    private Constraint largeLab70CapacityBatchCombining(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getCourse),
                        Joiners.equal(Lesson::getStudentGroup))
                .filter((l1, l2) -> {
                    // Must be lab sessions with different batches of same course/group
                    if (!(l1.requiresLabRoom() && l2.requiresLabRoom())) return false;
                    if (!(l1.isSplitBatch() && l2.isSplitBatch())) return false;
                    if (l1.getLabBatch().equals(l2.getLabBatch())) return false;
                    
                    // If both lessons are in the SAME large-capacity room, we reward
                    Room room1 = l1.getRoom();
                    Room room2 = l2.getRoom();
                    if (room1 == null || room2 == null) return false;
                    if (!room1.equals(room2)) return false; // Must be the same room
                    if (room1.getCapacity() < TimetableConfig.FULL_CLASS_LAB_THRESHOLD) return false;
                    
                    return true;
                })
                .filter((l1, l2) -> {
                    // CRITICAL: Only reward if they're ALSO in the same time slot
                    // Same room + different time = still wasted capacity!
                    return Objects.equals(l1.getTimeSlot(), l2.getTimeSlot());
                })
                .reward(HardSoftScore.of(0, 100)) // Moderate reward for TRUE batch combining
                .asConstraint("Encourage large lab batch combining");
    }

    // NEW: Prevent inefficient use of large labs by small batches
    private Constraint efficientLargeLabUtilization(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.requiresLabRoom() && 
                        lesson.getRoom() != null && 
                        lesson.getTimeSlot() != null &&
                        lesson.getRoom().getCapacity() >= TimetableConfig.FULL_CLASS_LAB_THRESHOLD)
                .filter(lesson -> lesson.isSplitBatch()) // Only check batched lessons in large labs
                .filter(lesson -> !hasCompatibleBatchInSameSlot(lesson)) // No compatible batch to combine with
                .penalize(HardSoftScore.of(0, 150), // High soft penalty for wasteful use
                        lesson -> calculateWastefulnessScore(lesson))
                .asConstraint("Efficient large lab utilization");
    }

    /**
     * Check if there's a compatible batch in the same room and time slot that could be combined
     * Compatible means: same course, same student group, different batch (B1 vs B2)
     * 
     * NOTE: This is a simplified implementation. In a full implementation, this would
     * need access to all lessons in the solution to check for compatible batches.
     */
    private boolean hasCompatibleBatchInSameSlot(Lesson lesson) {
        // For now, we assume no compatible batches exist
        // This makes the constraint always apply, encouraging efficient lab usage
        return false;
    }

    /**
     * Calculate wastefulness score for using a large lab with a small batch
     * Higher score = more wasteful
     */
    private int calculateWastefulnessScore(Lesson lesson) {
        if (lesson.getRoom() == null || !lesson.isSplitBatch()) {
            return 0;
        }
        
        int roomCapacity = lesson.getRoom().getCapacity();
        int batchSize = lesson.getStudentGroup().getSize() / 2; // Approximate batch size (half of group)
        
        // Calculate utilization percentage
        double utilization = (double) batchSize / roomCapacity;
        
        // Higher penalty for lower utilization of large labs
        if (utilization < 0.3) { // Less than 30% utilization
            return 25; // High penalty for severe waste
        } else if (utilization < 0.5) { // Less than 50% utilization  
            return 15; // Medium penalty for moderate waste
        } else if (utilization < 0.7) { // Less than 70% utilization
            return 8; // Low penalty for minor waste
        } else {
            return 2; // Minimal penalty for acceptable utilization
        }
    }
    
    // ############################################################################
    // Lunch Break Helper Methods
    // ############################################################################
    
    /**
     * Check if a list of lessons for a student group on a particular day violates lunch break rules
     */
    private boolean violatesLunchBreakRule(java.util.List<Lesson> lessons) {
        if (lessons.isEmpty()) return false;
        
        // Filter lessons that have time slots assigned
        java.util.List<Lesson> scheduledLessons = lessons.stream()
                .filter(lesson -> lesson.getTimeSlot() != null)
                .collect(java.util.stream.Collectors.toList());
        
        if (scheduledLessons.size() < 2) return false; // Need at least 2 lessons to have a break issue
        
        // Sort lessons by start time
        scheduledLessons.sort(java.util.Comparator.comparing(lesson -> lesson.getTimeSlot().getStartTime()));
        
        // Check if lessons span the lunch period (11:00-14:00) without adequate break
        java.time.LocalTime lunchPeriodStart = java.time.LocalTime.of(11, 0);
        java.time.LocalTime lunchPeriodEnd = java.time.LocalTime.of(14, 0);
        
        // Find lessons that overlap with lunch period
        java.util.List<Lesson> lunchPeriodLessons = scheduledLessons.stream()
                .filter(lesson -> {
                    java.time.LocalTime lessonStart = lesson.getTimeSlot().getStartTime();
                    java.time.LocalTime lessonEnd = lesson.getTimeSlot().getEndTime();
                    // Lesson overlaps if it starts before 14:00 and ends after 11:00
                    return lessonStart.isBefore(lunchPeriodEnd) && lessonEnd.isAfter(lunchPeriodStart);
                })
                .collect(java.util.stream.Collectors.toList());
        
        if (lunchPeriodLessons.size() < 2) return false; // Need overlap to be a problem
        
        // Check for continuous scheduling without adequate break
        for (int i = 0; i < lunchPeriodLessons.size() - 1; i++) {
            Lesson current = lunchPeriodLessons.get(i);
            Lesson next = lunchPeriodLessons.get(i + 1);
            
            java.time.LocalTime currentEnd = current.getTimeSlot().getEndTime();
            java.time.LocalTime nextStart = next.getTimeSlot().getStartTime();
            
            // If lessons are back-to-back or have less than 50-minute break during lunch period
            if (currentEnd.equals(nextStart) || 
                java.time.Duration.between(currentEnd, nextStart).toMinutes() < 50) {
                
                // Check if this gap occurs during critical lunch period (11:50-13:00)
                java.time.LocalTime criticalLunchStart = java.time.LocalTime.of(11, 50);
                java.time.LocalTime criticalLunchEnd = java.time.LocalTime.of(13, 0);
                
                if ((currentEnd.isAfter(criticalLunchStart) || currentEnd.equals(criticalLunchStart)) &&
                    (nextStart.isBefore(criticalLunchEnd) || nextStart.equals(criticalLunchEnd))) {
                    return true; // Violation found
                }
            }
        }
        
        return false;
    }
    
    /**
     * Calculate the severity of lunch break violations
     */
    private int calculateLunchBreakViolations(java.util.List<Lesson> lessons) {
        int violations = 0;
        
        // Filter and sort lessons
        java.util.List<Lesson> scheduledLessons = lessons.stream()
                .filter(lesson -> lesson.getTimeSlot() != null)
                .sorted(java.util.Comparator.comparing(lesson -> lesson.getTimeSlot().getStartTime()))
                .collect(java.util.stream.Collectors.toList());
        
        java.time.LocalTime lunchPeriodStart = java.time.LocalTime.of(11, 0);
        java.time.LocalTime lunchPeriodEnd = java.time.LocalTime.of(14, 0);
        
        // Find lessons in lunch period
        java.util.List<Lesson> lunchPeriodLessons = scheduledLessons.stream()
                .filter(lesson -> {
                    java.time.LocalTime lessonStart = lesson.getTimeSlot().getStartTime();
                    java.time.LocalTime lessonEnd = lesson.getTimeSlot().getEndTime();
                    return lessonStart.isBefore(lunchPeriodEnd) && lessonEnd.isAfter(lunchPeriodStart);
                })
                .collect(java.util.stream.Collectors.toList());
        
        // Calculate violations based on break gaps
        for (int i = 0; i < lunchPeriodLessons.size() - 1; i++) {
            Lesson current = lunchPeriodLessons.get(i);
            Lesson next = lunchPeriodLessons.get(i + 1);
            
            java.time.LocalTime currentEnd = current.getTimeSlot().getEndTime();
            java.time.LocalTime nextStart = next.getTimeSlot().getStartTime();
            long breakMinutes = java.time.Duration.between(currentEnd, nextStart).toMinutes();
            
            // Critical lunch period check
            java.time.LocalTime criticalLunchStart = java.time.LocalTime.of(11, 50);
            java.time.LocalTime criticalLunchEnd = java.time.LocalTime.of(13, 0);
            
            if ((currentEnd.isAfter(criticalLunchStart) || currentEnd.equals(criticalLunchStart)) &&
                (nextStart.isBefore(criticalLunchEnd) || nextStart.equals(criticalLunchEnd))) {
                
                if (breakMinutes == 0) {
                    violations += 10; // Back-to-back classes during lunch - severe violation
                } else if (breakMinutes < 50) {
                    violations += (int)(5 + (50 - breakMinutes) / 10); // Graduated penalty
                }
            }
        }
        
        return Math.max(1, violations); // Ensure at least penalty of 1 if violation detected
    }
    
    /**
     * CRITICAL HARD: Prevent the same batch of the same student group from being scheduled simultaneously
     * This is logically impossible as B1 (or B2) is a subset of students that cannot be in two places at once
     */
    private Constraint preventSameBatchOverlap(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getStudentGroup),
                        Joiners.equal(Lesson::getCourse))
                .filter((lesson1, lesson2) -> {
                    // Both must be batched lab sessions
                    if (!lesson1.isSplitBatch() || !lesson2.isSplitBatch()) return false;
                    
                    // Must be the SAME batch (this is impossible)
                    if (!lesson1.getLabBatch().equals(lesson2.getLabBatch())) return false;
                    
                    // Check if they overlap in time
                    if (lesson1.getTimeSlot() == null || lesson2.getTimeSlot() == null) return false;
                    
                    // Different days are fine
                    if (!lesson1.getTimeSlot().getDayOfWeek().equals(lesson2.getTimeSlot().getDayOfWeek())) return false;
                    
                    // Check for time overlap
                    java.time.LocalTime start1 = lesson1.getTimeSlot().getStartTime();
                    java.time.LocalTime end1 = lesson1.getTimeSlot().getEndTime();
                    java.time.LocalTime start2 = lesson2.getTimeSlot().getStartTime();
                    java.time.LocalTime end2 = lesson2.getTimeSlot().getEndTime();
                    
                    // Overlap if start1 < end2 && start2 < end1
                    boolean overlap = start1.isBefore(end2) && start2.isBefore(end1);
                    
                    if (overlap) {
                        // Log this critical violation for debugging
                        String msg = String.format("CRITICAL VIOLATION: Same batch %s of %s-%s scheduled simultaneously at %s and %s", 
                                lesson1.getLabBatch(), 
                                lesson1.getStudentGroup().getName(), 
                                lesson1.getCourse().getCode(),
                                lesson1.getTimeSlot().toString(),
                                lesson2.getTimeSlot().toString());
                        // System.err.println(msg);
                    }
                    
                    return overlap;
                })
                .penalize(HardSoftScore.ONE_HARD.multiply(100000)) // Very high penalty - this should never happen
                .asConstraint("CRITICAL: Same batch cannot be in two places simultaneously");
    }

    /**
     * SOFT: Enforce strict shift adherence - students should follow 8-3, 10-5, or 12-7 patterns
     * Each day should fit within one of these three shift windows
     */
    private Constraint studentStrictShiftAdherence(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null)
                .groupBy(Lesson::getStudentGroup, 
                        (Lesson lesson) -> lesson.getTimeSlot().getDayOfWeek(),
                        min((Lesson lesson) -> lesson.getTimeSlot().getStartTime()),
                        max((Lesson lesson) -> lesson.getTimeSlot().getEndTime()))
                .filter((studentGroup, day, earliestStart, latestEnd) -> {
                    if (earliestStart == null || latestEnd == null) return false;
                    return !fitsInStandardShift(earliestStart, latestEnd);
                })
                .penalize(HardSoftScore.of(0, 100), // High soft penalty
                        (studentGroup, day, earliestStart, latestEnd) -> {
                            return calculateShiftViolationPenalty(earliestStart, latestEnd);
                        })
                .asConstraint("Student strict shift adherence (8-3, 10-5, 12-7)");
    }

    /**
     * SOFT: Ensure minimum 3 classes per day for each student group
     * This prevents having too few classes on any given day
     */
    private Constraint minimumClassesPerDay(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null)
                .groupBy(Lesson::getStudentGroup,
                        (Lesson lesson) -> lesson.getTimeSlot().getDayOfWeek(),
                        count())
                .filter((studentGroup, day, classCount) -> classCount < 3)
                .penalize(HardSoftScore.of(0, 50), // Moderate penalty
                        (studentGroup, day, classCount) -> (3 - classCount.intValue()) * 10)
                .asConstraint("Minimum 3 classes per day");
    }

    /**
     * SOFT: Balance daily class load across the week
     * Prevent having too many classes on one day and too few on others
     */
    private Constraint balancedDailyClassLoad(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null)
                .groupBy(Lesson::getStudentGroup, toList())
                .filter((studentGroup, lessons) -> calculateDailyLoadImbalance(lessons) > 0)
                .penalize(HardSoftScore.of(0, 30), // Moderate penalty for imbalance
                        (studentGroup, lessons) -> calculateDailyLoadImbalance(lessons))
                .asConstraint("Balanced daily class load");
    }

    /**
     * SOFT: Enforce weekly shift pattern (2-2-1, 2-1-2, or 1-2-2)
     * Students should follow consistent shift patterns across the week
     */
    private Constraint studentWeeklyShiftPattern(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null)
                .groupBy(Lesson::getStudentGroup, toList())
                .filter((studentGroup, lessons) -> calculateWeeklyShiftPatternPenalty(lessons) > 0)
                .penalize(HardSoftScore.of(0, 80), // High penalty for shift pattern violations
                        (studentGroup, lessons) -> calculateWeeklyShiftPatternPenalty(lessons))
                .asConstraint("Weekly shift pattern (2-2-1 variations)");
    }

    /**
     * Calculate the maximum continuous working hours for a teacher on a given day
     * @param lessons List of lessons for a teacher on a specific day
     * @return Maximum continuous working hours
     */
    private int calculateMaxContinuousHours(java.util.List<Lesson> lessons) {
        if (lessons == null || lessons.isEmpty()) {
            return 0;
        }

        // Sort lessons by start time
        java.util.List<Lesson> sortedLessons = lessons.stream()
                .filter(lesson -> lesson.getTimeSlot() != null)
                .sorted((l1, l2) -> l1.getTimeSlot().getStartTime().compareTo(l2.getTimeSlot().getStartTime()))
                .collect(java.util.stream.Collectors.toList());

        if (sortedLessons.isEmpty()) {
            return 0;
        }

        int maxContinuousHours = 0;
        int currentContinuousHours = 0;
        java.time.LocalTime currentEndTime = null;

        for (Lesson lesson : sortedLessons) {
            java.time.LocalTime lessonStart = lesson.getTimeSlot().getStartTime();
            java.time.LocalTime lessonEnd = lesson.getTimeSlot().getEndTime();
            
            // Calculate lesson duration in hours
            int lessonDuration = (int) java.time.Duration.between(lessonStart, lessonEnd).toHours();
            
            if (currentEndTime == null) {
                // First lesson
                currentContinuousHours = lessonDuration;
                currentEndTime = lessonEnd;
            } else if (lessonStart.equals(currentEndTime) || 
                       java.time.Duration.between(currentEndTime, lessonStart).toMinutes() <= 30) {
                // Lessons are consecutive (allowing up to 30 minutes break)
                currentContinuousHours += lessonDuration;
                currentEndTime = lessonEnd;
            } else {
                // Gap is too large, reset continuous hours
                maxContinuousHours = Math.max(maxContinuousHours, currentContinuousHours);
                currentContinuousHours = lessonDuration;
                currentEndTime = lessonEnd;
            }
        }

        // Check the final continuous block
        maxContinuousHours = Math.max(maxContinuousHours, currentContinuousHours);
        
        return maxContinuousHours;
    }

    // ############################################################################
    // Helper Methods for Student Shift Pattern Constraints
    // ############################################################################

    /**
     * Check if a day's schedule fits within one of the standard shift patterns:
     * 8-3 (7:30-15:30), 10-5 (9:30-17:30), or 12-7 (11:30-19:30)
     * Allows 30-minute flexibility on each side
     */
    private boolean fitsInStandardShift(java.time.LocalTime earliestStart, java.time.LocalTime latestEnd) {
        if (earliestStart == null || latestEnd == null) return false;
        
        // Define shift windows with 30-minute flexibility
        // 8-3 shift: 7:30 - 15:30 (flexible: 7:00 - 16:00)
        java.time.LocalTime earlyShiftStart = java.time.LocalTime.of(7, 0);
        java.time.LocalTime earlyShiftEnd = java.time.LocalTime.of(16, 0);
        
        // 10-5 shift: 9:30 - 17:30 (flexible: 9:00 - 18:00)
        java.time.LocalTime midShiftStart = java.time.LocalTime.of(9, 0);
        java.time.LocalTime midShiftEnd = java.time.LocalTime.of(18, 0);
        
        // 12-7 shift: 11:30 - 19:30 (flexible: 11:00 - 20:00)
        java.time.LocalTime lateShiftStart = java.time.LocalTime.of(11, 0);
        java.time.LocalTime lateShiftEnd = java.time.LocalTime.of(20, 0);
        
        // Check if the day fits in any of the three shifts
        boolean fitsEarlyShift = (!earliestStart.isBefore(earlyShiftStart)) && (!latestEnd.isAfter(earlyShiftEnd));
        boolean fitsMidShift = (!earliestStart.isBefore(midShiftStart)) && (!latestEnd.isAfter(midShiftEnd));
        boolean fitsLateShift = (!earliestStart.isBefore(lateShiftStart)) && (!latestEnd.isAfter(lateShiftEnd));
        
        return fitsEarlyShift || fitsMidShift || fitsLateShift;
    }

    /**
     * Calculate penalty for shift violations based on how far outside standard shifts the day extends
     */
    private int calculateShiftViolationPenalty(java.time.LocalTime earliestStart, java.time.LocalTime latestEnd) {
        if (earliestStart == null || latestEnd == null) return 0;
        
        // Find the closest valid shift and calculate the penalty
        int penalty = 0;
        
        // Calculate span in hours
        long daySpanHours = java.time.Duration.between(earliestStart, latestEnd).toHours();
        
        // Base penalty for being outside any standard shift
        penalty += 20;
        
        // Additional penalty for excessive day length (more than 8 hours)
        if (daySpanHours > 8) {
            penalty += (int) (daySpanHours - 8) * 10;
        }
        
        // Penalty for starting too early (before 7:00) or too late (after 12:00)
        if (earliestStart.isBefore(java.time.LocalTime.of(7, 0))) {
            penalty += 15; // Discourage very early starts
        }
        if (earliestStart.isAfter(java.time.LocalTime.of(12, 0))) {
            penalty += 15; // Discourage very late starts for first class
        }
        
        // Penalty for ending too late (after 20:00)
        if (latestEnd.isAfter(java.time.LocalTime.of(20, 0))) {
            penalty += 25; // Strong penalty for very late ends
        }
        
        return penalty;
    }

    /**
     * Calculate daily load imbalance penalty for a student group across the week
     */
    private int calculateDailyLoadImbalance(java.util.List<Lesson> lessons) {
        if (lessons == null || lessons.isEmpty()) return 0;
        
        // Count classes per day
        java.util.Map<java.time.DayOfWeek, Integer> dailyCounts = new java.util.EnumMap<>(java.time.DayOfWeek.class);
        
        for (Lesson lesson : lessons) {
            if (lesson.getTimeSlot() != null) {
                java.time.DayOfWeek day = lesson.getTimeSlot().getDayOfWeek();
                dailyCounts.put(day, dailyCounts.getOrDefault(day, 0) + 1);
            }
        }
        
        if (dailyCounts.isEmpty()) return 0;
        
        // Calculate mean and standard deviation
        double mean = dailyCounts.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = dailyCounts.values().stream()
                .mapToDouble(count -> Math.pow(count - mean, 2))
                .average().orElse(0.0);
        double stdDev = Math.sqrt(variance);
        
        // Penalty based on standard deviation (higher = more imbalanced)
        int penalty = 0;
        if (stdDev > 2.0) {
            penalty += (int) ((stdDev - 2.0) * 20); // Strong penalty for high imbalance
        } else if (stdDev > 1.5) {
            penalty += (int) ((stdDev - 1.5) * 10); // Moderate penalty for moderate imbalance
        }
        
        // Additional penalty for days with 0 classes (if the department should have classes)
        long daysWithZeroClasses = dailyCounts.values().stream().filter(count -> count == 0).count();
        penalty += (int) (daysWithZeroClasses * 15);
        
        // Additional penalty for days with too many classes (>6)
        long daysWithTooManyClasses = dailyCounts.values().stream().filter(count -> count > 6).count();
        penalty += (int) (daysWithTooManyClasses * 25);
        
        return penalty;
    }

    /**
     * Calculate weekly shift pattern penalty based on 2-2-1 distribution requirement
     * Enforces patterns like 2-2-1, 2-1-2, or 1-2-2 (any permutation)
     */
    private int calculateWeeklyShiftPatternPenalty(java.util.List<Lesson> lessons) {
        if (lessons == null || lessons.isEmpty()) return 0;
        
        // Find earliest start time per day to classify shifts
        java.util.Map<java.time.DayOfWeek, java.time.LocalTime> earliestByDay = new java.util.EnumMap<>(java.time.DayOfWeek.class);
        
        for (Lesson lesson : lessons) {
            if (lesson.getTimeSlot() != null) {
                java.time.DayOfWeek day = lesson.getTimeSlot().getDayOfWeek();
                java.time.LocalTime start = lesson.getTimeSlot().getStartTime();
                java.time.LocalTime current = earliestByDay.get(day);
                if (current == null || start.isBefore(current)) {
                    earliestByDay.put(day, start);
                }
            }
        }
        
        if (earliestByDay.isEmpty()) return 0;
        
        // Classify each day into shift buckets
        int[] shiftCounts = new int[3]; // [early (8-3), mid (10-5), late (12-7)]
        
        for (java.time.LocalTime startTime : earliestByDay.values()) {
            int shiftBucket = classifyStudentShift(startTime);
            shiftCounts[shiftBucket]++;
        }
        
        // Check if it matches the desired 2-2-1 pattern
        // Sort the counts to see if they form [1,2,2] pattern
        int[] sortedCounts = java.util.Arrays.stream(shiftCounts).sorted().toArray();
        
        // Perfect pattern: [1, 2, 2] when sorted
        if (sortedCounts.length >= 3 && sortedCounts[0] == 1 && sortedCounts[1] == 2 && sortedCounts[2] == 2) {
            return 0; // Perfect 2-2-1 pattern
        }
        
        // Calculate penalty based on deviation from ideal pattern
        int penalty = 0;
        
        // Count total working days
        int totalDays = java.util.Arrays.stream(shiftCounts).sum();
        
        if (totalDays >= 5) { // Only evaluate if we have a full week
            // Penalize for not having the 2-2-1 distribution
            for (int count : shiftCounts) {
                if (count == 0) {
                    penalty += 30; // Missing a shift entirely
                } else if (count > 2) {
                    penalty += (count - 2) * 20; // Too many days in one shift
                } else if (count == 1) {
                    // One day is okay if we have exactly one bucket with 1 day
                    long bucketsWithOneDay = java.util.Arrays.stream(shiftCounts).filter(c -> c == 1).count();
                    if (bucketsWithOneDay > 1) {
                        penalty += 15; // Too many buckets with only 1 day
                    }
                }
            }
            
            // Bonus penalty for very unbalanced patterns
            int maxCount = java.util.Arrays.stream(shiftCounts).max().orElse(0);
            int minCount = java.util.Arrays.stream(shiftCounts).filter(c -> c > 0).min().orElse(0);
            if (maxCount - minCount > 2) {
                penalty += 25; // High imbalance penalty
            }
        } else if (totalDays > 0) {
            // For partial weeks, lighter penalty based on available data
            penalty = 10; // Light penalty for not having full week data
        }
        
        return penalty;
    }

    /**
     * Classify a start time into shift bucket for student scheduling
     * 0 = Early shift (8-3): starts before 10:00
     * 1 = Mid shift (10-5): starts between 10:00-11:59
     * 2 = Late shift (12-7): starts at/after 12:00
     */
    private int classifyStudentShift(java.time.LocalTime startTime) {
        int hour = startTime.getHour();
        if (hour < 10) {
            return 0; // Early shift (8-3)
        } else if (hour < 12) {
            return 1; // Mid shift (10-5)
        } else {
            return 2; // Late shift (12-7)
        }
    }

    /**
     * SOFT: Ensure at least one lab session per day for each student group
     * This prevents days with only theory classes and encourages a balanced mix
     */
    private Constraint minimumLabsPerDay(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeSlot() != null)
                .groupBy(Lesson::getStudentGroup,
                        (Lesson lesson) -> lesson.getTimeSlot().getDayOfWeek(),
                        toList())
                .filter((studentGroup, day, lessons) -> {
                    // Count lab sessions on this day
                    long labCount = lessons.stream()
                            .filter(lesson -> lesson.requiresLabRoom())
                            .count();
                    return labCount == 0; // No lab sessions on this day
                })
                .filter((studentGroup, day, lessons) -> {
                    // Only apply penalty if the student group has classes on this day
                    // (don't penalize completely empty days)
                    return lessons.size() > 0;
                })
                .penalize(HardSoftScore.of(0, 60), // Moderate penalty for theory-only days
                        (studentGroup, day, lessons) -> {
                            // Higher penalty for days with more theory classes but no labs
                            int theoryCount = (int) lessons.stream()
                                    .filter(lesson -> lesson.requiresTheoryRoom())
                                    .count();
                            return Math.max(10, theoryCount * 5); // Minimum 10, scales with theory count
                        })
                .asConstraint("Minimum 1 lab session per day");
    }

    // === NEW: Teacher unavailable due to external matrix ===
    private Constraint teacherExternalUnavailability(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Lesson.class)
                .filter(lesson -> lesson.getTeacher() != null && lesson.getTimeSlot() != null)
                .filter(lesson -> org.timetable.persistence.ExternalAvailabilityLoader.teacherUnavailable.stream().anyMatch(
                        slot -> slot.id.equals(lesson.getTeacher().getId())
                                && slot.day == lesson.getTimeSlot().getDayOfWeek()
                                && !lesson.getTimeSlot().getStartTime().isAfter(slot.end)
                                && !lesson.getTimeSlot().getEndTime().isBefore(slot.start)
                ))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Teacher unavailable (external matrix)");
    }

    // === NEW: Room unavailable due to external matrix ===
    private Constraint roomExternalUnavailability(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Lesson.class)
                .filter(lesson -> lesson.getRoom() != null && lesson.getTimeSlot() != null)
                .filter(lesson -> org.timetable.persistence.ExternalAvailabilityLoader.roomUnavailable.stream().anyMatch(
                        slot -> slot.id.equals(lesson.getRoom().getId())
                                && slot.day == lesson.getTimeSlot().getDayOfWeek()
                                && !lesson.getTimeSlot().getStartTime().isAfter(slot.end)
                                && !lesson.getTimeSlot().getEndTime().isBefore(slot.start)
                ))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Room unavailable (external matrix)");
    }

    // === NEW: Prefer D Block for all student groups (theory/tutorial sessions) ===
    private Constraint preferDBlockForAll(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Lesson.class)
                .filter(lesson -> lesson.requiresTheoryRoom())
                .filter(lesson -> lesson.getRoom() != null)
                .filter(lesson -> !"D Block".equalsIgnoreCase(lesson.getRoom().getBlock()))
                .penalize(HardSoftScore.ONE_SOFT.multiply(5))
                .asConstraint("Prefer D Block for all student groups (theory/tutorial)");
    }
}