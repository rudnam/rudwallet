# Converts the web app's Inter (web/src/fonts/inter-latin.woff2, a variable
# font) into the TTFs the native screens use (app/src/main/res/font): the
# three weights the app uses, as static fonts, since Android before 8 can't
# pick a weight from a variable font. Like the web app, only Latin: other
# scripts (Japanese above all) come from the phone's own fonts.
#
#   python3 -m venv /tmp/fonts && /tmp/fonts/bin/pip install fonttools brotli
#   /tmp/fonts/bin/python android/fonts.py
import os

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, "web", "src", "fonts", "inter-latin.woff2")
out = os.path.join(root, "android", "app", "src", "main", "res", "font")

for weight, name in [(400, "inter_regular"), (500, "inter_medium"), (600, "inter_semibold")]:
    font = TTFont(src)
    where = {"wght": weight}
    if "opsz" in {a.axisTag for a in font["fvar"].axes}:
        where["opsz"] = 14
    static = instancer.instantiateVariableFont(font, where)
    static.flavor = None
    static.save(os.path.join(out, name + ".ttf"))
