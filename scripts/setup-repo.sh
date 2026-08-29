#!/usr/bin/env bash
#
# One-time GitHub setup for Wisp. Run this once, locally, from inside a clone
# of the repo — it needs `gh` and `keytool`, neither of which exists in the
# environment the app was written in.
#
#   ./scripts/setup-repo.sh
#
# What it does:
#   * generates the release keystore with strong random passwords, OUTSIDE the
#     git tree, and refuses to overwrite one that already exists
#   * uploads the four signing secrets to the repo
#   * gives Actions write permission so it can publish releases
#   * turns off Wiki / Projects / Discussions
#   * prints both passwords once, at the end
#
# The keystore and its passwords are the only thing that lets a future build
# install as an update over what is already on your phones. Lose them and both
# of you have to uninstall and reinstall from scratch.

set -euo pipefail

KEYSTORE_DIR="${WISP_KEYSTORE_DIR:-$HOME/.wisp}"
KEYSTORE_PATH="$KEYSTORE_DIR/release.keystore"
KEY_ALIAS="wisp"

die() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
step() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }

command -v gh >/dev/null || die "gh is not installed. See https://cli.github.com/"
command -v keytool >/dev/null || die "keytool is not on PATH. Install a JDK (17 is what CI uses)."

step "Checking GitHub authentication"
if ! gh auth status >/dev/null 2>&1; then
  die "Not logged in to GitHub. Run 'gh auth login' first, then re-run this script."
fi
gh auth status 2>&1 | sed 's/^/    /'

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
step "Working on $REPO"

# ---------------------------------------------------------------- keystore --
if [ -f "$KEYSTORE_PATH" ]; then
  step "Keystore already exists at $KEYSTORE_PATH"
  echo "    Leaving it alone — replacing it would break updates on phones that"
  echo "    already have Wisp installed."
  echo
  read -r -p "    Re-upload secrets using this keystore? You'll be asked for the passwords. [y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]] || die "Nothing to do."
  read -r -s -p "    Keystore password: " STORE_PASS; echo
  read -r -s -p "    Key password: " KEY_PASS; echo
  GENERATED=false
else
  step "Generating release keystore"
  mkdir -p "$KEYSTORE_DIR"
  chmod 700 "$KEYSTORE_DIR"

  random_password() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 40; }
  STORE_PASS="$(random_password)"
  KEY_PASS="$(random_password)"
  GENERATED=true

  keytool -genkeypair -v \
    -keystore "$KEYSTORE_PATH" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=Wisp, OU=Wisp, O=Wisp, L=, ST=, C=" >/dev/null
  chmod 600 "$KEYSTORE_PATH"
  echo "    Wrote $KEYSTORE_PATH"
fi

# Sanity check: the keystore has to actually open with the passwords we hold.
keytool -list -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" -storepass "$STORE_PASS" >/dev/null \
  || die "The keystore did not open with that password."

# ----------------------------------------------------------------- secrets --
step "Uploading signing secrets"
if base64 --help 2>&1 | grep -q -- '-w'; then
  KEYSTORE_B64="$(base64 -w0 "$KEYSTORE_PATH")"   # GNU coreutils
else
  KEYSTORE_B64="$(base64 < "$KEYSTORE_PATH" | tr -d '\n')"  # BSD/macOS
fi

gh secret set KEYSTORE_BASE64 -b"$KEYSTORE_B64"
gh secret set KEYSTORE_PASSWORD -b"$STORE_PASS"
gh secret set KEY_ALIAS -b"$KEY_ALIAS"
gh secret set KEY_PASSWORD -b"$KEY_PASS"
echo "    KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD"

# ------------------------------------------------------------ repo settings --
step "Allowing Actions to publish releases"
gh api "repos/$REPO/actions/permissions/workflow" -X PUT \
  -f default_workflow_permissions=write \
  -F can_approve_pull_request_reviews=false

step "Turning off Wiki, Projects and Discussions"
gh api "repos/$REPO" -X PATCH \
  -F has_wiki=false -F has_projects=false -F has_discussions=false >/dev/null

# ------------------------------------------------------------------ output --
cat <<EOF

================================================================================
 Done.
================================================================================

Keystore:  $KEYSTORE_PATH
Alias:     $KEY_ALIAS
EOF

if [ "$GENERATED" = true ]; then
  cat <<EOF
Store password: $STORE_PASS
Key password:   $KEY_PASS

SAVE BOTH PASSWORDS AND A COPY OF THE KEYSTORE FILE SOMEWHERE SAFE, NOW.
This is the only time they are printed. Every build has to be signed with this
exact key for Android to treat it as an update; if it's lost, both of you have
to uninstall Wisp and install the new one fresh.
EOF
fi

cat <<'EOF'

Still to do, on your Cloudflare account (this part can't be done with gh):

  1. npm install -g wrangler   (or use `npx wrangler`)
  2. wrangler login
  3. Create a TURN key: Cloudflare dashboard -> Realtime -> TURN Keys.
     Note the Key ID and the API token.
  4. cd worker && npx wrangler deploy     # creates the Worker first time
  5. npx wrangler secret put TURN_KEY_ID
     npx wrangler secret put TURN_KEY_API_TOKEN
  6. Create a GitHub API-token secret so CI can redeploy the Worker:
     gh secret set CLOUDFLARE_API_TOKEN
  7. Put the Worker's hostname into app/src/main/java/com/wisp/app/Config.kt
     (SIGNALING_HOST) and push — CI builds a signed APK into a GitHub Release.
EOF
