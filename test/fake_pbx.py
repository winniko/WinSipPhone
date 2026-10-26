"""Finto centralino SIP (stile FreePBX) per provare WinSipPhone Tester sull'emulatore.

- REGISTER con autenticazione Digest (interno 201, password test201)
- SUBSCRIBE Event: dialog (BLF): 202 libero, 203 occupato, 204 squilla; dopo 15 s il 202 diventa occupato
- INVITE verso 301 -> risponde dopo 2 s, chiude dopo N s; 486 -> occupato; 404 -> inesistente
- OPTIONS -> 200 OK
- con "incoming": 20 s dopo la registrazione chiama il telefono (dall'interno 202)

Uso: python -I test/fake_pbx.py [porta=15060] [secondi_conversazione=6] [incoming]
Nell'app: centralino 10.0.2.2, porta 15060, interno 201, password test201
"""
import hashlib
import re
import socket
import sys
import threading
import time
import uuid

port = int(sys.argv[1]) if len(sys.argv) > 1 else 15060
talk = float(sys.argv[2]) if len(sys.argv) > 2 else 6
incoming = "incoming" in sys.argv
REALM, USER, PASSWORD, NONCE = "asterisk", "201", "test201", uuid.uuid4().hex
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", port))
lock = threading.Lock()
phone = {"addr": None, "contact": None}
subs = {}  # ext -> (addr, headers, cseq)
blf = {"202": "terminated", "203": "confirmed", "204": "early"}
calls = {}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def parse(msg):
    head, _, body = msg.partition("\r\n\r\n")
    lines = head.split("\r\n")
    h = {}
    for l in lines[1:]:
        if ":" in l:
            k, v = l.split(":", 1)
            h.setdefault(k.strip().lower(), v.strip())
    return lines[0], h, body


def uri_of(header, fallback):
    m = re.search(r"<([^>]+)>", header or "")
    return (m.group(1) if m else (header or "").split(";")[0].strip()) or fallback


def send(data, addr):
    with lock:
        s.sendto(data.encode(), addr)


def resp(code, h, addr, extra=(), body="", ctype="application/sdp", to_tag="pbx1"):
    to = h["to"] if "tag=" in h["to"] else h["to"] + ";tag=" + to_tag
    out = [f"SIP/2.0 {code}", f"Via: {h['via']}", f"From: {h['from']}", f"To: {to}",
           f"Call-ID: {h['call-id']}", f"CSeq: {h['cseq']}", "Server: FakePBX/1.0", f"Contact: <sip:pbx@10.0.2.2:{port}>"]
    out += list(extra)
    if body:
        out.append(f"Content-Type: {ctype}")
    out += [f"Content-Length: {len(body.encode())}", "", ""]
    send("\r\n".join(out) + body, addr)


def md5(x):
    return hashlib.md5(x.encode()).hexdigest()


def check_auth(h, method):
    a = h.get("authorization", "")
    if not a.lower().startswith("digest"):
        return None
    p = {}
    for part in a[6:].split(","):
        if "=" in part:
            k, v = part.strip().split("=", 1)
            p[k.strip()] = v.strip().strip('"')
    ha1 = md5(f"{p.get('username')}:{REALM}:{PASSWORD}")
    ha2 = md5(f"{method}:{p.get('uri')}")
    if p.get("qop"):
        expected = md5(f"{ha1}:{p['nonce']}:{p['nc']}:{p['cnonce']}:{p['qop']}:{ha2}")
    else:
        expected = md5(f"{ha1}:{p.get('nonce')}:{ha2}")
    return p.get("username") == USER and p.get("response") == expected


SDP = ("v=0\r\no=- 1 1 IN IP4 10.0.2.2\r\ns=-\r\nc=IN IP4 10.0.2.2\r\nt=0 0\r\n"
       "m=audio 41000 RTP/AVP 0 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:101 telephone-event/8000\r\na=sendrecv\r\n")


