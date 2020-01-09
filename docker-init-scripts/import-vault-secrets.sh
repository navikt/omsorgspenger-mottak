#!/usr/bin/env sh

echo "Importing vault secrets"

if test -d /var/run/secrets/nais.io/vault;
then
    for FILE in /var/run/secrets/nais.io/vault/*
    do
        FILE_NAME=$(echo $FILE | sed 's:.*/::')
        KEY=VAULT$FILE_NAME
        VALUE=$(cat "$FILE")

        echo "- exporting $KEY"
        export "$KEY"="$VALUE"
    done
fi