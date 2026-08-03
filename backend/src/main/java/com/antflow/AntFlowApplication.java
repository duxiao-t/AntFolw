package com.antflow;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(value = "com.antflow", annotationClass = Mapper.class)
public class AntFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(AntFlowApplication.class, args);
    }
}
