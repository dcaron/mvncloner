#!/bin/sh
set -e

LOG=/logs/ofelia.log

cat > /tmp/ofelia.ini << EOF
[job-run "mvncloner"]
schedule             = ${SCHEDULE:-@every 1h}
image                = mvncloner-app
network              = mvncloner_default
volume               = mvncloner_mirror:/mirror
delete               = true
environment          = ADVISOR_MAVEN_SOURCE_URL=${ADVISOR_MAVEN_SOURCE_URL}
environment          = ADVISOR_MAVEN_SERVER_USERNAME=${ADVISOR_MAVEN_SERVER_USERNAME}
environment          = ADVISOR_MAVEN_SERVER_PASSWORD=${ADVISOR_MAVEN_SERVER_PASSWORD}
environment          = TARGET_MAVEN_URL=${TARGET_MAVEN_URL}
environment          = TARGET_MAVEN_USERNAME=${TARGET_MAVEN_USERNAME}
environment          = TARGET_MAVEN_PASSWORD=${TARGET_MAVEN_PASSWORD}
environment          = MIRROR_PATH=${MIRROR_PATH}
environment          = SYNC_SCRAPER_MAX_CONCURRENT=${SYNC_SCRAPER_MAX_CONCURRENT:-6}
environment          = SYNC_SCRAPER_FILE_DELAY_MS=${SYNC_SCRAPER_FILE_DELAY_MS:-500}
environment          = SYNC_SCRAPER_DIRECTORY_DELAY_MS=${SYNC_SCRAPER_DIRECTORY_DELAY_MS:-1000}
environment          = SYNC_PUBLISHER_MAX_CONCURRENT=${SYNC_PUBLISHER_MAX_CONCURRENT:-8}
environment          = SYNC_PUBLISHER_FILE_DELAY_MS=${SYNC_PUBLISHER_FILE_DELAY_MS:-1000}
environment          = SYNC_PUBLISHER_SKIP_EXISTING=${SYNC_PUBLISHER_SKIP_EXISTING:-true}
environment          = SYNC_PUBLISHER_VERIFY_SIZE=${SYNC_PUBLISHER_VERIFY_SIZE:-true}
environment          = SYNC_RETRY_MAX_ATTEMPTS=${SYNC_RETRY_MAX_ATTEMPTS:-3}
environment          = SYNC_RETRY_BACKOFF_BASE_MS=${SYNC_RETRY_BACKOFF_BASE_MS:-1000}
EOF

echo "Starting Ofelia — mvncloner schedule: ${SCHEDULE:-@every 1h}" | tee -a "$LOG"
exec ofelia daemon --config=/tmp/ofelia.ini 2>&1 | tee -a "$LOG"
