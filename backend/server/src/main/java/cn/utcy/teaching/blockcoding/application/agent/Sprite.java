package cn.utcy.teaching.blockcoding.application.agent;

import java.util.ArrayList;
import java.util.List;

public record Sprite(String name, boolean isStage, List<String> costumes, List<String> sounds,
                     List<String> localVariables, List<String> localLists) {
    public Sprite {
        costumes = costumes == null ? List.of() : List.copyOf(costumes);
        sounds = sounds == null ? List.of() : List.copyOf(sounds);
        localVariables = localVariables == null ? List.of() : List.copyOf(localVariables);
        localLists = localLists == null ? List.of() : List.copyOf(localLists);
    }

    public static Sprite bare(String name) {
        return new Sprite(name, "Stage".equals(name), List.of(), List.of(), List.of(), List.of());
    }

    Sprite withLocalVariable(String name) {
        return localVariables.contains(name) ? this : new Sprite(this.name, isStage, costumes, sounds, plus(localVariables, name), localLists);
    }

    Sprite withLocalList(String name) {
        return localLists.contains(name) ? this : new Sprite(this.name, isStage, costumes, sounds, localVariables, plus(localLists, name));
    }

    Sprite withoutLocalVariable(String name) {
        return new Sprite(this.name, isStage, costumes, sounds, localVariables.stream().filter(v -> !v.equals(name)).toList(), localLists);
    }

    Sprite withoutLocalList(String name) {
        return new Sprite(this.name, isStage, costumes, sounds, localVariables, localLists.stream().filter(v -> !v.equals(name)).toList());
    }

    private static List<String> plus(List<String> values, String value) {
        List<String> out = new ArrayList<>(values);
        out.add(value);
        return out;
    }
}
