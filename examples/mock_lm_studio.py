import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        self._send({"data": [{"id": "mock-qwen-7b-instruct", "object": "model"}]})
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); req = json.loads(self.rfile.read(n))
        msgs = req["messages"]; last = msgs[-1]
        sys.stderr.write("REQ last_role=%s tools=%s\n" % (last["role"], "tools" in req)); sys.stderr.flush()
        def text(t): return {"choices": [{"index": 0, "message": {"role": "assistant", "content": t}, "finish_reason": "stop"}],
                             "usage": {"prompt_tokens": 3100, "completion_tokens": 140, "total_tokens": 3240}}
        def call(i, name, args): return {"choices": [{"index": 0, "finish_reason": "tool_calls", "message": {"role": "assistant", "content": "", "tool_calls": [
            {"id": i, "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}]}}], "usage": {"prompt_tokens": 2900, "completion_tokens": 30, "total_tokens": 2930}}
        user = [m for m in msgs if m["role"] == "user"][-1]["content"]
        if "Summarize function" in user:
            return self._send(text("SUBSYSTEM: Asset loading\nSUMMARY: Loads and validates an asset file.\nCONFIDENCE: LIKELY"))
        if last["role"] == "tool":
            if last.get("name") == "get_callers":
                return self._send(call("c2", "propose_rename_function", {"function": "current", "new_name": "load_asset_file", "reason": "Opens a file with fopen, reads a header, validates a magic constant and stores the result in a global.", "confidence": "LIKELY"}))
            return self._send(text("## Summary\nThis function **loads an asset file** from disk and validates its header.\n\n### Evidence\n- CONFIRMED: calls fopen/fread/fclose (file I/O)\n- CONFIRMED: compares the first dword to 0x41535354 (\"ASST\")\n- LIKELY: returns a heap-allocated header (malloc via a wrapper)\n- UNKNOWN: the layout of the structure beyond the first dword\n\nI queued a rename to `load_asset_file` - please review it in the Proposals tab."))
        if "Drill down" in user or "Drill into" in user:
            return self._send(text("The Asset subsystem is LIKELY a loader. UNKNOWN beyond that."))
        if "architecture" in user.lower() and "[Function summaries" in user:
            return self._send(text("The program loads an asset then validates it.\n```json\n{\"overview\":\"main -> load_asset -> validate\",\"subsystems\":[{\"name\":\"Asset Loading\",\"description\":\"File parsing\",\"confidence\":\"LIKELY\",\"functions\":[],\"children\":[]}]}\n```"))
        return self._send(call("c1", "get_callers", {"function": "current"}))

HTTPServer(("127.0.0.1", 1234), H).serve_forever()
