#!/usr/bin/env bash
# Fails if any ad, analytics or tracking SDK appears in the Gradle build files.
# MinMusic must never ship ads or third-party telemetry, on Android or on desktop.
set -euo pipefail
pattern='play-services-ads|gms[.:]ads|admob|applovin|unity3d\.ads|unityads|ironsource|facebook\.(android|audience)|audience-network|mopub|inmobi|chartboost|vungle|adcolony|startapp|mintegral|pangle|bytedance|firebase|crashlytics|google-analytics|play-services-analytics|io\.sentry|sentry-android|appcenter|mixpanel|amplitude|segment\.analytics|branch\.io|appsflyer|adjust\.sdk|bugsnag|instabug|datadog|newrelic|flurry|yandex\.(mobile|metrica)|onesignal'
files=$(git ls-files '*.gradle' '*.gradle.kts' 'gradle/*.toml')
if [ -z "$files" ]; then echo "no gradle files found" >&2; exit 1; fi
if hits=$(grep -nEi "$pattern" $files); then
  echo "::error::Ad/analytics/tracking dependency detected:" >&2
  echo "$hits" >&2
  exit 1
fi
echo "No ad, analytics or tracking SDKs found."
