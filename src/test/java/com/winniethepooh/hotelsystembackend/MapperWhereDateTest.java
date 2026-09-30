package com.winniethepooh.hotelsystembackend;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** P4：mapper XML 的 WHERE 条件里不对列套 DATE()（纯静态检查，不启动容器）。 */
class MapperWhereDateTest {

    private static final Pattern DATE_ON_COLUMN = Pattern.compile("(?i)\\bDATE\\s*\\(\\s*[a-z_][a-z0-9_.]*\\s*\\)");
    private static final Pattern WHERE = Pattern.compile("(?i)\\bWHERE\\b");
    private static final Pattern WHERE_END = Pattern.compile("(?i)\\b(GROUP\\s+BY|ORDER\\s+BY|LIMIT)\\b");

    @Test
    void tc007_mapperWhereClausesDoNotWrapColumnsInDate() throws Exception {
        List<String> hits = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of("src/main/resources/mapper"))) {
            files = s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
        assertThat(files).isNotEmpty();

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        for (Path file : files) {
            Element root = f.newDocumentBuilder().parse(new File(file.toString())).getDocumentElement();
            for (String tag : List.of("select", "update", "delete", "insert")) {
                NodeList statements = root.getElementsByTagName(tag);
                for (int i = 0; i < statements.getLength(); i++) {
                    Element st = (Element) statements.item(i);
                    String sql = text(st);
                    Matcher w = WHERE.matcher(sql);
                    while (w.find()) {
                        Matcher end = WHERE_END.matcher(sql);
                        int stop = end.find(w.start()) ? end.start() : sql.length();
                        String fragment = sql.substring(w.start(), stop);
                        Matcher m = DATE_ON_COLUMN.matcher(fragment);
                        while (m.find()) {
                            hits.add(file.getFileName() + "#" + st.getAttribute("id") + ": " + m.group()
                                    + " in [" + fragment.replaceAll("\\s+", " ").trim() + "]");
                        }
                    }
                }
            }
        }
        assertThat(hits).as("WHERE 中对列使用 DATE() 的位置").isEmpty();
    }

    /** 语句文本：&lt;where&gt; 标签展开为 WHERE 关键字，其余动态标签只取内容。 */
    private static String text(Node node) {
        StringBuilder sb = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            switch (c.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(c.getNodeValue());
                case Node.ELEMENT_NODE -> {
                    if ("where".equals(c.getNodeName())) sb.append(" WHERE ");
                    sb.append(' ').append(text(c)).append(' ');
                }
                default -> { }
            }
        }
        return sb.toString();
    }
}
