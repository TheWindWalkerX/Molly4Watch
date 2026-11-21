"""Add to ORIG only the components/artifacts that Gradle's freshly written NEW file has and ORIG lacks.
Never removes or changes anything in ORIG. Usage: merge_meta.py ORIG NEW OUT"""
import sys, re
orig, new, out = sys.argv[1:4]
O = open(orig, encoding="utf-8").read(); N = open(new, encoding="utf-8").read()
comp_re = re.compile(r'^      <component group="([^"]+)" name="([^"]+)" version="([^"]+)">\n(.*?)^      </component>\n', re.M | re.S)
art_re = re.compile(r'^         <artifact name="([^"]+)">\n.*?^         </artifact>\n', re.M | re.S)
ocomps = {m.group(1, 2, 3): m for m in comp_re.finditer(O)}
added = []
inserts = []  # (position in O, text)
for m in comp_re.finditer(N):
    key = m.group(1, 2, 3)
    if key not in ocomps:
        # insert before first orig component sorting after key
        pos = next((om.start() for k, om in sorted(ocomps.items()) if k > key), None)
        if pos is None: sys.exit("no insertion point for %s" % (key,))
        inserts.append((pos, m.group(0))); added.append(":".join(key))
    else:
        om = ocomps[key]
        have = {a.group(1) for a in art_re.finditer(om.group(4))}
        for a in art_re.finditer(m.group(4)):
            if a.group(1) not in have:
                inserts.append((om.start(4), a.group(0))); added.append(":".join(key) + " +" + a.group(1))
                # sanity: same artifact must not have a different checksum elsewhere
for pos, text in sorted(inserts, key=lambda t: t[0], reverse=True):
    O = O[:pos] + text + O[pos:]
open(out, "w", encoding="utf-8").write(O)
print("\n".join(added) or "nothing new")
