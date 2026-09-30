package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

// test profile：不执行 dev 的建表/演示数据脚本、不开定时任务，mvn test 不需要 MySQL / Redis
@SpringBootTest
@ActiveProfiles("test")
class HotelSystemBackendApplicationTests {

	@Test
	void contextLoads() {

	}

}
