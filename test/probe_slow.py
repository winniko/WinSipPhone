"""Prove INVITE distanziate (il citofono Hikvision resta "bloccato" qualche minuto dopo un rifiuto).

Ogni variante parte dalla chiamata semplice che il citofono accetta e cambia UNA cosa.
Uso: python -I test/probe_slow.py <ip_citofono> <ip_pc> <dominio_mittente> [pausa_s=200]
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

SIMPLE = (f"v=0\r\no=- 1 1 IN IP4 {lip}\r\ns=Talk\r\nc=IN IP4 {lip}\r\nt=0 0\r\n"
          "m=audio 40000 RTP/AVP 0 8 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=rtpmap:101 telephone-event/8000\r\na=sendrecv\r\n")
APP = (f"v=0\r\no=linphone 318 3072 IN IP4 {lip}\r\ns=Talk\r\nc=IN IP4 {lip}\r\nt=0 0\r\n"
       "a=rtcp-xr:rcvr-rtt=all:10000 stat-summary=loss,dup,jitt,TTL voip-metrics\r\n"
       "m=audio 44217 RTP/AVP 96 9 8 0 101 97\r\na=rtpmap:96 opus/48000/2\r\na=fmtp:96 useinbandfec=1\r\na=rtpmap:101 telephone-event/48000\r\n"
       "a=rtpmap:97 telephone-event/8000\r\na=rtcp:39435\r\na=rtcp-fb:* trr-int 5000\r\na=rtcp-fb:* ccm tmmbr\r\n")
APP_NO_OPUS = (f"v=0\r\no=linphone 318 3072 IN IP4 {lip}\r\ns=Talk\r\nc=IN IP4 {lip}\r\nt=0 0\r\n"
               "m=audio 44217 RTP/AVP 8 0 101\r\na=rtpmap:101 telephone-event/8000\r\n")

# (nome, sdp, uri_con_porta, to_con_<>, contact_extra, supported, cseq)
VARIANTS = [
    ("1 controllo (chiamata semplice riuscita prima)", SIMPLE, True, True, "", False, 1),
    ("2 come 1 ma SDP dell'app (opus, rtcp-xr...)", APP, True, True, "", False, 1),
    ("3 come 1 ma intestazioni dell'app (To senza <>, URI senza porta, Contact lime, Supported, CSeq 20)", SIMPLE, False, False, ';+org.linphone.specs="lime"', True, 20),
    ("4 come 1 ma SDP app SENZA opus/telephone-event 48k/rtcp", APP_NO_OPUS, True, True, "", False, 1),
]


def run(name, sdp, uri_port, to_br, cextra, sup, cseq):
    cid, tag, br = uuid.uuid4().hex[:12], uuid.uuid4().hex[:8], "z9hG4bK" + uuid.uuid4().hex[:10]
    ruri = f"sip:101@{dst}" + (":5060" if uri_port else "")
    to = f"<sip:101@{dst}>" if to_br else f"sip:101@{dst}"
    contact = f"<sip:200@{lip}:{lport}" + (";transport=udp>" if cextra else ">") + cextra
    inv = (f"INVITE {ruri} SIP/2.0\r\nVia: SIP/2.0/UDP {lip}:{lport};branch={br};rport\r\nFrom: <sip:200@{fdom}>;tag={tag}\r\n"
           f"To: {to}\r\nCall-ID: {cid}\r\nCSeq: {cseq} INVITE\r\nMax-Forwards: 70\r\n"
           + ("Supported: replaces, outbound, gruu, path\r\n" if sup else "")
           + f"Contact: {contact}\r\nContent-Type: application/sdp\r\nContent-Length: {len(sdp)}\r\n\r\n{sdp}")
    s.sendto(inv.encode(), (dst, 5060))
    got, end, totag = [], time.time() + 4, f"To: {to}"
    while time.time() < end:
        try:
            d = s.recvfrom(65535)[0].decode(errors="replace")
            if not d.startswith("SIP/2.0"):
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


for i, v in enumerate(VARIANTS):
    if i:
        time.sleep(pause)
    run(*v)
