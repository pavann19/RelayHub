FROM alpine:3.24
# Install only the TLS reverse proxy and current signed distribution patches.
RUN apk --no-cache upgrade && apk --no-cache add nginx
USER 101:101
EXPOSE 8443
ENTRYPOINT ["nginx", "-g", "daemon off;", "-c", "/config/nginx.conf"]
