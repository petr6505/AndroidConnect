#!/usr/bin/env bash
# Builds dist/androidconnect_<version>_all.deb from linux/.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$HERE/../dist}"
VERSION="$(python3 -B -c "import sys; sys.path.insert(0, '$HERE'); import androidconnect; print(androidconnect.__version__)")"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
R="$STAGE/root"

install -d "$R/DEBIAN" "$R/usr/bin" "$R/usr/lib/androidconnect/androidconnect" \
    "$R/usr/lib/systemd/user" "$R/etc/xdg/autostart" "$R/usr/share/applications" \
    "$R/usr/share/icons/hicolor/scalable/apps" "$R/usr/share/doc/androidconnect"

install -m644 "$HERE"/androidconnect/*.py "$R/usr/lib/androidconnect/androidconnect/"
for pair in androidconnect:gui androidconnectd:daemon; do
    name="${pair%%:*}"; module="${pair##*:}"
    printf '#!/bin/sh\nexec python3 -P -c "import sys; sys.path.insert(0, \\"/usr/lib/androidconnect\\"); from androidconnect.%s import main; main()" "$@"\n' \
        "$module" > "$R/usr/bin/$name"
    chmod 755 "$R/usr/bin/$name"
done

install -m644 "$HERE/packaging/androidconnectd.service" "$R/usr/lib/systemd/user/"
install -m644 "$HERE/packaging/io.github.androidconnect.daemon.desktop" "$R/etc/xdg/autostart/"
install -m644 "$HERE/data/io.github.androidconnect.desktop" "$R/usr/share/applications/"
install -m644 "$HERE/data/io.github.androidconnect.svg" "$R/usr/share/icons/hicolor/scalable/apps/"
for size in 48 64 128 256 512; do
    install -d "$R/usr/share/icons/hicolor/${size}x${size}/apps"
    python3 - "$HERE/data/io.github.androidconnect.svg" "$R/usr/share/icons/hicolor/${size}x${size}/apps/io.github.androidconnect.png" "$size" <<'PY'
import sys, gi
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import GdkPixbuf
size = int(sys.argv[3])
GdkPixbuf.Pixbuf.new_from_file_at_size(sys.argv[1], size, size).savev(sys.argv[2], "png", [], [])
PY
done
gzip -9n < "$HERE/../README.md" > "$R/usr/share/doc/androidconnect/README.md.gz"
cat > "$R/usr/share/doc/androidconnect/copyright" <<COPY
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: androidconnect
Files: *
Copyright: 2026 Petr Dobiáš
License: MIT
COPY

chmod -R u=rwX,go=rX "$R"
chmod 755 "$R"/usr/bin/*
for script in postinst prerm postrm; do
    install -m755 "$HERE/packaging/debian/$script" "$R/DEBIAN/"
done
SIZE="$(du -sk --exclude=DEBIAN "$R" | cut -f1)"
sed -e "s/@VERSION@/$VERSION/" -e "s/@SIZE@/$SIZE/" "$HERE/packaging/debian/control" > "$R/DEBIAN/control"
(cd "$R" && find . -path ./DEBIAN -prune -o -type f -printf '%P\0' | sort -z | xargs -0 md5sum > DEBIAN/md5sums)

mkdir -p "$OUT"
DEB="$OUT/androidconnect_${VERSION}_all.deb"
dpkg-deb --root-owner-group -Zxz --build "$R" "$DEB" >/dev/null
echo "$DEB"
