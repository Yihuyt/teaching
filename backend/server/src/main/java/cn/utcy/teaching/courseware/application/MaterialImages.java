package cn.utcy.teaching.courseware.application;

import dev.langchain4j.data.message.ImageContent;

import java.util.Base64;
import cn.utcy.teaching.courseware.domain.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 逐页生成时可引用的图片集合:
 * 模型只见短 id(img_N),对象键由本类在落库前解析回填。图片来源不限——素材包里抽出的图、文生图产物、
 * 本页原有的 image 块,统一以「对象键 + 尺寸 + 描述」表示。
 */
final class MaterialImages {

    static final int MAX_VISION_IMAGES = MaterialBundleBuilder.MAX_VISION_IMAGES;

    record Ref(String id, String objectKey, String contentType, int width, int height, String description,
               String source, boolean required) {
    }

    private final Map<String, Ref> byId;

    private MaterialImages(Map<String, Ref> byId) {
        this.byId = byId;
    }

    static MaterialImages empty() {
        return new MaterialImages(Map.of());
    }

    static MaterialImages of(List<SceneGenerator.ImageInput> inputs) {
        Map<String, Ref> byId = new LinkedHashMap<>();
        int n = 0;
        for (SceneGenerator.ImageInput input : inputs) {
            String id = "img_" + (++n);
            byId.put(id, new Ref(id, input.src(), contentTypeOf(input.src()), input.width(), input.height(),
                    input.description() == null ? "" : input.description(), input.source(), input.required()));
        }
        return new MaterialImages(byId);
    }

    /** 页面里已有的 image 块 → 可用图片(重生成时模型可以原样保留它们) */
    static MaterialImages fromBlocks(List<Block> blocks) {
        List<SceneGenerator.ImageInput> inputs = new ArrayList<>();
        if (blocks != null) {
            List<Block.Image> images = new ArrayList<>();
            collectImages(blocks, images);
            for (Block.Image image : images) {
                inputs.add(new SceneGenerator.ImageInput(image.src(), image.caption() == null ? "" : image.caption(),
                        image.width(), image.height(), "本页原有", false));
            }
        }
        return of(inputs);
    }

    static Set<String> imageSrcs(List<Block> blocks) {
        List<Block.Image> images = new ArrayList<>();
        if (blocks != null) {
            collectImages(blocks, images);
        }
        Set<String> srcs = new java.util.LinkedHashSet<>();
        images.forEach(image -> srcs.add(image.src()));
        return srcs;
    }

    List<String> requiredButUnused(Set<String> usedSrcs) {
        List<String> missing = new ArrayList<>();
        for (Ref ref : byId.values()) {
            if (ref.required() && !usedSrcs.contains(ref.id())) {
                missing.add(ref.id());
            }
        }
        return missing;
    }

    private static void collectImages(List<Block> blocks, List<Block.Image> out) {
        for (Block block : blocks) {
            if (block instanceof Block.Image image) {
                out.add(image);
            } else if (block instanceof Block.Columns columns) {
                columns.children().forEach(column -> collectImages(column, out));
            }
        }
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }

    Set<String> ids() {
        return byId.keySet();
    }

    Ref get(String id) {
        return byId.get(id);
    }

    List<Ref> visionSlice() {
        List<Ref> all = new ArrayList<>(byId.values());
        return all.subList(0, Math.min(MAX_VISION_IMAGES, all.size()));
    }

    String describeAll(boolean attachVision) {
        if (byId.isEmpty()) {
            return "(无可用图片)";
        }
        Set<String> attached = new java.util.LinkedHashSet<>();
        if (attachVision) {
            visionSlice().forEach(ref -> attached.add(ref.id()));
        }
        List<String> lines = new ArrayList<>();
        for (Ref ref : byId.values()) {
            lines.add(describe(ref, attached.contains(ref.id())));
        }
        return String.join("\n", lines);
    }

    static String describe(Ref ref, boolean attached) {
        StringBuilder line = new StringBuilder("- **").append(ref.id()).append("**:").append(ref.source());
        if (ref.width() > 0 && ref.height() > 0) {
            line.append(" | 尺寸 ").append(ref.width()).append("×").append(ref.height())
                    .append("(宽高比 ").append(String.format(Locale.ROOT, "%.2f", (double) ref.width() / ref.height()))
                    .append(")");
        }
        if (attached) {
            line.append(" [见附图]");
        }
        if (ref.required()) {
            line.append(" | 必须放上本页");
        }
        if (ref.description() != null && !ref.description().isBlank()) {
            line.append(" | ").append(ref.description().strip());
        }
        return line.toString();
    }

    List<ImageContent> visionParts(Function<String, byte[]> loader) {
        List<ImageContent> parts = new ArrayList<>();
        for (Ref ref : visionSlice()) {
            parts.add(ImageContent.from(Base64.getEncoder().encodeToString(loader.apply(ref.objectKey())), ref.contentType()));
        }
        return parts;
    }

    static String contentTypeOf(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }
}
