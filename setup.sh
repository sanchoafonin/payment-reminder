#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${PROJECT_DIR}/.env"
ENV_EXAMPLE="${PROJECT_DIR}/.env.example"
DOCKER=(docker)

log() {
    printf '[setup] %s\n' "$*"
}

fail() {
    printf '[setup] Ошибка: %s\n' "$*" >&2
    exit 1
}

run_as_root() {
    if [[ "$(id -u)" -eq 0 ]]; then
        "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo "$@"
    else
        fail "для установки Docker требуется root или команда sudo"
    fi
}

install_docker_macos() {
    if ! command -v brew >/dev/null 2>&1; then
        fail "установите Homebrew с https://brew.sh и повторите запуск"
    fi

    log "Устанавливаю Docker Desktop через Homebrew"
    brew install --cask docker
}

install_docker_linux() {
    if command -v apt-get >/dev/null 2>&1; then
        log "Устанавливаю Docker и Compose через apt"
        run_as_root apt-get update
        run_as_root apt-get install -y docker.io docker-compose-v2
    elif command -v dnf >/dev/null 2>&1; then
        log "Устанавливаю Docker и Compose через dnf"
        run_as_root dnf install -y docker docker-compose-plugin
    else
        fail "поддерживаются apt и dnf; установите Docker с Compose вручную"
    fi

    if command -v systemctl >/dev/null 2>&1; then
        run_as_root systemctl enable --now docker
    fi
}

ensure_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        case "$(uname -s)" in
            Darwin) install_docker_macos ;;
            Linux) install_docker_linux ;;
            *) fail "неподдерживаемая ОС: $(uname -s)" ;;
        esac
    fi

    if [[ "$(uname -s)" == "Darwin" ]] && ! docker info >/dev/null 2>&1; then
        log "Запускаю Docker Desktop"
        open -a Docker
    fi

    if [[ "$(uname -s)" == "Linux" ]] && ! docker info >/dev/null 2>&1; then
        if command -v systemctl >/dev/null 2>&1; then
            run_as_root systemctl start docker
        fi
        if [[ "$(id -u)" -ne 0 ]] && command -v sudo >/dev/null 2>&1 && sudo docker info >/dev/null 2>&1; then
            DOCKER=(sudo docker)
            log "Docker требует повышенных прав; команды запускаются через sudo"
        fi
    fi

    local attempt
    for attempt in {1..60}; do
        if "${DOCKER[@]}" info >/dev/null 2>&1; then
            break
        fi
        sleep 2
    done

    "${DOCKER[@]}" info >/dev/null 2>&1 || fail "Docker daemon недоступен"
    "${DOCKER[@]}" compose version >/dev/null 2>&1 || fail "плагин Docker Compose не установлен"
}

create_env() {
    [[ -f "${ENV_EXAMPLE}" ]] || fail "не найден ${ENV_EXAMPLE}"

    if [[ -e "${ENV_FILE}" ]]; then
        log ".env уже существует, оставляю его без изменений"
    else
        cp "${ENV_EXAMPLE}" "${ENV_FILE}"
        log "Создан .env из .env.example"
    fi

    mkdir -p "${PROJECT_DIR}/.data/postgres"
}

main() {
    ensure_docker
    create_env

    log "Собираю и запускаю приложение"
    "${DOCKER[@]}" compose --project-directory "${PROJECT_DIR}" up -d --build
    "${DOCKER[@]}" compose --project-directory "${PROJECT_DIR}" ps

    log "Приложение запускается на http://localhost:8080"
    log "Telegram настраивается в ${ENV_FILE}; после изменения выполните: docker compose up -d"
}

main "$@"