def dialog_xml(ext, state):
    d = "" if state == "terminated" else f'<dialog id="d{ext}" direction="recipient"><state>{state}</state></dialog>'
    return (f'<?xml version="1.0"?>\n<dialog-info xmlns="urn:ietf:params:xml:ns:dialog-info" version="{int(time.time())}" '
            f'state="full" entity="sip:{ext}@10.0.2.2">{d}</dialog-info>')


def notify(ext):
    if ext not in subs:
        return
    addr, h, seq = subs[ext]
    seq += 1
    subs[ext] = (addr, h, seq)
    contact = uri_of(h.get("contact"), f"sip:201@{addr[0]}:{addr[1]}")
    body = dialog_xml(ext, blf.get(ext, "terminated"))
    to_tag = "blf" + ext
    out = [f"NOTIFY {contact} SIP/2.0", f"Via: SIP/2.0/UDP 10.0.2.2:{port};branch=z9hG4bK{uuid.uuid4().hex[:10]};rport",
           "Max-Forwards: 70", f"From: {h['to']};tag={to_tag}" if "tag=" not in h["to"] else f"From: {h['to']}",
           f"To: {h['from']}", f"Call-ID: {h['call-id']}", f"CSeq: {seq} NOTIFY", f"Contact: <sip:{ext}@10.0.2.2:{port}>",
           "Event: dialog", "Subscription-State: active;expires=600", "Content-Type: application/dialog-info+xml",
           f"Content-Length: {len(body.encode())}", "", ""]
    send("\r\n".join(out) + body, addr)
    log(f"NOTIFY BLF {ext} = {blf.get(ext)}")


def blf_changer():
    time.sleep(15)
    blf["202"] = "confirmed"
    notify("202")


def call_phone():
    time.sleep(20)
    addr, contact = phone["addr"], phone["contact"]
    if not addr:
        log("Nessun telefono registrato: niente chiamata in arrivo")
        return
    cid = uuid.uuid4().hex + "@fakepbx"
    calls[cid] = {"addr": addr, "contact": contact, "state": "inviting"}
    inv = [f"INVITE {contact} SIP/2.0", f"Via: SIP/2.0/UDP 10.0.2.2:{port};branch=z9hG4bK{uuid.uuid4().hex[:10]};rport",
           "Max-Forwards: 70", '"Interno 202" <sip:202@10.0.2.2>;tag=pbxin', f"To: <sip:201@10.0.2.2>", f"Call-ID: {cid}",
           "CSeq: 1 INVITE", f"Contact: <sip:202@10.0.2.2:{port}>", "Content-Type: application/sdp", f"Content-Length: {len(SDP)}", "", ""]
    inv[3] = "From: " + inv[3]
    calls[cid]["invite"] = inv
    send("\r\n".join(inv) + SDP, addr)
    log("INVITE in arrivo verso il telefono inviato")


def bye_later(cid, h, addr, from_pbx):
    time.sleep(talk)
    c = calls.get(cid)
    if not c or c.get("state") == "ended":
        return
    contact = uri_of(h.get("contact"), f"sip:201@{addr[0]}:{addr[1]}")
    if from_pbx:  # chiamata fatta dal telefono: noi siamo il "To"
        frm = h["to"] if "tag=" in h["to"] else h["to"] + ";tag=pbx1"
        to = h["from"]
    else:
        frm, to = '"Interno 202" <sip:202@10.0.2.2>;tag=pbxin', h["to"]
    bye = [f"BYE {contact} SIP/2.0", f"Via: SIP/2.0/UDP 10.0.2.2:{port};branch=z9hG4bK{uuid.uuid4().hex[:10]};rport",
           "Max-Forwards: 70", f"From: {frm}", f"To: {to}", f"Call-ID: {cid}", "CSeq: 10 BYE", "Content-Length: 0", "", ""]
    send("\r\n".join(bye), addr)
    c["state"] = "ended"
    log("BYE inviato")


