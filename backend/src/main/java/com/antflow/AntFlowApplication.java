package com.antflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.antflow")
public class AntFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(AntFlowApplication.class, args);
    }
}
