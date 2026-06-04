#!/bin/sh
set -e

# Generate FcgidInitialEnv config from MySQL-related environment variables.
# mod_fcgid does not inherit the Apache process environment, so these must
# be explicitly declared here and included by apache-default.conf.
CONF=/etc/apache2/fcgid-env.conf

printf '' > "$CONF"

for var in MYSQL_HOST MYSQL_DATABASE MYSQL_USERNAME MYSQL_PASSWORD; do
    val=$(eval "echo \"\${$var}\"")
    if [ -n "$val" ]; then
        printf 'FcgidInitialEnv %s "%s"\n' "$var" "$val" >> "$CONF"
    fi
done

exec apache2ctl -D FOREGROUND
