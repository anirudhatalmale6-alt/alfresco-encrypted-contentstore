#!/bin/bash
#
# Iurit Encrypted Content Store - Deployment Script
# ==================================================
# Deploys the encryption module to Alfresco Community 6.1
#
# Usage: ./deploy_encryption.sh [generate-keystore|deploy|migrate|status|uninstall]
#

set -e

ALFRESCO_HOME="${ALFRESCO_HOME:-/home/alfresco/alfresco}"
TOMCAT_DIR="$ALFRESCO_HOME/tomcat"
WEBAPPS_DIR="$TOMCAT_DIR/webapps/alfresco"
LIB_DIR="$WEBAPPS_DIR/WEB-INF/lib"
SHARED_CLASSES="$TOMCAT_DIR/shared/classes"
GLOBAL_PROPS="$SHARED_CLASSES/alfresco-global.properties"

# Default keystore location (on a SEPARATE volume for key segregation)
KEYSTORE_DIR="${KEYSTORE_DIR:-/opt/iurit-keys}"
KEYSTORE_FILE="$KEYSTORE_DIR/encryption-keystore.jceks"
KEY_ALIAS="iurit-content-key"

JAR_FILE="encrypted-contentstore-1.0.0.jar"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

usage() {
    echo "Usage: $0 <command>"
    echo ""
    echo "Commands:"
    echo "  generate-keystore   Generate AES-256 keystore (run ONCE)"
    echo "  deploy              Deploy the encryption module to Alfresco"
    echo "  migrate             Start migrating existing content (run after deploy)"
    echo "  status              Check migration status"
    echo "  uninstall           Remove the encryption module"
    echo ""
    echo "Environment variables:"
    echo "  ALFRESCO_HOME       Alfresco installation directory (default: /home/alfresco/alfresco)"
    echo "  KEYSTORE_DIR        Directory for encryption keystore (default: /opt/iurit-keys)"
    echo "  KEYSTORE_PASS       Keystore password (prompted if not set)"
    echo "  KEY_PASS            Key password (prompted if not set)"
    echo ""
    echo "Recommended deployment order:"
    echo "  1. ./deploy_encryption.sh generate-keystore"
    echo "  2. ./deploy_encryption.sh deploy"
    echo "  3. Restart Alfresco"
    echo "  4. Test with a new document upload"
    echo "  5. ./deploy_encryption.sh migrate  (to encrypt existing content)"
    exit 1
}

generate_keystore() {
    info "Generating AES-256 encryption keystore..."

    if [ -f "$KEYSTORE_FILE" ]; then
        error "Keystore already exists at: $KEYSTORE_FILE"
        error "Delete it first if you want to regenerate (WARNING: this will make encrypted content unreadable!)"
        exit 1
    fi

    # Prompt for passwords if not set
    if [ -z "$KEYSTORE_PASS" ]; then
        read -sp "Enter keystore password (min 8 chars): " KEYSTORE_PASS
        echo
        read -sp "Confirm keystore password: " KEYSTORE_PASS_CONFIRM
        echo
        if [ "$KEYSTORE_PASS" != "$KEYSTORE_PASS_CONFIRM" ]; then
            error "Passwords don't match!"
            exit 1
        fi
    fi

    if [ -z "$KEY_PASS" ]; then
        read -sp "Enter key password (min 8 chars): " KEY_PASS
        echo
        read -sp "Confirm key password: " KEY_PASS_CONFIRM
        echo
        if [ "$KEY_PASS" != "$KEY_PASS_CONFIRM" ]; then
            error "Passwords don't match!"
            exit 1
        fi
    fi

    # Create keystore directory
    mkdir -p "$KEYSTORE_DIR"
    chmod 700 "$KEYSTORE_DIR"

    # Generate AES-256 key
    keytool -genseckey \
        -alias "$KEY_ALIAS" \
        -keyalg AES \
        -keysize 256 \
        -storetype JCEKS \
        -keystore "$KEYSTORE_FILE" \
        -storepass "$KEYSTORE_PASS" \
        -keypass "$KEY_PASS"

    chmod 600 "$KEYSTORE_FILE"

    info "Keystore created at: $KEYSTORE_FILE"
    info "Key alias: $KEY_ALIAS"
    info "Key algorithm: AES-256"
    info ""
    warn "IMPORTANT: Store these passwords securely!"
    warn "IMPORTANT: Back up $KEYSTORE_FILE to a secure offline location!"
    warn "IMPORTANT: Without this keystore, encrypted content is UNRECOVERABLE!"
}

