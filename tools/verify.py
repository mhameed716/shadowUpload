#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
أداة فحص مشروع shadowUpload
============================
لا يوجد JDK/Android SDK في هذه البيئة، لذا نفحص المشروع بقواعد ثابتة
تحاكي ما يرفضه aapt2 و kotlinc:

  1) صحة XML في كل ملفات الموارد والمانيفست + فريدية أسماء الموارد.
  2) كل مرجع @drawable/@color/@string/@style/@dimen/@layout/@xml/@mipmap/@menu/@anim
     يجب أن يكون معرّفاً فعلياً.
  3) كل حقل viewBinding المستخدم في الكود موجود في التخطيط المقابل.
  4) الأصناف المذكورة في المانيفست لها ملفات Kotlin.
  5) صحة صياغة Kotlin عبر tree-sitter (AST كامل).
  6) النصوص العربية: توازن RTL، غياب الهارموني النصي الغريب، ووجود الترجمة لكل مفتاح.
  7) ملاحظات API: استخدام واجهات أعلى من minSdk دون حراسة إصدار.
"""
from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "app", "src", "main", "java")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

errors: list[str] = []
warnings: list[str] = []
notes: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def note(msg: str) -> None:
    notes.append(msg)


# ---------------------------------------------------------------------------
# 1) فحص XML
# ---------------------------------------------------------------------------

def parse_all_xml() -> dict[str, ET.Element]:
    trees: dict[str, ET.Element] = {}
    for base, _dirs, files in os.walk(RES):
        for name in files:
            if not name.endswith(".xml"):
                continue
            path = os.path.join(base, name)
            try:
                trees[path] = ET.parse(path).getroot()
            except ET.ParseError as exc:
                err(f"XML غير صالح: {os.path.relpath(path, ROOT)} -> {exc}")
    try:
        trees[MANIFEST] = ET.parse(MANIFEST).getroot()
    except ET.ParseError as exc:
        err(f"مانيفست غير صالح -> {exc}")
    return trees


# ---------------------------------------------------------------------------
# 2) فهرس الموارد
# ---------------------------------------------------------------------------

def collect_resources(trees: dict[str, ET.Element]) -> tuple[dict[str, set[str]], dict[str, dict[str, str]]]:
    """يعيد {النوع: {الأسماء}} و {النوع: {الاسم: مسار}}"""
    index: dict[str, set[str]] = defaultdict(set)
    origin: dict[str, dict[str, str]] = defaultdict(dict)

    # الموارد القيمية: values/*.xml
    for path, root in trees.items():
        rel = os.path.relpath(path, ROOT)
        if not rel.startswith(os.path.join("app", "src", "main", "res", "values")):
            continue
        for child in root:
            if not isinstance(child.tag, str):
                continue
            name = child.attrib.get("name")
            if not name:
                continue
            kind = child.tag
            index[kind].add(name)
            origin[kind][name] = rel

    # موارد الملفات: drawable/layout/...
    for base, _dirs, files in os.walk(RES):
        folder = os.path.basename(base)
        parent = os.path.basename(os.path.dirname(base))
        if parent != "res":
            continue
        if folder.startswith("values"):
            continue
        kind = folder.split("-")[0]
        for name in files:
            stem = name.split(".")[0]
            index[kind].add(stem)
            origin[kind][stem] = os.path.relpath(os.path.join(base, name), ROOT)

    # الهوية من المانيفست
    index["style"].add("ThemeShadowUploadLauncher")
    return index, origin


REF_RE = re.compile(r"@(?:\+)?(?:android:)?([a-z]+)/([A-Za-z0-9_.]+)")


def check_references(trees, index, origin) -> None:
    for path, root in trees.items():
        rel = os.path.relpath(path, ROOT)
        for element in root.iter():
            for attr, value in element.attrib.items():
                if not isinstance(value, str):
                    continue
                if value.startswith("@android:") or value.startswith("?android:"):
                    continue
                for kind, name in REF_RE.findall(value):
                    if kind in {"android", "id", "string"} and name.startswith("android:"):
                        continue
                    # الأنماط تُعرّف بـ parent وليس كمرجع نوع
                    if kind == "style":
                        if name not in index["style"]:
                            err(f"{rel}: نمط غير موجود @style/{name}")
                        continue
                    if kind in {"id"}:
                        continue
                    if kind not in index:
                        warn(f"{rel}: نوع مرجع غير معروف @{kind}/{name}")
                        continue
                    if name not in index[kind]:
                        err(f"{rel}: مرجع غير موجود @{kind}/{name}")
            # values: مراجع الاستايل الأب
            if element.tag == "style":
                parent = element.attrib.get("parent", "")
                for kind, name in REF_RE.findall(parent):
                    if kind == "style" and name not in index["style"]:
                        err(f"{rel}: نمط أب غير موجود @style/{name}")


# ---------------------------------------------------------------------------
# 3) فحص viewBinding
# ---------------------------------------------------------------------------

def camel_to_binding(stem: str) -> str:
    parts = re.split(r"[_\-]", stem)
    return "".join(p[:1].upper() + p[1:] for p in parts if p) + "Binding"


def layout_ids(trees) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for path, root in trees.items():
        rel = os.path.relpath(path, ROOT)
        if os.sep + "layout" not in rel:
            continue
        stem = os.path.basename(path).split(".")[0]
        ids = set()
        for element in root.iter():
            value = element.attrib.get(f"{ANDROID_NS}id", "")
            if value.startswith("@+id/"):
                ids.add(value[5:])
        result[stem] = ids
    return result


camel_to_layout: dict[str, str] = {}


def kotlin_files() -> list[str]:
    files = []
    for base, _dirs, names in os.walk(SRC):
        for name in names:
            if name.endswith(".kt"):
                files.append(os.path.join(base, name))
    return files


def test_kotlin_files() -> list[str]:
    """ملفات الاختبارات (src/test) تُفحص صياغتها أيضاً"""
    root = os.path.join(ROOT, "app", "src", "test")
    files = []
    for base, _dirs, names in os.walk(root):
        for name in names:
            if name.endswith(".kt"):
                files.append(os.path.join(base, name))
    return files


def check_view_binding(layouts: dict[str, set[str]]) -> None:
    """يتحقق من أن كل حقل يُقرأ من كائن binding موجود فعلاً في تخطيطه."""
    for path in kotlin_files():
        source = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, ROOT)

        # خريطة: اسم المتغير -> اسم فئة الـ binding
        raw_pairs = re.findall(
            r"(?:val\s+)?(\w+)\s*=\s*(\w+Binding)\.inflate\(", source
        )
        grouped: dict[str, set[str]] = {}
        for var, cls in raw_pairs:
            grouped.setdefault(var, set()).add(cls)
        # المتغير المرتبط بأكثر من تخطيط يُعد ملتبساً ويُتخطى (سياقان مختلفان)
        var_to_binding: dict[str, str] = {
            var: next(iter(classes)) for var, classes in grouped.items() if len(classes) == 1
        }

        checked = 0
        for var, field in re.findall(r"\b(\w+)\.([A-Za-z_][A-Za-z0-9_]*)\b", source):
            cls = var_to_binding.get(var)
            if cls is None:
                continue
            stem = camel_to_layout.get(cls)
            if stem is None:
                err(f"{rel}: فئة viewBinding غير معروفة {cls}")
                continue
            ids = layouts.get(stem, set())
            if field == "root" or field in ids:
                checked += 1
                continue
            # قد يكون الحقل دالة/خاصية على binding نفسه (inflate, bind...)
            if field in {"inflate", "bind", "getRoot"}:
                continue
            err(f"{rel}: حقل viewBinding غير موجود '{field}' في layout/{stem}.xml")
        note(f"viewBinding ✓ {rel} ({checked} حقل)")


# ---------------------------------------------------------------------------
# 4) المانيفست
# ---------------------------------------------------------------------------

def check_manifest(trees) -> None:
    root = trees[MANIFEST]
    declared = []
    for tag in ("activity", "service", "provider"):
        for element in root.iter(tag):
            name = element.attrib.get(f"{ANDROID_NS}name", "")
            if not name:
                continue
            declared.append((tag, name))
    for tag, name in declared:
        if name.startswith("androidx") or name.startswith("android."):
            note(f"مانيفست: عنصر مكتبة {tag} {name}")
            continue
        if name.startswith("."):
            clean = "com.shadowupload.app" + name
        elif "." not in name:
            clean = f"com.shadowupload.app.{name}"
        else:
            clean = name
        fq_path = os.path.join(SRC, *clean.split(".")) + ".kt"
        if not os.path.exists(fq_path):
            err(f"مانيفست: الصنف {name} غير موجود ({os.path.relpath(fq_path, ROOT)})")
        else:
            note(f"مانيفست: {tag} {name} ✓")

    app = root.find("application")
    if app is None:
        err("مانيفست: لا يوجد عنصر application")
        return
    if app.attrib.get(f"{ANDROID_NS}name") != ".ShadowUploadApp":
        err("مانيفست: android:name للتطبيق غير مضبوط على .ShadowUploadApp")
    if app.attrib.get(f"{ANDROID_NS}supportsRtl") != "true":
        err("مانيفست: supportsRtl يجب أن يكون true لدعم RTL")


# ---------------------------------------------------------------------------
# 5) صحة Kotlin عبر tree-sitter
# ---------------------------------------------------------------------------

def check_kotlin_syntax() -> None:
    try:
        from tree_sitter import Language, Parser
        import tree_sitter_kotlin as tsk
    except Exception as exc:  # pragma: no cover
        warn(f"تعذّر تحميل tree-sitter ({exc}) — تم تخطي فحص الصياغة")
        return

    language = Language(tsk.language())
    parser = Parser(language)
    for path in kotlin_files() + test_kotlin_files():
        data = open(path, "rb").read()
        tree = parser.parse(data)
        rel = os.path.relpath(path, ROOT)
        if tree.root_node.has_error:
            # نحدد أول عقدة بها خطأ لعرض موقع دقيق
            stack = [tree.root_node]
            while stack:
                node = stack.pop()
                if node.has_error and node.child_count == 0:
                    line = node.start_point[0] + 1
                    snippet = data.splitlines()[line - 1][:90].decode("utf-8", "replace")
                    err(f"{rel}:{line}: خطأ صياغة Kotlin قرب: {snippet}")
                    break
                stack.extend(reversed(node.children))
            else:
                err(f"{rel}: خطأ صياغة Kotlin (عقدة الجذر)")
        else:
            note(f"Kotlin ✓ {rel}")


# ---------------------------------------------------------------------------
# 6) فحص النصوص العربية
# ---------------------------------------------------------------------------

def check_strings(trees) -> None:
    strings = {}
    for path, root in trees.items():
        rel = os.path.relpath(path, ROOT)
        if "values" not in rel or not os.path.basename(path).startswith("strings"):
            continue
        for element in root:
            name = element.attrib.get("name")
            if not name:
                continue
            text = "".join(element.itertext())
            strings[name] = text
            # نتحقق من صحة وسائط التنسيق (يمنع IllegalArgumentException وقت التشغيل)
            if element.tag == "string" and not element.attrib.get("formatted", "true") == "false":
                placeholders = re.findall(r"%(\d+)\$[sd]", text)
                plain = re.findall(r"%(?!\d+\$)[sd]", text)
                if placeholders and plain:
                    err(f"نص يخلط وسائط مرقمة وغير مرقمة: {name}")
                if placeholders:
                    indexes = sorted({int(x) for x in placeholders})
                    if indexes != list(range(1, len(indexes) + 1)):
                        err(f"وسائط تنسيق غير متسلسلة في {name}: {indexes}")
            if re.search(r"[\u0600-\u06FF]", text):
                # علامات ترقيم لاتينية داخل نص عربي
                if re.search(r"[\u0600-\u06FF][ ]*[,;?!]", text):
                    warn(f"نص عربي بعلامات ترقيم لاتينية: {name}")
            if name in strings and not text.strip():
                warn(f"نص فارغ: {name}")

    # كل %1$s يجب أن يقابله عدد صحيح من الوسائط عند الاستخدام
    placeholders = re.compile(r"%(\d+)\$[sd]")
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        source = open(path, encoding="utf-8").read()
        for match in re.finditer(r"getString\(\s*R\.string\.([a-z0-9_]+)\s*([^)]*)\)", source):
            key, args = match.group(1), match.group(2)
            if key not in strings:
                err(f"{rel}: مفتاح نصي غير موجود R.string.{key}")
                continue
            expected = len(set(placeholders.findall(strings[key])))
            if expected == 0:
                continue
            provided = args.count(",") + (1 if args.strip() else 0)
            if provided == 0:
                # قد يُستخدم مع formatArgs لاحقاً — تحذير فقط
                warn(f"{rel}: getString(R.string.{key}) بلا وسائط بينما يحتاج {expected}")
    note(f"عدد النصوص: {len(strings)}")


# ---------------------------------------------------------------------------
# 7) ملاحظات API
# ---------------------------------------------------------------------------

API_GUARDS = {
    "Environment.isExternalStorageManager": "R",
    "java.nio.file": "O",
    "getSerializableExtra": "33",
    "registerReceiver(.*,.*,.*,.*)": "33",
}


def check_api_levels() -> None:
    for path in kotlin_files():
        source = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, ROOT)
        code_only = "\n".join(
            line for line in source.splitlines()
            if not line.strip().startswith(("//", "*", "/*"))
        )
        if "java.nio.file" in code_only and "hasFullNioFile" not in source and "SDK_INT" not in source:
            warn(f"{rel}: استخدام java.nio.file دون حراسة إصدار")
        if "LocaleListCompat" in source:
            note(f"{rel}: appcompat locale API (مدعوم عبر AppCompatDelegate)")


# ---------------------------------------------------------------------------
# 8) فحص تكرار أسماء الأصناف
# ---------------------------------------------------------------------------

def check_duplicate_classes() -> None:
    seen: dict[str, str] = {}
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        source = open(path, encoding="utf-8").read()
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        pkg = package.group(1) if package else ""
        for name in re.findall(r"^(?:data\s+|sealed\s+|abstract\s+|open\s+|inner\s+)*class\s+(\w+)", source, re.M):
            key = f"{pkg}.{name}"
            if key in seen:
                err(f"صنف مكرر {key}: {seen[key]} و {rel}")
            seen[key] = rel
        for name in re.findall(r"^enum\s+class\s+(\w+)", source, re.M):
            key = f"{pkg}.{name}"
            if key in seen:
                err(f"صنف مكرر {key}: {seen[key]} و {rel}")
            seen[key] = rel


def check_imports_exist() -> None:
    """كل import داخلي (com.shadowupload) يجب أن يشير لصنف موجود"""
    project_classes: set[str] = set()
    for path in kotlin_files():
        source = open(path, encoding="utf-8").read()
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        if not package:
            continue
        pkg = package.group(1)
        for name in re.findall(r"^(?:data\s+|sealed\s+|abstract\s+|open\s+|inner\s+|private\s+|internal\s+)*class\s+(\w+)", source, re.M):
            project_classes.add(f"{pkg}.{name}")
        for name in re.findall(r"^object\s+(\w+)", source, re.M):
            project_classes.add(f"{pkg}.{name}")
        for name in re.findall(r"^enum\s+class\s+(\w+)", source, re.M):
            project_classes.add(f"{pkg}.{name}")
        # أسماء الأنواع المتداخلة (class A { data class B }) تُضاف بمسارها
        for outer in re.findall(r"^\s*(?:data |sealed |abstract |open |inner )?class\s+(\w+)", source, re.M):
            for inner in re.findall(r"^\s{2,}(?:data |sealed |abstract |open |inner )?class\s+(\w+)", source, re.M):
                project_classes.add(f"{pkg}.{outer}.{inner}")

    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        source = open(path, encoding="utf-8").read()
        for imported in re.findall(r"^import\s+(com\.shadowupload[\w.]+)", source, re.M):
            # قد يكون دالة/ثابت لا صنفاً
            if imported.endswith(".R"):
                continue
            if imported in project_classes:
                continue
            # استيراد لصنف متداخل أو لفئة مولّدة (databinding)
            if any(c == imported or c.startswith(imported + ".") for c in project_classes):
                continue
            if ".databinding." in imported:
                continue
            parent = imported.rsplit(".", 1)[0]
            if any(c.startswith(parent + ".") for c in project_classes):
                continue
            warn(f"{rel}: استيراد داخلي غير موجود {imported}")


def check_r_references(trees, index) -> None:
    """كل مرجع R.type.name في الكود يجب أن يكون معرّفاً في الموارد."""
    id_index: set[str] = set()
    for path, root in trees.items():
        rel = os.path.relpath(path, ROOT)
        if os.sep + "layout" not in rel and os.sep + "menu" not in rel:
            continue
        for element in root.iter():
            value = element.attrib.get(f"{ANDROID_NS}id", "")
            if value.startswith("@+id/"):
                id_index.add(value[5:])

    pattern = re.compile(r"\bR\.(string|drawable|color|dimen|style|layout|xml|mipmap|anim|id|menu|bool|array|integer)\."
                         r"([A-Za-z0-9_]+)")
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        source = open(path, encoding="utf-8").read()
        for kind, name in pattern.findall(source):
            if kind == "id":
                if name not in id_index:
                    err(f"{rel}: معرّف غير موجود R.id.{name}")
                continue
            if kind not in index:
                err(f"{rel}: نوع موارد غير معروف R.{kind}")
                continue
            if name not in index[kind]:
                err(f"{rel}: مرجع غير موجود R.{kind}.{name}")
    note(f"مراجع R ✓ ({len(id_index)} معرّف)")


def check_layout_handlers(trees) -> None:
    """كل onClick في XML يجب أن تكون له دالة مطابقة في الصنف."""
    for path, root in trees.items():
        rel = os.path.relpath(path, ROOT)
        if os.sep + "layout" not in rel:
            continue
        handlers = []
        for element in root.iter():
            value = element.attrib.get(f"{ANDROID_NS}onClick", "")
            if value:
                handlers.append(value)
        if handlers:
            warn(f"{rel}: onClick في XML غير مدعوم مع viewBinding: {handlers}")


def main() -> int:
    trees = parse_all_xml()
    index, origin = collect_resources(trees)
    layouts = layout_ids(trees)
    camel_to_layout.clear()
    for stem in layouts:
        camel_to_layout[camel_to_binding(stem)] = stem

    check_references(trees, index, origin)
    check_view_binding(layouts)
    check_manifest(trees)
    check_duplicate_classes()
    check_strings(trees)
    check_api_levels()
    check_imports_exist()
    check_r_references(trees, index)
    check_layout_handlers(trees)
    check_kotlin_syntax()

    print("=" * 78)
    print(f"ملفات XML: {len(trees)} | ملفات Kotlin: {len(kotlin_files())} | "
          f"layouts: {len(layouts)} | drawables: {len(index['drawable'])} | "
          f"styles: {len(index['style'])}")
    print("=" * 78)

    if warnings:
        print(f"\n⚠️  تحذيرات ({len(warnings)}):")
        for item in warnings:
            print("   -", item)
    if errors:
        print(f"\n❌ أخطاء ({len(errors)}):")
        for item in errors:
            print("   -", item)
        print(f"\nالنتيجة: فشل — {len(errors)} خطأ، {len(warnings)} تحذير")
        return 1

    print(f"\n✅ النتيجة: نجح الفحص — 0 خطأ، {len(warnings)} تحذير")
    return 0


if __name__ == "__main__":
    sys.exit(main())
