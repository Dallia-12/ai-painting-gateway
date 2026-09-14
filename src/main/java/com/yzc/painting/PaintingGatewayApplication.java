package com.yzc.painting;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.yzc.painting.mapper")
public class PaintingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaintingGatewayApplication.class, args);
    }
}
