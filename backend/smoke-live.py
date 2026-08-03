import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
import json, urllib.request, urllib.parse, uuid, sys, os

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
    short = (body or "")[:160].replace("\n", " ")
    RESULTS.append((name, status, expect, short))

def login(user, pw):
    s, h, b = http("POST", "/api/auth/login", body={"username": user, "password": pw})
    if s != 200:
        return None, s, b
    return json.loads(b)["accessToken"], s, b

# 0. health
s, h, b = http("GET", "/actuator/health"); record("00 health", s, b, 200)

# 1. public branding without token
print("== 1. Public branding no-token ==")
for p in ["/api/public/branding", "/api/branding/public", "/api/branding"]:
    s, h, b = http("GET", p)
    record(f"01 public GET {p}", s, b, "401/403/404")

# 2. admin-only brand mutation
print("== 2. Admin-only brand mutation ==")
adminTok, sa, ba = login("admin", "ant.design")
bobTok, sb, bb = login("bob", "ant.design")
record("02 login admin", sa, ba, 200)
record("02 login bob",   sb, bb, 200)
adminHdr = {"Authorization": "Bearer " + adminTok} if adminTok else {}
bobHdr   = {"Authorization": "Bearer " + bobTok}   if bobTok   else {}
for m, p in [("PUT","/api/branding"),("PATCH","/api/branding"),("PUT","/api/admin/branding")]:
    s, h, b = http(m, p, headers=adminHdr, body={"displayName":"x"}); record(f"02 admin {m} {p}", s, b, "401/403/404/405")
    s, h, b = http(m, p, headers=bobHdr,   body={"displayName":"x"}); record(f"02 bob   {m} {p}", s, b, "401/403/404/405")
# confirm /api/auth/me works for both roles
s, h, b = http("GET", "/api/auth/me", headers=adminHdr); record("02 auth/me admin", s, b, 200)
s, h, b = http("GET", "/api/auth/me", headers=bobHdr);   record("02 auth/me bob",   s, b, 200)

# 3. unauthenticated mobile access (no token) -> 403
print("== 3. Unauth mobile 403 ==")
for p in ["/api/mobile/forms/LEAVE_REQ","/api/mobile/instances","/api/mobile/tasks","/api/mobile/org/users","/api/mobile/files"]:
    if p.endswith("files"):
        s, h, b = http("GET", p)
    else:
        s, h, b = http("GET", p)
    record(f"03 unauth GET {p}", s, b, 401/403)

# admin reads existing form, process
s, h, b = http("GET", "/api/mobile/forms/LEAVE_REQ", headers=adminHdr); record("03 admin GET form", s, b, 200)

# Start an instance via existing LEAVE_REQ flow (admin starter, bob approver)
print("== 6. Idempotency replay ==")
idem = "smoke-" + uuid.uuid4().hex
data = {"reason": "smoke", "startDate": "2026-07-22", "endDate":"2026-07-22","type":"年假","days":1,"title":"smoke"}
start = {"formCode":"LEAVE_REQ", "data": data, "selfSelected":{}}
hdr = dict(adminHdr); hdr["Idempotency-Key"] = idem
s1, _, b1 = http("POST", "/api/mobile/instances", headers=hdr, body=start)
record("06 start #1 (idem)", s1, b1, 200)
s2, _, b2 = http("POST", "/api/mobile/instances", headers=hdr, body=start)
record("06 start #2 (idem replay)", s2, b2, 200)
inst1 = json.loads(b1).get("instanceId") if s1 == 200 else None
inst2 = json.loads(b2).get("instanceId") if s2 == 200 else None
deduped = (inst1 == inst2)
record("06 idem-deduped-same-key", "PASS" if deduped else "FAIL", f"i1={inst1} i2={inst2}", "expect equal")

# 3. Unrelated instance 403 - need a SECOND user to be unrelated
# We have bob (approver) and a third user "alice" or "test001" who is not involved.
# Create a third user via admin if needed.
print("== Creating third user for unrelated 403 ==")
# try to login third user; if missing, create via /api/users (admin)
thirdUsername = "smoke_third_" + uuid.uuid4().hex[:6]
try_user = http("POST", "/api/auth/login", body={"username": thirdUsername, "password":"ant.design"})
if try_user[0] != 200:
    s, h, b = http("POST", "/api/users", headers=adminHdr, body={"username":thirdUsername, "displayName":"Smoke Third","email":thirdUsername+"@antflow.local","status":"ACTIVE"})
    record("03 create third user", s, b, 200)
    if s == 200:
        newUid = json.loads(b)["id"]
        # assign user role
        # find role ids
        s, h, b = http("GET", "/api/roles", headers=adminHdr); roles = json.loads(b) if s==200 else []
        userRole = next((r["id"] for r in roles if r.get("code")=="user"), None)
        if userRole:
            http("PUT", f"/api/users/{newUid}/roles", headers=adminHdr, body=[userRole])
thirdTok, s3, b3 = login(thirdUsername, "ant.design")
record("03 login third", s3, b3, 200)
thirdHdr = {"Authorization":"Bearer "+thirdTok} if thirdTok else {}

