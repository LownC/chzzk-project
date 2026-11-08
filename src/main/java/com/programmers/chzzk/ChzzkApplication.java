package com.programmers.chzzk;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChzzkApplication {

	static final String DEFAULT_TIME_ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone(DEFAULT_TIME_ZONE));
		SpringApplication.run(ChzzkApplication.class, args);
	}

}
