package org.timetable.solver;

import org.optaplanner.core.api.solver.event.BestSolutionChangedEvent;
import org.optaplanner.core.api.solver.event.SolverEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.timetable.domain.TimetableProblem;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

public class SolverProgressMonitor implements SolverEventListener<TimetableProblem> {
    private static final Logger logger = LoggerFactory.getLogger(SolverProgressMonitor.class);
    
    private final LocalDateTime startTime;
    private final AtomicInteger improvementCount = new AtomicInteger(0);
    private final ConstraintMonitor constraintMonitor = ConstraintMonitor.getInstance();
    private volatile String lastBestScore = "N/A";
    private volatile LocalDateTime lastImprovementTime;
    
    public SolverProgressMonitor() {
        this.startTime = LocalDateTime.now();
        this.lastImprovementTime = startTime;
        // Reset constraint monitoring for new solving session
        constraintMonitor.reset();
        logger.info("Started constraint monitoring for new solving session");
    }
    
    @Override
    public void bestSolutionChanged(BestSolutionChangedEvent<TimetableProblem> event) {
        TimetableProblem newBestSolution = event.getNewBestSolution();
        String newScore = newBestSolution.getScore() != null ? newBestSolution.getScore().toString() : "N/A";
        
        if (!newScore.equals(lastBestScore)) {
            lastBestScore = newScore;
            lastImprovementTime = LocalDateTime.now();
            int improvements = improvementCount.incrementAndGet();
            
            Duration totalTime = Duration.between(startTime, lastImprovementTime);
            long assignedLessons = newBestSolution.getLessons().stream()
                    .filter(lesson -> lesson.getTimeSlot() != null && lesson.getRoom() != null)
                    .count();
            
            logger.info("Improvement #{}: Score={}, Assigned={}/{}, Time={}",
                    improvements,
                    newScore,
                    assignedLessons,
                    newBestSolution.getLessons().size(),
                    formatDuration(totalTime));
            
            // Trigger constraint monitoring evaluation
            constraintMonitor.newEvaluation();
            // Log detailed constraint status (method is not visible, so use current stats instead)
            var stats = constraintMonitor.getCurrentStats();
            if (stats.isEmpty()) {
                logger.info("Constraint summary: No constraint violations recorded during solving");
            } else {
                int totalHardViolations = stats.values().stream().mapToInt(s -> s.getHardViolations()).sum();
                int totalSoftViolations = stats.values().stream().mapToInt(s -> s.getSoftViolations()).sum();
                logger.info("Constraint summary: {} hard, {} soft violations", totalHardViolations, totalSoftViolations);
            }

            // Log feasibility status
            if (newBestSolution.getScore() != null) {
                if (newBestSolution.getScore().isFeasible()) {
                    logger.info("✓ Solution is FEASIBLE (all hard constraints satisfied)");
                } else {
                    logger.warn("✗ Solution is NOT feasible ({} hard constraint violations)",
                            -newBestSolution.getScore().getHardScore());
                }
            }
        }
    }
    
    public void logFinalStats() {
        Duration totalTime = Duration.between(startTime, LocalDateTime.now());
        Duration timeSinceLastImprovement = Duration.between(lastImprovementTime, LocalDateTime.now());
        
        logger.info("--- Solver Statistics ---");
        logger.info("Total solving time: {}", formatDuration(totalTime));
        logger.info("Time since last improvement: {}", formatDuration(timeSinceLastImprovement));
        logger.info("Total improvements found: {}", improvementCount.get());
        logger.info("Final best score: {}", lastBestScore);
        
        if (improvementCount.get() > 0) {
            double improvementsPerMinute = improvementCount.get() / Math.max(1.0, totalTime.toMinutes());
            logger.info("Average improvements per minute: {:.2f}", improvementsPerMinute);
        }
        
        // Log final constraint analysis
        logger.info("--- Final Constraint Analysis ---");
        logConstraintSummary();
    }
    
    private void logConstraintSummary() {
        var stats = constraintMonitor.getCurrentStats();
        
        if (stats.isEmpty()) {
            logger.info("No constraint violations recorded during solving");
            return;
        }
        
        int totalHardViolations = stats.values().stream().mapToInt(ConstraintMonitor.ConstraintStats::getHardViolations).sum();
        int totalSoftViolations = stats.values().stream().mapToInt(ConstraintMonitor.ConstraintStats::getSoftViolations).sum();
        
        logger.info("Total Hard Violations: {}", totalHardViolations);
        logger.info("Total Soft Violations: {}", totalSoftViolations);
        
        // Show top 5 most violated constraints
        stats.values().stream()
                .filter(s -> s.getTotalViolations() > 0)
                .sorted((a, b) -> {
                    if (a.getHardViolations() != b.getHardViolations()) {
                        return Integer.compare(b.getHardViolations(), a.getHardViolations());
                    }
                    return Integer.compare(b.getSoftViolations(), a.getSoftViolations());
                })
                .limit(5)
                .forEach(stat -> logger.info("  {} - Hard: {}, Soft: {}", 
                        stat.getConstraintName(), stat.getHardViolations(), stat.getSoftViolations()));
    }
    
    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long absSeconds = Math.abs(seconds);
        String positive = String.format(
                "%d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        return seconds < 0 ? "-" + positive : positive;
    }
} 