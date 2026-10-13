#!/usr/bin/env python3
"""Local demo supplier only; deduplication is in memory and resets on restart."""
import json
import os
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

seen = set()
lock = threading.Lock()
callback_key = os.environ["SUPPLIER_CALLBACK_KEY"]
callback_base = os.environ.get("CALLBACK_BASE_URL", "http://localhost:8080").rstrip("/")
port = int(os.environ.get("SUPPLIER_PORT", "9090"))
business_paths = {f"/{name}/notify": name for name in ("advertising", "crm", "inventory")}


def callback(business, task_id, request_id):
    body = json.dumps({"taskId": task_id, "requestId": request_id, "success": True}).encode()
    request = urllib.request.Request(f"{callback_base}/api/{business}/callback", data=body, headers={
        "Content-Type": "application/json", "X-Callback-Key": callback_key})
    try:
        with urllib.request.urlopen(request, timeout=3) as response:
            print(f"Callback {task_id}: HTTP {response.status}", flush=True)
    except Exception as error:
        print(f"Callback {task_id} failed: {type(error).__name__}", flush=True)


class Supplier(BaseHTTPRequestHandler):
    def do_GET(self):
        self.notify()

    def do_POST(self):
        self.notify()

    def notify(self):
        business = business_paths.get(urlsplit(self.path).path)
        if business is None:
            self.send_error(404)
            return
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        task_id = self.headers.get("X-Task-Id")
        request_id = self.headers.get("X-Request-Id")
        if not task_id or not request_id:
            self.send_error(400)
            return
        with lock:
            duplicate = (business, request_id) in seen
            seen.add((business, request_id))
        result = json.dumps({"retryable": False, "repeatRequest": duplicate, "success": not duplicate}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(result)))
        self.end_headers()
        self.wfile.write(result)
        # Duplicate requests also re-send the callback to help recover a lost response.
        threading.Thread(target=callback, args=(business, task_id, request_id), daemon=True).start()


if __name__ == "__main__":
    for path in business_paths:
        print(f"Demo supplier: http://localhost:{port}{path} (GET / POST)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Supplier).serve_forever()
