package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// test profile：不执行 dev 的建表/演示数据脚本、不开定时任务，mvn test 不需要 MySQL / Redis
@SpringBootTest
@ActiveProfiles("test")
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
