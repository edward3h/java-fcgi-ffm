FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y \
    apache2 \
    libapache2-mod-fcgid \
    libfcgi0ldbl \
    && rm -rf /var/lib/apt/lists/* \
    && a2enmod fcgid rewrite \
    && ln -sf /usr/lib/x86_64-linux-gnu/libfcgi.so.0 /usr/lib/x86_64-linux-gnu/libfcgi.so
COPY apache-default.conf /etc/apache2/sites-available/000-default.conf
RUN chown www-data:www-data /var/www/html
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 80
ENTRYPOINT ["/entrypoint.sh"]
