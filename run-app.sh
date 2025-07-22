#!/bin/bash

# ==========================================
# ADVANCED TIMETABLE APPLICATION LAUNCHER
# ==========================================
# Enhanced argument parser for course selection
# ==========================================

set -e

# Default values
SOLVER_MINUTES=${SOLVER_MINUTES:-30}
SOLVER_THREADS=${SOLVER_THREADS:-auto}
COURSE_TYPE=""
CUSTOM_FILES=()
SHOW_HELP=false
QUICK_MODE=false
VERBOSE_LOGGING=false
BENCHMARK_MODE=false

# Function to show usage
show_help() {
    cat << EOF
TIMETABLE SOLVER - Course Selection Tool

USAGE:
    $0 [OPTIONS] [COURSE_TYPE]

COURSE TYPES:
    first-year    Run first year courses (data/courses/first_year_data.csv)
    custom        Use custom CSV files (specify with -f option)

OPTIONS:
    -m, --minutes MINUTES    Set solver time limit in minutes (default: 30)
    -t, --threads THREADS    Set number of solver threads (default: auto)
    -q, --quick             Quick mode: 5 minutes solving time
    -v, --verbose           Enable verbose progress logging
    -b, --benchmark         Benchmark mode: Run multiple configurations for comparison
    -f, --file FILE         Add custom CSV file (can be used multiple times)
    -h, --help              Show this help message

EXAMPLES:
    $0 first-year           # Run first year courses
    $0 -m 60 first-year     # Run first year courses for 60 minutes
    $0 -q first-year        # Quick 5-minute run for first year courses
    $0 -t 8 -m 45 first-year # Use 8 threads, solve for 45 minutes
    $0 -v --quick first-year # Verbose logging with quick mode
    $0 custom -f data/courses/my_custom.csv
    $0 custom -f file1.csv -f file2.csv

ENVIRONMENT VARIABLES:
    SOLVER_MINUTES          Override default solver time (same as -m option)
    SOLVER_THREADS          Override default thread count (same as -t option)

PERFORMANCE TIPS:
    • Use -q/--quick for testing and validation
    • Increase -m/--minutes for better solutions (60+ recommended for production)
    • Set -t/--threads to match your CPU cores (multithreading support depends on OptaPlanner version)
    • Use -v/--verbose to monitor solving progress in real-time
    • The solver automatically detects optimal thread count when using 'auto'

EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        -m|--minutes)
            if [[ -n $2 && $2 =~ ^[0-9]+$ ]]; then
                SOLVER_MINUTES="$2"
                shift 2
            else
                echo "[ERROR] Invalid minutes value: $2"
                echo "Use --help for usage information"
                exit 1
            fi
            ;;
        -t|--threads)
            if [[ -n $2 && ($2 =~ ^[0-9]+$ || $2 == "auto") ]]; then
                SOLVER_THREADS="$2"
                shift 2
            else
                echo "[ERROR] Invalid threads value: $2 (use number or 'auto')"
                echo "Use --help for usage information"
                exit 1
            fi
            ;;
        -q|--quick)
            QUICK_MODE=true
            SOLVER_MINUTES=5
            shift
            ;;
        -v|--verbose)
            VERBOSE_LOGGING=true
            shift
            ;;
        -b|--benchmark)
            BENCHMARK_MODE=true
            VERBOSE_LOGGING=true
            shift
            ;;
        -f|--file)
            if [[ -n $2 ]]; then
                CUSTOM_FILES+=("$2")
                shift 2
            else
                echo "[ERROR] File path required after -f/--file"
                exit 1
            fi
            ;;
        first-year|custom)
            if [[ -n $COURSE_TYPE ]]; then
                echo "[ERROR] Course type already specified: $COURSE_TYPE"
                echo "Use --help for usage information"
                exit 1
            fi
            COURSE_TYPE="$1"
            shift
            ;;
        -*)
            echo "[ERROR] Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
        *)
            echo "[ERROR] Unknown argument: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Set default course type if not specified
if [[ -z $COURSE_TYPE ]]; then
    COURSE_TYPE="first-year"
fi

