# -*- coding: utf-8 -*-
"""الفحص الشامل النهائي: 12 طبقة تدقيق"""
import os, re, sys, collections, xml.etree.ElementTree as ET
import pathlib
ROOT=str(pathlib.Path(__file__).resolve().parent.parent); RES=os.path.join(ROOT,'app/src/main/res'); SRC=os.path.join(ROOT,'app/src/main/java'); NS='{http://schemas.android.com/apk/res/android}'
errors=[]; warns=[]; infos=[]
def err(m): errors.append(m)
def warn(m): warns.append(m)

def kt_files(root=SRC):
    out=[]
    for b,_,fs in os.walk(root):
        for f in fs:
            if f.endswith('.kt'): out.append(os.path.join(b,f))
    return sorted(out)

# ---------- قراءة الموارد ----------
index=collections.defaultdict(set); origin={}
trees={}
for b,_,fs in os.walk(RES):
    for f in fs:
        if not f.endswith('.xml'): continue
        p=os.path.join(b,f)
        try: trees[p]=ET.parse(p).getroot()
        except ET.ParseError as e: err(f"XML غير صالح: {p}: {e}")
MAN=os.path.join(ROOT,'app/src/main/AndroidManifest.xml')
trees[MAN]=ET.parse(MAN).getroot()
for p,root in trees.items():
    if '/values' in p:
        for ch in root:
            n=ch.attrib.get('name')
            if n: index[ch.tag].add(n); origin[(ch.tag,n)]=p
    elif '/res/' in p:
        kind=os.path.basename(os.path.dirname(p)).split('-')[0]
        stem=os.path.basename(p).split('.')[0]
        index[kind].add(stem); origin[(kind,stem)]=p

# ---------- 1) تكرار الموارد داخل نفس المجلد ----------
seen=collections.defaultdict(list)
for p,root in trees.items():
    if '/values' not in p: continue
    folder=os.path.dirname(p)
    for ch in root:
        n=ch.attrib.get('name')
        if n: seen[(folder,ch.tag,n)].append(os.path.basename(p))
for k,v in seen.items():
    if len(v)>1: err(f"مورد مكرر في {k[0]}: <{k[1]} name=\"{k[2]}\"> في {v}")

# ---------- 2) أسماء ملفات الموارد ----------
for b,_,fs in os.walk(RES):
    for f in fs:
        stem=f.split('.')[0]
        if not re.fullmatch(r'[a-z][a-z0-9_]*', stem): err(f"اسم ملف موارد غير صالح: {os.path.join(b,f)}")

# ---------- 3) كل المراجع ----------
REF=re.compile(r'@(?:\+)?(?:android:)?([a-z]+)/([A-Za-z0-9_.]+)')
for p,root in trees.items():
    for el in root.iter():
        val=el.attrib.get('{http://schemas.android.com/tools}targetApi','')
        for a,v in list(el.attrib.items())+[('parent',el.attrib.get('parent',''))]:
            if not isinstance(v,str): continue
            if v.startswith('@android:') or v.startswith('?android:') or v.startswith('?'): continue
            for kind,name in REF.findall(v):
                if kind=='id': continue
                if name not in index.get(kind,()):
                    err(f"{os.path.relpath(p)}: مرجع مفقود @{kind}/{name} (السمة {a})")

# ---------- 4) مراجع R في الكود ----------
ids=set()
for p,root in trees.items():
    if '/layout' in p or '/menu' in p:
        for el in root.iter():
            v=el.attrib.get(NS+'id','')
            if v.startswith('@+id/'): ids.add(v[5:])
RP=re.compile(r'\bR\.(string|drawable|color|dimen|style|layout|xml|mipmap|anim|id|menu|bool|integer|array)\.([A-Za-z0-9_]+)')
for f in kt_files():
    s=open(f,encoding='utf-8').read()
    for kind,name in RP.findall(s):
        if kind=='id':
            if name not in ids: err(f"{f}: R.id.{name} غير موجود")
        elif name not in index.get(kind,()):
            err(f"{f}: R.{kind}.{name} غير موجود")

# ---------- 5) viewBinding ----------
def bind(stem): return ''.join(x[:1].upper()+x[1:] for x in stem.split('_'))+'Binding'
layouts={}
for p,root in trees.items():
    if '/layout' not in p: continue
    stem=os.path.basename(p).split('.')[0]
    layouts[stem]={el.attrib.get(NS+'id','')[5:] for el in root.iter() if el.attrib.get(NS+'id','').startswith('@+id/')}
c2l={bind(k):k for k in layouts}
for f in kt_files():
    s=open(f,encoding='utf-8').read()
    pairs=re.findall(r'(?:val\s+)?(\w+)\s*=\s*(\w+Binding)\.inflate\(', s)
    grouped=collections.defaultdict(set)
    for v,c in pairs: grouped[v].add(c)
    vmap={v:next(iter(cs)) for v,cs in grouped.items() if len(cs)==1}
    for var,field in re.findall(r'\b(\w+)\.([A-Za-z_]\w*)\b', s):
        cls=vmap.get(var)
        if not cls: continue
        if field=='root': continue
        stem=c2l.get(cls)
        if stem is None: err(f"{f}: فئة binding مجهولة {cls}"); continue
        if field not in layouts[stem]: err(f"{f}: {var}.{field} غير موجود في layout/{stem}.xml")

# ---------- 6) المانيفست ----------
app=trees[MAN].find('application')
for tag in ('activity','service','provider','receiver'):
    for el in trees[MAN].iter(tag):
        nm=el.attrib.get(NS+'name','')
        if not nm or nm.startswith(('android.','androidx.')): continue
        cls = 'com.shadowupload.app'+nm if nm.startswith('.') else nm
        path=os.path.join(SRC,*cls.split('.'))+'.kt'
        if not os.path.exists(path): err(f"مانيفست: {nm} غير موجود")
