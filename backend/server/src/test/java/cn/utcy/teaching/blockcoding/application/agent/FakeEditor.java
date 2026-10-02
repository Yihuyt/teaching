package cn.utcy.teaching.blockcoding.application.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeEditor implements Browser {
    private record Entry(String sprite, String code, String xml, int blockCount, List<String> definedProcedures) {
    }

    private final Map<String, Entry> stacks = new LinkedHashMap<>();
    private int nextId;

    public FakeEditor seed(List<Script> existing) {
        for (Script script : existing) {
            stacks.put(script.blockId(), new Entry(script.sprite(), script.code(), script.xml(), script.blockCount(),
                    script.definedProcedures()));
        }
        return this;
    }

    public List<String> codes() {
        return stacks.values().stream().map(Entry::code).toList();
    }

    @Override
    public String createSprite(String name) {
        return name;
    }

    @Override
    public String insertScript(Script script) {
        String id = "ins-" + (++nextId);
        stacks.put(id, new Entry(script.sprite(), script.code(), script.xml(), script.blockCount(), script.definedProcedures()));
        return id;
    }

    @Override
    public void removeScript(String sprite, String blockId) {
        stacks.remove(blockId);
    }

    public final List<String> deletedVariables = new java.util.ArrayList<>();
    public final List<String> deletedSprites = new java.util.ArrayList<>();

    @Override
    public void deleteVariable(String sprite, String name, boolean list) {
        deletedVariables.add((sprite == null ? "global" : sprite) + ":" + name + (list ? "(list)" : ""));
    }

    @Override
    public void deleteSprite(String name) {
        deletedSprites.add(name);
        stacks.values().removeIf(entry -> entry.sprite().equals(name));
    }
}
