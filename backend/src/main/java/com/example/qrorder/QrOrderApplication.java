package com.example.qrorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QrOrderApplication {
    public static void main(String[] args){
        SpringApplication.run(QrOrderApplication.class, args);
    }
}