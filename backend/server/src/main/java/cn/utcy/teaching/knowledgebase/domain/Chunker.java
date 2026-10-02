package cn.utcy.teaching.knowledgebase.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown/纯文本分块器:按 markdown 标题维护 section 路径,段内按句子边界
 * 组块——目标长度内尽量凑整句,超硬上限的单句按固定步长硬切。
 * 重叠取上一块尾部的整句(不超过重叠预算)。纯函数,长度一律按字符计
 * (中文语料下 token 估算不稳定,字符预算更可控)。
 */
public final class Chunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    /** 句子终止符(含成对收尾的引号/括号),另以空行作为硬边界 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。!?;!?;\\n](?=[\"'”』)】\\)]*)");
    private static final int MAX_SECTION_CHARS = 255;

    public record Chunk(int seq, String section, String content) {
    }

    private final int targetChars;
    private final int maxChars;
    private final int overlapChars;

    public Chunker(int targetChars, int maxChars, int overlapChars) {
        if (targetChars <= 0 || maxChars < targetChars || overlapChars >= targetChars) {
            throw new IllegalArgumentException(
                    "分块参数非法: target=" + targetChars + " max=" + maxChars + " overlap=" + overlapChars);
        }
        this.targetChars = targetChars;
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<Chunk> chunk(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        List<String> headingPath = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSection = "";
        String previousTail = "";

        for (String line : text.split("\n", -1)) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                previousTail = flush(chunks, current, currentSection, previousTail);
                int level = heading.group(1).length();
                while (headingPath.size() >= level) {
                    headingPath.remove(headingPath.size() - 1);
                }
                while (headingPath.size() < level - 1) {
                    headingPath.add("");
                }
                headingPath.add(heading.group(2).trim());
                currentSection = sectionPath(headingPath);
                previousTail = "";
                continue;
            }
            current.append(line).append('\n');
            if (current.length() >= targetChars) {
                previousTail = flushAtSentence(chunks, current, currentSection, previousTail);
            }
        }
        flush(chunks, current, currentSection, previousTail);
        return chunks;
    }

    private String flushAtSentence(List<Chunk> chunks, StringBuilder current,
                                   String section, String previousTail) {
        String text = current.toString();
        int cut = lastSentenceEnd(text, maxChars);
        if (cut <= 0) {
            if (text.length() < maxChars) {
                return previousTail;
            }
            cut = Math.min(maxChars, text.length());
        }
        String piece = text.substring(0, cut).trim();
        String rest = text.substring(cut);
        current.setLength(0);
        current.append(rest);
        if (!piece.isEmpty()) {
            chunks.add(new Chunk(chunks.size(), section, withOverlap(previousTail, piece)));
        }
        return tailSentences(piece);
    }

    private String flush(List<Chunk> chunks, StringBuilder current,
                         String section, String previousTail) {
        String piece = current.toString().trim();
        current.setLength(0);
        if (piece.isEmpty()) {
            return previousTail;
        }
        while (piece.length() > maxChars) {
            int cut = lastSentenceEnd(piece, maxChars);
            if (cut <= 0) {
                cut = maxChars;
            }
            String head = piece.substring(0, cut).trim();
            if (!head.isEmpty()) {
                chunks.add(new Chunk(chunks.size(), section, withOverlap(previousTail, head)));
                previousTail = tailSentences(head);
            }
            piece = piece.substring(cut).trim();
        }
        if (!piece.isEmpty()) {
            chunks.add(new Chunk(chunks.size(), section, withOverlap(previousTail, piece)));
            previousTail = tailSentences(piece);
        }
        return previousTail;
    }

    private String withOverlap(String previousTail, String piece) {
        return previousTail.isEmpty() ? piece : previousTail + "\n" + piece;
    }

    private int lastSentenceEnd(String text, int limit) {
        int end = Math.min(text.length(), limit);
        Matcher matcher = SENTENCE_END.matcher(text.substring(0, end));
        int last = -1;
        while (matcher.find()) {
            last = matcher.end();
        }
        return last;
    }

    private String tailSentences(String piece) {
        if (overlapChars == 0 || piece.length() <= overlapChars) {
            return "";
        }
        String window = piece.substring(piece.length() - overlapChars);
        Matcher matcher = SENTENCE_END.matcher(window);
        int firstEnd = -1;
        if (matcher.find()) {
            firstEnd = matcher.end();
        }
        if (firstEnd < 0 || firstEnd >= window.length()) {
            return "";
        }
        String tail = window.substring(firstEnd).trim();
        return tail.length() < 20 ? "" : tail; // 太短的重叠没有召回价值
    }

    private static String sectionPath(List<String> headingPath) {
        String joined = String.join(" › ",
                headingPath.stream().filter(part -> !part.isBlank()).toList());
        return joined.length() > MAX_SECTION_CHARS
                ? joined.substring(0, MAX_SECTION_CHARS) : joined;
    }
}