# now check unrelated: third reads instance started by admin
print("== Unrelated instance 403 ==")
if inst1:
    s, h, b = http("GET", f"/api/mobile/instances/{inst1}", headers=thirdHdr); record("03 unrelated 3rd reads admin instance", s, b, 403)
    s, h, b = http("GET", f"/api/mobile/instances/{inst1}", headers=adminHdr);  record("03 admin reads own instance", s, b, 200)
    s, h, b = http("GET", f"/api/mobile/instances/{inst1}", headers=bobHdr);    record("03 bob (assignee) reads instance", s, b, 200)
    # task detail: third gets forbidden
    # find bob's task on this instance
    s, h, b = http("GET", f"/api/mobile/tasks?view=pending&size=5", headers=bobHdr); tlist = json.loads(b) if s==200 else {}
    taskIds = [t["id"] for t in (tlist.get("items") or []) if t.get("instanceId")==inst1]
    if taskIds:
        s, h, b = http("GET", f"/api/mobile/tasks/{taskIds[0]}", headers=thirdHdr); record("03 unrelated task detail", s, b, 403)
else:
    record("03 unrelated instance", "SKIP", "no instance", "n/a")

# 4. refresh replay rejection (no refresh endpoint)
print("== 4. Refresh replay rejection ==")
for p in ["/api/auth/refresh","/api/auth/refresh-token","/api/auth/token/refresh","/api/auth/rotate"]:
    s, h, b = http("POST", p, body={"refreshToken":"anything"}); record(f"04 refresh probe {p}", s, b, "401/403/404/405")
# Re-login: returns a NEW accessToken (stateless); confirm it differs
sa2, _, ba2 = http("POST", "/api/auth/login", body={"username":"admin","password":"ant.design"})
record("04 re-login admin", sa2, ba2, 200)
if sa2 == 200:
    newTok = json.loads(ba2)["accessToken"]
    record("04 new-token-differs", "PASS" if newTok != adminTok else "FAIL", f"old/neq", "expect new token")
# Token reuse: old token still valid (stateless)
s, h, b = http("GET", "/api/auth/me", headers=adminHdr); record("04 old-token-still-valid", s, b, 200)

# 5. upload type rejection
print("== 5. Upload type rejection ==")
PNG = bytes([0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A]) + b"\x00"*32 + b"smoke-png-data-12345678901234567890"
JPEG = bytes([0xFF,0xD8,0xFF,0xE0]) + b"\x00"*16 + b"smoke-jpeg-data-12345678901234567890"
EXE  = bytes([0x4D,0x5A,0x90,0x00]) + b"\x00"*16 + b"smoke-exe-data-12345678901234567890"
PDF  = b"%PDF-1.4\n" + b"%smoke\n" + b"12345678901234567890"
TXT  = b"hello, this is plain text not matching any allowed type 12345678901234567890"

s, h, b = http("POST","/api/mobile/files", headers=adminHdr, files={"file":("good.png", PNG, "image/png")}); record("05 upload png",   s, b, 200)
goodId = json.loads(b).get("id") if s==200 else None
s, h, b = http("POST","/api/mobile/files", headers=adminHdr, files={"file":("good.jpg", JPEG,"image/jpeg")}); record("05 upload jpeg",  s, b, 200)
s, h, b = http("POST","/api/mobile/files", headers=adminHdr, files={"file":("good.pdf", PDF, "application/pdf")}); record("05 upload pdf",   s, b, 200)
s, h, b = http("POST","/api/mobile/files", headers=adminHdr, files={"file":("lie.png",  PNG, "text/plain")}); record("05 lie png as text/plain (mismatch)", s, b, 422)
s, h, b = http("POST","/api/mobile/files", headers=adminHdr, files={"file":("evil.exe", EXE, "application/octet-stream")}); record("05 evil.exe signature", s, b, 422)
s, h, b = http("POST","/api/mobile/files", headers=adminHdr, files={"file":("evil.txt", TXT, "text/plain")}); record("05 disallowed mime text/plain", s, b, 422)
s, h, b = http("POST","/api/mobile/files", headers=adminHdr, files={"file":("empty.png", b"", "image/png")}); record("05 empty file", s, b, 422)
# oversized
big = b"X" * (11*1024*1024)
s, h, b = http("POST","/api/mobile/files", headers=adminHdr, files={"file":("big.png", big, "image/png")}); record("05 oversized >10MB", s, b, 422/413)
# owner can read metadata; unrelated cannot
if goodId:
    s, h, b = http("GET", f"/api/mobile/files/{goodId}", headers=adminHdr); record("05 owner metadata ok", s, b, 200)
    s, h, b = http("GET", f"/api/mobile/files/{goodId}", headers=thirdHdr); record("05 unrelated metadata forbidden", s, b, 403)
    s, h, b = http("GET", f"/api/mobile/files/{goodId}/content", headers=adminHdr); record("05 owner content ok", s, b, 200)

print("=== SUMMARY ===")
ok = fail = na = 0
for r in RESULTS:
    line = f"{r[0]:<55} status={str(r[1]):<5} expect={str(r[2]):<20} body={r[3]}"
    print(line)
print(f"TOTAL: {len(RESULTS)} cases")

