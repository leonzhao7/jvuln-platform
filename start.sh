#!/bin/bash
# JVuln Platform 启动脚本
# 用法: ./start.sh [--build] [--stop]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/backend"
FRONTEND_DIR="$SCRIPT_DIR/frontend"
LOG_DIR="$SCRIPT_DIR/logs"
BACKEND_PID_FILE="$SCRIPT_DIR/.backend.pid"
FRONTEND_PID_FILE="$SCRIPT_DIR/.frontend.pid"
JAR="$BACKEND_DIR/jvuln-app/target/jvuln-app-1.0.0-SNAPSHOT.jar"

mkdir -p "$LOG_DIR"

# ── 颜色 ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()      { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERR ]${NC}  $*"; }

# ── 停止服务 ─────────────────────────────────────────────────────────────────
stop_services() {
    info "Stopping services..."
    if [ -f "$BACKEND_PID_FILE" ]; then
        PID=$(cat "$BACKEND_PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID" && ok "Backend stopped (PID $PID)"
        else
            warn "Backend PID $PID not running"
        fi
        rm -f "$BACKEND_PID_FILE"
    else
        warn "No backend PID file found"
    fi

    if [ -f "$FRONTEND_PID_FILE" ]; then
        PID=$(cat "$FRONTEND_PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID" && ok "Frontend stopped (PID $PID)"
        else
            warn "Frontend PID $PID not running"
        fi
        rm -f "$FRONTEND_PID_FILE"
    else
        warn "No frontend PID file found"
    fi
}

# ── 参数解析 ──────────────────────────────────────────────────────────────────
BUILD=false
for arg in "$@"; do
    case $arg in
        --build) BUILD=true ;;
        --stop)  stop_services; exit 0 ;;
        --help|-h)
            echo "Usage: $0 [--build] [--stop]"
            echo "  --build   Build backend before starting (mvn install -DskipTests)"
            echo "  --stop    Stop running services"
            exit 0
            ;;
    esac
done

# ── 检查已运行实例 ────────────────────────────────────────────────────────────
check_running() {
    local pidfile=$1 name=$2
    if [ -f "$pidfile" ]; then
        PID=$(cat "$pidfile")
        if kill -0 "$PID" 2>/dev/null; then
            warn "$name is already running (PID $PID). Use --stop first."
            return 1
        fi
        rm -f "$pidfile"
    fi
    return 0
}
check_running "$BACKEND_PID_FILE"  "Backend"  || exit 1
check_running "$FRONTEND_PID_FILE" "Frontend" || exit 1

# ── 构建后端 ──────────────────────────────────────────────────────────────────
if [ "$BUILD" = true ] || [ ! -f "$JAR" ]; then
    if [ ! -f "$JAR" ]; then
        info "JAR not found, building backend..."
    else
        info "Building backend (--build)..."
    fi
    cd "$BACKEND_DIR" || exit 1
    mvn install -DskipTests -q
    if [ $? -ne 0 ]; then
        error "Backend build failed"
        exit 1
    fi
    ok "Backend build complete"
fi

# ── 检查 Node 依赖 ────────────────────────────────────────────────────────────
if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
    info "Installing frontend dependencies..."
    cd "$FRONTEND_DIR" || exit 1
    npm install --silent
    ok "Frontend dependencies installed"
fi

# ── 启动后端 ──────────────────────────────────────────────────────────────────
info "Starting backend..."
cd "$BACKEND_DIR" || exit 1
nohup java -jar "$JAR" \
    --spring.profiles.active=default \
    > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
echo "$BACKEND_PID" > "$BACKEND_PID_FILE"
info "Backend starting (PID $BACKEND_PID) — log: logs/backend.log"

# 等待后端就绪（最多 30 秒）
info "Waiting for backend to be ready..."
for i in $(seq 1 30); do
    if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1 || \
       curl -sf http://localhost:8080/api/analysis     > /dev/null 2>&1; then
        ok "Backend is ready (${i}s)"
        break
    fi
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
        error "Backend process died. Check logs/backend.log"
        cat "$LOG_DIR/backend.log" | tail -20
        exit 1
    fi
    sleep 1
done

# ── 启动前端 ──────────────────────────────────────────────────────────────────
info "Starting frontend..."
cd "$FRONTEND_DIR" || exit 1
nohup npm run dev \
    > "$LOG_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!
echo "$FRONTEND_PID" > "$FRONTEND_PID_FILE"
info "Frontend starting (PID $FRONTEND_PID) — log: logs/frontend.log"

# 等待前端就绪（最多 15 秒）
for i in $(seq 1 15); do
    if curl -sf http://localhost:5173 > /dev/null 2>&1; then
        ok "Frontend is ready (${i}s)"
        break
    fi
    sleep 1
done

# ── 汇总 ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo -e "${GREEN}  JVuln Platform started successfully${NC}"
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo -e "  Backend   http://localhost:8080   (PID $BACKEND_PID)"
echo -e "  Frontend  http://localhost:5173   (PID $FRONTEND_PID)"
echo -e "  Logs      $LOG_DIR/"
echo ""
echo -e "  Stop:  ${YELLOW}./start.sh --stop${NC}"
echo -e "  Logs:  ${YELLOW}tail -f logs/backend.log${NC}"
echo ""
