package com.example.qrorder.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest{
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    static {
        MONGO.start();
    }

    @DynamicPropertySource
    static void mongoProps(DynamicPropertyRegistry registry){
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    }
}