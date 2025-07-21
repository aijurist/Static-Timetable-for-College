#!/bin/bash

# Find Maven
MVN_CMD="mvn"
if ! command -v mvn &> /dev/null; then
    # Try common Maven installation locations
    if [ -f "/usr/local/bin/mvn" ]; then
        MVN_CMD="/usr/local/bin/mvn"
    elif [ -f "$HOME/apache-maven/bin/mvn" ]; then
        MVN_CMD="$HOME/apache-maven/bin/mvn"
    elif [ -f "/opt/homebrew/bin/mvn" ]; then
        MVN_CMD="/opt/homebrew/bin/mvn"
    else
        echo "Maven not found. Please install Maven or add it to your PATH."
        exit 1
    fi
fi

# Build the project
echo "Building the project using $MVN_CMD..."
$MVN_CMD clean package

# Check if build was successful
if [ $? -ne 0 ]; then
    echo "Build failed. Exiting."
    exit 1
fi

# Always use first_year_data.csv for course data
COURSE_DATA="data/courses/first_year_data.csv"
DATA_DIR="data"

# Override with command line arguments if provided
if [ ! -z "$1" ]; then
    COURSE_DATA="$1"
fi

if [ ! -z "$2" ]; then
    DATA_DIR="$2"
fi

# Run the application
echo "Running the timetable scheduler..."
echo "Using courses file: $COURSE_DATA"
echo "Using data directory: $DATA_DIR"
java -jar target/timejava-1.0-SNAPSHOT-jar-with-dependencies.jar "$COURSE_DATA" "$DATA_DIR" 