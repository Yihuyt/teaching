package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.resource.domain.CourseMaterial;
import cn.utcy.teaching.resource.domain.MaterialKind;
import cn.utcy.teaching.resource.domain.MaterialState;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MaterialOutlineSourceProviderTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CourseMaterial.class);
    }

    @Test
    void activeFileIsLinkableAndReturnsItsName() {
        CourseMaterialMapper materials = mock(CourseMaterialMapper.class);
        when(materials.selectForUpdate(20L, 10L))
                .thenReturn(material(MaterialKind.FILE, MaterialState.ACTIVE));
        MaterialOutlineSourceProvider provider = new MaterialOutlineSourceProvider(materials);

        String title = provider.requireLinkable(20L, 10L);

        assertThat(title).isEqualTo("课程资料");
        verify(materials).selectForUpdate(20L, 10L);
        verify(materials, never()).selectOne(any());
    }

    @Test
    void pendingUploadIsNotLinkable() {
        CourseMaterialMapper materials = mock(CourseMaterialMapper.class);
        when(materials.selectForUpdate(20L, 10L))
                .thenReturn(material(MaterialKind.FILE, MaterialState.PENDING_UPLOAD));
        MaterialOutlineSourceProvider provider = new MaterialOutlineSourceProvider(materials);

        assertThatThrownBy(() -> provider.requireLinkable(20L, 10L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("文件尚未上传完成");
    }

    @Test
    void folderIsNotLinkable() {
        CourseMaterialMapper materials = mock(CourseMaterialMapper.class);
        when(materials.selectForUpdate(20L, 10L))
                .thenReturn(material(MaterialKind.FOLDER, MaterialState.ACTIVE));
        MaterialOutlineSourceProvider provider = new MaterialOutlineSourceProvider(materials);

        assertThatThrownBy(() -> provider.requireLinkable(20L, 10L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程中不存在该文件");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requireTitlesFailsWhenAnyIdIsMissing() {
        CourseMaterialMapper materials = mock(CourseMaterialMapper.class);
        when(materials.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(material(MaterialKind.FILE, MaterialState.ACTIVE)));
        MaterialOutlineSourceProvider provider = new MaterialOutlineSourceProvider(materials);

        assertThat(provider.requireTitles(20L, Set.of(10L, 11L))).hasSize(1);
    }

    @Test
    void requireTitlesReturnsEmptyForNoIds() {
        CourseMaterialMapper materials = mock(CourseMaterialMapper.class);
        MaterialOutlineSourceProvider provider = new MaterialOutlineSourceProvider(materials);

        assertThat(provider.requireTitles(20L, Set.of())).isEmpty();

        verifyNoInteractions(materials);
    }

    private static CourseMaterial material(MaterialKind kind, MaterialState state) {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        boolean file = kind == MaterialKind.FILE;
        return new CourseMaterial(
                10L,
                20L,
                null,
                "课程资料",
                kind,
                file ? "courses/20/materials/object" : null,
                file ? "application/pdf" : null,
                file ? 128L : null,
                file ? "a".repeat(64) : null,
                state,
                now,
                now);
    }
}
