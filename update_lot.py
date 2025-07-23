import pandas as pd

# Load the CSV
df = pd.read_csv(r'data/courses/first_year_data.csv')

# Update lecture_hours and practical_hours for the specific course_name
df.loc[df['course_name'] == 'Programming using C', ['lecture_hours', 'practical_hours']] = [3, 4]

# Optional: Save the updated DataFrame back to CSV
df.to_csv(r'data/courses/first_year_data.csv', index=False)
