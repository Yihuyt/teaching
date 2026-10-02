package cn.utcy.teaching.ai.document;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MineruLayoutPagesTest {

    /** 与平台 mapper 同样严格:值后不许有多余 token——流式逐页读树正是在这种 mapper 下出过错 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    @DisplayName("表格正文从 html 字段转纯文本进入页文本")
    void tableHtmlBecomesText() throws Exception {
        String layout = """
                {"pdf_info":[{"page_idx":0,"page_size":[600,800],"para_blocks":[
                  {"type":"text","index":0,"lines":[{"spans":[{"content":"关系运算符如下表:"}]}]},
                  {"type":"table","index":1,"blocks":[
                    {"type":"table_body","lines":[{"spans":[{"type":"table",
                      "html":"<table><tr><td>等于</td><td>大于</td></tr><tr><td>==</td><td>&gt;</td></tr></table>"}]}]}
                  ]}
                ],"discarded_blocks":[]}]}
                """;
        var pages = MineruLayoutPages.fromLayoutJson(layout.getBytes(java.nio.charset.StandardCharsets.UTF_8), mapper);
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0)).contains("关系运算符如下表:");
        assertThat(pages.get(0)).contains("等于 | 大于");
        assertThat(pages.get(0)).contains("== | >");
    }

    @Test
    @DisplayName("html 转文本:行转换行、格转竖线、实体还原、行尾分隔符清理")
    void tableHtmlToText() {
        assertThat(MineruLayoutPages.tableHtmlToText(
                "<table><tr><th>名称</th><th>符号</th></tr><tr><td>大等于</td><td>&gt;=</td></tr></table>"))
                .isEqualTo("名称 | 符号\n大等于 | >=");
    }
}
