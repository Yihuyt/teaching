package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 孤儿对象对账:死课件/死素材包的对象全清;活归属下只清「未被行引用且超过宽限期」的;
 * 素材原件(sources/)只随死素材包清;文档损坏的课件本轮全保留;不认识的键形状不动。
 */
class CoursewareAssetJanitorTest {

    private static final Date OLD = new Date(System.currentTimeMillis()
            - CoursewareAssetJanitor.ORPHAN_GRACE.toMillis() - 3_600_000);
    private static final Date FRESH = new Date(System.currentTimeMillis() - 60_000);

    private final CoursewareAssetStorage storage = mock(CoursewareAssetStorage.class);
    private final CoursewareMapper coursewares = mock(CoursewareMapper.class);
    private final MaterialBundleMapper bundles = mock(MaterialBundleMapper.class);
    private final CoursewareAssetJanitor janitor = new CoursewareAssetJanitor(storage, coursewares, bundles,
            new ObjectMapper(), mock(TransactionTemplate.class));

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CoursewareEntity.class);
        TableInfoHelper.initTableInfo(assistant, MaterialBundleEntity.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 对账裁决_按归属与引用与年龄() {
        CoursewareEntity cw10 = mock(CoursewareEntity.class);
        when(cw10.getId()).thenReturn(10L);
        when(cw10.getBody()).thenReturn("""
                {"title":"课","scenes":[{"blocks":[{"type":"image","src":"courseware/10/images/keep.png"}],
                "speech":[{"text":"讲","audioPath":"courseware/10/audio/keep.wav"}]}]}""");
        CoursewareEntity cw11 = mock(CoursewareEntity.class);
        when(cw11.getId()).thenReturn(11L);
        when(cw11.getBody()).thenReturn("这不是 JSON");
        when(coursewares.selectList(any(Wrapper.class))).thenReturn(List.of(cw10, cw11));
        when(coursewares.selectById(10L)).thenReturn(cw10);
        when(coursewares.selectById(11L)).thenReturn(cw11);

        MaterialBundleEntity bundle5 = mock(MaterialBundleEntity.class);
        when(bundle5.getId()).thenReturn(5L);
        when(bundle5.getImagesJson()).thenReturn("[{\"objectKey\":\"courseware/bundles/5/img_1.png\"}]");
        when(bundles.selectList(any(Wrapper.class))).thenReturn(List.of(bundle5));

        List<String> orphans = janitor.findOrphans(List.of(
                new CoursewareAssetStorage.StoredObject("courseware/10/images/keep.png", OLD),      // 被引用
                new CoursewareAssetStorage.StoredObject("courseware/10/images/orphan.png", OLD),    // 未引用且超龄
                new CoursewareAssetStorage.StoredObject("courseware/10/images/fresh.png", FRESH),   // 宽限期内
                new CoursewareAssetStorage.StoredObject("courseware/11/images/any.png", OLD),       // 文档损坏,保留
                new CoursewareAssetStorage.StoredObject("courseware/99/images/x.png", FRESH),       // 死课件,全清
                new CoursewareAssetStorage.StoredObject("courseware/bundles/5/img_1.png", OLD),     // 清单里
                new CoursewareAssetStorage.StoredObject("courseware/bundles/5/sources/1.pdf", OLD), // 原件,留
                new CoursewareAssetStorage.StoredObject("courseware/bundles/5/stray.png", OLD),     // 不在清单且超龄
                new CoursewareAssetStorage.StoredObject("courseware/bundles/77/a.png", FRESH),      // 死素材包,全清
                new CoursewareAssetStorage.StoredObject("courseware/abc/whatever", OLD)));          // 不认识,不动

        assertThat(orphans).containsExactlyInAnyOrder(
                "courseware/10/images/orphan.png",
                "courseware/99/images/x.png",
                "courseware/bundles/5/stray.png",
                "courseware/bundles/77/a.png");
    }

    @Test
    void 键形状解析() {
        assertThat(CoursewareAssetJanitor.bundleIdOf("courseware/bundles/5/img.png")).isEqualTo(5L);
        assertThat(CoursewareAssetJanitor.bundleIdOf("courseware/10/images/a.png")).isNull();
        assertThat(CoursewareAssetJanitor.coursewareIdOf("courseware/10/audio/a.wav")).isEqualTo(10L);
        assertThat(CoursewareAssetJanitor.coursewareIdOf("courseware/bundles/5/img.png")).isNull();
        assertThat(CoursewareAssetJanitor.coursewareIdOf("courseware/abc/x")).isNull();
        assertThat(CoursewareAssetJanitor.coursewareIdOf("courseware/10")).isNull();
    }
}
