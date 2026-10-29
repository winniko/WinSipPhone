"""Prova diverse varianti di INVITE verso un citofono/telefono e mostra quale accetta.

Ogni variante: INVITE -> se 1xx aspetta fino a 3 s -> CANCEL (o BYE se risponde 200).
Uso: python -I test/probe_invite.py <ip_destinazione> [porta=5060] [numero=101] [ip_locale]
"""
import socket
import sys
import time
import uuid

dst = sys.argv[1]
dport = int(sys.argv[2]) if len(sys.argv) > 2 else 5060
num = sys.argv[3] if len(sys.argv) > 3 else "101"
lip = sys.argv[4] if len(sys.argv) > 4 else socket.gethostbyname(socket.gethostname())
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind((lip, 0))
lport = s.getsockname()[1]
s.settimeout(0.5)

AUDIO_MIN = "m=audio 40000 RTP/AVP 0 8 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=rtpmap:101 telephone-event/8000\r\na=fmtp:101 0-15\r\na=sendrecv\r\n"
VIDEO_H264 = "m=video 40002 RTP/AVP 99\r\na=rtpmap:99 H264/90000\r\na=fmtp:99 profile-level-id=42801F\r\na=sendrecv\r\n"
LINPHONE_AUDIO = ("a=rtcp-xr:rcvr-rtt=all:10000 stat-summary=loss,dup,jitt,TTL voip-metrics\r\n"
                  "m=audio 40000 RTP/AVP 96 9 8 0 101 97\r\na=rtpmap:96 opus/48000/2\r\na=fmtp:96 useinbandfec=1\r\n"
                  "a=rtpmap:101 telephone-event/48000\r\na=rtpmap:97 telephone-event/8000\r\na=rtcp:40001\r\n"
                  "a=rtcp-fb:* trr-int 5000\r\na=rtcp-fb:* ccm tmmbr\r\n")


def sdp(body):
    return f"v=0\r\no=- 1 1 IN IP4 {lip}\r\ns=Talk\r\nc=IN IP4 {lip}\r\nt=0 0\r\n" + body


VARIANTS = [
    ("1 come l'app (linphone, solo audio)", "linphone", sdp(LINPHONE_AUDIO), True),
    ("2 audio G.711 semplice", "tester", sdp(AUDIO_MIN), False),
    ("3 audio G.711 + video H.264", "tester", sdp(AUDIO_MIN + VIDEO_H264), False),
    ("4 come 3 ma From con IP e numero 9999", "9999", sdp(AUDIO_MIN + VIDEO_H264), False),
]


def send(msg):
    s.sendto(msg.encode(), (dst, dport))


def recv_all(timeout):
    out, end = [], time.time() + timeout
    while time.time() < end:
        try:
            d, _ = s.recvfrom(65535)
            out.append(d.decode(errors="replace"))
            if out[-1].split(" ")[1][:1] in "23456" and "INVITE" in out[-1].split("CSeq:")[1].split("\r\n")[0]:
                break
        except socket.timeout:
            pass
        except ConnectionResetError:
            out.append("SIP/2.0 000 porta non raggiungibile")
            break
    return out


for name, user, body, lime in VARIANTS:
    cid, tag, br = uuid.uuid4().hex[:12], uuid.uuid4().hex[:8], "z9hG4bK" + uuid.uuid4().hex[:10]
    contact = f"<sip:{user}@{lip}:{lport};transport=udp>" + (';+org.linphone.specs="lime"' if lime else "")
    hdr = (f"Via: SIP/2.0/UDP {lip}:{lport};branch={br};rport\r\nFrom: <sip:{user}@{lip}>;tag={tag}\r\n"
           f"To: sip:{num}@{dst}\r\nCall-ID: {cid}\r\n")
    extra = "Supported: replaces, outbound, gruu, path\r\n" if lime else ""
    inv = (f"INVITE sip:{num}@{dst}:{dport} SIP/2.0\r\n{hdr}CSeq: 20 INVITE\r\nMax-Forwards: 70\r\n{extra}"
           "Allow: INVITE, ACK, CANCEL, OPTIONS, BYE, REFER, NOTIFY, INFO, UPDATE\r\nContent-Type: application/sdp\r\n"
           f"Contact: {contact}\r\nUser-Agent: WinSipPhone-Probe\r\nContent-Length: {len(body)}\r\n\r\n{body}")
    send(inv)
    msgs = recv_all(3.5)
    codes = [m.split("\r\n")[0] for m in msgs]
    print(f"Variante {name}: {' | '.join(codes) or 'nessuna risposta'}", flush=True)
    final = next((m for m in msgs if m.split(" ")[1][:1] in "23456"), None)
    to_tagged = next((l for m in msgs for l in m.split("\r\n") if l.startswith("To:") and "tag=" in l), f"To: sip:{num}@{dst}")
    if final is None:
        # ancora squilla: CANCEL
        send(f"CANCEL sip:{num}@{dst}:{dport} SIP/2.0\r\nVia: SIP/2.0/UDP {lip}:{lport};branch={br};rport\r\n"
             f"From: <sip:{user}@{lip}>;tag={tag}\r\nTo: sip:{num}@{dst}\r\nCall-ID: {cid}\r\nCSeq: 20 CANCEL\r\n"
             "Max-Forwards: 70\r\nContent-Length: 0\r\n\r\n")
        print("   (squillava: CANCEL inviato)", flush=True)
        final_msgs = recv_all(2)
        print("   ", " | ".join(m.split("\r\n")[0] for m in final_msgs), flush=True)
        to_tagged = next((l for m in final_msgs for l in m.split("\r\n") if l.startswith("To:") and "tag=" in l), to_tagged)
    # ACK della risposta finale
    send(f"ACK sip:{num}@{dst}:{dport} SIP/2.0\r\nVia: SIP/2.0/UDP {lip}:{lport};branch={br};rport\r\n"
         f"From: <sip:{user}@{lip}>;tag={tag}\r\n{to_tagged}\r\nCall-ID: {cid}\r\nCSeq: 20 ACK\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n")
    if final and final.split(" ")[1].startswith("2"):
        time.sleep(0.5)
        send(f"BYE sip:{num}@{dst}:{dport} SIP/2.0\r\nVia: SIP/2.0/UDP {lip}:{lport};branch=z9hG4bK{uuid.uuid4().hex[:10]};rport\r\n"
             f"From: <sip:{user}@{lip}>;tag={tag}\r\n{to_tagged}\r\nCall-ID: {cid}\r\nCSeq: 21 BYE\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n")
        print("   (aveva risposto: BYE inviato)", flush=True)
    if final:
        for l in final.split("\r\n"):
            if l.lower().startswith(("warning", "reason")):
                print("   ", l, flush=True)
    time.sleep(1.5)
