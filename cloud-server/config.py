import os
from datetime import timedelta

class Config:
    SECRET_KEY = os.environ.get('SECRET_KEY', 'Satyadeep')
    JWT_SECRET_KEY = os.environ.get('JWT_SECRET_KEY', 'SatyadeepNayak')
    JWT_ACCESS_TOKEN_EXPIRES = timedelta(days=30)
    JWT_REFRESH_TOKEN_EXPIRES = timedelta(days=90)
    
    SQLALCHEMY_DATABASE_URI = os.environ.get(
        'DATABASE_URL',
        'sqlite:///' + os.path.join(os.path.dirname(__file__), 'tracking.db')
    )
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    
    UPLOAD_FOLDER = os.path.join(os.path.dirname(__file__), 'uploads')
    MAX_CONTENT_LENGTH = 50 * 1024 * 1024  # 50MB
    
    CLOUD_SERVER_URL = os.environ.get(
        'CLOUD_SERVER_URL',
        'http://localhost:5000'
    )
    
    PAIRING_CODE_TTL = 600  # 10 minutes
    
    GEO_FENCE_DEFAULT_RADIUS = 500  # meters
