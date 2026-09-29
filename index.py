import os
import sys

SERVER_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "server"))
if SERVER_DIR not in sys.path:
    sys.path.insert(0, SERVER_DIR)

from app import create_app

app = create_app()


class EntrypointPathMiddleware:
    def __init__(self, application):
        self.application = application

    def __call__(self, environ, start_response):
        prefix = "/index.py"
        path_info = environ.get("PATH_INFO", "")
        if path_info == prefix or path_info.startswith(prefix + "/"):
            environ["PATH_INFO"] = path_info[len(prefix):] or "/"
        return self.application(environ, start_response)


app.wsgi_app = EntrypointPathMiddleware(app.wsgi_app)