#!/usr/bin/env python3
"""Static compatibility check: which classes, methods and fields do the given dex files (jars/apks) use
that the target Android build does not provide?

  dexcheck.py --world DIR_OR_JAR [--world ...] SUBJECT.jar|apk [...]

World = the dex files that make up the target system (e.g. out/.../system/framework/*.jar). Subjects are
checked against it AND against each other (the subjects load together). Reports:
  MISSING METHOD / FIELD   the class exists in the world but no such member exists in it or its supertypes
  UNKNOWN                  the lookup reached a supertype that is not in the world (could be inherited)
  UNRESOLVED CLASS         a class from android.* / com.android.* / com.mediatek.* that no dex provides
                           (it may live in an APEX that was not given to --world)
Plain Java classes (java.*, javax.*, dalvik.*, libcore.*, sun.*, org.*, kotlin.*, androidx.*) are ignored.
Read-only; no dependencies beyond the standard library.
"""
import os, struct, sys, zipfile, collections

IGNORE = ("Ljava/", "Ljavax/", "Ldalvik/", "Llibcore/", "Lsun/", "Lorg/", "Lkotlin/", "Landroidx/", "Lcom/google/", "Ljdk/")
CHECK_PREFIX = ("Landroid/", "Lcom/android/", "Lcom/mediatek/", "Lvendor/", "Lmediatek/", "Lorg/lineageos")
OBJECT_METHODS = {("equals", "(Ljava/lang/Object;)Z"), ("hashCode", "()I"), ("toString", "()Ljava/lang/String;"), ("getClass", "()Ljava/lang/Class;"),
                  ("notify", "()V"), ("notifyAll", "()V"), ("wait", "()V"), ("wait", "(J)V"), ("wait", "(JI)V"), ("clone", "()Ljava/lang/Object;"),
                  ("finalize", "()V")}


def uleb(d, p):
    r = 0; s = 0
    while True:
        b = d[p]; p += 1; r |= (b & 0x7f) << s; s += 7
        if not b & 0x80: return r, p


class Dex:
    def __init__(self, data, origin):
        self.d = data; self.origin = origin
        (self.str_n, self.str_o, self.type_n, self.type_o, self.proto_n, self.proto_o, self.field_n, self.field_o,
         self.meth_n, self.meth_o, self.cls_n, self.cls_o) = struct.unpack_from("<12I", data, 0x38)
        self._s = {}

    def string(self, i):
        if i in self._s: return self._s[i]
        off = struct.unpack_from("<I", self.d, self.str_o + 4 * i)[0]
        _, p = uleb(self.d, off); e = self.d.index(b"\0", p)
        s = self.d[p:e].decode("utf-8", "replace"); self._s[i] = s; return s

    def type(self, i): return self.string(struct.unpack_from("<I", self.d, self.type_o + 4 * i)[0])

    def proto(self, i):
        _, ret, po = struct.unpack_from("<III", self.d, self.proto_o + 12 * i)
        ps = ""
        if po:
            n = struct.unpack_from("<I", self.d, po)[0]
            ps = "".join(self.type(struct.unpack_from("<H", self.d, po + 4 + 2 * k)[0]) for k in range(n))
        return "(" + ps + ")" + self.type(ret)

    def method_ref(self, i):
        c, p, n = struct.unpack_from("<HHI", self.d, self.meth_o + 8 * i)
        return self.type(c), self.string(n), self.proto(p)

    def field_ref(self, i):
        c, t, n = struct.unpack_from("<HHI", self.d, self.field_o + 8 * i)
        return self.type(c), self.string(n), self.type(t)

    def classes(self):
        for k in range(self.cls_n):
            ci, acc, sup, ifo, _, _, cdo, _ = struct.unpack_from("<8I", self.d, self.cls_o + 32 * k)
            name = self.type(ci); supn = self.type(sup) if sup != 0xffffffff else None
            ifs = []
            if ifo:
                n = struct.unpack_from("<I", self.d, ifo)[0]
                ifs = [self.type(struct.unpack_from("<H", self.d, ifo + 4 + 2 * j)[0]) for j in range(n)]
            yield name, supn, ifs, acc, cdo


def parse_class_members(dex, cdo):
    """Return (methods{(name,proto)}, fields{(name,type)}) for one class_data_item."""
    d = dex.d; meths = set(); flds = set()
    if not cdo: return meths, flds
    p = cdo; sf, p = uleb(d, p); inf, p = uleb(d, p); dm, p = uleb(d, p); vm, p = uleb(d, p)
    for count in (sf, inf):
        idx = 0
        for _ in range(count):
            di, p = uleb(d, p); _acc, p = uleb(d, p); idx += di
            _c, fn, ft = dex.field_ref(idx); flds.add((fn, ft))
    for count in (dm, vm):
        idx = 0
        for _ in range(count):
            di, p = uleb(d, p); _acc, p = uleb(d, p); _code, p = uleb(d, p); idx += di
            _c, mn, mp = dex.method_ref(idx); meths.add((mn, mp))
    return meths, flds


def dex_blobs(path):
    if path.endswith((".jar", ".apk", ".zip")):
        try:
            z = zipfile.ZipFile(path)
        except zipfile.BadZipFile:
            return
        for n in sorted(z.namelist()):
            if n.startswith("classes") and n.endswith(".dex"): yield os.path.basename(path) + ":" + n, z.read(n)
    elif path.endswith(".dex"):
        yield os.path.basename(path), open(path, "rb").read()


