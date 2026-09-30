package com.winniethepooh.hotelsystembackend.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.winniethepooh.hotelsystembackend.utils.AliOSSProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * OSS 客户端单例，所有上传复用，应用关闭时 shutdown。
 * 延迟创建：SDK 在密钥为空时直接抛异常，没配 OSS 的环境（开发、测试）只有真正上传时才会失败。
 */
@Configuration
public class AliOSSConfig {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    public OSS ossClient(AliOSSProperties p) {
        return new OSSClientBuilder().build(p.getEndpoint(), p.getAccessKeyId(), p.getAccessKeySecret());
    }
}