if app.attrib.get(NS+'supportsRtl')!='true': err("supportsRtl ليس true")

# ---------- 7) صياغة Kotlin ----------
from tree_sitter import Language, Parser
import tree_sitter_kotlin as tsk
parser=Parser(Language(tsk.language()))
for f in kt_files()+[p for p in kt_files(os.path.join(ROOT,'app/src/test'))]:
    t=parser.parse(open(f,'rb').read())
    if t.root_node.has_error:
        # ابحث عن أول عقدة خاطئة
        stack=[t.root_node]; line=None
        while stack:
            n=stack.pop()
            if n.has_error and n.child_count==0: line=n.start_point[0]+1; break
            stack.extend(n.children)
        err(f"{f}: خطأ صياغة Kotlin (سطر {line})")

# ---------- 8) توازن {} و () في Kotlin باحترام السلاسل ----------
def strip_strings(s):
    out=[]; i=0; n=len(s)
    while i<n:
        if s[i]=='"':
            if s[i:i+3]=='"""':
                j=s.find('"""',i+3); j=n if j<0 else j+3; out.append('""'); i=j; continue
            j=i+1
            while j<n and s[j]!='"':
                if s[j]=='\\': j+=1
                j+=1
            out.append('""'); i=j+1; continue
        if s[i]=="'":
            j=i+1
            while j<n and s[j]!="'":
                if s[j]=='\\': j+=1
                j+=1
            out.append("''"); i=j+1; continue
        if s[i:i+2]=='//':
            j=s.find('\n',i); i=n if j<0 else j; continue
        if s[i:i+2]=='/*':
            j=s.find('*/',i+2); i=n if j<0 else j+2; continue
        out.append(s[i]); i+=1
    return ''.join(out)
for f in kt_files():
    code=strip_strings(open(f,encoding='utf-8').read())
    for open_c,close_c in (('{','}'),('(',')')):
        if code.count(open_c)!=code.count(close_c):
            err(f"{f}: عدم توازن {open_c}{close_c}: {code.count(open_c)} مقابل {code.count(close_c)}")

# ---------- 9) نصوص مضمّنة في التخطيطات ----------
for p,root in trees.items():
    if '/layout' not in p: continue
    for el in root.iter():
        for a in ('text','hint'):
            v=el.attrib.get(NS+a,'')
            if v and not v.startswith(('@','?','{')): err(f"{p}: نص مضمّن android:{a}=\"{v}\"")

# ---------- 10) إمكانية الوصول ----------
for p,root in trees.items():
    if '/layout' not in p: continue
    for el in root.iter():
        tag=el.tag.split('.')[-1]
        if tag in ('ImageView','ImageButton','FloatingActionButton') and NS+'contentDescription' not in el.attrib:
            err(f"{p}: <{tag}> بلا contentDescription")

# ---------- 11) وسائط النصوص ----------
for p,root in trees.items():
    if '/values' not in p or not os.path.basename(p).startswith('strings'): continue
    for el in root:
        if el.tag!='string': continue
        txt=''.join(el.itertext()); n=el.attrib.get('name')
        ph=re.findall(r'%(\d+)\$[sd]',txt); plain=re.findall(r'%(?!\d+\$)[sd]',txt)
        if ph and plain: err(f"نص يخلط وسائط مرقمة وغير مرقمة: {n}")
        if ph:
            idx=sorted({int(x) for x in ph})
            if idx!=list(range(1,len(idx)+1)): err(f"وسائط غير متسلسلة: {n} {idx}")

# ---------- 12) استيرادات داخلية ----------
classes=set()
for f in kt_files():
    s=open(f,encoding='utf-8').read()
    pkg=re.search(r'^package\s+([\w.]+)',s,re.M)
    if not pkg: continue
    pg=pkg.group(1)
    for m in re.finditer(r'^\s*(?:data\s+|sealed\s+|abstract\s+|open\s+|inner\s+|enum\s+)?(?:class|object)\s+(\w+)',s,re.M):
        classes.add(f"{pg}.{m.group(1)}")
nest=set()
for f in kt_files():
    s=open(f,encoding='utf-8').read()
    pkg=re.search(r'^package\s+([\w.]+)',s,re.M)
    if not pkg: continue
    pg=pkg.group(1)
    outers=re.findall(r'^(?:private |internal )?(?:data |sealed |abstract |open |inner )?class\s+(\w+)',s,re.M)
    for m in re.finditer(r'^\s{4,}(?:data\s+|sealed\s+|abstract\s+|open\s+|inner\s+)?class\s+(\w+)',s,re.M):
        for o in outers: nest.add(f"{pg}.{o}.{m.group(1)}")
known=classes|nest
for f in kt_files():
    s=open(f,encoding='utf-8').read()
    for imp in re.findall(r'^import\s+(com\.shadowupload[\w.]+)',s,re.M):
        if imp.endswith('.R') or '.databinding.' in imp: continue
        if imp in known: continue
        parent=imp.rsplit('.',1)[0]
        if any(k.startswith(parent+'.') for k in known): continue
        warn(f"{f}: استيراد داخلي غير مؤكد {imp}")

print(f"ملفات Kotlin: {len(kt_files())} | XML: {len(trees)} | نصوص: {len(index['string'])}")
print(f"\n❌ أخطاء ({len(errors)}):")
for e in errors[:40]: print("  -",e)
print(f"\n⚠️ تحذيرات ({len(warns)}):")
for w in warns[:15]: print("  -",w)
sys.exit(1 if errors else 0)