def expand(paths):
    for p in paths:
        if os.path.isdir(p):
            fs = [f for f in sorted(os.listdir(p)) if f.endswith((".jar", ".apk"))]
            fs.sort(key=lambda f: f.startswith("mediatek"))          # the MediaTek jars come last on our boot class path
            for f in fs: yield os.path.join(p, f)
        else: yield p


def main():
    a = sys.argv[1:]; worlds = []; subjects = []
    i = 0
    while i < len(a):
        if a[i] == "--world": worlds.append(a[i + 1]); i += 2
        else: subjects.append(a[i]); i += 1
    world = {}                      # class -> dict(sup, ifs, meths, flds, origin)
    shadowed = collections.defaultdict(set)
    def load(path, tag):
        out = []
        for origin, blob in dex_blobs(path):
            dx = Dex(blob, origin); out.append(dx)
            for name, sup, ifs, acc, cdo in dx.classes():
                m, f = parse_class_members(dx, cdo)
                if name in world:                                                   # first definition wins, as on the boot class path
                    if world[name]["origin"].split(":")[0] != origin.split(":")[0]: shadowed[name].add(origin.split(":")[0] + " (shadowed by " + world[name]["origin"].split(":")[0] + ")")
                    continue
                world[name] = dict(sup=sup, ifs=ifs, meths=m, flds=f, origin=origin, tag=tag)
        return out
    for p in expand(worlds): load(p, "world")
    subj = []
    for p in expand(subjects): subj.extend((p, dx) for dx in load(p, "subject"))
    print("world classes: %d  (subjects: %d dex files)" % (len(world), len(subj)))

    def find(cls, kind, key, seen=None):
        """True found, False not found, None unknown (hit a type outside the world)."""
        seen = seen or set()
        if cls in seen: return False
        seen.add(cls)
        c = world.get(cls)
        if c is None:
            if cls == "Ljava/lang/Object;": return bool(kind == "m" and key in OBJECT_METHODS)      # end of every chain
            return None
        if key in c["meths" if kind == "m" else "flds"]: return True
        unknown = False
        for s in ([c["sup"]] if c["sup"] else []) + c["ifs"]:
            r = find(s, kind, key, seen)
            if r is True: return True
            if r is None: unknown = True
        if kind == "m" and key in OBJECT_METHODS: return True
        return None if unknown else False

    missing = collections.defaultdict(set); unknown = collections.defaultdict(set); unres = collections.defaultdict(set)
    for path, dx in subj:
        base = os.path.basename(path)
        for k in range(dx.meth_n):
            cls, name, proto = dx.method_ref(k)
            if cls.startswith("["): continue
            c = world.get(cls)
            if c is not None and c["tag"] == "subject":
                if find(cls, "m", (name, proto)) is False: missing[base].add("%s.%s%s" % (cls, name, proto))   # subject-internal gap
                continue
            if c is None:
                if cls.startswith(IGNORE) and not cls.startswith(("Lorg/lineageos",)): continue
                if cls.startswith(CHECK_PREFIX): unres[base].add(cls)
                continue
            r = find(cls, "m", (name, proto))
            if r is False: missing[base].add("%s.%s%s" % (cls, name, proto))
            elif r is None: unknown[base].add("%s.%s%s" % (cls, name, proto))
        for k in range(dx.field_n):
            cls, name, ty = dx.field_ref(k)
            if cls.startswith("["): continue
            c = world.get(cls)
            if c is None or c["tag"] == "subject": continue
            r = find(cls, "f", (name, ty))
            if r is False: missing[base].add("%s.%s:%s (field)" % (cls, name, ty))

    print("\n==== MISSING (class present, member absent) ====")
    total = 0
    for base in sorted(missing):
        print("\n## %s: %d" % (base, len(missing[base]))); total += len(missing[base])
        bycls = collections.defaultdict(list)
        for m in sorted(missing[base]): bycls[m.split(".", 1)[0]].append(m.split(".", 1)[1])
        for cls in sorted(bycls):
            print("  %s" % cls)
            for m in bycls[cls]: print("      %s" % m)
    print("\nTOTAL MISSING MEMBERS: %d" % total)
    print("\n==== UNRESOLVED CLASSES (no dex given provides them) ====")
    for base in sorted(unres):
        print("## %s: %d" % (base, len(unres[base])))
        for c in sorted(unres[base]): print("   ", c)
    print("\n==== SHADOWED: classes that a MediaTek jar defines but an earlier jar already provides (the MediaTek copy is never used) ====")
    byjar = collections.defaultdict(list)
    for cls, whos in shadowed.items():
        for w in whos: byjar[w].append(cls)
    for w in sorted(byjar):
        print("## %s: %d" % (w, len(byjar[w])))
        for c in sorted(byjar[w])[:40]: print("   ", c)
        if len(byjar[w]) > 40: print("    ... and %d more" % (len(byjar[w]) - 40))
    print("\n==== UNKNOWN (lookup reached a supertype outside the world): count only ====")
    for base in sorted(unknown): print("## %s: %d" % (base, len(unknown[base])))


if __name__ == "__main__":
    main()