log(f"Finto centralino in ascolto UDP {port} (interno {USER} / {PASSWORD})")
threading.Thread(target=blf_changer, daemon=True).start()
while True:
    data, addr = s.recvfrom(65535)
    msg = data.decode(errors="replace")
    if not msg.strip():
        continue
    first, h, body = parse(msg)
    if "call-id" not in h:
        continue
    log(addr, "->", first)
    method = first.split(" ")[0]
    if first.startswith("SIP/2.0"):
        code = int(first.split(" ")[1])
        cid = h["call-id"]
        c = calls.get(cid)
        if c and "INVITE" in h.get("cseq", "") and code == 200:
            ack = [f"ACK {uri_of(h.get('contact'), phone['contact'])} SIP/2.0",
                   f"Via: SIP/2.0/UDP 10.0.2.2:{port};branch=z9hG4bK{uuid.uuid4().hex[:10]};rport", "Max-Forwards: 70",
                   f"From: {h['from']}", f"To: {h['to']}", f"Call-ID: {cid}", "CSeq: 1 ACK", "Content-Length: 0", "", ""]
            send("\r\n".join(ack), addr)
            log("Il telefono ha risposto: ACK inviato")
            c["state"] = "talking"
            threading.Thread(target=bye_later, args=(cid, h, addr, False), daemon=True).start()
        continue
    if method == "REGISTER":
        ok = check_auth(h, "REGISTER")
        if ok is None:
            resp("401 Unauthorized", h, addr, [f'WWW-Authenticate: Digest realm="{REALM}", nonce="{NONCE}", algorithm=MD5'])
        elif ok:
            phone["addr"] = addr
            phone["contact"] = uri_of(h.get("contact"), f"sip:201@{addr[0]}:{addr[1]}")
            exp = h.get("expires", "600")
            resp("200 OK", h, addr, [f"Contact: {h.get('contact', '')};expires={exp}", f"Expires: {exp}"])
            log("Registrato:", phone["contact"])
            if incoming and not phone.get("called"):
                phone["called"] = True
                threading.Thread(target=call_phone, daemon=True).start()
        else:
            resp("403 Forbidden", h, addr)
    elif method == "SUBSCRIBE":
        ext = first.split(" ")[1].split("sip:")[1].split("@")[0]
        resp("200 OK", h, addr, ["Expires: 600", "Event: dialog"], to_tag="blf" + ext)
        subs[ext] = (addr, h, 0)
        notify(ext)
    elif method == "OPTIONS":
        resp("200 OK", h, addr, ["Allow: INVITE, ACK, CANCEL, BYE, OPTIONS, SUBSCRIBE, NOTIFY"])
    elif method == "INVITE":
        ext = first.split(" ")[1].split("sip:")[1].split("@")[0]
        if ext == "486":
            resp("486 Busy Here", h, addr)
        elif ext == "404" or ext not in ("301", "202", "203", "204"):
            resp("404 Not Found", h, addr)
        elif "tag=" in h["to"]:
            resp("200 OK", h, addr, body=SDP)  # re-INVITE (attesa, video)
        else:
            resp("100 Trying", h, addr)
            resp("180 Ringing", h, addr)
            calls[h["call-id"]] = {"state": "ringing"}

            def answer(h=h, addr=addr):
                time.sleep(2)
                if calls.get(h["call-id"], {}).get("state") != "ringing":
                    return
                resp("200 OK", h, addr, body=SDP)
                calls[h["call-id"]]["state"] = "talking"
                threading.Thread(target=bye_later, args=(h["call-id"], h, addr, True), daemon=True).start()
            threading.Thread(target=answer, daemon=True).start()
    elif method == "CANCEL":
        resp("200 OK", h, addr)
        calls.setdefault(h["call-id"], {})["state"] = "ended"
    elif method in ("BYE",):
        resp("200 OK", h, addr)
        calls.setdefault(h["call-id"], {})["state"] = "ended"
    elif method in ("ACK", "NOTIFY", "INFO", "REFER"):
        if method != "ACK":
            resp("200 OK", h, addr)
