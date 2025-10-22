# Timetable Scheduler with OptaPlanner

[![Java](https://img.shields.io/badge/Java-11+-blue.svg)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-green.svg)](https://maven.apache.org/)
[![OptaPlanner](https://img.shields.io/badge/OptaPlanner-8.44.0-orange.svg)](https://www.optaplanner.org/)

A powerful timetable scheduling system using OptaPlanner's constraint solver to generate optimal academic timetables for universities. The system handles complex scheduling requirements including room allocation, teacher availability, student groups, and lab-specific constraints.

**GIST Reference:** https://gist.github.com/aijurist/f6af8384bb6c6c21f96a02c72a1b397c

## Table of Contents

- [Features](#features)
- [Project Structure](#project-structure)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Building the Project](#building-the-project)
- [Running the Application](#running-the-application)
- [Data Configuration](#data-configuration)
- [Constraint System](#constraint-system)
- [Output Files](#output-files)
- [Analysis Tools](#analysis-tools)
- [Advanced Usage](#advanced-usage)

## Features

### Core Capabilities
- ✅ **Multi-Department Support** - Handles CSE, EEE, MECH, CIVIL, AUTO, and other departments
- ✅ **Intelligent Room Allocation** - Separate handling for classrooms and specialized labs
- ✅ **Lab Type Enforcement** - Computer labs for CSE/IT, core labs for engineering departments
- ✅ **Teacher Scheduling** - Prevents conflicts and optimizes teacher room stability
- ✅ **Student Group Management** - Handles theory, lab, and tutorial sessions with batch splitting
- ✅ **External Availability** - Loads teacher and room unavailability from matrix files
- ✅ **Constraint Optimization** - Hard and soft constraints for feasible and high-quality solutions
- ✅ **Multiple Output Formats** - CSV and JSON exports with detailed breakdowns

### Advanced Features
- Pre-allocation support for specific rooms (e.g., A105 optimization)
- Department-specific workday and time block configurations
- Course-lab mapping for specialized equipment requirements
- Comprehensive validation and analytics tools
- Real-time constraint monitoring during solving
- Batch analysis for student distribution across shifts

## Project Structure

```
static-timetable/
├── src/
│   └── main/
│       ├── java/
│       │   └── org/
│       │       └── timetable/
│       │           ├── domain/           # Domain classes (Lesson, Teacher, Course, etc.)
│       │           ├── persistence/      # Data loading and exporting utilities
│       │           ├── solver/           # OptaPlanner constraint definitions
│       │           ├── validation/       # Solution validation and analytics
│       │           ├── config/           # Configuration classes
│       │           └── TimetableApp.java # Main application entry point
│       └── resources/
│           ├── logback.xml              # Logging configuration
│           └── solver.properties        # Solver properties
├── data/
│   ├── courses/                         # Course data CSVs (by department)
│   ├── classroom/                       # Regular classroom definitions
│   ├── labs/                            # Lab room definitions (with lab_type)
│   ├── config/
│   │   └── course_lab_mapping.csv      # Course-to-lab mappings
│   ├── room_matrix/                     # Room availability matrices
│   └── teacher_matrix/                  # Teacher availability matrices
├── output/
│   ├── timetable_solution_*.csv        # Complete timetable solution
│   ├── timetable.json                  # JSON format for visualization
│   ├── classroom_availability.csv      # Classroom usage summary
│   ├── lab_availability.csv            # Lab usage summary
│   ├── student_timetables/             # Individual student group timetables
│   ├── teacher_timetables/             # Individual teacher timetables
│   └── violation/                      # Constraint violation reports
├── scripts/                             # Utility Python scripts
│   └── panel_student_count.py
├── lib/                                 # Java libraries (if not using Maven)
├── pom.xml                             # Maven project configuration
├── Makefile                            # Alternative build system
├── run-app.sh                          # Unix/Linux/macOS launcher
├── run-app.ps1                         # Windows PowerShell launcher
└── README.md                           # This file
```

## Requirements

### Core Requirements
- **Java** 11 or higher
- **Maven** 3.6 or higher (for building)

### Optional Requirements
- **Python** 3.6+ (for analysis scripts)
- **PowerShell** 5.1+ (for Windows users)
- **Bash** (for Unix/Linux/macOS users)

## Quick Start

### 1. Clone and Build
```bash
# Clone the repository
git clone <repository-url>
cd static-timetable

# Build with Maven
mvn clean package
```

### 2. Run with Default Configuration
```bash
# Unix/Linux/macOS
./run-app.sh both

# Windows PowerShell
./run-app.ps1 both
```

This will generate a timetable for both CSE and core departments with default settings.

## Building the Project

### Using Maven (Recommended)
```bash
mvn clean package
```

This creates a fat JAR with all dependencies in `target/timejava-1.0-SNAPSHOT.jar`.

### Using Makefile
```bash
make compile
```

### Downloading Libraries (Non-Maven)
If not using Maven, download the required libraries:
```bash
./download-libs.sh
```

## Running the Application

### Quick Start Scripts

#### Unix/Linux/macOS
```bash
./run-app.sh [OPTIONS] [DEPARTMENT_SELECTOR]
```

#### Windows PowerShell
```powershell
./run-app.ps1 [OPTIONS] [DEPARTMENT_SELECTOR]
```

### Department Selectors

| Selector | Description |
|----------|-------------|
| `core`   | Run only core department courses |
| `cse`    | Run only computer science courses |
| `both`   | Run both CSE and core departments (default) |
| `custom` | Use custom CSV files via `-f` option |

### Command Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `-m, --minutes <N>` | Solver time limit in minutes | 20 |
| `-f, --file <csv>` | Add course CSV (custom mode) | - |
| `-h, --help` | Show help message | - |

### Examples

```bash
# CSE department for 30 minutes
./run-app.sh cse -m 30

# Core departments with 15-minute limit
./run-app.sh core --minutes 15

# Custom mix of departments
./run-app.sh custom -f data/courses/custom_dept.csv -m 10

# Windows example
./run-app.ps1 both --minutes 25
```

### Manual Execution
```bash
java -Dsolver.minutes=20 \
     -jar target/timejava-1.0-SNAPSHOT.jar \
     "data/courses/cse_dept_red.csv,data/courses/core_dept_red.csv" \
     data
```

## Data Configuration

### Course Data Format

**File:** `data/courses/*.csv`

Required columns:
```csv
teacher_id,first_name,last_name,teacher_email,course_id,course_code,course_name,course_dept,lecture_hours,practical_hours,tutorial_hours,credits,student_count,academic_year,semester
```

Example:
```csv
101,John,Doe,john.doe@university.edu,CS101,CS101,Data Structures,CSE,3,2,1,4,70,2,1
```

### Room Data Format

#### Classrooms: `data/classroom/*.csv`
```csv
id,room_number,block,description,is_lab,room_max_cap
1,A101,A Block,Lecture Hall,0,100
```

#### Labs: `data/labs/*.csv`
```csv
id,room_number,block,description,is_lab,room_max_cap,lab_type
201,K101,K Block,Computer Lab 1,1,35,computer
202,J101,J Block,Control Systems Lab,1,35,core
```

**Lab Types:**
- `computer` - For CSE, IT, AIDS, CSBS departments
- `core` - For EEE, MECH, CIVIL, AUTO, and other engineering departments

### Course-Lab Mapping

**File:** `data/config/course_lab_mapping.csv`

Maps specific courses to designated labs:
```csv
course_code,course_name,department,total_labs,lab_1,lab_2,lab_3
EE23521,Control and Instrumentation Laboratory,EEE,1,Control and Instrumentation Lab,,
AT19721,Vehicle Maintenance Laboratory,AUTO,1,Vehicle Maintenance Lab,,
```

### External Availability

#### Teacher Matrix: `data/teacher_matrix/`
Individual CSV files for each teacher with time slot availability (0=unavailable, 1=available).

#### Room Matrix: `data/room_matrix/`
Individual CSV files for each room with time slot availability (0=unavailable, 1=available).

## Constraint System

### Hard Constraints (Must Be Satisfied)

1. **Room Conflict Prevention** - No room double-booking
2. **Teacher Conflict Prevention** - Teachers can't be in two places at once
3. **Student Group Conflict Prevention** - Student groups attend one lesson at a time
4. **Room Capacity** - Student count must not exceed room capacity
5. **Lab Type Enforcement**:
   - Computer departments (CSE/IT/AIDS/CSBS) → Computer labs only
   - Core departments → Core labs (computer labs only if mapping allows)
6. **Course-Lab Mapping** - Courses with specific lab requirements must use designated labs
7. **Session Type Matching** - Lab sessions in lab rooms, theory in classrooms
8. **Teacher Availability** - Respect external unavailability from teacher matrix
9. **Room Availability** - Respect external unavailability from room matrix
10. **Department Workday Rules** - MA courses only on Tuesday-Friday
11. **Continuous Hours Limit** - Teachers scheduled for max 3 continuous hours
12. **A105 Pre-allocation** - Pre-assigned lessons remain in A105

### Soft Constraints (Optimized)

1. **Teacher Room Stability** - Same teacher, same room per day (weight: MEDIUM)
2. **Theory Session Distribution** - Theory sessions spread across different days (weight: SOFT)
3. **Avoid Late Classes** - Prefer earlier time slots (weight: SOFT)
4. **Room Size Optimization** - Match room capacity to class size (weight: SOFT)
5. **Minimize Teacher Travel** - Reduce room changes for teachers (weight: MEDIUM)

## Output Files

The application generates comprehensive output in the `output/` directory:

### Main Output Files

| File | Description |
|------|-------------|
| `timetable_solution_YYYYMMDD_HHMMSS.csv` | Complete timetable with all lessons |
| `timetable.json` | JSON format for visualization tools |
| `classroom_availability.csv` | Classroom utilization summary |
| `lab_availability.csv` | Lab utilization summary |

### Subdirectories

| Directory | Contents |
|-----------|----------|
| `teacher_timetables/` | Individual CSV for each teacher |
| `student_timetables/` | Individual CSV for each student group |
| `violation/` | Constraint violation analysis reports |

### Sample Output Format

```csv
Day,Start Time,End Time,Course,Group,Teacher,Room,Type,Students
Monday,08:00,09:00,Data Structures,CSE-2A,Dr. Smith,A101,lecture,70
Monday,09:00,12:00,DS Lab,CSE-2A-BATCH1,Dr. Smith,K101,lab,35
```

## Analysis Tools

### Student Distribution Analyzer

Analyze student distribution across time shifts (morning/afternoon/evening).

```bash
# Quick analysis
python simple_student_analyzer.py

# Detailed analysis with export
python student_distribution_analyzer_basic.py --detailed --export

# Custom JSON file
python simple_student_analyzer.py output/timetable.json
```

**See [README_student_analyzer.md](README_student_analyzer.md) for detailed documentation.**

### Batch Combining Analysis

```bash
python analyze_batch_combining.py
```

### Student Shift Analysis

```bash
python student_shift_analyzer.py
```

### Panel Student Count

```bash
python scripts/panel_student_count.py
```

## Advanced Usage

### Environment Variables

```bash
# Set solver time limit
export SOLVER_MINUTES=30

# Run with environment variable
./run-app.sh both
```

### Solver Properties

Edit `src/main/resources/solver.properties`:
```properties
# Solver time limit (seconds)
solver.time.limit=1200

# Termination condition
solver.termination.early=true

# Thread count
solver.thread.count=AUTO
```

### Custom Solver Configuration

Modify `src/main/java/org/timetable/solver/EnhancedSolverConfig.java` to adjust:
- Time limits
- Early termination conditions
- Construction heuristic strategies
- Local search algorithms

### Debugging

Enable detailed logging:
```bash
# Set log level in logback.xml to DEBUG
java -Dlogback.configurationFile=logback-debug.xml -jar target/timejava-1.0-SNAPSHOT.jar
```

### Validation

Run validation on existing timetable:
```bash
java -cp target/timejava-1.0-SNAPSHOT.jar \
     org.timetable.validation.TimetableValidationApp \
     output/timetable.json
```

## Troubleshooting

### Common Issues

**Issue:** No feasible solution found
- **Solution:** Increase solver time limit with `-m` option
- **Solution:** Check room capacity vs. student counts
- **Solution:** Verify teacher availability in teacher matrix

**Issue:** Lab allocation errors
- **Solution:** Ensure `lab_type` column exists in lab CSV files
- **Solution:** Verify course-lab mapping CSV format
- **Solution:** Check that enough labs of correct type are available

**Issue:** OutOfMemoryError
- **Solution:** Increase JVM heap size: `java -Xmx4g -jar ...`

**Issue:** Constraint violations
- **Solution:** Check `output/violation/*.txt` for detailed analysis
- **Solution:** Review hard constraint configurations
- **Solution:** Verify input data consistency

## Contributing

When contributing to this project:
1. Follow Java code conventions
2. Add tests for new constraints
3. Update documentation for new features
4. Ensure all existing tests pass

## License

[Specify your license here]

## Acknowledgments

- Built with [OptaPlanner](https://www.optaplanner.org/) - AI constraint solver
- Uses [Apache Commons CSV](https://commons.apache.org/proper/commons-csv/) for data handling
- Uses [Gson](https://github.com/google/gson) for JSON processing
- Uses [Logback](http://logback.qos.ch/) for logging

## Contact

[Add your contact information or team details here]

---

**Last Updated:** October 2025
