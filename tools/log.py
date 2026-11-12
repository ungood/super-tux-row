import serial, time, sys, json
s = serial.Serial("/dev/ttyACM0", 19200, timeout=0.05)
f = open(sys.argv[1], "w"); t0 = time.time()
def ev(kind, **kw): f.write(json.dumps({"t": round(time.time()-t0, 3), "k": kind, **kw}) + "\n")
def handle(l):
    if not l: return None
    if l.startswith("ID") or l == "ERROR": return l
    if l in ("SS", "SE", "PING"): ev(l)
    elif l.startswith("P"): ev("P", n=int(l[1:], 16))
    else: ev("other", l=l)
def cmd(c):
    s.write((c + "\r\n").encode()); end = time.time() + 0.5
    while time.time() < end:
        r = handle(s.readline().decode().strip())
        if r: return r
    return None
cmd("USB")
S = "00D 041 042 043 044 047 057 083 084 088 08A 140 14A 1A0 1A1 1A9 1E0 1E1".split()
D = "041 043 057 083 088 140 14A 1A0".split()
reads = [f"IRS{a}" for a in S] + [f"IRD{a}" for a in D] + ["IRT08A"]
end = time.time() + float(sys.argv[2])
while time.time() < end:
    for c in reads:
        r = cmd(c); ev("R", c=c, v=(r[6:] if r and r.startswith("ID") else r))
s.write(b"EXIT\r\n"); f.close()
