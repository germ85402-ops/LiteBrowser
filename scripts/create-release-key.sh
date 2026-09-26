#!/usr/bin/env bash
# Creates the permanent Svetlo release key and stores it in the repository's GitHub Actions secrets.
# Run once on your own computer (needs: keytool from a JDK, gh logged in with admin access to the repo).
set -euo pipefail

REPO="${1:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
OUT="${SVETLO_KEY_DIR:-$HOME/svetlo-release-key}"
KEYSTORE="$OUT/svetlo-release.jks"
ALIAS="svetlo"

if [ -e "$KEYSTORE" ]; then
  echo "Key already exists: $KEYSTORE — refusing to overwrite it." >&2
  echo "Delete it manually only if you are sure no published APK was signed with it." >&2
  exit 1
fi

mkdir -p "$OUT"
chmod 700 "$OUT"
PASS="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"

keytool -genkeypair -v -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Svetlo, O=Svetlo, C=RU"

printf '%s\n' "$PASS" > "$OUT/password.txt"
chmod 600 "$KEYSTORE" "$OUT/password.txt"

base64 < "$KEYSTORE" | tr -d '\n' | gh secret set SVETLO_KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$PASS" | gh secret set SVETLO_STORE_PASSWORD -R "$REPO"
printf '%s' "$PASS" | gh secret set SVETLO_KEY_PASSWORD -R "$REPO"
printf '%s' "$ALIAS" | gh secret set SVETLO_KEY_ALIAS -R "$REPO"

echo
keytool -list -v -keystore "$KEYSTORE" -storepass "$PASS" | grep -E "SHA256:" | head -1
cat <<EOF

Done. Secrets are set for $REPO.

IMPORTANT: back up $OUT (keystore + password) somewhere safe and offline.
If this key is lost, installed copies of Svetlo can never be updated — users would have to reinstall.
Never commit these files to git.
EOF
