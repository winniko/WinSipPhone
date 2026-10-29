"""Cambia UNA intestazione alla volta rispetto all'INVITE che il citofono Hikvision accetta, con pausa tra le prove.

Uso: python -I test/probe_headers.py <ip_citofono> <ip_pc> <dominio_mittente> [pausa_s=200]
"""
import socket
import sys
import time
import uuid

dst, lip, fdom = sys.argv[1], sys.argv[2], sys.argv[3]
pause = int(sys.argv[4]) if len(sys.argv) > 4 else 200
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind((lip, 0))
lport = s.getsockname()[1]
s.settimeout(0.5)
SDP = (f"v=0\r\no=- 1 1 IN IP4 {lip}\r\ns=Talk\r\nc=IN IP4 {lip}\r\nt=0 0\r\n"
       "m=audio 40000 RTP/AVP 0 8 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=rtpmap:101 telephone-event/8000\r\na=sendrecv\r\n")
BASE = dict(uri_port=True, to_br=True, transport=False, lime=False, supported=False, cseq=1)
CHANGES = [
    ("0 base", {}),
    ("a URI senza :5060", {"uri_port": False}),
    ("b To senza < >", {"to_br": False}),
    ("c Contact con ;transport=udp", {"transport": True}),
    ("d Contact con +org.linphone.specs=lime", {"lime": True}),
    ("e Supported: replaces, outbound, gruu, path", {"supported": True}),
    ("f CSeq 20", {"cseq": 20}),
]


def run(name, o):
    cid, tag, br = uuid.uuid4().hex[:12], uuid.uuid4().hex[:8], "z9hG4bK" + uuid.uuid4().hex[:10]
    ruri = f"sip:101@{dst}" + (":5060" if o["uri_port"] else "")
    to = f"<sip:101@{dst}>" if o["to_br"] else f"sip:101@{dst}"
    contact = f"<sip:200@{lip}:{lport}" + (";transport=udp" if o["transport"] else "") + ">" + (';+org.linphone.specs="lime"' if o["lime"] else "")
    cseq = o["cseq"]
    inv = (f"INVITE {ruri} SIP/2.0\r\nVia: SIP/2.0/UDP {lip}:{lport};branch={br};rport\r\nFrom: <sip:200@{fdom}>;tag={tag}\r\n"
           f"To: {to}\r\nCall-ID: {cid}\r\nCSeq: {cseq} INVITE\r\nMax-Forwards: 70\r\n"
           + ("Supported: replaces, outbound, gruu, path\r\n" if o["supported"] else "")
           + f"Contact: {contact}\r\nContent-Type: application/sdp\r\nContent-Length: {len(SDP)}\r\n\r\n{SDP}")
    s.sendto(inv.encode(), (dst, 5060))
    got, end, totag = [], time.time() + 4, f"To: {to}"
    while time.time() < end:
        try:
            d = s.recvfrom(65535)[0].decode(errors="replace")
            if not d.startswith("SIP/2.0") or cid not in d:
                continue
            got.append(d.split("\r\n")[0][8:])
            for l in d.split("\r\n"):
                if l.startswith("To:") and "tag=" in l:
                    totag = l
            if d.split(" ")[1][0] in "23456":
                break
        except socket.timeout:
            pass
    print(time.strftime("%H:%M:%S"), name, "=>", " | ".join(got) or "nessuna risposta", flush=True)
    last = got[-1] if got else ""
    if last.startswith("1"):
        s.sendto((f"CANCEL {ruri} SIP/2.0\r\nVia: SIP/2.0/UDP {lip}:{lport};branch={br};rport\r\nFrom: <sip:200@{fdom}>;tag={tag}\r\nTo: {to}\r\n"
                  f"Call-ID: {cid}\r\nCSeq: {cseq} CANCEL\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n").encode(), (dst, 5060))
    if last.startswith("2"):
        time.sleep(1)
        s.sendto((f"BYE {ruri} SIP/2.0\r\nVia: SIP/2.0/UDP {lip}:{lport};branch=z9hG4bKb{tag};rport\r\nFrom: <sip:200@{fdom}>;tag={tag}\r\n{totag}\r\n"
                  f"Call-ID: {cid}\r\nCSeq: {cseq + 1} BYE\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n").encode(), (dst, 5060))
    s.sendto((f"ACK {ruri} SIP/2.0\r\nVia: SIP/2.0/UDP {lip}:{lport};branch={br};rport\r\nFrom: <sip:200@{fdom}>;tag={tag}\r\n{totag}\r\n"
              f"Call-ID: {cid}\r\nCSeq: {cseq} ACK\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n").encode(), (dst, 5060))


for i, (name, ch) in enumerate(CHANGES):
    time.sleep(pause)
    run(name, {**BASE, **ch})
