FROM canal/canal-server:v1.1.7

ARG CANAL_VERSION=1.1.7
ARG CANAL_ADAPTER_URL=https://github.com/alibaba/canal/releases/download/canal-${CANAL_VERSION}/canal.adapter-${CANAL_VERSION}.tar.gz

RUN mkdir -p /opt/canal-adapter \
    && curl -fL --retry 5 --retry-delay 2 "${CANAL_ADAPTER_URL}" -o /tmp/canal-adapter.tar.gz \
    && tar -xzf /tmp/canal-adapter.tar.gz -C /opt/canal-adapter \
    && rm /tmp/canal-adapter.tar.gz

COPY conf/bootstrap.yml /opt/canal-adapter/conf/bootstrap.yml
COPY conf/application.yml /opt/canal-adapter/conf/application.yml
COPY conf/es8/paicoding-article.yml /opt/canal-adapter/conf/es8/paicoding-article.yml
COPY docker-entrypoint.sh /usr/local/bin/canal-adapter-entrypoint

RUN chmod +x /usr/local/bin/canal-adapter-entrypoint

WORKDIR /opt/canal-adapter/bin
EXPOSE 8081
ENTRYPOINT ["/usr/local/bin/canal-adapter-entrypoint"]
