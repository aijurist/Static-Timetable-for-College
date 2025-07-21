package org.timetable;

import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.SolverConfig;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.timetable.domain.Lesson;
import org.timetable.domain.TimetableProblem;
import org.timetable.persistence.TimetableDataLoader;
import org.timetable.persistence.TimetableExporter;
import org.timetable.persistence.TimetableJsonExporter;
import org.timetable.solver.OptimizedTimetableConstraintProvider;
import org.timetable.solver.EnhancedSolverConfig;
import org.timetable.solver.SolverProgressMonitor;
import org.timetable.config.SolverProperties;
import org.timetable.validation.OptimizationValidator;
import org.timetable.validation.CoreLabMappingValidator;
import org.timetable.validation.CoreLabMappingEnforcer;
import org.timetable.validation.ConstraintAnalysisRunner;
import org.timetable.config.DepartmentBlockConfig;
import org.timetable.validation.ConstraintViolationAnalyzer;
import org.timetable.persistence.ExternalAvailabilityLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.io.PrintWriter;
import java.io.FileWriter;

public class TimetableApp {
    private static final Logger logger = LoggerFactory.getLogger(TimetableApp.class);

    public static void main(String[] args) {
        String coursesFile = args.length > 0 ? args[0] : "data/courses/cse_dept_red.csv";
        String dataDir = args.length > 1 ? args[1] : "data";

        try {
            Files.createDirectories(Paths.get("output/teacher_timetables"));
            Files.createDirectories(Paths.get("output/student_timetables"));
            Files.createDirectories(Paths.get("output/violation"));
        } catch (IOException e) {
            logger.error("Failed to create output directories", e);
            return;
        }

        logger.info("Loading timetable problem from {} and {}", coursesFile, dataDir);
        
        // Validate department block configuration
        logger.info("--- Department Block Configuration Validation ---");
        DepartmentBlockConfig.validateConfiguration();
        
        TimetableProblem problem = TimetableDataLoader.loadProblem(coursesFile, dataDir);
        
        // Validate core lab mappings before solving
        logger.info("--- Pre-Solve Validation ---");
        CoreLabMappingEnforcer.validateMappingsBeforeSolving(problem);

        logger.info("--- Problem Statistics ---");
        logger.info("Loaded {} teachers", problem.getTeachers().size());
        logger.info("Loaded {} courses", problem.getCourses().size());
        logger.info("Loaded {} student groups", problem.getStudentGroups().size());
        logger.info("Loaded {} rooms", problem.getRooms().size());
        logger.info("Loaded {} timeslots", problem.getTimeSlots().size());
        logger.info("Created {} lessons to schedule", problem.getLessons().size());

        logger.info("Loading external teacher and room unavailability...");
        try {
            ExternalAvailabilityLoader.loadAll("data/teacher_matrix", "data/room_matrix");
            logger.info("Loaded unavailable slots: {} teacher, {} room", ExternalAvailabilityLoader.teacherUnavailable.size(), ExternalAvailabilityLoader.roomUnavailable.size());
        } catch (Exception e) {
            logger.error("Failed to load external unavailability: {}", e.getMessage());
        }

        logger.info("--- Solver Phase ---");
        SolverProperties.logCurrentConfiguration();
        logger.info("Creating enhanced multithreaded solver...");
        Solver<TimetableProblem> solver = EnhancedSolverConfig.createEnhancedSolver();
        
        // Add progress monitoring
        SolverProgressMonitor progressMonitor = new SolverProgressMonitor();
        solver.addEventListener(progressMonitor);

        logger.info("Solving timetable problem...");
        LocalDateTime solveStartTime = LocalDateTime.now();
        TimetableProblem solution = solver.solve(problem);
        LocalDateTime solveEndTime = LocalDateTime.now();
        
        // Log final solver statistics
        progressMonitor.logFinalStats();

        Duration solvingTime = Duration.between(solveStartTime, solveEndTime);
        logger.info("Solved in {}.", formatDuration(solvingTime));
        logger.info("Final score: {}", solution.getScore());

        long assignedLessons = solution.getLessons().stream()
                .filter(lesson -> lesson.getTimeSlot() != null && lesson.getRoom() != null)
                .count();
        logger.info("Assigned lessons: {}/{}", assignedLessons, solution.getLessons().size());

        if (solution.getScore().isFeasible()) {
            logger.info("Solution is FEASIBLE (all hard constraints are met).");
        } else {
            logger.warn("Solution is NOT FEASIBLE ({} hard constraints broken).", -solution.getScore().getHardScore());
        }

        // Validate optimization results
        logger.info("--- Validation Phase ---");
        OptimizationValidator.ValidationReport report = OptimizationValidator.validateSolution(solution);
        report.printReport();

        // Validate core lab mappings
        CoreLabMappingValidator.validateCoreLabMappings(solution);
        
        // Enforce core lab mapping constraints
        CoreLabMappingEnforcer.ValidationResult mappingResult = CoreLabMappingEnforcer.validateSolutionMappings(solution);
        if (mappingResult.hasViolations()) {
            logger.error("CRITICAL: Found {} core lab mapping violations in final solution!", mappingResult.getViolationCount());
            logger.error("This indicates the constraint system needs strengthening.");
        } else {
            logger.info("✓ Perfect core lab mapping achieved - no violations found!");
        }
        
        // Validate batch assignments
        logger.info("--- Batch Assignment Validation ---");
        validateBatchAssignments(solution);

        // Run constraint violation analysis
        logger.info("--- Constraint Violation Analysis ---");
        runConstraintViolationAnalysis(solution);

        try {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
            String outputFile = "output/timetable_solution_" + timestamp + ".csv";

            logger.info("Exporting full solution to {}", outputFile);
            TimetableExporter.exportTimetableToCsv(solution, outputFile);

            logger.info("Exporting individual teacher timetables...");
            TimetableExporter.exportTeacherTimetables(solution, "output/teacher_timetables");

            logger.info("Exporting individual student group timetables...");
            TimetableExporter.exportStudentGroupTimetables(solution, "output/student_timetables");

            logger.info("Exporting JSON for visualizer to output/timetable.json");
            TimetableJsonExporter.exportTimetableToJson(solution, "output/timetable.json");

            logger.info("Generating room availability CSV files...");
            RoomAvailabilityAnalyzer.generateRoomAvailabilityCSV(solution);

            logger.info("Export completed successfully.");
        } catch (IOException e) {
            logger.error("Failed to export solution", e);
        }
    }

