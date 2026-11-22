#!/usr/bin/env bash
# ⑥ OCI VM 데이터 백업: H2 파일 DB와 업로드 영상을 tar.gz 하나로 묶는다(README "백업과 복구").
# H2 파일 DB는 앱이 열고 있는 동안 파일을 복사하면 깨질 수 있어, 서비스를 잠시 멈추고 묶은 뒤 다시 시작한다.
# 사용: sudo /opt/chzzk/backup.sh   (보관 개수는 KEEP, 기본 7개)
set -euo pipefail

DATA_ROOT=/data/chzzk
BACKUP_DIR=${BACKUP_DIR:-/data/backup}
KEEP=${KEEP:-7}
STAMP=$(date +%Y%m%d-%H%M%S)

mkdir -p "$BACKUP_DIR"
systemctl stop chzzk
trap 'systemctl start chzzk' EXIT
tar -czf "$BACKUP_DIR/chzzk-$STAMP.tar.gz" -C "$DATA_ROOT" db uploads
systemctl start chzzk
trap - EXIT

# 오래된 백업은 최근 KEEP개만 남긴다.
ls -1t "$BACKUP_DIR"/chzzk-*.tar.gz | tail -n +$((KEEP + 1)) | xargs -r rm --
echo "backup: $BACKUP_DIR/chzzk-$STAMP.tar.gz"
