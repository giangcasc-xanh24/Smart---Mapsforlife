import os
import sys

SERVER_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "server"))
if SERVER_DIR not in sys.path:
    sys.path.insert(0, SERVER_DIR)

from app import create_app

app = create_app()