#!/system/bin/sh
# Python launcher: resolves RPATH hardcoding by setting LD_LIBRARY_PATH before exec
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export LD_LIBRARY_PATH="$SCRIPT_DIR:${LD_LIBRARY_PATH:-}"
export PYTHONHOME="$SCRIPT_DIR"
export PYTHONPATH="$SCRIPT_DIR/lib/python3.12"
exec "$SCRIPT_DIR/python" "$@"
