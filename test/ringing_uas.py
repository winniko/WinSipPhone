"""Finto telefono che SQUILLA e non risponde mai (per provare il riaggancio prima della risposta).

Risponde all'INVITE con 100 Trying + 180 Ringing (modo "180") oppure 183 Session Progress con SDP
(modo "183", early media come KioskKNX). Al CANCEL risponde 200 OK + 487 e stampa l'orario:
se il CANCEL non arriva, il telefono "continuerebbe a squillare".
Uso: python -I test/ringing_uas.py [porta=5070] [180|183]
"""
import socket
import sys
import time

port = int(sys.argv[1]) if len(sys.argv) > 1 else 5070
mode = sys.argv[2] if len(sys.argv) > 2 else "180"
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", port))
print(f"Telefono che squilla in ascolto UDP {port}, modo {mode}", flush=True)
SDP = ("v=0\r\no=- 1 1 IN IP4 10.0.2.2\r\ns=-\r\nc=IN IP4 10.0.2.2\r\nt=0 0\r\n"
       "m=audio 41000 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=sendrecv\r\n")


def hdrs(msg):
    lines = msg.split("\r\n")
    return lines[0], {l.split(":", 1)[0].strip().lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}


def resp(code, h, addr, body="", cseq=None):
    to = h["to"] if "tag=" in h["to"] else h["to"] + ";tag=ring1"
    out = [f"SIP/2.0 {code}", f"Via: {h['via']}", f"From: {h['from']}", f"To: {to}", f"Call-ID: {h['call-id']}",
           f"CSeq: {cseq or h['cseq']}", f"Contact: <sip:telefono@10.0.2.2:{port}>"]
    if body:
        out.append("Content-Type: application/sdp")
    out += [f"Content-Length: {len(body)}", "", ""]
    s.sendto(("\r\n".join(out) + body).encode(), addr)


invites = {}
while True:
    try:
        data, addr = s.recvfrom(65535)
    except ConnectionResetError:
        # Windows: l'ultimo pacchetto inviato ha ricevuto "porta non raggiungibile" (ICMP)
        print(time.strftime("%H:%M:%S"), "   !!! il telefono ha RIFIUTATO la risposta (porta non raggiungibile)", flush=True)
        continue
    msg = data.decode(errors="replace")
    if not msg.strip():
        continue
    first, h = hdrs(msg)
    print(time.strftime("%H:%M:%S"), "da", addr, "->", first, flush=True)
    if first.startswith("INVITE"):
        for k in ("via", "contact", "from", "user-agent"):
            print(f"   {k}: {h.get(k, '')}", flush=True)
    if first.startswith("INVITE") and h["call-id"] not in invites:
        invites[h["call-id"]] = h
        resp("100 Trying", h, addr)
        if mode == "183":
            resp("183 Session Progress", h, addr, body=SDP)
        else:
            resp("180 Ringing", h, addr)
        print("   ... squilla", flush=True)
    elif first.startswith("CANCEL"):
        resp("200 OK", h, addr)
        inv = invites.get(h["call-id"])
        if inv:
            resp("487 Request Terminated", inv, addr)
        print("   CANCEL ricevuto: smetto di squillare", flush=True)
    elif first.startswith("BYE"):
        resp("200 OK", h, addr)
        print("   BYE ricevuto", flush=True)
