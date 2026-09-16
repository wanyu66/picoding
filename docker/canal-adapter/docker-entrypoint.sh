#!/bin/sh
set -eu

BASE_DIR=/opt/canal-adapter
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"

exec java ${JAVA_OPTS} \
  -DappName=canal-adapter \
  -Djava.awt.headless=true \
  -Djava.net.preferIPv4Stack=true \
  -Dfile.encoding=UTF-8 \
  -classpath "${BASE_DIR}/conf:${BASE_DIR}/lib/*" \
  com.alibaba.otter.canal.adapter.launcher.CanalAdapterApplication

