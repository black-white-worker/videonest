FROM rabbitmq:4.2.0-management

ADD --chmod=0644 https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v4.2.0/rabbitmq_delayed_message_exchange-4.2.0.ez /plugins/rabbitmq_delayed_message_exchange-4.2.0.ez

RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
