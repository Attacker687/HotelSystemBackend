package com.winniethepooh.hotelsystembackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HotelSystemBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotelSystemBackendApplication.class, args);
    }

}
