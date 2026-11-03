package com.relayhub.kafka;

import com.relayhub.model.Event;

public interface EventBus {

    void publish(Event event);

    void publishDlq(Event event, String reason);
}

