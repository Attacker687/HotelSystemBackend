package com.winniethepooh.hotelsystembackend.utils;

import com.aliyun.oss.OSS;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Component
public class AliOSSUtil {

    private final AliOSSProperties aliOSSProperties;
    private final OSS ossClient;

    /** ossClient 是单例 Bean（见 AliOSSConfig）；@Lazy：首次上传时才创建，未配置 OSS 密钥也能启动。 */
    public AliOSSUtil(AliOSSProperties aliOSSProperties, @Lazy OSS ossClient) {
        this.aliOSSProperties = aliOSSProperties;
        this.ossClient = ossClient;
    }

    public String upload(MultipartFile file) throws IOException {
        String endpoint = aliOSSProperties.getEndpoint();
        String bucketName = aliOSSProperties.getBucketName();

        // 避免文件名重复
        String originalFilename = file.getOriginalFilename();
        String fileName = UUID.randomUUID().toString()
                + originalFilename.substring(originalFilename.lastIndexOf("."));

        // 上传：复用单例客户端，输入流用完即关（成功或 OSS 抛异常都关）
        try (InputStream inputStream = file.getInputStream()) {
            ossClient.putObject(bucketName, fileName, inputStream);
        }

        // 拼接访问地址
        String url = "https://" + bucketName + "." + endpoint.replace("https://", "") + "/" + fileName;

        return url;
    }
}
