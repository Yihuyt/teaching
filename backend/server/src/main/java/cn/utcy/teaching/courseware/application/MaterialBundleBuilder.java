package cn.utcy.teaching.courseware.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MaterialBundleBuilder {

    static final int MAX_CHARS = 50_000;
    static final int MAX_VISION_IMAGES = 20;
    static final int BASE_BUDGET_PER_DOCUMENT = 1_500;
    static final double RESERVED_BUDGET_RATIO = 0.4;
    static final String SECTION_SEPARATOR = "\n\n---\n\n";

    private static final Pattern WORD_CHAR = Pattern.compile("[\\p{L}\\p{N}_-]");

    private MaterialBundleBuilder() {
    }

    /** 一份已解析的素材:来源信息 + 文本 + 图片(id 为解析器给的原始标识,文本里可能引用它) */
    record Part(String name, int order, String mimeType, int pageCount, String text, List<Image> images) {
    }

    record Image(String id, byte[] bytes, String contentType, int pageNumber, String description,
                 int width, int height, String sourceDocumentName, int sourceDocumentOrder, int visionPriority) {
        Image withId(String newId) {
            return new Image(newId, bytes, contentType, pageNumber, description, width, height,
                    sourceDocumentName, sourceDocumentOrder, visionPriority);
        }

        Image withSource(String name, int order) {
            return new Image(id, bytes, contentType, pageNumber, description, width, height,
                    name, order, visionPriority);
        }

        Image withVisionPriority(int priority) {
            return new Image(id, bytes, contentType, pageNumber, description, width, height,
                    sourceDocumentName, sourceDocumentOrder, priority);
        }
    }

    record Result(String text, List<Image> images, int textContentBudget, int totalRawTextLength,
                  int totalImageCount, int visionImageCount) {
    }

    static Result build(List<Part> parts, int maxChars, int maxVisionImages) {
        List<Part> ordered = new ArrayList<>(parts);
        ordered.sort((a, b) -> Integer.compare(a.order(), b.order()));

        List<Part> stable = new ArrayList<>();
        for (Part part : ordered) {
            Map<String, String> idMap = new LinkedHashMap<>();
            List<Image> images = new ArrayList<>();
            for (int i = 0; i < part.images().size(); i++) {
                Image image = part.images().get(i);
                String stableId = "doc_" + part.order() + "_img_" + (i + 1);
                idMap.put(image.id(), stableId);
                images.add(image.withId(stableId).withSource(part.name(), part.order()));
            }
            stable.add(new Part(part.name(), part.order(), part.mimeType(), part.pageCount(),
                    replaceImageIds(part.text(), idMap), images));
        }

        // 预算全程按码点计数,与 truncateTextAtBoundary 的截断口径一致(代理对不吃双份额度)
        List<String> headers = new ArrayList<>();
        int framing = Math.max(0, stable.size() - 1) * SECTION_SEPARATOR.length();
        for (int i = 0; i < stable.size(); i++) {
            String header = sectionHeader(stable.get(i), i);
            headers.add(header);
            framing += header.codePointCount(0, header.length());
        }
        int textContentBudget = Math.max(0, maxChars - framing);
        int[] budgets = allocateDocumentTextBudgets(
                stable.stream().mapToInt(part -> part.text().codePointCount(0, part.text().length())).toArray(),
                textContentBudget);

        List<Image> flattened = new ArrayList<>();
        stable.forEach(part -> flattened.addAll(part.images()));
        Map<String, String> finalIdMap = new LinkedHashMap<>();
        for (int i = 0; i < flattened.size(); i++) {
            finalIdMap.put(flattened.get(i).id(), "img_" + (i + 1));
        }
        StringBuilder text = new StringBuilder();
        int totalRaw = 0;
        for (int i = 0; i < stable.size(); i++) {
            if (i > 0) {
                text.append(SECTION_SEPARATOR);
            }
            Part part = stable.get(i);
            totalRaw += part.text().codePointCount(0, part.text().length());
            text.append(headers.get(i))
                    .append(replaceImageIds(truncateTextAtBoundary(part.text(), budgets[i]), finalIdMap));
        }
        List<Image> images = new ArrayList<>();
        for (Image image : flattened) {
            images.add(image.withId(finalIdMap.getOrDefault(image.id(), image.id())));
        }

        List<String> visionIds = pickVisionImageIds(images, maxVisionImages);
        Map<String, Integer> priority = new LinkedHashMap<>();
        for (int i = 0; i < visionIds.size(); i++) {
            priority.put(visionIds.get(i), visionIds.size() - i);
        }
        List<Image> prioritized = new ArrayList<>();
        for (Image image : images) {
            prioritized.add(image.withVisionPriority(priority.getOrDefault(image.id(), 0)));
        }
        return new Result(text.toString(), prioritized, textContentBudget, totalRaw,
                prioritized.size(), visionIds.size());
    }

    static String sectionHeader(Part part, int index) {
        StringBuilder header = new StringBuilder();
        header.append("## 来源文档 ").append(index + 1).append(":").append(part.name()).append('\n');
        header.append("- 顺序:").append(part.order()).append('\n');
        if (part.mimeType() != null && !part.mimeType().isBlank()) {
            header.append("- 类型:").append(part.mimeType()).append('\n');
        }
        if (part.pageCount() > 0) {
            header.append("- 页数:").append(part.pageCount()).append('\n');
        }
        header.append('\n');
        return header.toString();
    }

    static int[] allocateDocumentTextBudgets(int[] lengths, int maxChars) {
        int n = lengths.length;
        int[] budgets = new int[n];
        if (n == 0 || maxChars <= 0) {
            return budgets;
        }
        int reserved = Math.min(n * BASE_BUDGET_PER_DOCUMENT, (int) Math.floor(maxChars * RESERVED_BUDGET_RATIO));
        int basePerDocument = reserved / n;
        int used = 0;
        for (int i = 0; i < n; i++) {
            budgets[i] = Math.min(lengths[i], basePerDocument);
            used += budgets[i];
        }
        int remainingBudget = maxChars - used;
        List<int[]> unmet = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int remaining = Math.max(0, lengths[i] - budgets[i]);
            if (remaining > 0) {
                unmet.add(new int[]{i, remaining});
            }
        }
        while (remainingBudget > 0 && !unmet.isEmpty()) {
            long totalRemaining = 0;
            for (int[] entry : unmet) {
                totalRemaining += entry[1];
            }
            if (totalRemaining == 0) {
                break;
            }
            int distributed = 0;
            for (int[] entry : unmet) {
                if (remainingBudget == 0) {
                    break;
                }
                int share = (int) Math.floor((double) remainingBudget * entry[1] / totalRemaining);
                int allocation = Math.min(entry[1], Math.min(share > 0 ? share : 1, remainingBudget));
                budgets[entry[0]] += allocation;
                entry[1] -= allocation;
                remainingBudget -= allocation;
                distributed += allocation;
            }
            if (distributed == 0) {
                break;
            }
            unmet.removeIf(entry -> entry[1] == 0);
        }
        return budgets;
    }

    static String truncateTextAtBoundary(String text, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxChars);
        String sliced = text.substring(0, end);
        int cut = sliced.length();
        while (cut > 0 && WORD_CHAR.matcher(sliced.substring(cut - 1, cut)).matches()) {
            cut--;
        }
        return cut > 0 ? sliced.substring(0, cut) : sliced;
    }

    static String replaceImageIds(String text, Map<String, String> idMap) {
        String next = text;
        for (Map.Entry<String, String> entry : idMap.entrySet()) {
            Pattern pattern = Pattern.compile("(?<![\\w-])" + Pattern.quote(entry.getKey()) + "(?![\\w-])");
            next = pattern.matcher(next).replaceAll(Matcher.quoteReplacement(entry.getValue()));
        }
        return next;
    }

    private static int compareForVision(Image a, Image b) {
        int aHas = a.description() != null && !a.description().isEmpty() ? 1 : 0;
        int bHas = b.description() != null && !b.description().isEmpty() ? 1 : 0;
        if (aHas != bHas) {
            return bHas - aHas;
        }
        if (a.sourceDocumentOrder() != b.sourceDocumentOrder()) {
            return Integer.compare(a.sourceDocumentOrder(), b.sourceDocumentOrder());
        }
        if (a.pageNumber() != b.pageNumber()) {
            return Integer.compare(a.pageNumber(), b.pageNumber());
        }
        return Long.compare((long) b.width() * b.height(), (long) a.width() * a.height());
    }

    static List<String> pickVisionImageIds(List<Image> images, int maxImages) {
        List<String> selected = new ArrayList<>();
        if (images.isEmpty() || maxImages <= 0) {
            return selected;
        }
        Map<Integer, List<Image>> grouped = new LinkedHashMap<>();
        for (Image image : images) {
            grouped.computeIfAbsent(image.sourceDocumentOrder(), k -> new ArrayList<>()).add(image);
        }
        List<List<Image>> groups = new ArrayList<>(grouped.values());
        groups.sort((a, b) -> Integer.compare(a.get(0).sourceDocumentOrder(), b.get(0).sourceDocumentOrder()));
        groups.forEach(group -> group.sort(MaterialBundleBuilder::compareForVision));
        boolean added = true;
        while (selected.size() < maxImages && added) {
            added = false;
            for (List<Image> group : groups) {
                if (selected.size() >= maxImages) {
                    break;
                }
                if (!group.isEmpty()) {
                    selected.add(group.remove(0).id());
                    added = true;
                }
            }
        }
        return selected;
    }
}
