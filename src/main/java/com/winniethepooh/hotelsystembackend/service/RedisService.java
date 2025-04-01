package com.winniethepooh.hotelsystembackend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class RedisService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public void deleteKeysByValue(String targetValue) {
        // 使用 SCAN 遍历所有键
        Cursor<byte[]> cursor = Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .scan(ScanOptions.scanOptions().count(1000).match("*").build());

        while (cursor.hasNext()) {
            String key = new String(cursor.next());
            DataType keyType = redisTemplate.type(key);
            if (keyType == DataType.STRING) {
                // 获取键对应的值
                Object value = redisTemplate.opsForValue().get(key);

                // 如果值为目标字符串，删除该键
                if (targetValue.equals(value)) {
                    redisTemplate.delete(key);
                    System.out.println("Deleted key: " + key);
                    break;
                }
            }
        }

        // 关闭游标
        cursor.close();
    }
}
