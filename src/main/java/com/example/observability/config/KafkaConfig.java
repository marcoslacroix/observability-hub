package com.example.observability.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${app.kafka.topics.user-registrations}")
    private String userRegistrationsTopic;

    @Value("${app.kafka.topics.custom-events}")
    private String customEventsTopic;

    @Bean
    public NewTopic userRegistrationsTopic() {
        return TopicBuilder.name(userRegistrationsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic customEventsTopic() {
        return TopicBuilder.name(customEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

}
