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

# Build the project if needed
if [ ! -f target/timejava-1.0-SNAPSHOT-jar-with-dependencies.jar ]; then
    echo "Building the project using $MVN_CMD..."
    $MVN_CMD clean package
    
    # Check if build was successful
    if [ $? -ne 0 ]; then
        echo "Build failed. Exiting."
        exit 1
    fi
fi

# Set default parameters
COURSES_FILE="data/courses/cse_dept_red.csv"
DATA_DIR="data"

# Override with command line arguments if provided
if [ ! -z "$1" ]; then
    COURSES_FILE="$1"
fi

if [ ! -z "$2" ]; then
    DATA_DIR="$2"
fi

# Run the data load test
echo "Running the data load test..."
java -cp target/timejava-1.0-SNAPSHOT-jar-with-dependencies.jar org.timetable.DataLoadTest "$COURSES_FILE" "$DATA_DIR" 