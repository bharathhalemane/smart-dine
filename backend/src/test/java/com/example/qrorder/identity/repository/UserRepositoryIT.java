package com.example.qrorder.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.qrorder.identity.model.User;

@Testcontainers
@DataMongoTest
class UserRepositoryIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    @Autowired
    UserRepository users;

    @Test
    void savesAndFindsByNormalizedEmail() {
        users.save(new User("  Owner@Example.COM ", "hash", "Owner"));

        assertThat(users.findByEmail("owner@example.com")).isPresent();
        assertThat(users.existsByEmail("owner@example.com")).isTrue();
    }

    @Test
    void duplicateEmailIsRejectedByTheDatabase() {
        users.save(new User("dup@example.com", "hash", "A"));

        assertThatThrownBy(() -> users.save(new User("DUP@example.com", "hash", "B")))
                .isInstanceOf(DuplicateKeyException.class);
    }
}