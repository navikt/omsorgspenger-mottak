#!/usr/bin/env sh

echo "Importing Serviceuser credentials"

if test -d docker-init-scripts/serviceuser
then
  echo "found files..."
    for FILE in docker-init-scripts/serviceuser/*
    do
        FILE_NAME=$(echo $FILE | sed 's:.*/::')
        KEY=NAIS_$FILE_NAME
        VALUE=$(cat "$FILE")

        echo "- exporting $KEY"
        export "$KEY"="$VALUE"
    done
fi