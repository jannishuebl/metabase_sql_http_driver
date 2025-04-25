#!/bin/bash

set -e

LATEST_TAG=$(curl -s https://api.github.com/repos/metabase/metabase/releases/latest | jq -r .tag_name)

echo "Latest Metabase tag: $LATEST_TAG"

if git rev-parse "$LATEST_TAG" >/dev/null 2>&1; then
  echo "Driver already built for $LATEST_TAG"
  exit 1  # abort workflow
else
  echo "New Metabase version found: $LATEST_TAG"
  echo "METABASE_VERSION=$LATEST_TAG" >> $GITHUB_ENV
fi
