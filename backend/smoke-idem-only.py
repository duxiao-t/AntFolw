import json, urllib.request, urllib.error, uuid, sys, io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
BASE = "http://127.0.0.1:8091"
RESULTS = []

def http(method, path, headers=None, body=None, files=None, timeout=10):
    headers = dict(headers or {})
    if files:
        boundary = "----smoke" + uuid.uuid4().hex
        crlf = b"\r\n"
        parts = []
        for name, (fname, data, ctype) in files.items():
            parts.append(b"--" + boundary.encode() + crlf)
            parts.append(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fname + "\"\r\nContent-Type: " + ctype + "\r\n\r\n").encode())
            parts.append(data + crlf)
        parts.append(b"--" + boundary.encode() + b"--" + crlf)
        body = b"".join(parts)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif body is not None:
        body = json.dumps(body).encode()
        headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")

def record(name, status, body, expect):
    short = (body or "")[:140].replace("\n", " ")
    RESULTS.append((name, status, expect, short))

def login(user, pw):
    s, h, b = http("POST", "/api/auth/login", body={"username": user, "password": pw})
    if s != 200: return None, s, b
    return json.loads(b)["accessToken"], s, b

# Smoke with idempotency key wired: attempt via headers (server-side currently NOT wired)
# Try both HeaderIdempotency-Key and X-Idempotency-Key; document if not deduped.
adminTok, sa, ba = login("admin", "ant.design"); bobTok, sb, bb = login("bob", "ant.design")
record("login admin", sa, ba, 200)
record("login bob",   sb, bb, 200)
adminHdr = {"Authorization":"Bearer "+adminTok}; bobHdr = {"Authorization":"Bearer "+bobTok}

# Use LEAVE_REQ form. Build small start.
idemKey = "smoke-" + uuid.uuid4().hex
start = {"formCode":"LEAVE_REQ","data":{"days":1,"reason":"smoke"}, "selfSelected":{}}
hdr = dict(adminHdr); hdr["Idempotency-Key"] = idemKey; hdr["X-Idempotency-Key"] = idemKey
s1, _, b1 = http("POST","/api/mobile/instances", headers=hdr, body=start)
s2, _, b2 = http("POST","/api/mobile/instances", headers=hdr, body=start)
inst1 = json.loads(b1).get("instanceId") if s1==200 else None
inst2 = json.loads(b2).get("instanceId") if s2==200 else None
record("start#1 (Idempotency-Key)", s1, b1, 200)
record("start#2 (Idempotency-Key replay)", s2, b2, 200)
deduped = (inst1 == inst2)
record("Idempotency-Key-same-key-deduped", "PASS" if deduped else "FAIL-GAP", f"i1={inst1} i2={inst2}", "expect equal")

# Print summary
for r in RESULTS:
    print(f"{r[0]:<40} status={str(r[1]):<5} expect={str(r[2]):<20} body={r[3]}")
print("TOTAL:", len(RESULTS))
