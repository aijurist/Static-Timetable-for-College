#!/bin/bash

# Check if libraries are downloaded
if [ ! -d "lib" ] || [ -z "$(ls -A lib)" ]; then
    echo "Libraries not found. Downloading required libraries..."
    ./download-libs.sh
fi

# Always use first_year_data.csv for course data
COURSE_DATA="data/courses/first_year_data.csv"

# Run the data load test using make
make test-data 