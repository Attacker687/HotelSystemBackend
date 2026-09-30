package com.winniethepooh.hotelsystembackend.utils;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P6：OSS 客户端注入复用、不在每次上传后 shutdown；输入流无论成功失败都关闭。纯单元测试，不连 OSS。 */
class AliOSSUtilTest {

    private static final String BUCKET = "test-bucket";

    private final OSS oss = mock(OSS.class);
    private final AliOSSUtil util = new AliOSSUtil(props(), oss);

    @Test
    void tc008_twoUploadsReuseTheSameOssClient() throws Exception {
        String first = util.upload(png(new ByteArrayInputStream(new byte[]{1, 2, 3})));
        String second = util.upload(png(new ByteArrayInputStream(new byte[]{1, 2, 3})));

        verify(oss, times(2)).putObject(eq(BUCKET), anyString(), any(InputStream.class));
        verify(oss, never()).shutdown();
        assertThat(first).endsWith(".png");
        assertThat(second).endsWith(".png").isNotEqualTo(first);
    }

    @ParameterizedTest(name = "putObject 抛异常={0}")
    @ValueSource(booleans = {false, true})
    void tc009_inputStreamClosedWhetherPutObjectSucceedsOrFails(boolean ossFails) throws Exception {
        InputStream in = spy(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        OSSException boom = new OSSException("boom");
        if (ossFails) when(oss.putObject(eq(BUCKET), anyString(), any(InputStream.class))).thenThrow(boom);

        if (ossFails) {
            assertThatThrownBy(() -> util.upload(png(in))).isSameAs(boom);
        } else {
            util.upload(png(in));
        }

        verify(in, times(1)).close();
    }

    private static MultipartFile png(InputStream in) throws Exception {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("a.png");
        when(f.getInputStream()).thenReturn(in);
        return f;
    }

    private static AliOSSProperties props() {
        AliOSSProperties p = new AliOSSProperties();
        p.setEndpoint("https://oss-cn-chengdu.aliyuncs.com");
        p.setBucketName(BUCKET);
        return p;
    }
}
