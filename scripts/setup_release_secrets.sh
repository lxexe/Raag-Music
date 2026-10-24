#!/usr/bin/env bash
# Run locally once. Upload secrets directly to GitHub; never print their values.
set -euo pipefail
set +x
repo=${1:-lxexe/Raag-Music}
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v gh >/dev/null || { echo 'Install GitHub CLI (brew install gh), then run gh auth login'; exit 1; }
command -v keytool >/dev/null || { echo 'Select JDK 17 with JAVA_HOME and add its bin directory to PATH'; exit 1; }
gh auth status >/dev/null 2>&1 || { echo 'Run gh auth login first'; exit 1; }
read -r -p "Signing keystore path [$HOME/RaagMusicSigning/raag-release.p12]: " keystore
keystore=${keystore:-$HOME/RaagMusicSigning/raag-release.p12}
read -r -p 'Key alias [raag]: ' RAAG_KEY_ALIAS
export RAAG_KEY_ALIAS=${RAAG_KEY_ALIAS:-raag}
read -r -s -p 'Keystore password: ' RAAG_KEYSTORE_PASSWORD
printf '\n'
export RAAG_KEYSTORE_PASSWORD
if [[ ! -f "$keystore" ]]; then
  read -r -s -p 'Confirm new password: ' confirmation
  printf '\n'
  [[ "$confirmation" == "$RAAG_KEYSTORE_PASSWORD" ]] || { echo 'Passwords differ'; exit 1; }
  mkdir -p "$(dirname "$keystore")"
  (umask 077; keytool -genkeypair -keystore "$keystore" -storetype PKCS12 \
    -alias "$RAAG_KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 10000 \
    -dname 'CN=Raag Music' -storepass:env RAAG_KEYSTORE_PASSWORD \
    -keypass:env RAAG_KEYSTORE_PASSWORD)
  RAAG_KEY_PASSWORD=$RAAG_KEYSTORE_PASSWORD
else
  read -r -s -p 'Key password (Enter if same as keystore): ' RAAG_KEY_PASSWORD
  printf '\n'
  RAAG_KEY_PASSWORD=${RAAG_KEY_PASSWORD:-$RAAG_KEYSTORE_PASSWORD}
fi
export RAAG_KEY_PASSWORD
keytool -list -keystore "$keystore" -alias "$RAAG_KEY_ALIAS" \
  -storepass:env RAAG_KEYSTORE_PASSWORD >/dev/null
# Existing public releases require the same signing key, never a replacement key.
base64 < "$keystore" | gh secret set RAAG_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$RAAG_KEYSTORE_PASSWORD" | gh secret set RAAG_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$RAAG_KEY_ALIAS" | gh secret set RAAG_KEY_ALIAS --repo "$repo"
printf '%s' "$RAAG_KEY_PASSWORD" | gh secret set RAAG_KEY_PASSWORD --repo "$repo"
read -r -p 'Path to Firebase google-services.json (Enter to skip): ' firebase_config
if [[ -n "$firebase_config" ]]; then
  test -f "$firebase_config"
  base64 < "$firebase_config" | gh secret set GOOGLE_SERVICES_JSON_BASE64 --repo "$repo"
fi
unset RAAG_KEYSTORE_PASSWORD RAAG_KEY_PASSWORD confirmation
echo 'Release secrets configured. Back up the keystore and password privately.'
echo 'Firebase tag builds require GOOGLE_SERVICES_JSON_BASE64; use the manual workflow with Firebase off for an SDK-free release.'
