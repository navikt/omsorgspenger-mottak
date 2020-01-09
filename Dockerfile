FROM navikt/java:11

COPY docker-init-scripts/import-azure-credentials.sh /init-scripts/20-import-azure-credentials.sh

COPY build/libs/app.jar ./