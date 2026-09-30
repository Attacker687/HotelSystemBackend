package com.winniethepooh.hotelsystembackend;

import com.aliyun.oss.OSS;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

/** S10：上传只限经理；按文件内容判断图片、扩展名白名单 jpg/jpeg/png/gif/webp；单文件上限 5MB，超限 413（GAP-08）。 */
class UploadImageIT extends IntegrationTestBase {

    private static final int LIMIT = 5 * 1024 * 1024;

    /** OSS 测试桩：只记录调用，不连真实 OSS。 */
    @MockBean
    private OSS oss;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"住客A", "前台", "餐厅"})
    void tc052_nonManagerRolesCannotUpload(String role) throws Exception {
        Fixtures.Account who = switch (role) {
            case "住客A" -> base.userA();
            case "前台" -> base.front();
            default -> base.restaurant();
        };

        Resp r = upload(login(who), "ok.png", png(0), MediaType.IMAGE_PNG);

        assertThat(r.status()).isEqualTo(403);
        assertThat(putObjectCalls()).isZero();
    }

    @Test
    void tc053_pngExtensionWithHtmlContentRejected() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        Resp r = upload(login(base.manager()), "fake.png", html, MediaType.IMAGE_PNG);

        assertThat(r.status()).isEqualTo(400);
        assertThat(putObjectCalls()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "logo.svg | image/svg+xml | <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>",
            "page.html | text/html | <!DOCTYPE html><html><body>hi</body></html>"})
    void tc054_extensionOutsideWhitelistRejected(String name, String type, String content) {
        Resp r = upload(login(base.manager()), name, content.getBytes(StandardCharsets.UTF_8), MediaType.parseMediaType(type));

        assertThat(r.status()).isEqualTo(400);
        assertThat(putObjectCalls()).isZero();
    }

    @Test
    void tc055_fileNameWithoutDotRejectedWithReason() throws Exception {
        Resp r = upload(login(base.manager()), "image", png(0), MediaType.IMAGE_PNG);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.msg()).contains("文件类型").isNotEqualTo("操作失败，请联系管理员");
        assertThat(putObjectCalls()).isZero();
    }

    @Test
    void tc056_managerUploadsPngOfExactlyTheLimit() throws Exception {
        byte[] big = png(LIMIT);
        assertThat(big).hasSize(5242880);
        assertThat(ImageIO.read(new ByteArrayInputStream(big))).isNotNull();

        Resp r = upload(login(base.manager()), "big.png", big, MediaType.IMAGE_PNG);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(r.data().asText()).startsWith("https://").endsWith(".png");
        assertThat(putObjectCalls()).isEqualTo(1);
    }

    @Test
    void tc057_managerUploadsPngOneByteOverTheLimitGets413() throws Exception {
        byte[] big1 = png(LIMIT + 1);
        assertThat(big1).hasSize(5242881);
        assertThat(ImageIO.read(new ByteArrayInputStream(big1))).isNotNull();

        Resp r = upload(login(base.manager()), "big1.png", big1, MediaType.IMAGE_PNG);

        assertThat(r.status()).isEqualTo(413);
        assertThat(putObjectCalls()).isZero();
    }

    /** 白名单内其他格式按各自魔数放行；扩展名大小写不敏感。只校验文件头，内容用最短的合法文件头即可。 */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a.jpg", "a.jpeg", "a.gif", "a.webp", "A.PNG"})
    void s10_otherWhitelistedFormatsAccepted(String name) throws Exception {
        byte[] head = switch (name) {
            case "a.jpg", "a.jpeg" -> new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
            case "a.gif" -> "GIF89a\1\0\1\0".getBytes(StandardCharsets.ISO_8859_1);
            case "a.webp" -> "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
            default -> png(0);
        };

        Resp r = upload(login(base.manager()), name, head, MediaType.APPLICATION_OCTET_STREAM);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.data().asText()).endsWith(name.substring(name.lastIndexOf('.')));
        assertThat(putObjectCalls()).isEqualTo(1);
    }

    private Resp upload(String token, String filename, byte[] content, MediaType type) {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("file", new ByteArrayResource(content)).filename(filename).contentType(type);
        return post("/upload/image", token, b.build());
    }

    /** OSS 测试桩上 putObject（任意重载）被调用的次数。 */
    private long putObjectCalls() {
        return mockingDetails(oss).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("putObject")).count();
    }

    /**
     * 1×1 的合法 PNG。size > 0 时在 IEND 前插入私有辅助块 paDd（0 填充，CRC 按规范计算），
     * 让文件总长恰为 size 字节，按魔数或按解码都认作合法图片。
     */
    static byte[] png(int size) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] small = out.toByteArray();
        if (size <= 0) return small;

        byte[] typeAndData = new byte[4 + size - small.length - 12];
        System.arraycopy("paDd".getBytes(StandardCharsets.US_ASCII), 0, typeAndData, 0, 4);
        CRC32 crc = new CRC32();
        crc.update(typeAndData);
        int iend = small.length - 12;
        return ByteBuffer.allocate(size)
                .put(small, 0, iend)
                .putInt(typeAndData.length - 4).put(typeAndData).putInt((int) crc.getValue())
                .put(small, iend, 12)
                .array();
    }
}
