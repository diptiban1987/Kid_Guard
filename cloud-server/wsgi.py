"""
WSGI entry point for PythonAnywhere.
Copy this file to PythonAnywhere's WSGI configuration.
"""
import sys
import os

# Add the project directory to the path
path = os.path.dirname(os.path.abspath(__file__))
if path not in sys.path:
    sys.path.append(path)

from app import app as application

# If running in PythonAnywhere context, uncomment and set:
# application.config['CLOUD_SERVER_URL'] = 'https://yourusername.pythonanywhere.com'
