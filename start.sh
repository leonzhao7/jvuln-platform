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
BACKEND_PORT=8080
FRONTEND_PORT=5173

mkdir -p "$LOG_DIR"

# ── 颜色 ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()      { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERR ]${NC}  $*"; }

stop_pidfile() {
    local pidfile=$1 name=$2
    if [ -f "$pidfile" ]; then
        local pid
        pid=$(cat "$pidfile")
        if kill -0 "$pid" 2>/dev/null; then
            if kill -TERM "-$pid" 2>/dev/null; then
                ok "$name stopped (process group $pid)"
            else
                kill -TERM "$pid" 2>/dev/null && ok "$name stopped (PID $pid)"
            fi
        else
            warn "$name PID $pid not running"
        fi
        rm -f "$pidfile"
    else
        warn "No $name PID file found"
    fi
}

stop_port() {
    local port=$1 name=$2
    if command -v lsof >/dev/null 2>&1; then
        local pids
        pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
        if [ -n "$pids" ]; then
            warn "$name port $port still in use, stopping leftover process(es): $pids"
            kill $pids 2>/dev/null || true
        fi
    fi
}

# ── 停止服务 ─────────────────────────────────────────────────────────────────
stop_services() {
    info "Stopping services..."
    stop_pidfile "$BACKEND_PID_FILE"  "Backend"
    stop_pidfile "$FRONTEND_PID_FILE" "Frontend"
    sleep 1
    stop_port "$BACKEND_PORT"  "Backend"
    stop_port "$FRONTEND_PORT" "Frontend"
}

# ── 参数解析 ──────────────────────────────────────────────────────────────────
BUILD=false
for arg in "$@"; do
    case $arg in
        --build) BUILD=true ;;
        --stop)  stop_services; exit 0 ;;
        --help|-h)
            echo "Usage: $0 [--build] [--stop]"
            echo "  --build   Build backend and validate frontend before starting"
            echo "  --stop    Stop running services"
            exit 0
            ;;
    esac
done

# ── 检查已运行实例 ────────────────────────────────────────────────────────────
check_running() {
    local pidfile=$1 name=$2
    if [ -f "$pidfile" ]; then
        local pid
        pid=$(cat "$pidfile")
        if kill -0 "$pid" 2>/dev/null; then
            warn "$name is already running (PID $pid). Use --stop first."
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

# ── 校验前端 ──────────────────────────────────────────────────────────────────
if [ "$BUILD" = true ]; then
    info "Validating frontend (--build)..."
    cd "$FRONTEND_DIR" || exit 1
    npm run build
    if [ $? -ne 0 ]; then
        error "Frontend build failed"
        exit 1
    fi
    ok "Frontend build complete"
fi

# ── 启动后端 ──────────────────────────────────────────────────────────────────
info "Starting backend..."
cd "$BACKEND_DIR" || exit 1
setsid java -jar "$JAR" \
    --spring.profiles.active=default \
    > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
echo "$BACKEND_PID" > "$BACKEND_PID_FILE"
info "Backend starting (PID $BACKEND_PID) — log: logs/backend.log"

# 等待后端就绪（最多 30 秒）
info "Waiting for backend to be ready..."
for i in $(seq 1 30); do
    if curl -sf http://localhost:$BACKEND_PORT/actuator/health > /dev/null 2>&1 || \
       curl -sf http://localhost:$BACKEND_PORT/api/analysis     > /dev/null 2>&1; then
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
if command -v lsof >/dev/null 2>&1 && lsof -tiTCP:"$FRONTEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    error "Frontend port $FRONTEND_PORT is already in use. Run ./start.sh --stop and retry."
    exit 1
fi
setsid npm run dev -- --host 0.0.0.0 --strictPort --port "$FRONTEND_PORT" \
    > "$LOG_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!
echo "$FRONTEND_PID" > "$FRONTEND_PID_FILE"
info "Frontend starting (PID $FRONTEND_PID) — log: logs/frontend.log"

# 等待前端就绪（最多 15 秒）
for i in $(seq 1 15); do
    if curl -sf http://localhost:$FRONTEND_PORT > /dev/null 2>&1; then
        ok "Frontend is ready (${i}s)"
        break
    fi
    if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
        error "Frontend process died. Check logs/frontend.log"
        tail -20 "$LOG_DIR/frontend.log"
        exit 1
    fi
    sleep 1
done

# ── 汇总 ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo -e "${GREEN}  JVuln Platform started successfully${NC}"
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo -e "  Backend   http://localhost:$BACKEND_PORT   (PID $BACKEND_PID)"
echo -e "  Frontend  http://localhost:$FRONTEND_PORT   (PID $FRONTEND_PID)"
echo -e "  Logs      $LOG_DIR/"
echo ""
echo -e "  Stop:  ${YELLOW}./start.sh --stop${NC}"
echo -e "  Logs:  ${YELLOW}tail -f logs/backend.log${NC}"
echo ""