    private static Solver<TimetableProblem> createBasicSolver() {
        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(TimetableProblem.class)
                .withEntityClasses(Lesson.class)
                .withScoreDirectorFactory(new ScoreDirectorFactoryConfig()
                        .withConstraintProviderClass(OptimizedTimetableConstraintProvider.class))
                .withTerminationConfig(new TerminationConfig()
                        .withMinutesSpentLimit(getSolveMinutes())
                        .withBestScoreFeasible(true));

        return SolverFactory.<TimetableProblem>create(solverConfig).buildSolver();
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long absSeconds = Math.abs(seconds);
        String positive = String.format(
                "%d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        return seconds < 0 ? "-" + positive : positive;
    }

    private static long getSolveMinutes() {
        // Allow overriding via system property or environment variable; default 20
        String prop = System.getProperty("solver.minutes");
        if (prop == null) {
            prop = System.getenv("SOLVER_MINUTES");
        }
        try {
            return prop != null ? Long.parseLong(prop) : 20L;
        } catch (NumberFormatException e) {
            return 20L;
        }
    }
    
    /**
     * Validates batch assignments in the final solution to catch any logical errors
     */
    private static void validateBatchAssignments(TimetableProblem solution) {
        int theoryBatchViolations = 0;
        int sameBatchOverlapViolations = 0;
        
        // Check for theory sessions assigned to batches (should never happen)
        for (Lesson lesson : solution.getLessons()) {
            if (lesson.requiresTheoryRoom() && lesson.isSplitBatch()) {
                theoryBatchViolations++;
                logger.error("❌ THEORY BATCH VIOLATION: {} {} assigned to batch {} (should be full group)", 
                    lesson.getStudentGroup().getName(), lesson.getCourse().getCode(), lesson.getLabBatch());
            }
        }
        
        // Check for same batch overlapping in time (should never happen)
        for (int i = 0; i < solution.getLessons().size(); i++) {
            for (int j = i + 1; j < solution.getLessons().size(); j++) {
                Lesson lesson1 = solution.getLessons().get(i);
                Lesson lesson2 = solution.getLessons().get(j);
                
                // Skip if not both batched lab sessions
                if (!lesson1.isSplitBatch() || !lesson2.isSplitBatch()) continue;
                
                // Skip if different student groups or courses
                if (!lesson1.getStudentGroup().equals(lesson2.getStudentGroup()) || 
                    !lesson1.getCourse().equals(lesson2.getCourse())) continue;
                
                // Skip if different batches (this is fine)
                if (!lesson1.getLabBatch().equals(lesson2.getLabBatch())) continue;
                
                // Check if they overlap in time on the same day
                if (lesson1.getTimeSlot() != null && lesson2.getTimeSlot() != null) {
                    if (lesson1.getTimeSlot().getDayOfWeek().equals(lesson2.getTimeSlot().getDayOfWeek())) {
                        LocalTime start1 = lesson1.getTimeSlot().getStartTime();
                        LocalTime end1 = lesson1.getTimeSlot().getEndTime();
                        LocalTime start2 = lesson2.getTimeSlot().getStartTime();
                        LocalTime end2 = lesson2.getTimeSlot().getEndTime();
                        
                        if (start1.isBefore(end2) && start2.isBefore(end1)) {
                            sameBatchOverlapViolations++;
                            logger.error("❌ SAME BATCH OVERLAP: {} {} batch {} overlaps: {} vs {}", 
                                lesson1.getStudentGroup().getName(), lesson1.getCourse().getCode(), 
                                lesson1.getLabBatch(), lesson1.getTimeSlot().toString(), lesson2.getTimeSlot().toString());
                        }
                    }
                }
            }
        }
        
        // Summary
        if (theoryBatchViolations == 0 && sameBatchOverlapViolations == 0) {
            logger.info("✅ Batch assignments are logically correct!");
            logger.info("   - No theory sessions assigned to batches");
            logger.info("   - No same-batch overlaps detected");
        } else {
            logger.error("❌ Found batch assignment violations:");
            logger.error("   - Theory batch violations: {}", theoryBatchViolations);
            logger.error("   - Same batch overlap violations: {}", sameBatchOverlapViolations);
        }
    }

    private static void runConstraintViolationAnalysis(TimetableProblem solution) {
        try {
            logger.info("Running detailed constraint violation analysis...");
            
            // Run the constraint analysis using the analyzer directly
            ConstraintViolationAnalyzer.ViolationReport report = ConstraintViolationAnalyzer.analyze(solution);
            
            // Generate filename with timestamp
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
            String violationFile = "output/violation/constraint_violations_" + timestamp + ".txt";
            
            // Write the analysis report to file
            try (PrintWriter writer = new PrintWriter(new FileWriter(violationFile))) {
                writer.println("=== CONSTRAINT VIOLATION ANALYSIS REPORT ===");
                writer.println("Generated at: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                writer.println("Final Score: " + solution.getScore());
                writer.println();
                
                // Write summary
                writer.println("SUMMARY:");
                writer.println("--------");
                if (report.getTotalViolations() == 0) {
                    writer.println("✅ PERFECT! No constraint violations found.");
                    writer.println("The timetable is completely feasible.");
                } else {
                    writer.println(String.format("⚠️ Found %d total constraint violations", report.getTotalViolations()));
                    
                    // Count by severity
                    long criticalCount = report.getViolations().stream()
                            .filter(v -> "CRITICAL".equals(v.getSeverity()))
                            .count();
                    long hardCount = report.getViolations().stream()
                            .filter(v -> "HARD".equals(v.getSeverity()))
                            .count();
                    
                    writer.println(String.format("   🔴 Critical: %d violations", criticalCount));
                    writer.println(String.format("   🟠 Hard:     %d violations", hardCount));
                    
                    // Write detailed violations
                    writer.println("\nDETAILED VIOLATIONS:");
                    writer.println("-------------------");
                    for (ConstraintViolationAnalyzer.ConstraintViolation violation : report.getViolations()) {
                        writer.println(String.format("\n[%s] %s", violation.getSeverity(), violation.getConstraintName()));
                        writer.println("  " + violation.getDescription());
                        if (violation.getLesson() != null) {
                            var lesson = violation.getLesson();
                            writer.println(String.format("  Lesson: %s | Course: %s | Group: %s", 
                                lesson.getId(),
                                lesson.getCourse() != null ? lesson.getCourse().getCode() : "N/A",
                                lesson.getStudentGroup() != null ? lesson.getStudentGroup().getName() : "N/A"));
                            writer.println(String.format("  Time: %s | Room: %s",
                                lesson.getTimeSlot() != null ? lesson.getTimeSlot().toString() : "UNASSIGNED",
                                lesson.getRoom() != null ? lesson.getRoom().getName() : "UNASSIGNED"));
                        }
                    }
                    
                    // Write statistics
                    writer.println("\nVIOLATION STATISTICS:");
                    writer.println("--------------------");
                    for (var entry : report.getViolationCountsByConstraint().entrySet()) {
                        writer.println(String.format("  %s: %d violations", entry.getKey(), entry.getValue()));
                    }
                }
            }
            
            logger.info("Constraint violation analysis saved to: {}", violationFile);
            
            // Also log a quick summary to console
            String quickSummary = ConstraintAnalysisRunner.getQuickSummary(solution);
            logger.info("Analysis summary: {}", quickSummary);
            
        } catch (Exception e) {
            logger.error("Failed to run constraint violation analysis", e);
        }
    }
}