#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 3 ]; then
    echo "Usage: $0 <project-name> <group> <base-package>"
    echo "Example: $0 order-service com.mycompany com.mycompany.orders"
    exit 1
fi

PROJECT_NAME="$1"
GROUP="$2"
BASE_PACKAGE="$3"

OLD_PROJECT_NAME="hexagonal-service-template"
OLD_GROUP="com.company"
OLD_BASE_PACKAGE="com.company.service"
OLD_PACKAGE_PATH="com/company/service"
NEW_PACKAGE_PATH="${BASE_PACKAGE//\.//}"

ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "Initializing project..."
echo "  Project name : $PROJECT_NAME"
echo "  Group        : $GROUP"
echo "  Base package : $BASE_PACKAGE"
echo ""

echo "[1/4] Renaming package directories..."

find "$ROOT" -type d -path "*src/main/java/$OLD_PACKAGE_PATH" -o -path "*src/test/java/$OLD_PACKAGE_PATH" | while read -r old_dir; do
    java_root="$(echo "$old_dir" | sed "s|/$OLD_PACKAGE_PATH$||")"
    new_dir="$java_root/$NEW_PACKAGE_PATH"
    mkdir -p "$(dirname "$new_dir")"
    mv "$old_dir" "$new_dir"
    old_top="$java_root/$(echo "$OLD_PACKAGE_PATH" | cut -d'/' -f1)"
    find "$old_top" -type d -empty -delete 2>/dev/null || true
    rmdir "$old_top" 2>/dev/null || true
done

echo "[2/4] Replacing package names in source files..."

find "$ROOT" \( -name "*.java" -o -name "*.kts" -o -name "*.yml" -o -name "*.yaml" \
    -o -name "*.xml" -o -name "*.toml" -o -name "*.properties" -o -name "*.md" \) \
    -not -path "*/build/*" -not -path "*/.gradle/*" -not -path "*/.git/*" | while read -r file; do
    if grep -q "$OLD_BASE_PACKAGE\|$OLD_GROUP\|$OLD_PROJECT_NAME" "$file" 2>/dev/null; then
        sed -i.bak \
            -e "s|$OLD_BASE_PACKAGE|$BASE_PACKAGE|g" \
            -e "s|$OLD_GROUP|$GROUP|g" \
            -e "s|$OLD_PROJECT_NAME|$PROJECT_NAME|g" \
            "$file"
        rm -f "${file}.bak"
        echo "  Updated: ${file#$ROOT/}"
    fi
done

echo "[3/4] Updating Docker container names..."

SHORT_NAME="$(echo "$PROJECT_NAME" | sed 's/-service$//' | tr -d '-')"

if [ -f "$ROOT/docker-compose.yml" ]; then
    sed -i.bak \
        -e "s/appdb/${SHORT_NAME}db/g" \
        -e "s/appuser/${SHORT_NAME}user/g" \
        -e "s/apppass/${SHORT_NAME}pass/g" \
        "$ROOT/docker-compose.yml"
    rm -f "$ROOT/docker-compose.yml.bak"
    echo "  Updated: docker-compose.yml"
fi

echo "[4/4] Updating application.yml defaults..."

APP_YML="$ROOT/infrastructure/app/src/main/resources/application.yml"
if [ -f "$APP_YML" ]; then
    sed -i.bak \
        -e "s/appdb/${SHORT_NAME}db/g" \
        -e "s/appuser/${SHORT_NAME}user/g" \
        -e "s/apppass/${SHORT_NAME}pass/g" \
        "$APP_YML"
    rm -f "${APP_YML}.bak"
    echo "  Updated: application.yml"
fi

echo ""
echo "Done! Project '$PROJECT_NAME' is ready."
echo ""
echo "Next steps:"
echo "  1. Replace the sample 'Item' domain with your actual domain model"
echo "  2. Update openapi.yaml with your API contract"
echo "  3. Modify Flyway migrations for your schema"
echo "  4. Run: docker compose up -d"
echo "  5. Run: ./gradlew build"
echo "  6. Run: ./gradlew :infrastructure:app:bootRun"
