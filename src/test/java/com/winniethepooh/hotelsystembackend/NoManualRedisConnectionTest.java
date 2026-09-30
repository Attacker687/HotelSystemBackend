package com.winniethepooh.hotelsystembackend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** P1：生产代码不再手动 getConnectionFactory().getConnection()（拿到的连接从不关闭）。 */
class NoManualRedisConnectionTest {

    private static final Pattern MANUAL_CONNECTION = Pattern.compile("getConnectionFactory\\(\\)\\)?\\.getConnection\\(\\)");

    @Test
    void tc006_sourceNeverFetchesRedisConnectionManually() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                // 去掉空白后整体匹配（调用可能跨行），命中时再定位到起始行号
                StringBuilder compact = new StringBuilder();
                List<Integer> lineOf = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    for (char ch : lines.get(i).toCharArray()) {
                        if (!Character.isWhitespace(ch)) {
                            compact.append(ch);
                            lineOf.add(i + 1);
                        }
                    }
                }
                var m = MANUAL_CONNECTION.matcher(compact);
                while (m.find()) hits.add(f + ":" + lineOf.get(m.start()));
            }
        }
        assertThat(hits).as("手动获取 Redis 连接的位置").isEmpty();
    }
}