deploy() {
    info "Deploying Iurit Encrypted Content Store..."

    # Check prerequisites
    if [ ! -d "$WEBAPPS_DIR" ]; then
        error "Alfresco webapp not found at: $WEBAPPS_DIR"
        exit 1
    fi

    if [ ! -f "$JAR_FILE" ]; then
        error "JAR file not found: $JAR_FILE (run from the directory containing the JAR)"
        exit 1
    fi

    if [ ! -f "$KEYSTORE_FILE" ]; then
        error "Keystore not found at: $KEYSTORE_FILE"
        error "Run '$0 generate-keystore' first"
        exit 1
    fi

    # Prompt for passwords if not set
    if [ -z "$KEYSTORE_PASS" ]; then
        read -sp "Enter keystore password: " KEYSTORE_PASS
        echo
    fi
    if [ -z "$KEY_PASS" ]; then
        read -sp "Enter key password: " KEY_PASS
        echo
    fi

    # Step 1: Copy JAR
    info "Step 1: Copying JAR to $LIB_DIR..."
    cp "$JAR_FILE" "$LIB_DIR/"
    info "  JAR deployed."

    # Step 2: Add encryption properties to alfresco-global.properties
    info "Step 2: Configuring alfresco-global.properties..."

    if grep -q "iurit.encryption" "$GLOBAL_PROPS" 2>/dev/null; then
        warn "  Encryption properties already exist in alfresco-global.properties"
        warn "  Skipping (edit manually if you need to change them)"
    else
        cat >> "$GLOBAL_PROPS" << EOF

#-----------------------
# Iurit Encryption at Rest
#-----------------------
iurit.encryption.keystore.path=$KEYSTORE_FILE
iurit.encryption.keystore.password=$KEYSTORE_PASS
iurit.encryption.key.alias=$KEY_ALIAS
iurit.encryption.key.password=$KEY_PASS
EOF
        info "  Encryption properties added."
    fi

    info ""
    info "Deployment complete!"
    info ""
    info "Next steps:"
    info "  1. Stop Alfresco:   \$ALFRESCO_HOME/alfresco-service.sh stop"
    info "  2. Start Alfresco:  \$ALFRESCO_HOME/alfresco-service.sh start"
    info "  3. Test: Upload a new document, then check if the file in alf_data/contentstore/ is encrypted"
    info "  4. Run migration:   $0 migrate"
}

migrate() {
    info "Starting content migration..."

    if [ -z "$KEYSTORE_PASS" ]; then
        read -sp "Enter keystore password (for admin auth): " ADMIN_PASS
        echo
    fi

    # Get admin password
    read -sp "Enter Alfresco admin password: " ADMIN_PASS
    echo

    BATCH_SIZE="${1:-100}"

    info "Migrating in batches of $BATCH_SIZE..."
    info "First running dry run to count unencrypted content..."

    # Dry run first
    RESULT=$(curl -s -u admin:"$ADMIN_PASS" \
        "http://localhost:8080/alfresco/s/api/encryption/migrate?batchSize=$BATCH_SIZE&dryRun=true")
    echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

    echo ""
    read -p "Proceed with encryption? (y/n): " CONFIRM
    if [ "$CONFIRM" != "y" ]; then
        info "Migration cancelled."
        exit 0
    fi

    # Actual migration
    RESULT=$(curl -s -u admin:"$ADMIN_PASS" \
        "http://localhost:8080/alfresco/s/api/encryption/migrate?batchSize=$BATCH_SIZE&dryRun=false")
    echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

    info "Batch complete. Run again to continue migrating remaining content."
}

status() {
    info "Checking encryption status..."

    CONTENT_DIR="$ALFRESCO_HOME/alf_data/contentstore"
    if [ ! -d "$CONTENT_DIR" ]; then
        error "Content store not found at: $CONTENT_DIR"
        exit 1
    fi

    TOTAL=$(find "$CONTENT_DIR" -type f | wc -l)
    # Check files that start with byte 0x01 (encrypted format marker)
    ENCRYPTED=$(find "$CONTENT_DIR" -type f -exec sh -c 'head -c1 "$1" | od -An -tx1 | tr -d " " | grep -q "^01$" && echo encrypted' _ {} \; 2>/dev/null | wc -l)
    PLAIN=$((TOTAL - ENCRYPTED))

    info "Content store: $CONTENT_DIR"
    info "Total files:     $TOTAL"
    info "Encrypted:       $ENCRYPTED"
    info "Unencrypted:     $PLAIN"

    if [ "$PLAIN" -eq 0 ]; then
        info "All content is encrypted!"
    else
        warn "$PLAIN files still need encryption. Run: $0 migrate"
    fi
}

uninstall() {
    warn "Uninstalling Iurit Encrypted Content Store..."
    warn ""
    warn "WARNING: If content has been encrypted, it will become UNREADABLE"
    warn "after uninstalling unless you decrypt it first!"
    warn ""
    read -p "Are you sure? Type 'yes' to confirm: " CONFIRM
    if [ "$CONFIRM" != "yes" ]; then
        info "Uninstall cancelled."
        exit 0
    fi

    # Remove JAR
    if [ -f "$LIB_DIR/$JAR_FILE" ]; then
        rm "$LIB_DIR/$JAR_FILE"
        info "Removed JAR from $LIB_DIR"
    fi

    # Remove properties (comment them out)
    if grep -q "iurit.encryption" "$GLOBAL_PROPS" 2>/dev/null; then
        sed -i 's/^iurit\.encryption/#iurit.encryption/' "$GLOBAL_PROPS"
        sed -i 's/^# Iurit Encryption/#-- Iurit Encryption (DISABLED)/' "$GLOBAL_PROPS"
        info "Commented out encryption properties in alfresco-global.properties"
    fi

    info "Uninstall complete. Restart Alfresco to apply."
    warn "The keystore at $KEYSTORE_FILE has NOT been deleted (needed to read any remaining encrypted content)."
}

# Main
case "${1:-}" in
    generate-keystore) generate_keystore ;;
    deploy)           deploy ;;
    migrate)          migrate ;;
    status)           status ;;
    uninstall)        uninstall ;;
    *)                usage ;;
esac
