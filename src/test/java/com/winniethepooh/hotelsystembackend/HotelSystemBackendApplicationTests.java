package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class HotelSystemBackendApplicationTests {

	// 应用没有 JWT 密钥会拒绝启动（S11），这里用测试进程运行时生成的随机值
	@DynamicPropertySource
	static void jwtSecret(DynamicPropertyRegistry registry) {
		registry.add("hotel.jwt.secret", () -> Fixtures.JWT_SECRET);
	}

	@Test
	void contextLoads() {

	}

}