# Validate custom files if custom course type is selected
if [[ $COURSE_TYPE == "custom" ]]; then
    if [[ ${#CUSTOM_FILES[@]} -eq 0 ]]; then
        echo "[ERROR] Custom course type requires at least one file specified with -f option"
        echo "Use --help for usage information"
        exit 1
    fi
    COURSE_FILES=("${CUSTOM_FILES[@]}")
else
    # Determine course files based on course type
    case $COURSE_TYPE in
        first-year)
            COURSE_FILES=("data/courses/first_year_data.csv")
            ;;
    esac
fi

# Validate that course files exist
for file in "${COURSE_FILES[@]}"; do
    if [[ ! -f "$file" ]]; then
        echo "[ERROR] Course file not found: $file"
        exit 1
    fi
done

# Join course files with comma for Java app
COURSE_FILES_STR=$(IFS=,; echo "${COURSE_FILES[*]}")

# Display configuration
echo "=========================================="
echo "ENHANCED TIMETABLE SOLVER"
echo "Course type    : $COURSE_TYPE"
echo "Course files   : ${COURSE_FILES_STR}"
echo "Solver minutes : $SOLVER_MINUTES"
echo "Solver threads : $SOLVER_THREADS"
if [[ $QUICK_MODE == true ]]; then
    echo "Mode          : QUICK (5 minutes)"
fi
if [[ $VERBOSE_LOGGING == true ]]; then
    echo "Logging       : VERBOSE"
fi
if [[ $BENCHMARK_MODE == true ]]; then
    echo "Mode          : BENCHMARK (multiple runs)"
fi
echo "Features      : Smart termination, Progress monitoring, Room analysis"
echo "=========================================="

echo "[INFO] Checking Maven…"
if ! command -v mvn &> /dev/null; then
    echo "[ERROR] Maven is not installed. Please install Maven first."
    exit 1
fi

if [[ ! -d "lib" ]] || [[ -z "$(ls -A lib 2>/dev/null)" ]]; then
    echo "[INFO] Downloading libraries…"
    ./download-libs.sh
fi

echo "[INFO] System Information:"
echo "  CPU cores available: $(nproc 2>/dev/null || echo "unknown")"
echo "  Java version: $(java -version 2>&1 | head -n 1 | cut -d'"' -f2 2>/dev/null || echo "unknown")"
echo "  Memory available: $(free -h 2>/dev/null | awk '/^Mem:/ {print $2}' || echo "unknown")"

echo "[INFO] Building project (may take a moment)…"
mvn -q clean package

echo "[INFO] Starting enhanced timetable solver…"

# Check for potential compatibility issues
if [[ $SOLVER_THREADS != "auto" && $SOLVER_THREADS -gt 1 ]]; then
    echo "[INFO] Multithreading requested ($SOLVER_THREADS threads)"
    echo "[INFO] Note: Multithreading support depends on your OptaPlanner version"
fi

# Build Java system properties
JAVA_OPTS="-Dsolver.minutes=$SOLVER_MINUTES"

if [[ $SOLVER_THREADS != "auto" ]]; then
    JAVA_OPTS="$JAVA_OPTS -Dsolver.threads=$SOLVER_THREADS"
fi

if [[ $VERBOSE_LOGGING == true ]]; then
    JAVA_OPTS="$JAVA_OPTS -Dsolver.progress.logging=true"
fi

# Detect system resources and optimize JVM performance
TOTAL_MEM_GB=$(free -g | awk '/^Mem:/ {print $2}')
CPU_CORES=$(nproc 2>/dev/null || echo "4")

# Configure memory allocation based on available RAM
if [[ $TOTAL_MEM_GB -gt 64 ]]; then
    # High-end system (64GB+) - allocate up to 32GB for solver
    HEAP_SIZE="32g"
    INIT_HEAP="8g"
    echo "[INFO] High-end system detected (${TOTAL_MEM_GB}GB RAM) - Using optimized 32GB heap"
elif [[ $TOTAL_MEM_GB -gt 16 ]]; then
    # Medium-high system (16-64GB) - allocate up to 12GB
    HEAP_SIZE="12g"
    INIT_HEAP="4g"
    echo "[INFO] Medium-high system detected (${TOTAL_MEM_GB}GB RAM) - Using 12GB heap"
else
    # Standard system - use conservative 4GB
    HEAP_SIZE="4g"
    INIT_HEAP="1g"
    echo "[INFO] Standard system detected (${TOTAL_MEM_GB}GB RAM) - Using 4GB heap"
fi

# Configure GC based on system capabilities
if [[ $CPU_CORES -gt 16 && $TOTAL_MEM_GB -gt 32 ]]; then
    # High-end systems: Use G1GC with optimized settings for large heaps
    JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=32m"
    JAVA_OPTS="$JAVA_OPTS -XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=40"
    echo "[INFO] High-performance system (${CPU_CORES} cores) - Using optimized G1GC"
else
    # Standard systems: Use G1GC with default settings
    JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"
    echo "[INFO] Using standard G1GC configuration"
fi

# Set memory allocation
JAVA_OPTS="$JAVA_OPTS -Xmx${HEAP_SIZE} -Xms${INIT_HEAP}"

# Additional high-performance optimizations
JAVA_OPTS="$JAVA_OPTS -XX:+UseStringDeduplication -XX:+OptimizeStringConcat"
JAVA_OPTS="$JAVA_OPTS -XX:+UseCompressedOops -XX:+TieredCompilation"

# Show final command for debugging
if [[ $VERBOSE_LOGGING == true ]]; then
    echo "[DEBUG] Java command: java $JAVA_OPTS -jar target/timejava-1.0-SNAPSHOT.jar \"$COURSE_FILES_STR\" data"
fi

# Execute the solver
if [[ $BENCHMARK_MODE == true ]]; then
    echo "[INFO] Running benchmark mode with multiple configurations..."
    
    # Run 1: Quick test
    echo "[BENCHMARK 1/3] Quick test (5 minutes)..."
    java -Dsolver.minutes=5 $JAVA_OPTS -jar target/timejava-1.0-SNAPSHOT.jar "$COURSE_FILES_STR" data
    mv output/timetable_solution_*.csv output/benchmark_quick_solution.csv 2>/dev/null || true
    
    # Run 2: Medium test
    echo "[BENCHMARK 2/3] Medium test (15 minutes)..."
    java -Dsolver.minutes=15 $JAVA_OPTS -jar target/timejava-1.0-SNAPSHOT.jar "$COURSE_FILES_STR" data
    mv output/timetable_solution_*.csv output/benchmark_medium_solution.csv 2>/dev/null || true
    
    # Run 3: Full test
    echo "[BENCHMARK 3/3] Full test ($SOLVER_MINUTES minutes)..."
    java $JAVA_OPTS -jar target/timejava-1.0-SNAPSHOT.jar "$COURSE_FILES_STR" data
    mv output/timetable_solution_*.csv output/benchmark_full_solution.csv 2>/dev/null || true
    
    echo "[INFO] Benchmark completed! Check output/ for benchmark_*.csv files"
else
    java $JAVA_OPTS -jar target/timejava-1.0-SNAPSHOT.jar "$COURSE_FILES_STR" data
fi

echo ""
echo "=========================================="
echo "SOLVER COMPLETED SUCCESSFULLY!"
echo ""
echo "📊 Generated Files (check 'output/' directory):"
echo "  📋 timetable_solution_*.csv - Main timetable"
echo "  👨‍🏫 teacher_timetables/ - Individual teacher schedules"
echo "  👥 student_timetables/ - Individual student schedules"
echo "  🏫 classroom_availability.csv - Room availability matrix"
echo "  🔬 lab_availability.csv - Lab availability matrix"
echo "  📱 timetable.json - JSON format for visualization"
echo ""
echo "💡 Next Steps:"
echo "  • Review the main timetable CSV for conflicts"
echo "  • Check room availability matrices for optimization opportunities"
echo "  • Use individual schedules for distribution to teachers/students"
echo "  • Import JSON file into visualization tools if available"
echo ""
echo "🚀 Performance Tips for Next Run:"
if [[ $SOLVER_MINUTES -lt 30 ]]; then
    echo "  • Consider increasing solve time (-m 60) for better solutions"
fi
if [[ $SOLVER_THREADS == "auto" ]]; then
    echo "  • Thread optimization is automatic (using available CPU cores)"
else
    echo "  • Using $SOLVER_THREADS threads (consider 'auto' for optimal performance)"
fi
echo "==========================================" 