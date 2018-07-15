FROM tutum/apache-php
RUN apt-get update && apt-get install -yq git php5-gmp && rm -rf /var/lib/apt/lists/*
RUN rm -fr /app
ADD 000-default.conf /etc/apache2/sites-available/000-default.conf
ADD simplesamlphp-for-cf /var/
# RUN composer install
